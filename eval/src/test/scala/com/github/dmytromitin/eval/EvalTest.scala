package com.github.dmytromitin.eval

import org.scalatest.*
import flatspec.*
import matchers.*

class EvalTest extends AnyFlatSpec with should.Matchers:
  "Eval" should "evaluate source code properly" in {
    Eval.apply[Int]("1 + 1") should be(2)
  }