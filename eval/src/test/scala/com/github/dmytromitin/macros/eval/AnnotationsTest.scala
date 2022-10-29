package com.github.dmytromitin.macros.eval

import org.scalatest.*
import flatspec.*
import matchers.*

class AnnotationsTest extends AnyFlatSpec with should.Matchers:
  "AnnotationsMacro.getAnnotations" should "return annotations for CanEqual" in {
    AnnotationsMacro.getAnnotations(classOf[scala.CanEqual[?, ?]]).map(_.getClass) should be (Seq(
      classOf[scala.annotation.internal.SourceFile],
      classOf[scala.annotation.internal.Child[?]],
      classOf[scala.annotation.implicitNotFound]
    ))
  }