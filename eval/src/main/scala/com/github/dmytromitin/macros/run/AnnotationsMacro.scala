package com.github.dmytromitin.macros.run

import scala.quoted.{Expr, Quotes, staging, quotes}

/**
 * @see https://stackoverflow.com/questions/71390113/get-annotations-from-class-in-scala-3-macros
 */
object AnnotationsMacro:
  /*given*/val c: staging.Compiler = staging.Compiler.make(this.getClass.getClassLoader)

  inline def getAnnotations(/*inline*/ clazz: Class[?]): Seq[String] = ${impl('clazz)}

  def impl(expr: Expr[Class[?]])(using Quotes): Expr[Seq[String]] =
    import quotes.reflect.*
//    given staging.Compiler = staging.Compiler.make(this.getClass.getClassLoader)

//    val tpe = TypeRepr.typeConstructorOf(staging.run[Class[?]](expr))
    val tpe = staging.run[Any]((_: Quotes) ?=> expr)(using c).asInstanceOf[TypeRepr]
    println("no ClassCastException so far")

    val annotations = Expr(tpe.typeSymbol.annotations.map(_.asExpr.show))
    report.info(s"annotations=${annotations.show}")
//    given Quotes = owner.asQuotes
    annotations
