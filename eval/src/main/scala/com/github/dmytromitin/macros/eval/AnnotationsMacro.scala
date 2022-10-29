package com.github.dmytromitin.macros.eval

import com.github.dmytromitin.eval.Eval
import scala.quoted.{Expr, Quotes, quotes}

/**
 * @see https://stackoverflow.com/questions/71390113/get-annotations-from-class-in-scala-3-macros
 */
object AnnotationsMacro:
  inline def getAnnotations(clazz: Class[?]): Seq[Any] = ${impl('clazz)}

  def impl(clazz: Expr[Class[?]])(using Quotes): Expr[Seq[Any]] =
    import quotes.reflect.*
    val str = clazz.asTerm.pos.sourceCode.getOrElse(
      report.errorAndAbort(s"No source code for ${clazz.show}")
    )

    val cls = Eval[Class[?]](str)

    val tpe = TypeRepr.typeConstructorOf(cls)
    val annotations = tpe.typeSymbol.annotations.map(_.asExpr)
    val res = Expr.ofSeq(annotations)
    report.info(s"annotations=${res.show}")
    res
