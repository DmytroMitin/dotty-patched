package com.github.dmytromitin.eval

import dotty.tools.io.AbstractFile
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.Driver
import dotty.tools.dotc.util.SourceFile
import dotty.tools.io.{VirtualDirectory, VirtualFile}
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import dotty.tools.repl.AbstractFileClassLoader
import scala.io.Codec
import coursier.{Dependency, Module, Organization, ModuleName, Fetch}

/**
 * @see https://stackoverflow.com/questions/70945320/how-to-compile-and-execute-scala-code-at-run-time-in-scala3
 */
object Eval:
  def apply[A](str: String): A =
    val files = Fetch()
      .addDependencies(
        Dependency(Module(Organization("org.scala-lang"), ModuleName("scala3-compiler_3")), "3.2.1"),
      ).run().map(_.toURI.toURL)

    val depClassLoader = new URLClassLoader(
      files.toArray,
      /*null*/getClass.getClassLoader
    )

    val code =
      s"""
         |package $$generated
         |
         |object $$Generated {
         |  def run = $str
         |}""".stripMargin

    val outputDirectory = VirtualDirectory("(memory)")
    compileCode(code, files.map(AbstractFile.getURL)/*List()*/, outputDirectory)
    val classLoader = AbstractFileClassLoader(outputDirectory, depClassLoader/*this.getClass.getClassLoader*/)
    runObjectMethod("$generated.$Generated", classLoader, "run", Seq()).asInstanceOf[A]

  def compileCode(
                   code: String,
                   classpathDirectories: Seq[AbstractFile],
                   outputDirectory: AbstractFile
                 ): Unit =
    class DriverImpl extends Driver:
      private val compileCtx0 = initCtx.fresh

      given Context = compileCtx0.fresh
        .setSetting(
          compileCtx0.settings.classpath,
          classpathDirectories.map(_.path).mkString(":")
        )/*.setSetting(
          compileCtx0.settings.usejavacp,
          true
        )*/.setSetting(
          compileCtx0.settings.outputDir,
          outputDirectory
        )

      val compiler = newCompiler

    val driver = new DriverImpl
    import driver.given Context

    val sourceFile = SourceFile(VirtualFile("(inline)", code.getBytes(StandardCharsets.UTF_8)), Codec.UTF8)
    val run = driver.compiler.newRun
    run.compileSources(List(sourceFile))

  def runObjectMethod(
                       objectName: String,
                       classLoader: ClassLoader,
                       methodName: String,
                       paramClasses: Seq[Class[?]],
                       arguments: Any*
                     ): Any =
    val clazz = Class.forName(s"$objectName$$", true, classLoader)
    val module = clazz.getField("MODULE$").get(null)
    val method = module.getClass.getMethod(methodName, paramClasses *)
    method.invoke(module, arguments *)
