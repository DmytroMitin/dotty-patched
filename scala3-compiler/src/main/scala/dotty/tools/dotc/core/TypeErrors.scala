package dotty.tools
package dotc
package core

import Types._
import Symbols._
import Flags._
import Names._
import Contexts._
import SymDenotations._
import Denotations._
import Decorators._
import reporting._
import ast.untpd
import config.Printers.cyclicErrors

class TypeError(msg: String) extends Exception(msg) {
  def this() = this("")
  final def toMessage(using Context): Message =
    withMode(Mode.Printing)(produceMessage)
  def produceMessage(using Context): Message = super.getMessage.nn
  override def getMessage: String = super.getMessage.nn
}

class MalformedType(pre: Type, denot: Denotation, absMembers: Set[Name]) extends TypeError {
  override def produceMessage(using Context): Message =
    i"malformed type: $pre is not a legal prefix for $denot because it contains abstract type member${if (absMembers.size == 1) "" else "s"} ${absMembers.mkString(", ")}"
}

class MissingType(pre: Type, name: Name) extends TypeError {
  private def otherReason(pre: Type)(using Context): String = pre match {
    case pre: ThisType if pre.cls.givenSelfType.exists =>
      i"\nor the self type of $pre might not contain all transitive dependencies"
    case _ => ""
  }

  override def produceMessage(using Context): Message = {
    if (ctx.debug) printStackTrace()
    i"""cannot resolve reference to type $pre.$name
       |the classfile defining the type might be missing from the classpath${otherReason(pre)}"""
  }
}

class RecursionOverflow(val op: String, details: => String, val previous: Throwable, val weight: Int)
extends TypeError {

  def explanation: String = s"$op $details"

  private def recursions: List[RecursionOverflow] = {
    import scala.collection.mutable.ListBuffer
    val result = ListBuffer.empty[RecursionOverflow]
    @annotation.tailrec def loop(throwable: Throwable): List[RecursionOverflow] = throwable match {
      case ro: RecursionOverflow =>
        result += ro
        loop(ro.previous)
      case _ => result.toList
    }

    loop(this)
  }

  def opsString(rs: List[RecursionOverflow])(using Context): String = {
    val maxShown = 20
    if (rs.lengthCompare(maxShown) > 0)
      i"""${opsString(rs.take(maxShown / 2))}
         |  ...
         |${opsString(rs.takeRight(maxShown / 2))}"""
    else
      (rs.map(_.explanation): List[String]).mkString("\n  ", "\n|  ", "")
  }

  override def produceMessage(using Context): Message = NoExplanation {
    val mostCommon = recursions.groupBy(_.op).toList.maxBy(_._2.map(_.weight).sum)._2.reverse
    s"""Recursion limit exceeded.
       |Maybe there is an illegal cyclic reference?
       |If that's not the case, you could also try to increase the stacksize using the -Xss JVM option.
       |For the unprocessed stack trace, compile with -Yno-decode-stacktraces.
       |A recurring operation is (inner to outer):
       |${opsString(mostCommon)}""".stripMargin
  }

  override def fillInStackTrace(): Throwable = this
  override def getStackTrace(): Array[StackTraceElement] = previous.getStackTrace().asInstanceOf
}

/** Post-process exceptions that might result from StackOverflow to add
  * tracing information while unwalking the stack.
  */
// Beware: Since this object is only used when handling a StackOverflow, this code
// cannot consume significant amounts of stack.
object handleRecursive {
  def apply(op: String, details: => String, exc: Throwable, weight: Int = 1)(using Context): Nothing =
    if (ctx.settings.YnoDecodeStacktraces.value)
      throw exc
    else
      exc match {
        case _: RecursionOverflow =>
          throw new RecursionOverflow(op, details, exc, weight)
        case _ =>
          var e: Throwable | Null = exc
          while (e != null && !e.isInstanceOf[StackOverflowError]) e = e.getCause
          if (e != null) throw new RecursionOverflow(op, details, e, weight)
          else throw exc
      }
}

/**
 * This TypeError signals that completing denot encountered a cycle: it asked for denot.info (or similar),
 * so it requires knowing denot already.
 * @param denot
 */
class CyclicReference private (val denot: SymDenotation) extends TypeError {
  var inImplicitSearch: Boolean = false

  override def produceMessage(using Context): Message = {
    val cycleSym = denot.symbol

    // cycleSym.flags would try completing denot and would fail, but here we can use flagsUNSAFE to detect flags
    // set by the parser.
    val unsafeFlags = cycleSym.flagsUNSAFE
    val isMethod = unsafeFlags.is(Method)
    val isVal = !isMethod && cycleSym.isTerm

    /* This CyclicReference might have arisen from asking for `m`'s type while trying to infer it.
     * To try to diagnose this, walk the context chain searching for context in
     * Mode.InferringReturnType for the innermost member without type
     * annotations (!tree.tpt.typeOpt.exists).
     */
    def errorMsg(cx: Context): Message =
      if (cx.mode is Mode.InferringReturnType)
        cx.tree match {
          case tree: untpd.ValOrDefDef if !tree.tpt.typeOpt.exists =>
            if (inImplicitSearch)
              TermMemberNeedsResultTypeForImplicitSearch(cycleSym)
            else if (isMethod)
              OverloadedOrRecursiveMethodNeedsResultType(cycleSym)
            else if (isVal)
              RecursiveValueNeedsResultType(cycleSym)
            else
              errorMsg(cx.outer)
          case _ =>
            errorMsg(cx.outer)
        }

      // Give up and give generic errors.
      else if (cycleSym.isOneOf(GivenOrImplicitVal, butNot = Method) && cycleSym.owner.isTerm)
        CyclicReferenceInvolvingImplicit(cycleSym)
      else
        CyclicReferenceInvolving(denot)

    errorMsg(ctx)
  }
}

object CyclicReference {
  def apply(denot: SymDenotation)(using Context): CyclicReference = {
    val ex = new CyclicReference(denot)
    if (!(ctx.mode is Mode.CheckCyclic) || ctx.settings.Ydebug.value) {
      cyclicErrors.println(s"Cyclic reference involving! $denot")
      val sts = ex.getStackTrace.asInstanceOf[Array[StackTraceElement]]
      for (elem <- sts take 200)
        cyclicErrors.println(elem.toString)
    }
    ex
  }
}

