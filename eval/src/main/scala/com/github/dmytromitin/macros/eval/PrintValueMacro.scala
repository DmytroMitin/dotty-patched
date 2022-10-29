package com.github.dmytromitin.macros.eval

import com.github.dmytromitin.eval.Eval

import scala.quoted.{Expr, Quotes, Type, quotes, staging}

object PrintValueMacro:
  inline def printAtCompileTime[A](a: A): Unit = ${impl[A]('a)}

  def impl[A: Type](a: Expr[A])(using Quotes): Expr[Unit] =
    import quotes.reflect.*
    val str = a.asTerm.pos.sourceCode.getOrElse(
      report.errorAndAbort(s"No source code for ${a.show}")
    )
    val aValue = Eval[A](str)
    report.info(aValue.toString)
    '{()}
