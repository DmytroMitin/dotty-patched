package dotty.tools
package dotc
package transform

import core.*
import Symbols.*, Contexts.*, Types.*, ContextOps.*, Decorators.*, SymDenotations.*
import Flags.*, SymUtils.*, NameKinds.*
import ast.*
import Names.Name
import Phases.Phase
import DenotTransformers.{DenotTransformer, IdentityDenotTransformer, SymTransformer}
import NamerOps.{methodType, linkConstructorParams}
import NullOpsDecorator.stripNull
import typer.ErrorReporting.err
import typer.ProtoTypes.*
import typer.TypeAssigner.seqLitType
import typer.ConstFold
import NamerOps.methodType
import config.Printers.recheckr
import util.Property
import StdNames.nme
import reporting.trace
import annotation.constructorOnly

object Recheck:
  import tpd.Tree

  /** A flag used to indicate that a ParamAccessor has been temporarily made not-private
   *  Only used at the start of the Recheck phase, reset at its end.
   *  The flag repurposes the Scala2ModuleVar flag. No confusion is possible since
   *  Scala2ModuleVar cannot be also ParamAccessors.
   */
  val ResetPrivate = Scala2ModuleVar
  val ResetPrivateParamAccessor = ResetPrivate | ParamAccessor

  /** Attachment key for rechecked types of TypeTrees */
  val RecheckedType = Property.Key[Type]

  extension (sym: Symbol)

    /** Update symbol's info to newInfo from prevPhase.next to lastPhase.
     *  Reset to previous info for phases after lastPhase.
     */
    def updateInfoBetween(prevPhase: DenotTransformer, lastPhase: DenotTransformer, newInfo: Type)(using Context): Unit =
      if sym.info ne newInfo then
        sym.copySymDenotation(
            initFlags =
              if sym.flags.isAllOf(ResetPrivateParamAccessor)
              then sym.flags &~ ResetPrivate | Private
              else sym.flags
          ).installAfter(lastPhase) // reset
        sym.copySymDenotation(
            info = newInfo,
            initFlags =
              if newInfo.isInstanceOf[LazyType] then sym.flags &~ Touched
              else sym.flags
          ).installAfter(prevPhase)

    /** Does symbol have a new denotation valid from phase.next that is different
     *  from the denotation it had before?
     */
    def isUpdatedAfter(phase: Phase)(using Context) =
      val symd = sym.denot
      symd.validFor.firstPhaseId == phase.id + 1 && (sym.originDenotation ne symd)

  extension (tree: Tree)

    /** Remember `tpe` as the type of `tree`, which might be different from the
     *  type stored in the tree itself, unless a type was already remembered for `tree`.
     */
    def rememberType(tpe: Type)(using Context): Unit =
      if !tree.hasAttachment(RecheckedType) then rememberTypeAlways(tpe)

    /** Remember `tpe` as the type of `tree`, which might be different from the
     *  type stored in the tree itself
     */
    def rememberTypeAlways(tpe: Type)(using Context): Unit =
      if tpe ne tree.tpe then tree.putAttachment(RecheckedType, tpe)

    /** The remembered type of the tree, or if none was installed, the original type */
    def knownType =
      tree.attachmentOrElse(RecheckedType, tree.tpe)

    def hasRememberedType: Boolean = tree.hasAttachment(RecheckedType)

/** A base class that runs a simplified typer pass over an already re-typed program. The pass
 *  does not transform trees but returns instead the re-typed type of each tree as it is
 *  traversed. The Recheck phase must be directly preceded by a phase of type PreRecheck.
 */
abstract class Recheck extends Phase, SymTransformer:
  thisPhase =>

  import ast.tpd.*
  import Recheck.*

  def preRecheckPhase = this.prev.asInstanceOf[PreRecheck]

  override def changesBaseTypes: Boolean = true

  override def isCheckable = false
    // TODO: investigate what goes wrong we Ycheck directly after rechecking.
    // One failing test is pos/i583a.scala

  /** Change any `ResetPrivate` flags back to `Private` */
  def transformSym(sym: SymDenotation)(using Context): SymDenotation =
    if sym.isAllOf(Recheck.ResetPrivateParamAccessor) then
      sym.copySymDenotation(initFlags = sym.flags &~ Recheck.ResetPrivate | Private)
    else sym

  def run(using Context): Unit =
    newRechecker().checkUnit(ctx.compilationUnit)

  def newRechecker()(using Context): Rechecker

  /** The typechecker pass */
  class Rechecker(@constructorOnly ictx: Context):
    private val ta = ictx.typeAssigner

    /** If true, remember types of all tree nodes in attachments so that they
     *  can be retrieved with `knownType`
     */
    private val keepAllTypes = inContext(ictx) {
      ictx.settings.Xprint.value.containsPhase(thisPhase)
    }

    /** Should type of `tree` be kept in an attachment so that it can be retrieved with
     *  `knownType`? By default true only is `keepAllTypes` hold, but can be overridden.
     */
    def keepType(tree: Tree): Boolean = keepAllTypes

    /** Constant-folded rechecked type `tp` of tree `tree` */
    private def constFold(tree: Tree, tp: Type)(using Context): Type =
      val tree1 = tree.withType(tp)
      val tree2 = ConstFold(tree1)
      if tree2 ne tree1 then tree2.tpe else tp

    def recheckIdent(tree: Ident)(using Context): Type =
      tree.tpe

    def recheckSelect(tree: Select)(using Context): Type =
      val Select(qual, name) = tree
      recheckSelection(tree, recheck(qual).widenIfUnstable, name)

    /** Keep the symbol of the `select` but re-infer its type */
    def recheckSelection(tree: Select, qualType: Type, name: Name)(using Context) =
      if name.is(OuterSelectName) then tree.tpe
      else
        //val pre = ta.maybeSkolemizePrefix(qualType, name)
        val mbr = qualType.findMember(name, qualType,
            excluded = if tree.symbol.is(Private) then EmptyFlags else Private
          ).suchThat(tree.symbol == _)
        constFold(tree, qualType.select(name, mbr))
          //.showing(i"recheck select $qualType . $name : ${mbr.info} = $result")

    def recheckBind(tree: Bind, pt: Type)(using Context): Type = tree match
      case Bind(name, body) =>
        recheck(body, pt)
        val sym = tree.symbol
        if sym.isType then sym.typeRef else sym.info

    def recheckLabeled(tree: Labeled, pt: Type)(using Context): Type = tree match
      case Labeled(bind, expr) =>
        val bindType = recheck(bind, pt)
        val exprType = recheck(expr, defn.UnitType)
        bindType

    def recheckValDef(tree: ValDef, sym: Symbol)(using Context): Unit =
      if !tree.rhs.isEmpty then recheck(tree.rhs, sym.info)

    def recheckDefDef(tree: DefDef, sym: Symbol)(using Context): Unit =
      val rhsCtx = linkConstructorParams(sym).withOwner(sym)
      if !tree.rhs.isEmpty && !sym.isInlineMethod && !sym.isEffectivelyErased then
        inContext(rhsCtx) { recheck(tree.rhs, recheck(tree.tpt)) }

    def recheckTypeDef(tree: TypeDef, sym: Symbol)(using Context): Type =
      recheck(tree.rhs)
      sym.typeRef

    def recheckClassDef(tree: TypeDef, impl: Template, sym: ClassSymbol)(using Context): Type =
      recheck(impl.constr)
      impl.parentsOrDerived.foreach(recheck(_))
      recheck(impl.self)
      recheckStats(impl.body)
      sym.typeRef

    /** Assuming `formals` are parameters of a Java-defined method, remap Object
     *  to FromJavaObject since it got lost in ElimRepeated
     */
    private def mapJavaArgs(formals: List[Type])(using Context): List[Type] =
      val tm = new TypeMap:
        def apply(t: Type) = t match
          case t: TypeRef if t.symbol == defn.ObjectClass => defn.FromJavaObjectType
          case _ => mapOver(t)
      formals.mapConserve(tm)

    /** Hook for method type instantiation */
    protected def instantiate(mt: MethodType, argTypes: List[Type], sym: Symbol)(using Context): Type =
      mt.instantiate(argTypes)

    def recheckApply(tree: Apply, pt: Type)(using Context): Type =
      recheck(tree.fun).widen match
        case fntpe: MethodType =>
          assert(fntpe.paramInfos.hasSameLengthAs(tree.args))
          val formals =
            if tree.symbol.is(JavaDefined) then mapJavaArgs(fntpe.paramInfos)
            else fntpe.paramInfos
          def recheckArgs(args: List[Tree], formals: List[Type], prefs: List[ParamRef]): List[Type] = args match
            case arg :: args1 =>
              val argType = recheck(arg, formals.head)
              val formals1 =
                if fntpe.isParamDependent
                then formals.tail.map(_.substParam(prefs.head, argType))
                else formals.tail
              argType :: recheckArgs(args1, formals1, prefs.tail)
            case Nil =>
              assert(formals.isEmpty)
              Nil
          val argTypes = recheckArgs(tree.args, formals, fntpe.paramRefs)
          constFold(tree, instantiate(fntpe, argTypes, tree.fun.symbol))
            //.showing(i"typed app $tree : $fntpe with ${tree.args}%, % : $argTypes%, % = $result")

    def recheckTypeApply(tree: TypeApply, pt: Type)(using Context): Type =
      recheck(tree.fun).widen match
        case fntpe: PolyType =>
          assert(fntpe.paramInfos.hasSameLengthAs(tree.args))
          val argTypes = tree.args.map(recheck(_))
          constFold(tree, fntpe.instantiate(argTypes))

    def recheckTyped(tree: Typed)(using Context): Type =
      val tptType = recheck(tree.tpt)
      recheck(tree.expr, tptType)
      tptType

    def recheckAssign(tree: Assign)(using Context): Type =
      val lhsType = recheck(tree.lhs)
      recheck(tree.rhs, lhsType.widen)
      defn.UnitType

    def recheckBlock(stats: List[Tree], expr: Tree, pt: Type)(using Context): Type =
      recheckStats(stats)
      val exprType = recheck(expr)
        // The expected type `pt` is not propagated. Doing so would allow variables in the
        // expected type to contain references to local symbols of the block, so the
        // local symbols could escape that way.
      TypeOps.avoid(exprType, localSyms(stats).filterConserve(_.isTerm))

    def recheckBlock(tree: Block, pt: Type)(using Context): Type =
      recheckBlock(tree.stats, tree.expr, pt)

    def recheckInlined(tree: Inlined, pt: Type)(using Context): Type =
      recheckBlock(tree.bindings, tree.expansion, pt)

    def recheckIf(tree: If, pt: Type)(using Context): Type =
      recheck(tree.cond, defn.BooleanType)
      recheck(tree.thenp, pt) | recheck(tree.elsep, pt)

    def recheckClosure(tree: Closure, pt: Type)(using Context): Type =
      if tree.tpt.isEmpty then
        tree.meth.tpe.widen.toFunctionType(tree.meth.symbol.is(JavaDefined))
      else
        recheck(tree.tpt)

    def recheckMatch(tree: Match, pt: Type)(using Context): Type =
      val selectorType = recheck(tree.selector)
      val casesTypes = tree.cases.map(recheckCase(_, selectorType.widen, pt))
      TypeComparer.lub(casesTypes)

    def recheckCase(tree: CaseDef, selType: Type, pt: Type)(using Context): Type =
      recheck(tree.pat, selType)
      recheck(tree.guard, defn.BooleanType)
      recheck(tree.body, pt)

    def recheckReturn(tree: Return)(using Context): Type =
      // Avoid local pattern defined symbols in returns from matchResult blocks
      // that are inserted by the PatternMatcher transform.
      // For regular symbols in the case branch, we already avoid them by the rule
      // for blocks since a case branch is of the form `return[MatchResultN] { ... }`
      // For source-level returns from methods, there's nothing to avoid, since the
      // result type of a method with a return must be given explicitly.
      def avoidMap = new TypeOps.AvoidMap:
        def toAvoid(tp: NamedType) =
           tp.symbol.is(Case) && tp.symbol.owner.isContainedIn(ctx.owner)

      val rawType = recheck(tree.expr)
      val ownType = avoidMap(rawType)
      checkConforms(ownType, tree.from.symbol.returnProto, tree)
      defn.NothingType
    end recheckReturn

    def recheckWhileDo(tree: WhileDo)(using Context): Type =
      recheck(tree.cond, defn.BooleanType)
      recheck(tree.body, defn.UnitType)
      defn.UnitType

    def recheckTry(tree: Try, pt: Type)(using Context): Type =
      val bodyType = recheck(tree.expr, pt)
      val casesTypes = tree.cases.map(recheckCase(_, defn.ThrowableType, pt))
      val finalizerType = recheck(tree.finalizer, defn.UnitType)
      TypeComparer.lub(bodyType :: casesTypes)

    def recheckSeqLiteral(tree: SeqLiteral, pt: Type)(using Context): Type =
      val elemProto = pt.stripNull.elemType match
        case NoType => WildcardType
        case bounds: TypeBounds => WildcardType(bounds)
        case elemtp => elemtp
      val declaredElemType = recheck(tree.elemtpt)
      val elemTypes = tree.elems.map(recheck(_, elemProto))
      seqLitType(tree, TypeComparer.lub(declaredElemType :: elemTypes))

    def recheckTypeTree(tree: TypeTree)(using Context): Type =
      knownType(tree) // allows to install new types at Setup

    def recheckAnnotated(tree: Annotated)(using Context): Type =
      tree.tpe match
        case tp: AnnotatedType =>
          val argType = recheck(tree.arg)
          tp.derivedAnnotatedType(argType, tp.annot)

    def recheckAlternative(tree: Alternative, pt: Type)(using Context): Type =
      val altTypes = tree.trees.map(recheck(_, pt))
      TypeComparer.lub(altTypes)

    def recheckPackageDef(tree: PackageDef)(using Context): Type =
      recheckStats(tree.stats)
      NoType

    def recheckStats(stats: List[Tree])(using Context): Unit =
      stats.foreach(recheck(_))

    def recheckDef(tree: ValOrDefDef, sym: Symbol)(using Context): Unit =
      inContext(ctx.localContext(tree, sym)) {
        tree match
          case tree: ValDef => recheckValDef(tree, sym)
          case tree: DefDef => recheckDefDef(tree, sym)
      }

    /** Recheck tree without adapting it, returning its new type.
     *  @param tree        the original tree
     *  @param pt          the expected result type
     */
    def recheckStart(tree: Tree, pt: Type = WildcardType)(using Context): Type =

      def recheckNamed(tree: NameTree, pt: Type)(using Context): Type =
        val sym = tree.symbol
        tree match
          case tree: Ident => recheckIdent(tree)
          case tree: Select => recheckSelect(tree)
          case tree: Bind => recheckBind(tree, pt)
          case tree: ValOrDefDef =>
            if tree.isEmpty then NoType
            else
              if sym.isUpdatedAfter(preRecheckPhase) then
                sym.ensureCompleted() // in this case the symbol's completer should recheck the right hand side
              else
                recheckDef(tree, sym)
              sym.termRef
          case tree: TypeDef =>
            // TODO: Should we allow for completers as for ValDefs or DefDefs?
            tree.rhs match
              case impl: Template =>
                recheckClassDef(tree, impl, sym.asClass)(using ctx.localContext(tree, sym))
              case _ =>
                recheckTypeDef(tree, sym)(using ctx.localContext(tree, sym))
          case tree: Labeled => recheckLabeled(tree, pt)

      def recheckUnnamed(tree: Tree, pt: Type): Type = tree match
        case tree: Apply => recheckApply(tree, pt)
        case tree: TypeApply => recheckTypeApply(tree, pt)
        case _: New | _: This | _: Super | _: Literal => tree.tpe
        case tree: Typed => recheckTyped(tree)
        case tree: Assign => recheckAssign(tree)
        case tree: Block => recheckBlock(tree, pt)
        case tree: If => recheckIf(tree, pt)
        case tree: Closure => recheckClosure(tree, pt)
        case tree: Match => recheckMatch(tree, pt)
        case tree: Return => recheckReturn(tree)
        case tree: WhileDo => recheckWhileDo(tree)
        case tree: Try => recheckTry(tree, pt)
        case tree: SeqLiteral => recheckSeqLiteral(tree, pt)
        case tree: Inlined => recheckInlined(tree, pt)
        case tree: TypeTree => recheckTypeTree(tree)
        case tree: Annotated => recheckAnnotated(tree)
        case tree: Alternative => recheckAlternative(tree, pt)
        case tree: PackageDef => recheckPackageDef(tree)
        case tree: Thicket => defn.NothingType
        case tree: Import => defn.NothingType

      tree match
        case tree: NameTree => recheckNamed(tree, pt)
        case tree => recheckUnnamed(tree, pt)
    end recheckStart

    /** Finish rechecking a tree node: check rechecked type against expected type
     *  and remember rechecked type in a tree attachment if required.
     *  @param tpe   the recheched type of `tree`
     *  @param tree  the rechecked tree
     *  @param pt    the expected type
     */
    def recheckFinish(tpe: Type, tree: Tree, pt: Type)(using Context): Type =
      checkConforms(tpe, pt, tree)
      if keepType(tree) then tree.rememberType(tpe)
      tpe

    def recheck(tree: Tree, pt: Type = WildcardType)(using Context): Type =
      trace(i"rechecking $tree with pt = $pt", recheckr, show = true) {
        try recheckFinish(recheckStart(tree, pt), tree, pt)
        catch case ex: Exception =>
          println(i"error while rechecking $tree")
          throw ex
      }

    /** If true, print info for some successful checkConforms operations (failing ones give
     *  an error message in any case).
     */
    private val debugSuccesses = false

    /** Check that widened types of `tpe` and `pt` are compatible. */
    def checkConforms(tpe: Type, pt: Type, tree: Tree)(using Context): Unit = tree match
      case _: DefTree | EmptyTree | _: TypeTree | _: Closure =>
        // Don't report closure nodes, since their span is a point; wait instead
        // for enclosing block to preduce an error
      case _ =>
        checkConformsExpr(tpe.widenExpr, pt.widenExpr, tree)

    def checkConformsExpr(actual: Type, expected: Type, tree: Tree)(using Context): Unit =
      //println(i"check conforms $actual <:< $expected")
      val isCompatible =
        actual <:< expected
        || expected.isRepeatedParam
            && actual <:< expected.translateFromRepeated(toArray = tree.tpe.isRef(defn.ArrayClass))
      if !isCompatible then
        recheckr.println(i"conforms failed for ${tree}: $actual vs $expected")
        err.typeMismatch(tree.withType(actual), expected)
      else if debugSuccesses then
        tree match
          case _: Ident =>
            println(i"SUCCESS $tree:\n${TypeComparer.explained(_.isSubType(actual, expected))}")
          case _ =>

    def checkUnit(unit: CompilationUnit)(using Context): Unit =
      recheck(unit.tpdTree)

  end Rechecker

  /** Show tree with rechecked types instead of the types stored in the `.tpe` field */
  override def show(tree: untpd.Tree)(using Context): String =
    val addRecheckedTypes = new TreeMap:
      override def transform(tree: Tree)(using Context): Tree =
        val tree1 = super.transform(tree)
        tree.getAttachment(RecheckedType) match
          case Some(tpe) => tree1.withType(tpe)
          case None => tree1
    atPhase(thisPhase) {
      super.show(addRecheckedTypes.transform(tree.asInstanceOf[tpd.Tree]))
    }
end Recheck

/** A class that can be used to test basic rechecking without any customaization */
object TestRecheck:
  class Pre extends PreRecheck, IdentityDenotTransformer:
    override def isEnabled(using Context) = ctx.settings.YrecheckTest.value

class TestRecheck extends Recheck:
  def phaseName: String = "recheck"
  override def isEnabled(using Context) = ctx.settings.YrecheckTest.value
  def newRechecker()(using Context): Rechecker = Rechecker(ctx)


