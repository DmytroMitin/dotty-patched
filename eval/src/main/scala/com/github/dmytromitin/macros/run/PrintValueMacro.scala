package com.github.dmytromitin.macros.run

import scala.quoted.{Expr, Quotes, Type, staging, quotes}

object PrintValueMacro:
  inline def printAtCompileTime[A](a: A): Unit = ${impl[A]('a)}

  def impl[A: Type](a: Expr[A])(using Quotes): Expr[Unit] =
    import quotes.reflect.*
    given staging.Compiler = staging.Compiler.make(this.getClass.getClassLoader)
    report.info(staging.run(a).toString)
    '{()}
