import sys.process._
import java.nio.file.{Files, Path, Paths}
import scala.collection.JavaConverters._

def parseVersion(file: Path): Option[String] =
  if (!Files.exists(file)) None
  else Files.readAllLines(file).asScala.filter(!_.startsWith("#"))
    .find(_.nonEmpty).map(_.trim)

def seedVersion = parseVersion(Paths.get("SEED"))  // CI
  .getOrElse(Seq("git", "describe", "--tags").!!.trim)  // Local development
def bloopVersion = parseVersion(Paths.get("BLOOP")).get
def bloopCoursierVersion = parseVersion(Paths.get("COURSIER")).get

organization      := "tindzk"
version           := seedVersion
scalaVersion      := "2.12.4-bin-typelevel-4"
scalaOrganization := "org.typelevel"
scalacOptions     += "-Yliteral-types"

Compile / sourceGenerators += Def.task {
  val file = (Compile / sourceManaged).value / "seed" / "BuildInfo.scala"
  IO.write(file,
    s"""package seed
       |
       |object BuildInfo {
       |  val Version  = "$seedVersion"
       |  val Bloop    = "$bloopVersion"
       |  val Coursier = "$bloopCoursierVersion"
       |}"""
      .stripMargin)

  Seq(file)
}.taskValue

libraryDependencies ++= Seq(
  "com.lihaoyi"        %% "fansi"          % "0.2.5",
  "io.get-coursier"    %% "coursier"       % bloopCoursierVersion,
  "io.get-coursier"    %% "coursier-cache" % bloopCoursierVersion,
  "tech.sparse"        %% "toml-scala"     % "0.2.0",
  "tech.sparse"        %% "pine"           % "0.1.3",
  "ch.epfl.scala"      %% "bloop-config"   % bloopVersion,
  "com.joefkelley"     %% "argyle"         % "1.0.0",
  "org.scalaj"         %% "scalaj-http"    % "2.4.1",
  "io.circe"           %% "circe-core"     % "0.11.1",
  "commons-io"         %  "commons-io"     % "2.6",
  "com.zaxxer"         %  "nuprocess"      % "1.2.4",
  "org.java-websocket" %  "Java-WebSocket" % "1.3.9",
  "io.monix"           %% "minitest"       % "2.3.2" % "test",

  scalaOrganization.value % "scala-reflect" % scalaVersion.value
)

run / fork := true
Global / cancelable := true

testFrameworks += new TestFramework("minitest.runner.Framework")

licenses += ("Apache-2.0", url("http://opensource.org/licenses/Apache-2.0"))
bintrayVcsUrl := Some("git@github.com:tindzk/seed.git")

publishArtifact in (Compile, packageDoc) := false
publishArtifact in (Compile, packageSrc) := false
