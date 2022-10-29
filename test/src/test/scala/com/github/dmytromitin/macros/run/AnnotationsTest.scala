package com.github.dmytromitin.macros.run

import org.scalatest.*
import flatspec.*
import matchers.*

class AnnotationsTest extends AnyFlatSpec with should.Matchers:
  "AnnotationsMacro.getAnnotations" should "return annotations for CanEqual" in {
    AnnotationsMacro.getAnnotations(classOf[scala.CanEqual[?, ?]]) should be (Seq(
      "scala.annotation.internal.SourceFile",
      "scala.annotation.internal.Child",
      "scala.annotation.implicitNotFound"
    ))
  }