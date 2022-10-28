lazy val scala3V = "3.2.1"
lazy val scala2V = "2.13.10"

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / scalaVersion := scala3V
ThisBuild / version := scala3V
lazy val myOrganization = "com.github.dmytromitin"
ThisBuild / organization := s"$myOrganization.patched.${scalaOrganization.value}"

lazy val patchedCompilerSettings = Seq(
  Compile / packageDoc / publishArtifact := false,
)

//https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/3.2.1/scala3-compiler_3-3.2.1-sources.jar
lazy val `scala3-compiler` = project
  .settings(
    patchedCompilerSettings,
    libraryDependencies ++= Seq(
      scalaOrganization.value % "scala3-interfaces" % scalaVersion.value,
      scalaOrganization.value %% "tasty-core" % scalaVersion.value,
      "org.scala-sbt" % "sbt" % "1.7.2",
      "org.scala-js" %% "scalajs-ir" % "1.11.0" cross CrossVersion.for3Use2_13,
    ),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", _*) => MergeStrategy.discard
//      case _ => MergeStrategy.first
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    },
  )

lazy val `scala3-compiler-assembly` = project
  .settings(
    patchedCompilerSettings,
    Compile / packageBin := (`scala3-compiler` / assembly).value,
  )

lazy val customScalaSettings = Seq(
  managedScalaInstance := false,
  ivyConfigurations += Configurations.ScalaTool,
  libraryDependencies ++= Seq(
    scalaOrganization.value % "scala-library" % scala2V,
    scalaOrganization.value %% "scala3-library" % scalaVersion.value,
//    organization.value %% "scala3-compiler" % version.value % "scala-tool",
    organization.value %% "scala3-compiler-assembly" % version.value % "scala-tool",
  ),
)

lazy val `test-macros` = project
  .settings(
    libraryDependencies ++= Seq(
      scalaOrganization.value %% "scala3-staging" % scalaVersion.value,
    ),
  )

lazy val test = project
  .settings(
//    customScalaSettings,
  )
  .dependsOn(`test-macros`)