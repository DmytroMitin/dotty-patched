package com.github.dmytromitin.macros.run

import org.scalatest.*
import flatspec.*
import matchers.*

class AnnotationsTest extends AnyFlatSpec with should.Matchers:
  "AnnotationsMacro.getAnnotations" should "return annotations for classOf[scala.CanEqual[?, ?]]" in {
    AnnotationsMacro.getAnnotations(classOf[scala.CanEqual[?, ?]]) should be (Seq(
      """new scala.annotation.internal.SourceFile("library/src/scala/CanEqual.scala")""",
      "new scala.annotation.internal.Child[scala.CanEqual.derived.type]()",
      """new scala.annotation.implicitNotFound("Values of types ${L} and ${R} cannot be compared with == or !=")"""
    ))
  }

  // TODO #14
  "AnnotationsMacro.getAnnotations" should """return annotations for Class.forName("scala.CanEqual")""" in {
    AnnotationsMacro.getAnnotations(Class.forName("scala.CanEqual")) should be(Seq(
      """new scala.annotation.internal.SourceFile("library/src/scala/CanEqual.scala")""",
      "new scala.annotation.internal.Child[scala.CanEqual.derived.type]()",
      """new scala.annotation.implicitNotFound("Values of types ${L} and ${R} cannot be compared with == or !=")"""
    ))
  }