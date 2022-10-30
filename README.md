# Background

https://github.com/lampepfl/dotty

https://stackoverflow.com/questions/71390113/get-annotations-from-class-in-scala-3-macros

https://stackoverflow.com/questions/70945320/how-to-compile-and-execute-scala-code-at-run-time-in-scala3

https://github.com/DmytroMitin/dotty-patched/commit/fdf010ca4901b22961f3ae1cb3459b5fa194652e

# Dotty-patched and multi-staging in Scala 3 macros
`staging.run` evaluates a typed tree into a value. This functionality exists in Dotty but blocked in macros (because of the [phase consistency principle](https://docs.scala-lang.org/scala3/reference/metaprogramming/macros.html#the-phase-consistency-principle)). To unblock, a code expanding macros should be compiled with the compiler patched. Macros themselves can be compiled with the standard compiler. Staging didn't have to be patched so far.
```scala
scalaVersion := "3.2.1" // 3.2.0, 3.1.3, ...
libraryDependencies += scalaOrganization.value %% "scala3-staging" % scalaVersion.value
// custom Scala settings
managedScalaInstance := false
ivyConfigurations += Configurations.ScalaTool
libraryDependencies ++= Seq(
  scalaOrganization.value  %  "scala-library"  % "2.13.10",
  scalaOrganization.value  %% "scala3-library" % "3.2.1",
  "com.github.dmytromitin" %% "scala3-compiler-patched-assembly" % "3.2.1" % "scala-tool"
)
```
```scala
import scala.quoted.*

inline def printAtCompileTime[A](a: A): Unit = ${impl[A]('a)}

def impl[A: Type](a: Expr[A])(using Quotes): Expr[Unit] =
  import quotes.reflect.*
  given staging.Compiler = staging.Compiler.make(this.getClass.getClassLoader)
  println(staging.run(a))
  '{()}
```
```scala
sbt clean compile
```
```scala
printAtCompileTime(1 + 1) // 2 (at compile time)
```
# Eval
```scala
scalaVersion := "3.2.1" // or 3.2.0
libraryDependencies += "com.github.dmytromitin" %% "eval" % "0.1"
```
```scala
sbt clean compile run
```
```scala
import com.github.dmytromitin.eval.Eval

Eval[Int]("1 + 1") // 2 (at runtime)
```
# Eval in Scala 3 macros
`Eval` evaluates a source code into a value.
```scala
import com.github.dmytromitin.eval.Eval
import scala.quoted.*

inline def printAtCompileTime[A](a: A): Unit = ${impl[A]('a)}

def impl[A: Type](a: Expr[A])(using Quotes): Expr[Unit] = 
  import quotes.reflect.*
  val str = a.asTerm.pos.sourceCode.getOrElse(
    report.errorAndAbort(s"No source code for ${a.show}")
  )
  val aValue = Eval[A](str)
  println(aValue.toString)

  '{()}
```
```scala
sbt clean compile
```
```scala
printAtCompileTime(1 + 1) // 2 (at compile time)
```
# Some built-in Scala 3 macros
```scala
import com.github.dmytromitin.macros.eval.AnnotationsMacro.getAnnotations
import com.github.dmytromitin.macros.eval.PrintValueMacro.printAtCompileTime

printAtCompileTime(1 + 1)
//2
getAnnotations(classOf[scala.CanEqual[?, ?]])
//new scala.annotation.internal.SourceFile("library/src/scala/CanEqual.scala"), new scala.annotation.internal.Child[scala.CanEqual.derived.type](), new scala.annotation.implicitNotFound("Values of types ${L} and ${R} cannot be compared with == or !=")
```
```scala
// add custom Scala settings to build.sbt, see above
import com.github.dmytromitin.macros.run.AnnotationsMacro.getAnnotations
import com.github.dmytromitin.macros.run.PrintValueMacro.printAtCompileTime

printAtCompileTime(1 + 1)
//2
getAnnotations(classOf[scala.CanEqual[?, ?]])
//scala.List.apply[java.lang.String]("new scala.annotation.internal.SourceFile(\"library/src/scala/CanEqual.scala\")", "new scala.annotation.internal.Child[scala.CanEqual.derived.type]()", "new scala.annotation.implicitNotFound(\"Values of types ${L} and ${R} cannot be compared with == or !=\")")
```