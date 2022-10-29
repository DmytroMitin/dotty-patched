package com.github.dmytromitin.macros.run

import scala.quoted.{Expr, Quotes, staging, quotes}

/**
 * @see https://stackoverflow.com/questions/71390113/get-annotations-from-class-in-scala-3-macros
 */
object AnnotationsMacro:
  inline def getAnnotations(clazz: Class[?]): Seq[String] = ${impl('clazz)}

  def impl(expr: Expr[Class[?]])(using Quotes): Expr[Seq[String]] =
    import quotes.reflect.*
    given staging.Compiler = staging.Compiler.make(this.getClass.getClassLoader)

    val tpe = staging.run[Any](expr).asInstanceOf[TypeRepr]

    val annotations = Expr(tpe.typeSymbol.annotations.map(_.asExpr.show))
    report.info(s"annotations=${annotations.show}")
    annotations
