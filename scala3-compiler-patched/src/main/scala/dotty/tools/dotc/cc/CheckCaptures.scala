package dotty.tools
package dotc
package cc

import core.*
import Phases.*, DenotTransformers.*, SymDenotations.*
import Contexts.*, Names.*, Flags.*, Symbols.*, Decorators.*
import Types.*, StdNames.*
import config.Printers.{capt, recheckr}
import config.Config
import ast.{tpd, untpd, Trees}
import Trees.*
import typer.RefChecks.{checkAllOverrides, checkParents}
import util.{SimpleIdentitySet, EqHashMap, SrcPos}
import transform.SymUtils.*
import transform.{Recheck, PreRecheck}
import Recheck.*
import scala.collection.mutable
import CaptureSet.{withCaptureSetsExplained, IdempotentCaptRefMap}
import StdNames.nme
import reporting.trace

/** The capture checker */
object CheckCaptures:
  import ast.tpd.*

  class Pre extends PreRecheck, SymTransformer:

    override def isEnabled(using Context) = ctx.settings.Ycc.value

  	/** Reset `private` flags of parameter accessors so that we can refine them
     *  in Setup if they have non-empty capture sets. Special handling of some
     *  symbols defined for case classes.
     */
    def transformSym(sym: SymDenotation)(using Context): SymDenotation =
      if sym.isAllOf(PrivateParamAccessor) && !sym.hasAnnotation(defn.ConstructorOnlyAnnot) then
        sym.copySymDenotation(initFlags = sym.flags &~ Private | Recheck.ResetPrivate)
      else if Synthetics.needsTransform(sym) then
        Synthetics.transformToCC(sym)
      else
        sym
  end Pre

  /** A class describing environments.
   *  @param owner    the current owner
   *  @param captured the caputure set containing all references to tracked free variables outside of boxes
   *  @param isBoxed  true if the environment is inside a box (in which case references are not counted)
   *  @param outer0   the next enclosing environment
   */
  case class Env(owner: Symbol, captured: CaptureSet, isBoxed: Boolean, outer0: Env | Null):
    def outer = outer0.nn

    def isOutermost = outer0 == null

    /** If an environment is open it tracks free references */
    def isOpen = !captured.isAlwaysEmpty && !isBoxed
  end Env

  /** Similar normal substParams, but this is an approximating type map that
   *  maps parameters in contravariant capture sets to the empty set.
   *  TODO: check what happens with non-variant.
   */
  final class SubstParamsMap(from: BindingType, to: List[Type])(using Context)
  extends ApproximatingTypeMap, IdempotentCaptRefMap:
    def apply(tp: Type): Type = tp match
      case tp: ParamRef =>
        if tp.binder == from then to(tp.paramNum) else tp
      case tp: NamedType =>
        if tp.prefix `eq` NoPrefix then tp
        else tp.derivedSelect(apply(tp.prefix))
      case _: ThisType =>
        tp
      case _ =>
        mapOver(tp)

  /** Check that a @retains annotation only mentions references that can be tracked.
   *  This check is performed at Typer.
   */
  def checkWellformed(ann: Tree)(using Context): Unit =
    for elem <- retainedElems(ann) do
      elem.tpe match
        case ref: CaptureRef =>
          if !ref.canBeTracked then
            report.error(em"$elem cannot be tracked since it is not a parameter or a local variable", elem.srcPos)
        case tpe =>
          report.error(em"$elem: $tpe is not a legal element of a capture set", elem.srcPos)

  /** If `tp` is a capturing type, check that all references it mentions have non-empty
   *  capture sets. Also: warn about redundant capture annotations.
   *  This check is performed after capture sets are computed in phase cc.
   */
  def checkWellformedPost(tp: Type, pos: SrcPos)(using Context): Unit = tp match
    case CapturingType(parent, refs) =>
      for ref <- refs.elems do
        if ref.captureSetOfInfo.elems.isEmpty then
          report.error(em"$ref cannot be tracked since its capture set is empty", pos)
        else if parent.captureSet.accountsFor(ref) then
          report.warning(em"redundant capture: $parent already accounts for $ref", pos)
    case _ =>

  /** Warn if `ann`, which is a tree of a @retains annotation, defines some elements that
   *  are already accounted for by other elements of the same annotation.
   *  Note: We need to perform the check on the original annotation rather than its
   *  capture set since the conversion to a capture set already eliminates redundant elements.
   */
  def warnIfRedundantCaptureSet(ann: Tree)(using Context): Unit =
    // The lists `elems(i) :: prev.reverse :: elems(0),...,elems(i-1),elems(i+1),elems(n)`
    // where `n == elems.length-1`, i <- 0..n`.
    // I.e.
    // choices(Nil, elems) = [[elems(i), elems(0), ..., elems(i-1), elems(i+1), .... elems(n)] | i <- 0..n]
    def choices(prev: List[Tree], elems: List[Tree]): List[List[Tree]] = elems match
      case Nil => Nil
      case elem :: elems =>
        List(elem :: (prev reverse_::: elems)) ++ choices(elem :: prev, elems)
    for case first :: others <- choices(Nil, retainedElems(ann)) do
      val firstRef = first.toCaptureRef
      val remaining = CaptureSet(others.map(_.toCaptureRef)*)
      if remaining.accountsFor(firstRef) then
        report.warning(em"redundant capture: $remaining already accounts for $firstRef", ann.srcPos)

class CheckCaptures extends Recheck, SymTransformer:
  thisPhase =>

  import ast.tpd.*
  import CheckCaptures.*

  def phaseName: String = "cc"
  override def isEnabled(using Context) = ctx.settings.Ycc.value

  def newRechecker()(using Context) = CaptureChecker(ctx)

  override def run(using Context): Unit =
    checkOverrides.traverse(ctx.compilationUnit.tpdTree)
    super.run

  override def transformSym(sym: SymDenotation)(using Context): SymDenotation =
    if Synthetics.needsTransform(sym) then Synthetics.transformFromCC(sym)
    else super.transformSym(sym)

  /** Check overrides again, taking capture sets into account.
   *  TODO: Can we avoid doing overrides checks twice?
   *  We need to do them here since only at this phase CaptureTypes are relevant
   *  But maybe we can then elide the check during the RefChecks phase if -Ycc is set?
   */
  def checkOverrides = new TreeTraverser:
    def traverse(t: Tree)(using Context) =
      t match
        case t: Template => checkAllOverrides(ctx.owner.asClass)
        case _ =>
      traverseChildren(t)

  class CaptureChecker(ictx: Context) extends Rechecker(ictx):
    import ast.tpd.*

    override def keepType(tree: Tree) =
      super.keepType(tree)
      || tree.isInstanceOf[Try]  // type of `try` needs tp be checked for * escapes

    /** Instantiate capture set variables appearing contra-variantly to their
     *  upper approximation.
     */
    private def interpolator(startingVariance: Int = 1)(using Context) = new TypeTraverser:
      variance = startingVariance
      override def traverse(t: Type) =
        t match
          case CapturingType(parent, refs: CaptureSet.Var) =>
            if variance < 0 then
              capt.println(i"solving $t")
              refs.solve()
            traverse(parent)
          case t @ RefinedType(_, nme.apply, rinfo) if defn.isFunctionOrPolyType(t) =>
            traverse(rinfo)
          case tp: TypeVar =>
          case tp: TypeRef =>
            traverse(tp.prefix)
          case _ =>
            traverseChildren(t)

    /** If `tpt` is an inferred type, interpolate capture set variables appearing contra-
     *  variantly in it.
     */
    private def interpolateVarsIn(tpt: Tree)(using Context): Unit =
      if tpt.isInstanceOf[InferredTypeTree] then
        interpolator().traverse(tpt.knownType)
          .showing(i"solved vars in ${tpt.knownType}", capt)

    /** Assert subcapturing `cs1 <: cs2` */
    def assertSub(cs1: CaptureSet, cs2: CaptureSet)(using Context) =
      assert(cs1.subCaptures(cs2, frozen = false).isOK, i"$cs1 is not a subset of $cs2")

    /** Check subcapturing `{elem} <: cs`, report error on failure */
    def checkElem(elem: CaptureRef, cs: CaptureSet, pos: SrcPos)(using Context) =
      val res = elem.singletonCaptureSet.subCaptures(cs, frozen = false)
      if !res.isOK then
        report.error(i"$elem cannot be referenced here; it is not included in the allowed capture set ${res.blocking}", pos)

    /** Check subcapturing `cs1 <: cs2`, report error on failure */
    def checkSubset(cs1: CaptureSet, cs2: CaptureSet, pos: SrcPos)(using Context) =
      val res = cs1.subCaptures(cs2, frozen = false)
      if !res.isOK then
        def header =
          if cs1.elems.size == 1 then i"reference ${cs1.elems.toList}%, % is not"
          else i"references $cs1 are not all"
        report.error(i"$header included in allowed capture set ${res.blocking}", pos)

    /** The current environment */
    private var curEnv: Env = Env(NoSymbol, CaptureSet.empty, isBoxed = false, null)

    private val myCapturedVars: util.EqHashMap[Symbol, CaptureSet] = EqHashMap()

    /** If `sym` is a class or method nested inside a term, a capture set variable representing
     *  the captured variables of the environment associated with `sym`.
     */
    def capturedVars(sym: Symbol)(using Context) =
      myCapturedVars.getOrElseUpdate(sym,
        if sym.ownersIterator.exists(_.isTerm) then CaptureSet.Var()
        else CaptureSet.empty)

    /** For all nested environments up to `limit` perform `op` */
    def forallOuterEnvsUpTo(limit: Symbol)(op: Env => Unit)(using Context): Unit =
      def recur(env: Env): Unit =
        if env.isOpen && env.owner != limit then
          op(env)
          if !env.isOutermost then
            var nextEnv = env.outer
            if env.owner.isConstructor then
              if nextEnv.owner != limit && !nextEnv.isOutermost then
                recur(nextEnv.outer)
            else recur(nextEnv)
      recur(curEnv)

    /** Include `sym` in the capture sets of all enclosing environments nested in the
     *  the environment in which `sym` is defined.
     */
    def markFree(sym: Symbol, pos: SrcPos)(using Context): Unit =
      if sym.exists then
        val ref = sym.termRef
        if ref.isTracked then
          forallOuterEnvsUpTo(sym.enclosure) { env =>
            capt.println(i"Mark $sym with cs ${ref.captureSet} free in ${env.owner}")
            checkElem(ref, env.captured, pos)
          }

    /** Make sure (projected) `cs` is a subset of the capture sets of all enclosing
     *  environments. At each stage, only include references from `cs` that are outside
     *  the environment's owner
     */
    def markFree(cs: CaptureSet, pos: SrcPos)(using Context): Unit =
      if !cs.isAlwaysEmpty then
        forallOuterEnvsUpTo(ctx.owner.topLevelClass) { env =>
          val included = cs.filter {
            case ref: TermRef => env.owner.isProperlyContainedIn(ref.symbol.owner)
            case ref: ThisType => env.owner.isProperlyContainedIn(ref.cls)
            case _ => false
          }
          capt.println(i"Include call capture $included in ${env.owner}")
          checkSubset(included, env.captured, pos)
        }

    /** Include references captured by the called method in the current environment stack */
    def includeCallCaptures(sym: Symbol, pos: SrcPos)(using Context): Unit =
      if sym.exists && curEnv.isOpen then markFree(capturedVars(sym), pos)

    override def recheckIdent(tree: Ident)(using Context): Type =
      if tree.symbol.is(Method) then includeCallCaptures(tree.symbol, tree.srcPos)
      else markFree(tree.symbol, tree.srcPos)
      super.recheckIdent(tree)

    /** A specialized implementation of the selection rule.
     *
     *  E |- f: Cf f { m: Cr R }
     *  ------------------------
     *  E |- f.m: C R
     *
     *  The implementation picks as `C` one of `{f}` or `Cr`, depending on the
     *  outcome of a `mightSubcapture` test. It picks `{f}` if this might subcapture Cr
     *  and Cr otherwise.
     */
    override def recheckSelection(tree: Select, qualType: Type, name: Name)(using Context) = {
      val selType = super.recheckSelection(tree, qualType, name)
      val selCs = selType.widen.captureSet
      if selCs.isAlwaysEmpty || selType.widen.isBoxedCapturing || qualType.isBoxedCapturing then
        selType
      else
        val qualCs = qualType.captureSet
        capt.println(i"intersect $qualType, ${selType.widen}, $qualCs, $selCs in $tree")
        if qualCs.mightSubcapture(selCs) then
          selType.widen.stripCapturing.capturing(qualCs)
        else
          selType
    }//.showing(i"recheck sel $tree, $qualType = $result")

    /** A specialized implementation of the apply rule.
     *
     *  E |- f: Cf (Ra -> Cr Rr)
     *  E |- a: Ca Ra
     *  ------------------------
     *  E |- f a: C Rr
     *
     *  The implementation picks as `C` one of `{f, a}` or `Cr`, depending on the
     *  outcome of a `mightSubcapture` test. It picks `{f, a}` if this might subcapture Cr
     *  and Cr otherwise.
     */
    override def recheckApply(tree: Apply, pt: Type)(using Context): Type =
      includeCallCaptures(tree.symbol, tree.srcPos)
      super.recheckApply(tree, pt) match
        case appType @ CapturingType(appType1, refs) =>
          tree.fun match
            case Select(qual, _)
            if !tree.fun.symbol.isConstructor
                && !qual.tpe.isBoxedCapturing
                && !tree.args.exists(_.tpe.isBoxedCapturing)
                && qual.tpe.captureSet.mightSubcapture(refs)
                && tree.args.forall(_.tpe.captureSet.mightSubcapture(refs))
            =>
              val callCaptures = tree.args.foldLeft(qual.tpe.captureSet)((cs, arg) =>
                cs ++ arg.tpe.captureSet)
              appType.derivedCapturingType(appType1, callCaptures)
                .showing(i"narrow $tree: $appType, refs = $refs, qual = ${qual.tpe.captureSet} --> $result", capt)
            case _ => appType
        case appType => appType

    /** Handle an application of method `sym` with type `mt` to arguments of types `argTypes`.
     *  This means:
     *   - Instantiate result type with actual arguments
     *   - If call is to a constructor:
     *      - remember types of arguments corresponding to tracked
     *        parameters in refinements.
     *      - add capture set of instantiated class to capture set of result type.
     */
    override def instantiate(mt: MethodType, argTypes: List[Type], sym: Symbol)(using Context): Type =
      val ownType =
        if mt.isResultDependent then SubstParamsMap(mt, argTypes)(mt.resType)
        else mt.resType

      if sym.isConstructor then
        val cls = sym.owner.asClass

        /** First half of result pair:
         *  Refine the type of a constructor call `new C(t_1, ..., t_n)`
         *  to C{val x_1: T_1, ..., x_m: T_m} where x_1, ..., x_m are the tracked
         *  parameters of C and T_1, ..., T_m are the types of the corresponding arguments.
         *
         *  Second half: union of all capture sets of arguments to tracked parameters.
         */
        def addParamArgRefinements(core: Type, initCs: CaptureSet): (Type, CaptureSet) =
          mt.paramNames.lazyZip(argTypes).foldLeft((core, initCs)) { (acc, refine) =>
            val (core, allCaptures) = acc
            val (getterName, argType) = refine
            val getter = cls.info.member(getterName).suchThat(_.is(ParamAccessor)).symbol
            if getter.termRef.isTracked && !getter.is(Private)
            then (RefinedType(core, getterName, argType), allCaptures ++ argType.captureSet)
            else (core, allCaptures)
          }

        def augmentConstructorType(core: Type, initCs: CaptureSet): Type = core match
          case core: MethodType =>
            // more parameters to follow; augment result type
            core.derivedLambdaType(resType = augmentConstructorType(core.resType, initCs))
          case CapturingType(parent, refs) =>
            // can happen for curried constructors if instantiate of a previous step
            // added capture set to result.
            augmentConstructorType(parent, initCs ++ refs)
          case _ =>
            val (refined, cs) = addParamArgRefinements(core, initCs)
            refined.capturing(cs)

        augmentConstructorType(ownType, CaptureSet.empty) match
          case augmented: MethodType =>
            augmented
          case augmented =>
            // add capture sets of class and constructor to final result of constructor call
            augmented.capturing(capturedVars(cls) ++ capturedVars(sym))
              .showing(i"constr type $mt with $argTypes%, % in $cls = $result", capt)
      else ownType
    end instantiate

    override def recheckClosure(tree: Closure, pt: Type)(using Context): Type =
      val cs = capturedVars(tree.meth.symbol)
      capt.println(i"typing closure $tree with cvs $cs")
      super.recheckClosure(tree, pt).capturing(cs)
        .showing(i"rechecked $tree / $pt = $result", capt)

    /** Additionally to normal processing, update types of closures if the expected type
     *  is a function with only pure parameters. In that case, make the anonymous function
     *  also have the same parameters as the prototype.
     *  TODO: Develop a clearer rationale for this.
     *  TODO: Can we generalize this to arbitrary parameters?
     *        Currently some tests fail if we do this. (e.g. neg.../stackAlloc.scala, others)
     */
    override def recheckBlock(block: Block, pt: Type)(using Context): Type =
      block match
        case closureDef(mdef) =>
          pt.dealias match
            case defn.FunctionOf(ptformals, _, _, _) if ptformals.forall(_.captureSet.isAlwaysEmpty) =>
              // Redo setup of the anonymous function so that formal parameters don't
              // get capture sets. This is important to avoid false widenings to `*`
              // when taking the base type of the actual closures's dependent function
              // type so that it conforms to the expected non-dependent function type.
              // See withLogFile.scala for a test case.
              val meth = mdef.symbol
              // First, undo the previous setup which installed a completer for `meth`.
              atPhase(preRecheckPhase.prev)(meth.denot.copySymDenotation())
                .installAfter(preRecheckPhase)
              // Next, update all parameter symbols to match expected formals
              meth.paramSymss.head.lazyZip(ptformals).foreach { (psym, pformal) =>
                psym.copySymDenotation(info = pformal).installAfter(preRecheckPhase)
              }
              // Next, update types of parameter ValDefs
              mdef.paramss.head.lazyZip(ptformals).foreach { (param, pformal) =>
                val ValDef(_, tpt, _) = param: @unchecked
                tpt.rememberTypeAlways(pformal)
              }
              // Next, install a new completer reflecting the new parameters for the anonymous method
              val completer = new LazyType:
                def complete(denot: SymDenotation)(using Context) =
                  denot.info = MethodType(ptformals, mdef.tpt.knownType)
                    .showing(i"simplify info of $meth to $result", capt)
                  recheckDef(mdef, meth)
              meth.copySymDenotation(info = completer, initFlags = meth.flags &~ Touched)
                .installAfter(preRecheckPhase)
            case _ =>
        case _ =>
      super.recheckBlock(block, pt)

    override def recheckValDef(tree: ValDef, sym: Symbol)(using Context): Unit =
      try
        if !sym.is(Module) then // Modules are checked by checking the module class
          super.recheckValDef(tree, sym)
      finally
        if !sym.is(Param) then
          // Parameters with inferred types belong to anonymous methods. We need to wait
          // for more info from the context, so we cannot interpolate. Note that we cannot
          // expect to have all necessary info available at the point where the anonymous
          // function is compiled since we do not propagate expected types into blocks.
          interpolateVarsIn(tree.tpt)

    override def recheckDefDef(tree: DefDef, sym: Symbol)(using Context): Unit =
      if !Synthetics.isExcluded(sym) then
        val saved = curEnv
        val localSet = capturedVars(sym)
        if !localSet.isAlwaysEmpty then curEnv = Env(sym, localSet, isBoxed = false, curEnv)
        try super.recheckDefDef(tree, sym)
        finally
          interpolateVarsIn(tree.tpt)
          curEnv = saved

    /** Class-specific capture set relations:
     *   1. The capture set of a class includes the capture sets of its parents.
     *   2. The capture set of the self type of a class includes the capture set of the class.
     *   3. The capture set of the self type of a class includes the capture set of every class parameter.
     */
    override def recheckClassDef(tree: TypeDef, impl: Template, cls: ClassSymbol)(using Context): Type =
      val saved = curEnv
      val localSet = capturedVars(cls)
      for parent <- impl.parents do // (1)
        checkSubset(capturedVars(parent.tpe.classSymbol), localSet, parent.srcPos)
      if !localSet.isAlwaysEmpty then curEnv = Env(cls, localSet, isBoxed = false, curEnv)
      try
        val thisSet = cls.classInfo.selfType.captureSet.withDescription(i"of the self type of $cls")
        checkSubset(localSet, thisSet, tree.srcPos) // (2)
        for param <- cls.paramGetters do
          checkSubset(param.termRef.captureSet, thisSet, param.srcPos) // (3)
        super.recheckClassDef(tree, impl, cls)
      finally
        curEnv = saved

    /** If type is of the form `T @requiresCapability(x)`,
     *  mark `x` as free in the current environment. This is used to require the
     *  correct `CanThrow` capability when encountering a `throw`.
     */
    override def recheckTyped(tree: Typed)(using Context): Type =
      tree.tpt.tpe match
        case AnnotatedType(_, annot) if annot.symbol == defn.RequiresCapabilityAnnot =>
          annot.tree match
            case Apply(_, cap :: Nil) =>
              markFree(cap.symbol, tree.srcPos)
            case _ =>
        case _ =>
      super.recheckTyped(tree)

    /* Currently not needed, since capture checking takes place after ElimByName.
     * Keep around in case we need to get back to it
    def recheckByNameArg(tree: Tree, pt: Type)(using Context): Type =
      val closureDef(mdef) = tree: @unchecked
      val arg = mdef.rhs
      val localSet = CaptureSet.Var()
      curEnv = Env(mdef.symbol, localSet, isBoxed = false, curEnv)
      val result =
        try
          inContext(ctx.withOwner(mdef.symbol)) {
            recheckStart(arg, pt).capturing(localSet)
          }
        finally curEnv = curEnv.outer
      recheckFinish(result, arg, pt)
    */

    /** If expected type `pt` is boxed, don't propagate free variables.
     *  Otherwise, if the result type is boxed, simulate an unboxing by
     *  adding all references in the boxed capture set to the current environment.
     */
    override def recheck(tree: Tree, pt: Type = WildcardType)(using Context): Type =
      if tree.isTerm && pt.isBoxedCapturing then
        val saved = curEnv
        curEnv = Env(curEnv.owner, CaptureSet.Var(), isBoxed = true, curEnv)
        try super.recheck(tree, pt)
        finally curEnv = saved
      else
        val res = super.recheck(tree, pt)
        if tree.isTerm then markFree(res.boxedCaptureSet, tree.srcPos)
        res

    /** If `tree` is a reference or an application where the result type refers
     *  to an enclosing class or method parameter of the reference, check that the result type
     *  does not capture the universal capability. This is justified since the
     *  result type would have to be implicitly unboxed.
     *  TODO: Can we find a cleaner way to achieve this? Logically, this should be part
     *  of simulated boxing and unboxing.
     */
    override def recheckFinish(tpe: Type, tree: Tree, pt: Type)(using Context): Type =
      val typeToCheck = tree match
        case _: Ident | _: Select | _: Apply | _: TypeApply if tree.symbol.unboxesResult =>
          tpe
        case _: Try =>
          tpe
        case _: ValDef if tree.symbol.is(Mutable) =>
          tree.symbol.info
        case _ =>
          NoType
      def checkNotUniversal(tp: Type): Unit = tp.widenDealias match
        case wtp @ CapturingType(parent, refs) =>
          refs.disallowRootCapability { () =>
            val kind = if tree.isInstanceOf[ValDef] then "mutable variable" else "expression"
            report.error(
              em"""The $kind's type $wtp is not allowed to capture the root capability `*`.
                  |This usually means that a capability persists longer than its allowed lifetime.""",
              tree.srcPos)
          }
          checkNotUniversal(parent)
        case _ =>
      checkNotUniversal(typeToCheck)
      super.recheckFinish(tpe, tree, pt)

    /** Massage `actual` and `expected` types using the methods below before checking conformance */
    override def checkConformsExpr(actual: Type, expected: Type, tree: Tree)(using Context): Unit =
      val expected1 = addOuterRefs(expected, actual)
      val actual1 = adaptBoxed(actual, expected1, tree.srcPos)
      //println(i"check conforms $actual1 <<< $expected1")
      super.checkConformsExpr(actual1, expected1, tree)

    /** For the expected type, implement the rule outlined in #14390:
     *   - when checking an expression `a: Ca Ta` against an expected type `Ce Te`,
     *   - where the capture set `Ce` contains Cls.this,
     *   - and where and all method definitions enclosing `a` inside class `Cls`
     *     have only pure parameters,
     *   - add to `Ce` all references to variables or this-references in `Ca`
     *     that are outside `Cls`. These are all accessed through `Cls.this`,
     *     so we can assume they are already accounted for by `Ce` and adding
     *     them explicitly to `Ce` changes nothing.
     */
    private def addOuterRefs(expected: Type, actual: Type)(using Context): Type =
      def isPure(info: Type): Boolean = info match
        case info: PolyType => isPure(info.resType)
        case info: MethodType => info.paramInfos.forall(_.captureSet.isAlwaysEmpty) && isPure(info.resType)
        case _ => true
      def isPureContext(owner: Symbol, limit: Symbol): Boolean =
        if owner == limit then true
        else if !owner.exists then false
        else isPure(owner.info) && isPureContext(owner.owner, limit)
      def augment(erefs: CaptureSet, arefs: CaptureSet): CaptureSet =
        (erefs /: erefs.elems) { (erefs, eref) =>
          eref match
            case eref: ThisType if isPureContext(ctx.owner, eref.cls) =>
              erefs ++ arefs.filter {
                case aref: TermRef => eref.cls.isProperlyContainedIn(aref.symbol.owner)
                case aref: ThisType => eref.cls.isProperlyContainedIn(aref.cls)
                case _ => false
              }
            case _ =>
              erefs
        }
      expected match
        case CapturingType(ecore, erefs) =>
          val erefs1 = augment(erefs, actual.captureSet)
          if erefs1 ne erefs then
            capt.println(i"augmented $expected from ${actual.captureSet} --> $erefs1")
          expected.derivedCapturingType(ecore, erefs1)
        case _ =>
          expected

    /** Adapt `actual` type to `expected` type by inserting boxing and unboxing conversions */
    def adaptBoxed(actual: Type, expected: Type, pos: SrcPos)(using Context): Type =

      /** Adapt function type `actual`, which is `aargs -> ares` (possibly with dependencies)
       *  to `expected` type.
       *   @param reconstruct  how to rebuild the adapted function type
       */
      def adaptFun(actual: Type, aargs: List[Type], ares: Type, expected: Type,
          covariant: Boolean,
          reconstruct: (List[Type], Type) => Type): Type =
        val (eargs, eres) = expected.dealias match
          case defn.FunctionOf(eargs, eres, _, _) => (eargs, eres)
          case _ => (aargs.map(_ => WildcardType), WildcardType)
        val aargs1 = aargs.zipWithConserve(eargs)(adapt(_, _, !covariant))
        val ares1 = adapt(ares, eres, covariant)
        if (ares1 eq ares) && (aargs1 eq aargs) then actual
        else reconstruct(aargs1, ares1)

      def adapt(actual: Type, expected: Type, covariant: Boolean): Type = actual.dealias match
        case actual @ CapturingType(parent, refs) =>
          val parent1 = adapt(parent, expected, covariant)
          if actual.isBoxed != expected.isBoxedCapturing then
            val criticalSet =          // the set which is not allowed to have `*`
              if covariant then refs   // can't box with `*`
              else expected.captureSet // can't unbox with `*`
            if criticalSet.isUniversal then
              // We can't box/unbox the universal capability. Leave `actual` as it is
              // so we get an error in checkConforms. This tends to give better error
              // messages than disallowing the root capability in `criticalSet`.
              capt.println(i"cannot box/unbox $actual vs $expected")
              actual
            else
              // Disallow future addition of `*` to `criticalSet`.
              criticalSet.disallowRootCapability { () =>
                report.error(
                  em"""$actual cannot be box-converted to $expected
                      |since one of their capture sets contains the root capability `*`""",
                pos)
              }
              if covariant == actual.isBoxed then markFree(refs, pos)
              CapturingType(parent1, refs, boxed = !actual.isBoxed)
          else
            actual.derivedCapturingType(parent1, refs)
        case actual @ AppliedType(tycon, args) if defn.isNonRefinedFunction(actual) =>
          adaptFun(actual, args.init, args.last, expected, covariant,
              (aargs1, ares1) => actual.derivedAppliedType(tycon, aargs1 :+ ares1))
        case actual @ RefinedType(_, _, rinfo: MethodType) if defn.isFunctionType(actual) =>
          // TODO Find a way to combine handling of generic and dependent function types (here and elsewhere)
          adaptFun(actual, rinfo.paramInfos, rinfo.resType, expected, covariant,
            (aargs1, ares1) =>
              rinfo.derivedLambdaType(paramInfos = aargs1, resType = ares1)
                .toFunctionType(isJava = false, alwaysDependent = true))
        case _ => actual

      var actualw = actual.widenDealias
      actual match
        case ref: CaptureRef if ref.isTracked =>
          actualw match
            case CapturingType(p, refs) =>
              actualw = actualw.derivedCapturingType(p, ref.singletonCaptureSet)
                // given `a: C T`, improve `C T` to `{a} T`
            case _ =>
        case _ =>
      val adapted = adapt(actualw, expected, covariant = true)
      if adapted ne actualw then
        capt.println(i"adapt boxed $actual vs $expected ===> $adapted")
        adapted
      else actual
    end adaptBoxed

    override def checkUnit(unit: CompilationUnit)(using Context): Unit =
      Setup(preRecheckPhase, thisPhase, recheckDef)
        .traverse(ctx.compilationUnit.tpdTree)
      withCaptureSetsExplained {
        super.checkUnit(unit)
        checkSelfTypes(unit.tpdTree)
        postCheck(unit.tpdTree)
        if ctx.settings.YccDebug.value then
          show(unit.tpdTree) // this does not print tree, but makes its variables visible for dependency printing
      }

    /** Check that self types of subclasses conform to self types of super classes.
     *  (See comment below how this is achieved). The check assumes that classes
     *  without an explicit self type have the universal capture set `{*}` on the
     *  self type. If a class without explicit self type is not `effectivelyFinal`
     *  it is checked that the inferred self type is universal, in order to assure
     *  that joint and separate compilation give the same result.
     */
    def checkSelfTypes(unit: tpd.Tree)(using Context): Unit =
      val parentTrees = mutable.HashMap[Symbol, List[Tree]]()
      unit.foreachSubTree {
        case cdef @ TypeDef(_, impl: Template) => parentTrees(cdef.symbol) = impl.parents
        case _ =>
      }
      // Perform self type checking. The problem here is that `checkParents` compares a
      // self type of a subclass with the result of an asSeenFrom of the self type of the
      // superclass. That's no good. We need to constrain the original superclass self type
      // capture set, not the set mapped by asSeenFrom.
      //
      // Instead, we proceed from parent classes to child classes. For every class
      // we first check its parents, and then interpolate the self type to an
      // upper approximation that satisfies all constraints on its capture set.
      // That means all capture sets of parent self types are constants, so mapping
      // them with asSeenFrom is OK.
      while parentTrees.nonEmpty do
        val roots = parentTrees.keysIterator.filter {
          cls => !parentTrees(cls).exists(ptree => parentTrees.contains(ptree.tpe.classSymbol))
        }
        assert(roots.nonEmpty)
        for root <- roots do
          checkParents(root, parentTrees(root))
          val selfType = root.asClass.classInfo.selfType
          interpolator(startingVariance = -1).traverse(selfType)
          if !root.isEffectivelySealed  then
            selfType match
              case CapturingType(_, refs: CaptureSet.Var) if !refs.isUniversal =>
                report.error(
                  i"""$root needs an explicitly declared self type since its
                     |inferred self type $selfType
                     |is not visible in other compilation units that define subclasses.""",
                  root.srcPos)
              case _ =>
          parentTrees -= root
          capt.println(i"checked $root with $selfType")
    end checkSelfTypes

    /** Perform the following kinds of checks
     *   - Check all explicitly written capturing types for well-formedness using `checkWellFormedPost`.
     *   - Check that externally visible `val`s or `def`s have empty capture sets. If not,
     *     suggest an explicit type. This is so that separate compilation (where external
     *     symbols have empty capture sets) gives the same results as joint compilation.
     */
    def postCheck(unit: tpd.Tree)(using Context): Unit =
      unit.foreachSubTree {
        case _: InferredTypeTree =>
        case tree: TypeTree if !tree.span.isZeroExtent =>
          tree.knownType.foreachPart { tp =>
            checkWellformedPost(tp, tree.srcPos)
            tp match
              case AnnotatedType(_, annot) if annot.symbol == defn.RetainsAnnot =>
                warnIfRedundantCaptureSet(annot.tree)
              case _ =>
          }
        case t: ValOrDefDef
        if t.tpt.isInstanceOf[InferredTypeTree] && !Synthetics.isExcluded(t.symbol) =>
          val sym = t.symbol
          val isLocal =
            sym.owner.ownersIterator.exists(_.isTerm)
            || sym.accessBoundary(defn.RootClass).isContainedIn(sym.topLevelClass)

          // The following classes of definitions need explicit capture types ...
          if !isLocal                            // ... since external capture types are not inferred
            || sym.owner.is(Trait)               // ... since we do OverridingPairs checking before capture inference
            || sym.allOverriddenSymbols.nonEmpty // ... since we do override checking before capture inference
          then
            val inferred = t.tpt.knownType
            def checkPure(tp: Type) = tp match
              case CapturingType(_, refs) if !refs.elems.isEmpty =>
                val resultStr = if t.isInstanceOf[DefDef] then " result" else ""
                report.error(
                  em"""Non-local $sym cannot have an inferred$resultStr type
                      |$inferred
                      |with non-empty capture set $refs.
                      |The type needs to be declared explicitly.""", t.srcPos)
              case _ =>
            inferred.foreachPart(checkPure, StopAt.Static)
        case _ =>
      }

  end CaptureChecker
end CheckCaptures
