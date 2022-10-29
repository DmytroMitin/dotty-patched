lazy val scala3V    = "3.2.1"
lazy val scala2V    = "2.13.10"
lazy val scalatestV = "3.2.14"

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / scalaVersion := scala3V

lazy val myOrganization = "com.github.dmytromitin"
def patchedOrganization(scalaOrganization: String) = s"$myOrganization.patched.$scalaOrganization"
lazy val patchedVersion = scala3V

lazy val patchedCompilerSettings = Seq(
  organization := patchedOrganization(scalaOrganization.value),
  version      := patchedVersion,
  Compile / packageDoc / publishArtifact := false,
)

//https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/3.2.1/scala3-compiler_3-3.2.1-sources.jar
lazy val `scala3-compiler` = project
  .settings(
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
//      case x =>
//        val oldStrategy = (assembly / assemblyMergeStrategy).value
//        oldStrategy(x)
    },
  )

lazy val `scala3-compiler-assembly` = project
  .settings(
    patchedCompilerSettings,
    Compile / packageBin := (`scala3-compiler` / assembly).value,
  )

lazy val commonCoreSettings = Seq(
  version := "0.1",
  organization := myOrganization,
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % scalatestV % Test
       exclude("org.scala-lang.modules", "scala-xml_3"),
  ),
)

lazy val eval = project
  .settings(
    commonCoreSettings,
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
//    patchedOrganization(scalaOrganization.value) %% "scala3-compiler" % patchedVersion % "scala-tool",
    patchedOrganization(scalaOrganization.value) %% "scala3-compiler-assembly" % patchedVersion % "scala-tool",
  ),
)

lazy val test = project
  .settings(
    commonCoreSettings,
    customScalaSettings,
  )
  .dependsOn(eval)