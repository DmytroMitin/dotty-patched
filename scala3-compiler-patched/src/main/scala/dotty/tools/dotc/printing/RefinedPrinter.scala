package dotty.tools
package dotc
package printing

import core._
import Texts._
import Types._
import Flags._
import Names._
import Symbols._
import NameOps._
import TypeErasure.ErasedValueType
import Contexts._
import Annotations.Annotation
import Denotations._
import SymDenotations._
import StdNames.{nme, tpnme}
import ast.{Trees, tpd, untpd}
import typer.{Implicits, Namer, Applications}
import typer.ProtoTypes._
import Trees._
import TypeApplications._
import NameKinds.{WildcardParamName, DefaultGetterName}
import util.Chars.isOperatorPart
import transform.TypeUtils._
import transform.SymUtils._
import config.Config

import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.ast.untpd.{MemberDef, Modifiers, PackageDef, RefTree, Template, TypeDef, ValOrDefDef}
import cc.{CaptureSet, toCaptureSet, IllegalCaptureRef}

class RefinedPrinter(_ctx: Context) extends PlainPrinter(_ctx) {

  /** A stack of enclosing DefDef, TypeDef, or ClassDef, or ModuleDefs nodes */
  private var enclosingDef: untpd.Tree = untpd.EmptyTree
  private var myCtx: Context = super.printerContext
  private var printPos = ctx.settings.YprintPos.value
  private val printLines = ctx.settings.printLines.value

  override def printerContext: Context = myCtx

  def withEnclosingDef(enclDef: Tree[? >: Untyped])(op: => Text): Text = {
    val savedCtx = myCtx
    if (enclDef.hasType && enclDef.symbol.exists)
      myCtx = ctx.withOwner(enclDef.symbol)
    val savedDef = enclosingDef
    enclosingDef = enclDef
    try op finally {
      myCtx = savedCtx
      enclosingDef = savedDef
    }
  }

  def inPattern(op: => Text): Text = {
    val savedCtx = myCtx
    myCtx = ctx.addMode(Mode.Pattern)
    try op finally myCtx = savedCtx
  }

  def withoutPos(op: => Text): Text = {
    val savedPrintPos = printPos
    printPos = false
    try op finally printPos = savedPrintPos
  }

  protected def enclDefIsClass: Boolean = enclosingDef match {
    case owner: TypeDef => owner.isClassDef
    case owner: untpd.ModuleDef => true
    case _ => false
  }

  protected def PrintableFlags(isType: Boolean): FlagSet = {
    val fs =
      if (isType) TypeSourceModifierFlags | Module | Local  // DOTTY problem: cannot merge these two statements
      else TermSourceModifierFlags | Module | Local
    fs.toCommonFlags
  }

  override def nameString(name: Name): String =
    def strippedName = if printDebug then name else name.stripModuleClassSuffix
    if ctx.settings.YdebugNames.value then strippedName.debugString
    else if name.isTypeName && name.is(WildcardParamName) && !printDebug then "_"
    else super.nameString(strippedName)

  override protected def simpleNameString(sym: Symbol): String =
    nameString(if (ctx.property(XprintMode).isEmpty) sym.initial.name else sym.name)

  override def fullNameString(sym: Symbol): String =
    if !sym.exists || isEmptyPrefix(sym.effectiveOwner) then nameString(sym)
    else super.fullNameString(sym)

  override protected def fullNameOwner(sym: Symbol): Symbol = {
    val owner = super.fullNameOwner(sym)
    if (owner.is(ModuleClass)) owner.sourceModule else owner
  }

  override def toTextRef(tp: SingletonType): Text = controlled {
    tp match {
      case tp: ThisType if !printDebug =>
        if (tp.cls.isAnonymousClass) keywordStr("this")
        if (tp.cls.is(ModuleClass)) fullNameString(tp.cls.sourceModule)
        else super.toTextRef(tp)
      case _ =>
        super.toTextRef(tp)
    }
  }

  override def toTextPrefix(tp: Type): Text = controlled {
    def isOmittable(sym: Symbol) =
      if printDebug then false
      else if homogenizedView then isEmptyPrefix(sym) // drop <root> and anonymous classes, but not scala, Predef.
      else if sym.isPackageObject then isOmittablePrefix(sym.owner)
      else isOmittablePrefix(sym)
    tp match {
      case tp: ThisType if isOmittable(tp.cls) =>
        ""
      case tp @ TermRef(pre, _) =>
        val sym = tp.symbol
        if sym.isPackageObject && !homogenizedView && !printDebug then toTextPrefix(pre)
        else if (isOmittable(sym)) ""
        else super.toTextPrefix(tp)
      case _ => super.toTextPrefix(tp)
    }
  }

  override protected def toTextParents(parents: List[Type]): Text =
    Text(parents.map(toTextLocal).map(typeText), keywordStr(" with "))

  override protected def refinementNameString(tp: RefinedType): String =
    if (tp.parent.isInstanceOf[WildcardType] || tp.refinedName == nme.WILDCARD)
      super.refinementNameString(tp)
    else {
      val tsym = tp.parent.member(tp.refinedName).symbol
      if (!tsym.exists) super.refinementNameString(tp)
      else simpleNameString(tsym)
    }

  private def arrow(isGiven: Boolean, isPure: Boolean): String =
    (if isGiven then "?" else "") + (if isPure then "->" else "=>")

  override def toText(tp: Type): Text = controlled {
    def toTextTuple(args: List[Type]): Text =
      "(" ~ argsText(args) ~ ")"

    def toTextFunction(args: List[Type], isGiven: Boolean, isErased: Boolean, isPure: Boolean): Text =
      changePrec(GlobalPrec) {
        val argStr: Text =
          if args.length == 2
             && !defn.isTupleNType(args.head)
             && !isGiven && !isErased
          then
            atPrec(InfixPrec) { argText(args.head) }
          else
            "("
            ~ keywordText("erased ").provided(isErased)
            ~ argsText(args.init)
            ~ ")"
        argStr ~ " " ~ arrow(isGiven, isPure) ~ " " ~ argText(args.last)
      }

    def toTextMethodAsFunction(info: Type, isPure: Boolean): Text = info match
      case info: MethodType =>
        changePrec(GlobalPrec) {
          "("
          ~ keywordText("erased ").provided(info.isErasedMethod)
          ~ paramsText(info)
          ~ ") "
          ~ arrow(info.isImplicitMethod, isPure)
          ~ " "
          ~ toTextMethodAsFunction(info.resultType, isPure)
        }
      case info: PolyType =>
        changePrec(GlobalPrec) {
          "["
          ~ paramsText(info)
          ~ "] => "
          ~ toTextMethodAsFunction(info.resultType, isPure)
        }
      case _ =>
        toText(info)

    def isInfixType(tp: Type): Boolean = tp match
      case AppliedType(tycon, args) =>
        args.length == 2
        && {
          val sym = tycon.typeSymbol
          sym.is(Infix)
          || sym.getAnnotation(defn.ShowAsInfixAnnot)
              .exists(_.argumentConstant(0).forall(_.booleanValue))
          || !Character.isUnicodeIdentifierStart(tycon.typeSymbol.name.toString.head)
        }
      case _ => false

    def tyconName(tp: Type): Name = tp.typeSymbol.name
    def checkAssocMismatch(tp: Type, isRightAssoc: Boolean) = tp match {
      case AppliedType(tycon, _) => isInfixType(tp) && tyconName(tycon).endsWith(":") != isRightAssoc
      case AndType(_, _) => isRightAssoc
      case OrType(_, _) => isRightAssoc
      case _ => false
    }

    def toTextInfixType(opName: Name, l: Type, r: Type)(op: => Text): Text = {
      val isRightAssoc = opName.endsWith(":")
      val opPrec = parsing.precedence(opName)

      changePrec(opPrec) {
        val leftPrec = if (isRightAssoc || checkAssocMismatch(l, isRightAssoc)) opPrec + 1 else opPrec
        val rightPrec = if (!isRightAssoc || checkAssocMismatch(r, isRightAssoc)) opPrec + 1 else opPrec

        atPrec(leftPrec) { argText(l) }  ~ " " ~ op ~ " " ~ atPrec(rightPrec) { argText(r) }
      }
    }

    def appliedText(tp: Type): Text = tp match
      case tp @ AppliedType(tycon, args) =>
        tp.tupleElementTypes match
          case Some(types) if types.size >= 2 && !printDebug => toTextTuple(types)
          case _ =>
            val tsym = tycon.typeSymbol
            if tycon.isRepeatedParam then toTextLocal(args.head) ~ "*"
            else if defn.isFunctionSymbol(tsym) then
              toTextFunction(args, tsym.name.isContextFunction, tsym.name.isErasedFunction,
                isPure = ctx.settings.Ycc.value && !tsym.name.isImpureFunction)
            else if isInfixType(tp) then
              val l :: r :: Nil = args: @unchecked
              val opName = tyconName(tycon)
              toTextInfixType(tyconName(tycon), l, r) { simpleNameString(tycon.typeSymbol) }
            else Str("")
      case _ =>
        Str("")

    homogenize(tp) match {
      case tp: AppliedType =>
        val refined = appliedText(tp)
        if refined.isEmpty then super.toText(tp) else refined
      // Since RefinedPrinter, unlike PlainPrinter, can output right-associative type-operators, we must override handling
      // of AndType and OrType to account for associativity
      case AndType(tp1, tp2) =>
        toTextInfixType(tpnme.raw.AMP, tp1, tp2) { toText(tpnme.raw.AMP) }
      case tp @ OrType(tp1, tp2) =>
        toTextInfixType(tpnme.raw.BAR, tp1, tp2) {
          if tp.isSoft && printDebug then toText(tpnme.ZOR) else toText(tpnme.raw.BAR)
        }
      case tp @ EtaExpansion(tycon)
      if !printDebug && appliedText(tp.asInstanceOf[HKLambda].resType).isEmpty =>
        // don't eta contract if the application would be printed specially
        toText(tycon)
      case tp: RefinedType if defn.isFunctionOrPolyType(tp) && !printDebug =>
        toTextMethodAsFunction(tp.refinedInfo,
          isPure = ctx.settings.Ycc.value && !tp.typeSymbol.name.isImpureFunction)
      case tp: TypeRef =>
        if (tp.symbol.isAnonymousClass && !showUniqueIds)
          toText(tp.info)
        else if (tp.symbol.is(Param))
          tp.prefix match {
            case pre: ThisType if pre.cls == tp.symbol.owner =>
              nameString(tp.symbol)
            case _ => super.toText(tp)
          }
        else super.toText(tp)
      case ErasedValueType(tycon, underlying) =>
        "ErasedValueType(" ~ toText(tycon) ~ ", " ~ toText(underlying) ~ ")"
      case tp: ClassInfo =>
        if tp.cls.derivesFrom(defn.PolyFunctionClass) then
          tp.member(nme.apply).info match
            case info: PolyType => toTextMethodAsFunction(info, isPure = false)
            case _ => toTextParents(tp.parents) ~~ "{...}"
        else toTextParents(tp.parents) ~~ "{...}"
      case JavaArrayType(elemtp) =>
        toText(elemtp) ~ "[]"
      case tp: LazyRef if !printDebug =>
        try toText(tp.ref)
        catch case ex: Throwable => "..."
      case tp: SelectionProto =>
        "?{ " ~ toText(tp.name) ~
           (Str(" ") provided !tp.name.toSimpleName.last.isLetterOrDigit) ~
           ": " ~ toText(tp.memberProto) ~ " }"
      case tp: ViewProto =>
        toText(tp.argType) ~ " ?=>? " ~ toText(tp.resultType)
      case tp @ FunProto(args, resultType) =>
        val argsText = args match {
          case dummyTreeOfType(tp) :: Nil if !(tp isRef defn.NullClass) => "null: " ~ toText(tp)
          case _ => toTextGlobal(args, ", ")
        }
        "[applied to ("
        ~ keywordText("using ").provided(tp.isContextualMethod)
        ~ keywordText("erased ").provided(tp.isErasedMethod)
        ~ argsText
        ~ ") returning "
        ~ toText(resultType)
        ~ "]"
      case IgnoredProto(ignored) =>
        "?" ~ (("(ignored: " ~ toText(ignored) ~ ")") provided printDebug)
      case tp @ PolyProto(targs, resType) =>
        "[applied to [" ~ toTextGlobal(targs, ", ") ~ "] returning " ~ toText(resType)
      case _ =>
        super.toText(tp)
    }
  }

  protected def exprToText(tp: ExprType): Text =
    "=> " ~ toText(tp.resType)

  protected def blockToText[T >: Untyped](block: Block[T]): Text =
    blockText(block.stats :+ block.expr)

  protected def blockText[T >: Untyped](trees: List[Tree[T]]): Text =
    ("{" ~ toText(trees, "\n") ~ "}").close

  protected def typeApplyText[T >: Untyped](tree: TypeApply[T]): Text = {
    val funText = toTextLocal(tree.fun)
    tree.fun match {
      case Select(New(tpt), nme.CONSTRUCTOR) if tpt.typeOpt.dealias.isInstanceOf[AppliedType] =>
        funText  // type was already printed by toText(new)
      case _ =>
        funText ~ "[" ~ toTextGlobal(tree.args, ", ") ~ "]"
    }
  }

  protected def toTextCore[T >: Untyped](tree: Tree[T]): Text = {
    import untpd._

    def isLocalThis(tree: Tree) = tree.typeOpt match {
      case tp: ThisType => tp.cls == ctx.owner.enclosingClass
      case _ => false
    }

    def optDotPrefix(tree: This) = optText(tree.qual)(_ ~ ".") provided !isLocalThis(tree)

    def caseBlockText(tree: Tree): Text = tree match {
      case Block(stats, expr) => toText(stats :+ expr, "\n")
      case expr => toText(expr)
    }

    // Dotty deviation: called with an untpd.Tree, so cannot be a untpd.Tree[T] (seems to be a Scala2 problem to allow this)
    // More deviations marked below as // DD
    def enumText(tree: untpd.Tree) = tree match { // DD
      case _: untpd.GenFrom | _: untpd.GenAlias => toText(tree)
      case _ => keywordStr("if ") ~ toText(tree)
    }

    def forText(enums: List[untpd.Tree], expr: untpd.Tree, sep: String): Text = // DD
      changePrec(GlobalPrec) { keywordStr("for ") ~ Text(enums map enumText, "; ") ~ sep ~ toText(expr) }

    def cxBoundToText(bound: untpd.Tree): Text = bound match { // DD
      case AppliedTypeTree(tpt, _) => " : " ~ toText(tpt)
      case untpd.Function(_, tpt) => " <% " ~ toText(tpt)
    }

    def varianceText(mods: untpd.Modifiers) =
      if (mods.is(Covariant)) "+"
      else if (mods.is(Contravariant)) "-"
      else ""

    def argText(arg: Tree): Text = arg match {
      case arg: TypeBoundsTree => "?" ~ toTextGlobal(arg)
      case arg: TypeTree =>
        arg.typeOpt match {
          case tp: TypeBounds => "?" ~ toTextGlobal(arg)
          case _ => toTextGlobal(arg)
        }
      case _ => toTextGlobal(arg)
    }

    def dropBlock(tree: Tree): Tree = tree match {
      case Block(Nil, expr) => expr
      case _ => tree
    }

    def importText(expr: Tree, selectors: List[untpd.ImportSelector]) =

      def selectorText(sel: untpd.ImportSelector): Text =
        val id: Text =
          if sel.isGiven then keywordText("given")
          else sel.imported.name match
            case nme.WILDCARD => "*"
            case nme.raw.STAR => "`*`"
            case name => toText(name)
        val rename: Text =
          if sel.renamed.isEmpty then "" else Str(" as ") ~ toText(sel.renamed)
        val bound: Text =
          if sel.bound.isEmpty then ""
          else if sel.isGiven then Str(" ") ~ toText(sel.bound)
          else Str(" : ") ~ toText(sel.bound)
        id ~ rename ~ bound

      val selectorsText: Text = selectors match
        case (sel @ untpd.ImportSelector(name, EmptyTree, EmptyTree)) :: Nil =>
          selectorText(sel)
        case _ =>
          "{" ~ Text(selectors.map(selectorText), ", ") ~ "}"

      toTextLocal(expr) ~ "." ~ selectorsText

    tree match {
      case id: Trees.SearchFailureIdent[?] =>
        tree.typeOpt match {
          case reason: Implicits.SearchFailureType =>
            Str(id.explanation)
            ~ ("summon[" ~ toText(reason.clarify(reason.expectedType)) ~ "]").close
          case _ =>
            Str(id.explanation)
        }
      case id @ Ident(name) =>
        val txt = tree.typeOpt match {
          case tp: NamedType if name != nme.WILDCARD =>
            val pre = if (tp.symbol.is(JavaStatic)) tp.prefix.widen else tp.prefix
            toTextPrefix(pre) ~ withPos(selectionString(tp), tree.sourcePos)
          case _ =>
            toText(name)
        }
        if (isBackquoted(tree) && !homogenizedView) "`" ~ toText(name) ~ "`"
        else if (name.isTypeName) typeText(txt)
        else txt
      case tree @ Select(qual, name) =>
        if (qual.isType) toTextLocal(qual) ~ "#" ~ typeText(toText(name))
        else toTextLocal(qual) ~ ("." ~ nameIdText(tree) provided (name != nme.CONSTRUCTOR || printDebug))
      case tree: This =>
        optDotPrefix(tree) ~ keywordStr("this") ~ idText(tree)
      case Super(qual: This, mix) =>
        optDotPrefix(qual) ~ keywordStr("super") ~ optText(mix)("[" ~ _ ~ "]")
      case app @ Apply(fun, args) =>
        if (fun.hasType && fun.symbol == defn.throwMethod)
          changePrec (GlobalPrec) {
            keywordStr("throw ") ~ toText(args.head)
          }
        else if (!printDebug && fun.hasType && fun.symbol == defn.QuotedRuntime_exprQuote)
          keywordStr("'{") ~ toTextGlobal(args, ", ") ~ keywordStr("}")
        else if (!printDebug && fun.hasType && fun.symbol.isExprSplice)
          keywordStr("${") ~ toTextGlobal(args, ", ") ~ keywordStr("}")
        else
          toTextLocal(fun)
          ~ "("
          ~ Str("using ").provided(app.applyKind == ApplyKind.Using && !homogenizedView)
          ~ toTextGlobal(args, ", ")
          ~ ")"
      case tree: TypeApply =>
        typeApplyText(tree)
      case Literal(c) =>
        tree.typeOpt match {
          case ConstantType(tc) => withPos(toText(tc), tree.sourcePos)
          case _ => withPos(toText(c), tree.sourcePos)
        }
      case New(tpt) =>
        keywordStr("new ") ~ {
          tpt match {
            case tpt: Template => toTextTemplate(tpt, ofNew = true)
            case _ => toTextLocal(tpt)
          }
        }
      case Typed(expr, tpt) =>
        changePrec(InfixPrec) {
          if isWildcardStarArg(tree) then
            expr match
              case Ident(nme.WILDCARD_STAR) =>
              	// `_*` is used as a wildcard name to indicate a vararg splice pattern;
              	// avoid the double `*` in this case.
              	toText(expr)
              case _ =>
                toText(expr) ~ "*"
          else
            val exprText = toText(expr)
            val line = exprText.lastLine
            val colon = if !line.isEmpty && isOperatorPart(line.last) then " :" else ":"
            exprText ~ colon ~ toText(tpt)
        }
      case NamedArg(name, arg) =>
        toText(name) ~ " = " ~ toText(arg)
      case Assign(lhs, rhs) =>
        changePrec(GlobalPrec) { toTextLocal(lhs) ~ " = " ~ toText(rhs) }
      case block: Block =>
        blockToText(block)
      case If(cond, thenp, elsep) =>
        val isInline = tree.isInstanceOf[Trees.InlineIf[?]]
        changePrec(GlobalPrec) {
          keywordStr(if (isInline) "inline if " else "if ") ~
          toText(cond) ~ (keywordText(" then") provided !cond.isInstanceOf[Parens]) ~~
          toText(thenp) ~ optText(elsep)(keywordStr(" else ") ~ _)
        }
      case Closure(env, ref, target) =>
        "closure(" ~ (toTextGlobal(env, ", ") ~ " | " provided env.nonEmpty) ~
        toTextGlobal(ref) ~ (":" ~ toText(target) provided !target.isEmpty) ~ ")"
      case Match(sel, cases) =>
        val isInline = tree.isInstanceOf[Trees.InlineMatch[?]]
        if (sel.isEmpty && !isInline) blockText(cases)
        else changePrec(GlobalPrec) {
          val selTxt: Text =
            if (isInline)
              if (sel.isEmpty) keywordStr("implicit")
              else keywordStr("inline ") ~ toText(sel)
            else toText(sel)
          selTxt ~ keywordStr(" match ") ~ blockText(cases)
        }
      case CaseDef(pat, guard, body) =>
        keywordStr("case ") ~ inPattern(toText(pat)) ~ optText(guard)(keywordStr(" if ") ~ _) ~ " => " ~ caseBlockText(body)
      case Labeled(bind, expr) =>
        changePrec(GlobalPrec) { toText(bind.name) ~ keywordStr("[") ~ toText(bind.symbol.info) ~ keywordStr("]: ") ~ toText(expr) }
      case Return(expr, from) =>
        val sym = from.symbol
        if (sym.is(Label))
          changePrec(GlobalPrec) { keywordStr("return[") ~ toText(sym.name) ~ keywordStr("]") ~ optText(expr)(" " ~ _) }
        else
          changePrec(GlobalPrec) { keywordStr("return") ~ optText(expr)(" " ~ _) }
      case WhileDo(cond, body) =>
        changePrec(GlobalPrec) { keywordStr("while ") ~ toText(cond) ~ keywordStr(" do ") ~ toText(body) }
      case Try(expr, cases, finalizer) =>
        changePrec(GlobalPrec) {
          keywordStr("try ") ~ toText(expr) ~ optText(cases)(keywordStr(" catch ") ~ _) ~ optText(finalizer)(keywordStr(" finally ") ~ _)
        }
      case Throw(expr) =>
        changePrec(GlobalPrec) {
          keywordStr("throw ") ~ toText(expr)
        }
      case SeqLiteral(elems, elemtpt) =>
        "[" ~ toTextGlobal(elems, ",") ~ " : " ~ toText(elemtpt) ~ "]"
      case tree @ Inlined(call, bindings, body) =>
        (("/* inlined from " ~ (if (call.isEmpty) "outside" else toText(call)) ~ " */ ") `provided`
          !homogenizedView && ctx.settings.XprintInline.value) ~
          (if bindings.isEmpty then toText(body) else blockText(bindings :+ body))
      case tpt: untpd.DerivedTypeTree =>
        "<derived typetree watching " ~ tpt.watched.showSummary() ~ ">"
      case TypeTree() =>
        typeText(toText(tree.typeOpt))
        ~ Str("(inf)").provided(tree.isInstanceOf[InferredTypeTree] && printDebug)
      case SingletonTypeTree(ref) =>
        toTextLocal(ref) ~ "." ~ keywordStr("type")
      case RefinedTypeTree(tpt, refines) =>
        toTextLocal(tpt) ~ " " ~ blockText(refines)
      case AppliedTypeTree(tpt, args) =>
        if (tpt.symbol == defn.orType && args.length == 2)
          changePrec(OrTypePrec) { toText(args(0)) ~ " | " ~ atPrec(OrTypePrec + 1) { toText(args(1)) } }
        else if (tpt.symbol == defn.andType && args.length == 2)
          changePrec(AndTypePrec) { toText(args(0)) ~ " & " ~ atPrec(AndTypePrec + 1) { toText(args(1)) } }
        else if defn.isFunctionSymbol(tpt.symbol)
            && tpt.isInstanceOf[TypeTree] && tree.hasType && !printDebug
        then changePrec(GlobalPrec) { toText(tree.typeOpt) }
        else args match
          case arg :: _ if arg.isTerm =>
            toTextLocal(tpt) ~ "(" ~ Text(args.map(argText), ", ") ~ ")"
          case _ =>
            toTextLocal(tpt) ~ "[" ~ Text(args.map(argText), ", ") ~ "]"
      case LambdaTypeTree(tparams, body) =>
        changePrec(GlobalPrec) {
          paramsText(tparams) ~ " =>> " ~ toText(body)
        }
      case TermLambdaTypeTree(params, body) =>
        changePrec(GlobalPrec) {
          paramsText(params) ~ " =>> " ~ toText(body)
        }
      case MatchTypeTree(bound, sel, cases) =>
        changePrec(GlobalPrec) {
          toText(sel) ~ keywordStr(" match ") ~ blockText(cases) ~
          (" <: " ~ toText(bound) provided !bound.isEmpty)
        }
      case ByNameTypeTree(tpt) =>
        (if ctx.settings.Ycc.value then "-> " else "=> ")
        ~ toTextLocal(tpt)
      case TypeBoundsTree(lo, hi, alias) =>
        if (lo eq hi) && alias.isEmpty then optText(lo)(" = " ~ _)
        else optText(lo)(" >: " ~ _) ~ optText(hi)(" <: " ~ _) ~ optText(alias)(" = " ~ _)
      case bind @ Bind(name, body) =>
        keywordText("given ").provided(tree.symbol.isOneOf(GivenOrImplicit) && !homogenizedView) ~ // Used for scala.quoted.Type in quote patterns (not pickled)
        changePrec(InfixPrec) { nameIdText(bind) ~ " @ " ~ toText(body) }
      case Alternative(trees) =>
        changePrec(OrPrec) { toText(trees, " | ") }
      case UnApply(fun, implicits, patterns) =>
        val extractor = fun match {
          case Select(extractor, name) if name.isUnapplyName => extractor
          case _ => fun
        }
        toTextLocal(extractor) ~
        "(" ~ toTextGlobal(patterns, ", ") ~ ")" ~
        ("(" ~ toTextGlobal(implicits, ", ") ~ ")" provided implicits.nonEmpty)
      case tree @ ValDef(_, _, _) =>
        valDefToText(tree)
      case tree @ DefDef(_, _, _, _) =>
        defDefToText(tree)
      case tree @ TypeDef(name, rhs) =>
        def typeDefText(tparamsText: => Text, rhsText: => Text) =
          dclTextOr(tree) {
            modText(tree.mods, tree.symbol, keywordStr("type"), isType = true) ~~
            (varianceText(tree.mods) ~ typeText(nameIdText(tree))) ~
            withEnclosingDef(tree) { tparamsText ~ rhsText }
          }
        def recur(rhs: Tree, tparamsTxt: => Text, printMemberArgs: Boolean): Text = rhs match {
          case impl: Template =>
            templateText(tree, impl)
          case rhs: TypeBoundsTree =>
            typeDefText(tparamsTxt, toText(rhs))
          case LambdaTypeTree(tparams, body) if printMemberArgs =>
            recur(body, paramsText(tparams), false)
          case rhs: TypeTree if isBounds(rhs.typeOpt) =>
            typeDefText(tparamsTxt, toText(rhs))
          case rhs =>
            typeDefText(tparamsTxt, optText(rhs)(" = " ~ _))
        }
        recur(rhs, "", true)
      case Import(expr, selectors) =>
        keywordText("import ") ~ importText(expr, selectors)
      case Export(expr, selectors) =>
        keywordText("export ") ~ importText(expr, selectors)
      case ExtMethods(paramss, mdefs) =>
        addParamssText(keywordText("extension "), paramss)
        ~ " " ~ (if mdefs.length == 1 then toText(mdefs.head) else blockText(mdefs))
      case packageDef: PackageDef =>
        packageDefText(packageDef)
      case tree: Template =>
        toTextTemplate(tree)
      case Annotated(arg, annot) =>
        def captureSet =
          annot.asInstanceOf[tpd.Tree].toCaptureSet
        def toTextAnnot =
          toTextLocal(arg) ~~ annotText(annot.symbol.enclosingClass, annot)
        def toTextRetainsAnnot =
          try changePrec(GlobalPrec)(toText(captureSet) ~ " " ~ toText(arg))
          catch case ex: IllegalCaptureRef => toTextAnnot
        if annot.symbol.maybeOwner == defn.RetainsAnnot
            && ctx.settings.Ycc.value && Config.printCaptureSetsAsPrefix && !printDebug
        then toTextRetainsAnnot
        else toTextAnnot
      case EmptyTree =>
        "<empty>"
      case TypedSplice(t) =>
        if (printDebug) "[" ~ toText(t) ~ "]#TS#"
        else toText(t)
      case tree @ ModuleDef(name, impl) =>
        withEnclosingDef(tree) {
          modText(tree.mods, NoSymbol, keywordStr("object"), isType = false) ~~
          nameIdText(tree) ~ toTextTemplate(impl)
        }
      case SymbolLit(str) =>
        "'" + str
      case InterpolatedString(id, segments) =>
        def strText(str: Literal) = withPos(escapedString(str.const.stringValue), tree.sourcePos)
        def segmentText(segment: Tree) = segment match {
          case Thicket(List(str: Literal, expr)) => strText(str) ~ "{" ~ toTextGlobal(expr) ~ "}"
          case str: Literal => strText(str)
        }
        toText(id) ~ "\"" ~ Text(segments map segmentText, "") ~ "\""
      case Function(args, body) =>
        var implicitSeen: Boolean = false
        var isGiven: Boolean = false
        var isErased: Boolean = false
        def argToText(arg: Tree) = arg match {
          case arg @ ValDef(name, tpt, _) =>
            val implicitText =
              if ((arg.mods.is(Given))) { isGiven = true; "" }
              else if ((arg.mods.is(Erased))) { isErased = true; "" }
              else if ((arg.mods.is(Implicit)) && !implicitSeen) { implicitSeen = true; keywordStr("implicit ") }
              else ""
            implicitText ~ toText(name) ~ optAscription(tpt)
          case _ =>
            toText(arg)
        }
        val argsText = args match {
          case (arg @ ValDef(_, tpt, _)) :: Nil if tpt.isEmpty => argToText(arg)
          case _ =>
            "("
            ~ keywordText("erased ").provided(isErased)
            ~ Text(args.map(argToText), ", ")
            ~ ")"
        }
        val isPure =
          ctx.settings.Ycc.value
          && tree.match
            case tree: FunctionWithMods => !tree.mods.is(Impure)
            case _ => true
        argsText ~ " " ~ arrow(isGiven, isPure) ~ " " ~ toText(body)
      case PolyFunction(targs, body) =>
        val targsText = "[" ~ Text(targs.map((arg: Tree) => toText(arg)), ", ") ~ "]"
        changePrec(GlobalPrec) {
          targsText ~ " => " ~ toText(body)
        }
      case InfixOp(l, op, r) =>
        val opPrec = parsing.precedence(op.name)
        changePrec(opPrec) { toText(l) ~ " " ~ toText(op) ~ " " ~ toText(r) }
      case PostfixOp(l, op) =>
        changePrec(InfixPrec) { toText(l) ~ " " ~ toText(op) }
      case PrefixOp(op, r) =>
        changePrec(DotPrec) { toText(op) ~ " " ~ toText(r) }
      case Parens(t) =>
        "(" ~ toTextGlobal(t) ~ ")"
      case Tuple(ts) =>
        "(" ~ toTextGlobal(ts, ", ") ~ ")"
      case ForYield(enums, expr) =>
        forText(enums, expr, keywordStr(" yield "))
      case ForDo(enums, expr) =>
        forText(enums, expr, keywordStr(" do "))
      case GenFrom(pat, expr, checkMode) =>
        (Str("case ") provided checkMode == untpd.GenCheckMode.FilterAlways) ~
        toText(pat) ~ " <- " ~ toText(expr)
      case GenAlias(pat, expr) =>
        toText(pat) ~ " = " ~ toText(expr)
      case ContextBounds(bounds, cxBounds) =>
        cxBounds.foldLeft(toText(bounds)) {(t, cxb) =>
          t ~ cxBoundToText(cxb)
        }
      case PatDef(mods, pats, tpt, rhs) =>
        modText(mods, NoSymbol, keywordStr("val"), isType = false) ~~
        toText(pats, ", ") ~ optAscription(tpt) ~ optText(rhs)(" = " ~ _)
      case ParsedTry(expr, handler, finalizer) =>
        changePrec(GlobalPrec) {
          keywordStr("try ") ~ toText(expr) ~ " " ~ keywordStr("catch") ~ " {" ~ toText(handler) ~ "}" ~ optText(finalizer)(keywordStr(" finally ") ~ _)
        }
      case Number(digits, kind) =>
        digits
      case Quote(tree) if tree.isTerm =>
        keywordStr("'{") ~ toTextGlobal(dropBlock(tree)) ~ keywordStr("}")
      case Splice(tree) =>
        keywordStr("${") ~ toTextGlobal(dropBlock(tree)) ~ keywordStr("}")
      case Thicket(trees) =>
        "Thicket {" ~~ toTextGlobal(trees, "\n") ~~ "}"
      case MacroTree(call) =>
        keywordStr("macro ") ~ toTextGlobal(call)
      case Hole(isTermHole, idx, args, content, tpt) =>
        val (prefix, postfix) = if isTermHole then ("{{{", "}}}") else ("[[[", "]]]")
        val argsText = toTextGlobal(args, ", ")
        val contentText = toTextGlobal(content)
        val tptText = toTextGlobal(tpt)
        prefix ~~ idx.toString ~~ "|" ~~ tptText ~~ "|" ~~ argsText ~~ "|" ~~ contentText ~~ postfix
      case CapturingTypeTree(refs, parent) =>
        parent match
          case ImpureByNameTypeTree(bntpt) =>
            "=> " ~ toTextLocal(bntpt)
          case _ =>
            changePrec(GlobalPrec)("{" ~ Text(refs.map(toText), ", ") ~ "} " ~ toText(parent))
      case _ =>
        tree.fallbackToText(this)
    }
  }

  override def toText[T >: Untyped](tree: Tree[T]): Text = controlled {
    import untpd._

    var txt = toTextCore(tree)

    def suppressTypes =
      tree.isType || tree.isDef || // don't print types of types or defs
      homogenizedView && ctx.mode.is(Mode.Pattern)
        // When comparing pickled info, disregard types of patterns.
        // The reason is that GADT matching can rewrite types of pattern trees
        // without changing the trees themselves. (see Typer.typedCase.indexPatterns.transform).
        // But then pickling and unpickling the original trees will yield trees
        // with the original types before they are rewritten, which causes a discrepancy.

    def suppressPositions = tree match {
      case _: WithoutTypeOrPos[?] | _: TypeTree => true // TypeTrees never have an interesting position
      case _ => false
    }

    if (ctx.settings.XprintTypes.value && tree.hasType) {
      // add type to term nodes; replace type nodes with their types unless -Yprint-pos is also set.
      val tp1 = tree.typeOpt match {
        case tp: TermRef if tree.isInstanceOf[RefTree] && !tp.denot.isOverloaded => tp.underlying
        case tp => tp
      }
      val tp2 = {
        val tp = tp1.tryNormalize
        if (tp != NoType) tp else tp1
      }
      val tp3 =
        if (homogenizedView && tree.isInstanceOf[If | Match | Annotated | Block | CaseDef]) {
          // Types of non-leaf trees are not pickled but reconstructed when
          // unpickled using the TypeAssigner. Sometimes, this requires choosing
          // arbitrarily between two =:= types (e.g., when typing an `if`, where
          // one branch is typed with a type alias and the other with a
          // dealiased version of the same type) and we cannot guarantee that
          // the same choice was made by the original Typer (e.g., because the
          // original choice involved type variables). So we need to get rid of
          // any alias in these types to make -Ytest-pickler work (the list of
          // types in the isInstanceOf check above is conservative and might
          // need to be expanded).
          val dealiasMap = new TypeMap {
            def apply(tp: Type) = mapOver(tp.dealias)
          }
          dealiasMap(tp2)
        }
        else tp2

      if (!suppressTypes)
        txt = ("<" ~ txt ~ ":" ~ toText(tp3) ~ ">").close
      else if (tree.isType && !homogenizedView)
        txt = toText(tp3)
    }
    if (!suppressPositions) {
      if (printPos) {
        val posStr =
          if (homogenizedView || debugPos)
            if (tree.isInstanceOf[MemberDef]) Str(s"${tree.source}${tree.span}")
            else Str(s"${tree.source}${tree.span.toSynthetic}")
          else
            "<" ~ toText(tree.sourcePos) ~ ">"
        val clsStr = ""//if (tree.isType) tree.getClass.toString else ""
        txt = (txt ~ "@" ~ posStr ~ clsStr).close
      }
      if (ctx.settings.YprintPosSyms.value && tree.isDef)
        txt = (txt ~
          s"@@(${tree.symbol.name}=" ~ tree.symbol.sourcePos.toString ~ ")").close
    }
    if (ctx.settings.YshowTreeIds.value)
      txt = (txt ~ "#" ~ tree.uniqueId.toString).close
    tree match {
      case Block(_, _) | Template(_, _, _, _) => txt
      case _ => txt.close
    }
  }

  /** Print modifiers from symbols if tree has type, overriding the behavior in Trees. */
  extension (mdef: untpd.DefTree) def mods: untpd.Modifiers =
    if mdef.hasType then Modifiers(mdef.symbol) else mdef.rawMods

  private def Modifiers(sym: Symbol): Modifiers = untpd.Modifiers(
    sym.flagsUNSAFE & (if (sym.isType) ModifierFlags | VarianceFlags else ModifierFlags),
    if (sym.privateWithin.exists) sym.privateWithin.asType.name else tpnme.EMPTY,
    sym.annotationsUNSAFE.filterNot(ann => dropAnnotForModText(ann.symbol)).map(_.tree))

  protected def dropAnnotForModText(sym: Symbol): Boolean = sym == defn.BodyAnnot

  protected def optAscription[T >: Untyped](tpt: Tree[T]): Text = optText(tpt)(": " ~ _)

  private def idText(tree: untpd.Tree): Text =
    (if showUniqueIds && tree.hasType && tree.symbol.exists then s"#${tree.symbol.id}" else "") ~
    (if showNestingLevel then tree.typeOpt match
      case tp: NamedType if !tp.symbol.isStatic => s"%${tp.symbol.nestingLevel}"
      case tp: TypeVar => s"%${tp.nestingLevel}"
      case tp: TypeParamRef => ctx.typerState.constraint.typeVarOfParam(tp) match
        case tvar: TypeVar => s"%${tvar.nestingLevel}"
        case _ => ""
      case _ => ""
     else "")

  private def useSymbol(tree: untpd.Tree) =
    tree.hasType && tree.symbol.exists && ctx.settings.YprintSyms.value

  protected def nameIdText[T >: Untyped](tree: NameTree[T]): Text =
    if (tree.hasType && tree.symbol.exists) {
      val str = nameString(tree.symbol)
      tree match {
        case tree: RefTree => withPos(str, tree.sourcePos)
        case tree: MemberDef => withPos(str, tree.sourcePos.withSpan(tree.nameSpan))
        case _ => str
      }
    }
    else toText(tree.name) ~ idText(tree)

  private def toTextOwner(tree: Tree[?]) =
    "[owner = " ~ tree.symbol.maybeOwner.show ~ "]" provided ctx.settings.YprintDebugOwners.value

  protected def dclTextOr[T >: Untyped](tree: Tree[T])(treeText: => Text): Text =
    toTextOwner(tree) ~ {
      if (useSymbol(tree)) annotsText(tree.symbol) ~~ dclText(tree.symbol)
      else treeText
    }

  def paramsText[T>: Untyped](params: ParamClause[T]): Text = (params: @unchecked) match
    case Nil =>
      "()"
    case untpd.ValDefs(vparams @ (vparam :: _)) =>
      "(" ~ keywordText("using ").provided(vparam.mods.is(Given))
          ~ keywordText("erased ").provided(vparam.mods.is(Erased))
          ~ toText(vparams, ", ") ~ ")"
    case untpd.TypeDefs(tparams) =>
      "[" ~ toText(tparams, ", ") ~ "]"

  def addParamssText[T >: Untyped](leading: Text, paramss: List[ParamClause[T]]): Text =
    paramss.foldLeft(leading)((txt, params) => txt ~ paramsText(params))

  protected def valDefToText[T >: Untyped](tree: ValDef[T]): Text = {
    dclTextOr(tree) {
      modText(tree.mods, tree.symbol, keywordStr(if (tree.mods.is(Mutable)) "var" else "val"), isType = false) ~~
        valDefText(nameIdText(tree)) ~ optAscription(tree.tpt) ~
        withEnclosingDef(tree) { optText(tree.rhs)(" = " ~ _) }
    }
  }

  protected def defDefToText[T >: Untyped](tree: DefDef[T]): Text = {
    import untpd._
    dclTextOr(tree) {
      val defKeyword = modText(tree.mods, tree.symbol, keywordStr("def"), isType = false)
      val isExtension = tree.hasType && tree.symbol.is(ExtensionMethod)
      withEnclosingDef(tree) {
        val coreSig =
          if isExtension then
            val paramss =
              if tree.name.isRightAssocOperatorName then
                // we have the following encoding of tree.paramss:
                //   (leadingTyParamss ++ leadingUsing
                //      ++ rightTyParamss ++ rightParamss
                //      ++ leftParamss ++ trailingUsing ++ rest)
                //   e.g.
                //     extension [A](using B)(c: C)(using D)
                //       def %:[E](f: F)(g: G)(using H): Res = ???
                //   will have the following values:
                //   - leadingTyParamss = List(`[A]`)
                //   - leadingUsing = List(`(using B)`)
                //   - rightTyParamss = List(`[E]`)
                //   - rightParamss = List(`(f: F)`)
                //   - leftParamss = List(`(c: C)`)
                //   - trailingUsing = List(`(using D)`)
                //   - rest = List(`(g: G)`, `(using H)`)
                // we need to swap (rightTyParams ++ rightParamss) with (leftParamss ++ trailingUsing)
                val (leadingTyParamss, rest1) = tree.paramss.span(isTypeParamClause)
                val (leadingUsing, rest2) = rest1.span(isUsingClause)
                val (rightTyParamss, rest3) = rest2.span(isTypeParamClause)
                val (rightParamss, rest4) = rest3.splitAt(1)
                val (leftParamss, rest5) = rest4.splitAt(1)
                val (trailingUsing, rest6) = rest5.span(isUsingClause)
                if leftParamss.nonEmpty then
                  leadingTyParamss ::: leadingUsing ::: leftParamss ::: trailingUsing ::: rightTyParamss ::: rightParamss ::: rest6
                else
                  tree.paramss // it wasn't a binary operator, after all.
              else
                tree.paramss
            val trailingParamss = paramss
              .dropWhile(isUsingOrTypeParamClause)
              .drop(1)
              .dropWhile(isUsingClause)
            val leadingParamss = paramss.take(paramss.length - trailingParamss.length)
            addParamssText(
              addParamssText(keywordStr("extension "), leadingParamss)
              ~~ (defKeyword ~~ valDefText(nameIdText(tree))).close,
             trailingParamss)
          else
            addParamssText(defKeyword ~~ valDefText(nameIdText(tree)), tree.paramss)

        coreSig
        ~ optAscription(tree.tpt)
        ~ optText(tree.rhs)(" = " ~ keywordText("macro ").provided(tree.symbol.isScala2Macro) ~ _)
      }
    }
  }

  protected def toTextTemplate(impl: Template, ofNew: Boolean = false): Text = {
    val Template(constr @ DefDef(_, paramss, _, _), _, self, _) = impl
    val tparamsTxt = withEnclosingDef(constr) {
      paramsText(constr.leadingTypeParams) provided constr.leadingTypeParams.nonEmpty
    }
    val primaryConstrs = if (constr.rhs.isEmpty) Nil else constr :: Nil
    val prefix: Text =
      if (constr.trailingParamss.isEmpty || primaryConstrs.nonEmpty) tparamsTxt
      else {
        var modsText = modText(constr.mods, constr.symbol, "", isType = false)
        if (!modsText.isEmpty) modsText = " " ~ modsText
        if (constr.mods.hasAnnotations && !constr.mods.hasFlags) modsText = modsText ~~ " this"
        withEnclosingDef(constr) { addParamssText(tparamsTxt ~~ modsText, constr.trailingParamss) }
      }
    val parentsText = Text(impl.parents.map(constrText), if (ofNew) keywordStr(" with ") else ", ")
    val derivedText = Text(impl.derived.map(toText(_)), ", ")
    val selfText = {
      val selfName = if (self.name == nme.WILDCARD) keywordStr("this") else self.name.toString
      (selfName ~ optText(self.tpt)(": " ~ _) ~ " =>").close
    }.provided(!self.isEmpty)
    val body = if (ctx.settings.YtestPickler.value) {
      // Pickling/unpickling reorders the body members, so we need to homogenize
      val (params, rest) = impl.body partition {
        case stat: TypeDef => stat.symbol.is(Param)
        case stat: ValOrDefDef =>
          stat.symbol.is(ParamAccessor) && !stat.symbol.isSetter
        case _ => false
      }
      params ::: rest
    }
    else impl.body

    val bodyText = " {" ~~ selfText ~ toTextGlobal(primaryConstrs ::: body, "\n") ~ "}"

    prefix ~
    keywordText(" extends").provided(!ofNew && impl.parents.nonEmpty) ~~ parentsText ~
    keywordText(" derives").provided(impl.derived.nonEmpty) ~~ derivedText ~
    bodyText
  }

  protected def templateText(tree: TypeDef, impl: Template): Text = {
    val kw = if tree.mods.is(Trait) then "trait" else "class"
    val decl = modText(tree.mods, tree.symbol, keywordStr(kw), isType = true)
    ( decl ~~ typeText(nameIdText(tree)) ~ withEnclosingDef(tree) { toTextTemplate(impl) }
    // ~ (if (tree.hasType && printDebug) i"[decls = ${tree.symbol.info.decls}]" else "") // uncomment to enable
    )
  }

  protected def toTextPackageId[T >: Untyped](pid: Tree[T]): Text =
    if (homogenizedView && pid.hasType) toTextLocal(pid.tpe.asInstanceOf[Showable])
    else toTextLocal(pid)

  protected def packageDefText(tree: PackageDef): Text = {
    val statsText = tree.stats match {
      case (pdef: PackageDef) :: Nil => toText(pdef)
      case _ => toTextGlobal(tree.stats, "\n")
    }
    val bodyText =
      if (currentPrecedence == TopLevelPrec) "\n" ~ statsText else " {" ~ statsText ~ "}"
    keywordStr("package ") ~ toTextPackageId(tree.pid) ~ bodyText
  }

  /** Textual representation of an instance creation expression without the leading `new` */
  protected def constrText(tree: untpd.Tree): Text = toTextLocal(tree).stripPrefix(keywordStr("new ")) // DD

  protected def annotText(sym: Symbol, tree: untpd.Tree): Text =
    def recur(t: untpd.Tree): Text = t match
      case Apply(fn, Nil) => recur(fn)
      case Apply(fn, args) =>
        val explicitArgs = args.filterNot(_.symbol.name.is(DefaultGetterName))
        recur(fn) ~ "(" ~ toTextGlobal(explicitArgs, ", ") ~ ")"
      case TypeApply(fn, args) => recur(fn) ~ "[" ~ toTextGlobal(args, ", ") ~ "]"
      case Select(qual, nme.CONSTRUCTOR) => recur(qual)
      case New(tpt) => recur(tpt)
      case _ =>
        val annotSym = sym.orElse(tree.symbol.enclosingClass)
        s"@${if annotSym.exists then annotSym.name.toString else t.show}"
    recur(tree)

  protected def modText(mods: untpd.Modifiers, sym: Symbol, kw: String, isType: Boolean): Text = { // DD
    val suppressKw = if (enclDefIsClass) mods.isAllOf(LocalParam) else mods.is(Param)
    var flagMask =
      if (ctx.settings.YdebugFlags.value) AnyFlags
      else if (suppressKw) PrintableFlags(isType) &~ Private
      else PrintableFlags(isType)
    if (homogenizedView && mods.flags.isTypeFlags) flagMask &~= GivenOrImplicit // drop implicit/given from classes
    val rawFlags = if (sym.exists) sym.flagsUNSAFE else mods.flags
    if (rawFlags.is(Param)) flagMask = flagMask &~ Given &~ Erased
    val flags = rawFlags & flagMask
    var flagsText = toTextFlags(sym, flags)
    val annotTexts =
      if sym.exists then
        sym.annotationsUNSAFE.filterNot(ann => dropAnnotForModText(ann.symbol)).map(toText)
      else
        mods.annotations.filterNot(tree => dropAnnotForModText(tree.symbol)).map(annotText(NoSymbol, _))
    Text(annotTexts, " ") ~~ flagsText ~~ (Str(kw) provided !suppressKw)
  }

  override def annotText(annot: Annotation): Text = annotText(annot.symbol, annot.tree)

  def optText(name: Name)(encl: Text => Text): Text =
    if (name.isEmpty) "" else encl(toText(name))

  def optText[T >: Untyped](tree: Tree[T])(encl: Text => Text): Text =
    if (tree.isEmpty) "" else encl(toText(tree))

  def optText[T >: Untyped](tree: List[Tree[T]])(encl: Text => Text): Text =
    if (tree.exists(!_.isEmpty)) encl(blockText(tree)) else ""

  override protected def treatAsTypeParam(sym: Symbol): Boolean = sym.is(TypeParam)

  override protected def treatAsTypeArg(sym: Symbol): Boolean =
    sym.isType && (sym.isAllOf(ProtectedLocal)) &&
      (sym.allOverriddenSymbols exists (_.is(TypeParam)))

  override def toText(sym: Symbol): Text = {
    if (sym.isImport)
      sym.infoOrCompleter match {
        case info: Namer#Completer => return info.original.show
        case info: ImportType => return s"import $info.expr.show"
        case _ =>
      }
    def name =
      if (printDebug)
        nameString(sym)
      else if sym.is(Package) then
        fullNameString(sym)
      else if (sym.is(ModuleClass) && sym.isPackageObject && sym.name.stripModuleClassSuffix == tpnme.PACKAGE)
        nameString(sym.owner.name)
      else if (sym.is(ModuleClass))
        nameString(sym.name.stripModuleClassSuffix) + idString(sym)
      else if (hasMeaninglessName(sym))
        simpleNameString(sym.owner) + idString(sym)
      else
        nameString(sym)
    (keywordText(kindString(sym)) ~~ {
      if (sym.isAnonymousClass)
        toTextParents(sym.info.parents) ~~ "{...}"
      else
        typeText(name)
    }).close
  }

  /** String representation of symbol's kind. */
  override def kindString(sym: Symbol): String = {
    val flags = sym.flagsUNSAFE
    if (flags.is(Package)) "package"
    else if (sym.isPackageObject) "package object"
    else if (flags.is(Module)) "object"
    else if (sym.isClassConstructor) "constructor"
    else super.kindString(sym)
  }

  /** String representation of symbol's definition keyword */
  override protected def keyString(sym: Symbol): String = {
    val flags = sym.flagsUNSAFE
    if (sym.isType && sym.owner.isTerm) ""
    else if (sym.isPackageObject) "package object"
    else if (flags.is(Module) && flags.is(Case)) "case object"
    else if (sym.isClass && flags.is(Case)) "case class"
    else super.keyString(sym)
  }

  override def toTextFlags(sym: Symbol): Text =
    if (ctx.settings.YdebugFlags.value)
      super.toTextFlags(sym)
    else {
      var flags = sym.flagsUNSAFE
      if (flags.is(TypeParam)) flags = flags &~ Protected
      toTextFlags(sym, flags & PrintableFlags(sym.isType))
    }

  override def toText(denot: Denotation): Text = denot match {
    case denot: MultiDenotation => Text(denot.alternatives.map(dclText), " <and> ")
    case NoDenotation => "NoDenotation"
    case _ =>
      if (denot.symbol.exists) toText(denot.symbol)
      else "some " ~ toText(denot.info)
  }

  override def plain: PlainPrinter = new PlainPrinter(_ctx)

  private def withPos(txt: Text, pos: SourcePosition): Text =
    if (!printLines || !pos.exists) txt
    else txt match {
      case Str(s, _) => Str(s, LineRange(pos.line, pos.endLine))
      case _ => txt
    }
}
