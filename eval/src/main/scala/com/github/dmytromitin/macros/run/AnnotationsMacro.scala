package com.github.dmytromitin.macros.run

import scala.quoted.{Expr, Quotes, staging, quotes}

/**
 * @see https://stackoverflow.com/questions/71390113/get-annotations-from-class-in-scala-3-macros
 */
object AnnotationsMacro:
  given staging.Compiler = staging.Compiler.make(this.getClass.getClassLoader)

  inline def getAnnotations(clazz: Class[?]): Seq[String] = ${impl('clazz)}

  def impl(expr: Expr[Class[?]])(using q0: Quotes): Expr[Seq[String]] =
//    import quotes.reflect.*

//    staging.run[Class[?]](expr)
//    val cls = staging.run[Class[?]](expr)
////    val tpe = staging.run[Any](expr).asInstanceOf[TypeRepr]
//
    val annotations = Expr(quotes.reflect.TypeRepr.typeConstructorOf(staging.run[Class[?]]((q1: Quotes) ?=> expr)).typeSymbol.annotations.map(_.asExpr.toString/*show*/))
//    val annotations = Expr(TypeRepr.typeConstructorOf(cls).typeSymbol.annotations.map(_.asExpr.show))
////    val annotations = Expr(tpe.typeSymbol.annotations.map(_.asExpr.show))
    quotes.reflect.report.info(s"annotations=${annotations.show}")
    annotations
//    Expr(Seq[String]())
