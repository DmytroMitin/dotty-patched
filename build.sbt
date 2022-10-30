lazy val scala3V = "3.2.1"
lazy val scala2V = "2.13.10"

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / name                 := "eval"
ThisBuild / organization         := "com.github.dmytromitin"
ThisBuild / organizationName     := "Dmytro Mitin"
ThisBuild / organizationHomepage := Some(url("https://github.com/DmytroMitin"))
ThisBuild / version              := "0.1"
ThisBuild / scalaVersion         := scala3V
ThisBuild / scmInfo := Some(ScmInfo(
  url("https://github.com/DmytroMitin/dotty-patched"),
  "https://github.com/DmytroMitin/dotty-patched.git"
))
ThisBuild / developers := List(Developer(
  id = "DmytroMitin",
  name = "Dmytro Mitin",
  email = "dmitin3@gmail.com",
  url = url("https://github.com/DmytroMitin")
))
ThisBuild / description := "Patched Scala-3/Dotty compiler and Eval library"
ThisBuild / licenses := List("Apache 2" -> new URL("http://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / homepage := Some(url("https://github.com/DmytroMitin/dotty-patched"))
// Remove all additional repository other than Maven Central from POM
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishMavenStyle := true
ThisBuild / credentials += Credentials(Path.userHome / ".sbt" / "sonatype_credential")
ThisBuild / publishTo := sonatypePublishToBundle.value
ThisBuild / sonatypeCredentialHost := "oss.sonatype.org"
ThisBuild / sonatypeRepository := "https://oss.sonatype.org/service/local"
//ThisBuild / publishTo := {
//  val nexus = "https://oss.sonatype.org/"
//  if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
//  else Some("releases" at nexus + "service/local/staging/deploy/maven2")
//}

lazy val root = (project in file("."))
  .aggregate(
    `scala3-compiler-patched`,
    `scala3-compiler-patched-assembly`,
    eval,
    `eval-test`,
  )
  .settings(
    crossScalaVersions := Nil,
    publish / skip := true,
  )

lazy val patchedScalaV = scala3V
lazy val patchedCompilerSettings = Seq(
  version := patchedScalaV,
//  Compile / packageDoc / publishArtifact := false,
)

//https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/3.2.1/scala3-compiler_3-3.2.1-sources.jar
lazy val `scala3-compiler-patched` = (project in file("scala3-compiler-patched"))
  .settings(
    name := "scala3-compiler-patched",
    patchedCompilerSettings,
    libraryDependencies ++= Seq(
      scalaOrganization.value %  "scala3-interfaces" % scalaVersion.value,
      scalaOrganization.value %% "tasty-core"        % scalaVersion.value,
      "org.scala-sbt" % "sbt" % "1.7.2",
      "org.scala-js" %% "scalajs-ir" % "1.11.0" cross CrossVersion.for3Use2_13,
    ),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", _*) => MergeStrategy.discard
      case _ => MergeStrategy.last
    },
  )

lazy val `scala3-compiler-patched-assembly` = (project in file("scala3-compiler-patched-assembly"))
  .settings(
    name := "scala3-compiler-patched-assembly",
    patchedCompilerSettings,
    Compile / packageBin := (`scala3-compiler-patched` / assembly).value,
  )

lazy val commonEvalSettings = Seq(
  version := "0.1",
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.2.14" % Test
       exclude("org.scala-lang.modules", "scala-xml_3"),
  ),
)

lazy val eval = (project in file("eval"))
  .settings(
    name := "eval",
    commonEvalSettings,
    libraryDependencies ++= Seq(
      scalaOrganization.value %% "scala3-staging"  % scalaVersion.value,
      scalaOrganization.value %% "scala3-compiler" % scalaVersion.value,
      "io.get-coursier" % "coursier_2.13" % "2.1.0-M7-39-gb8f3d7532",
    ),
  )

lazy val customScalaSettings = Seq(
  managedScalaInstance := false,
  ivyConfigurations += Configurations.ScalaTool,
  libraryDependencies ++= Seq(
    scalaOrganization.value %  "scala-library"  % scala2V,
    scalaOrganization.value %% "scala3-library" % scalaVersion.value,
//    organization.value %% "scala3-compiler-patched" % patchedScalaV % "scala-tool",
    organization.value %% "scala3-compiler-patched-assembly" % patchedScalaV % "scala-tool",
  ),
)

lazy val `eval-test` = (project in file("eval-test"))
  .settings(
    name := "eval-test",
    commonEvalSettings,
    customScalaSettings,
    publish / skip := true,
  )
  .dependsOn(eval)