import sys.process._
import java.nio.file.{Files, Path, Paths}
import scala.collection.JavaConverters._

def parseVersion(file: Path): Option[String] =
  if (!Files.exists(file)) None
  else
    Files
      .readAllLines(file)
      .asScala
      .filter(!_.startsWith("#"))
      .find(_.nonEmpty)
      .map(_.trim)

val seedOrganisation    = "tindzk"
val seedMavenRepository = s"https://dl.bintray.com/$seedOrganisation/maven/"

// Should be `val` instead of `def`, otherwise it is necessary to run
// scaladoc*/publishLocal after every commit
val seedVersion =
  parseVersion(Paths.get("SEED"))                        // CI
    .getOrElse(Seq("git", "describe", "--tags").!!.trim) // Local development
val bloopVersion = parseVersion(Paths.get("BLOOP")).get

val coursierVersion = "2.0.7"

organization := seedOrganisation
version := seedVersion
scalaVersion := "2.12.4-bin-typelevel-4"
scalaOrganization := "org.typelevel"
scalacOptions += "-Yliteral-types"

Compile / sourceGenerators += Def.task {
  val file = (Compile / sourceManaged).value / "seed" / "BuildInfo.scala"
  IO.write(
    file,
    s"""package seed
       |
       |object BuildInfo {
       |  val Organisation    = "$seedOrganisation"
       |  val Version         = "$seedVersion"
       |  val Bloop           = "$bloopVersion"
       |  val Coursier        = "$coursierVersion"
       |  val MavenRepository = "$seedMavenRepository"
       |}""".stripMargin
  )

  Seq(file)
}.taskValue

libraryDependencies ++= Seq(
  "com.lihaoyi"                  %% "fansi"            % "0.2.7",
  "io.get-coursier"              %% "coursier"         % coursierVersion,
  "io.get-coursier"              %% "coursier-cache"   % coursierVersion,
  "tech.sparse"                  %% "toml-scala"       % "0.2.2",
  "tech.sparse"                  %% "pine"             % "0.1.6",
  "ch.epfl.scala"                %% "bloop-config"     % bloopVersion,
  "ch.epfl.scala"                % "bsp4j"             % "2.0.0-M4",
  "ch.epfl.scala"                % "directory-watcher" % "0.8.0+6-f651bd93",
  "com.joefkelley"               %% "argyle"           % "1.0.0",
  "org.scalaj"                   %% "scalaj-http"      % "2.4.2",
  "org.apache.httpcomponents"    % "httpasyncclient"   % "4.1.4",
  "dev.zio"                      %% "zio"              % "1.0.0-RC14",
  "dev.zio"                      %% "zio-streams"      % "1.0.0-RC14",
  "io.circe"                     %% "circe-core"       % "0.11.1",
  "io.circe"                     %% "circe-generic"    % "0.11.1",
  "io.circe"                     %% "circe-parser"     % "0.11.1",
  "commons-io"                   % "commons-io"        % "2.6",
  "com.zaxxer"                   % "nuprocess"         % "1.2.4",
  "org.java-websocket"           % "Java-WebSocket"    % "1.4.0",
  "org.slf4j"                    % "slf4j-simple"      % "1.7.25",
  "com.kohlschutter.junixsocket" % "junixsocket-core"  % "2.2.0",
  "io.monix"                     %% "minitest"         % "2.7.0" % "test",
  scalaOrganization.value        % "scala-reflect"     % scalaVersion.value
)

run / fork := true
Global / cancelable := true

testFrameworks += new TestFramework("minitest.runner.Framework")

val licence = ("Apache-2.0", url("http://opensource.org/licenses/Apache-2.0"))
val vcsUrl  = Some(s"git@github.com:$seedOrganisation/seed.git")

licenses += licence
bintrayVcsUrl := vcsUrl
publishArtifact in (Compile, packageDoc) := false
publishArtifact in (Compile, packageSrc) := false

lazy val scaladoc211 = project
  .in(file("scaladoc211"))
  .settings(
    organization := seedOrganisation,
    version := seedVersion,
    name := "seed-scaladoc",
    scalaVersion := "2.11.12",
    libraryDependencies += "org.scala-lang" % "scala-compiler" % "2.11.12" % Provided,
    // Publish artefact without the standard library. The correct version of
    // scala-library will be resolved during runtime
    pomPostProcess := dropScalaLibraries,
    Compile / unmanagedSourceDirectories += baseDirectory.value / ".." / "scaladoc" / "src" / "main" / "scala",
    licenses += licence,
    bintrayVcsUrl := vcsUrl,
    publishArtifact in (Compile, packageDoc) := false,
    publishArtifact in (Compile, packageSrc) := false
  )

lazy val scaladoc212 = project
  .in(file("scaladoc212"))
  .settings(
    organization := seedOrganisation,
    version := seedVersion,
    name := "seed-scaladoc",
    scalaVersion := "2.12.10",
    libraryDependencies += "org.scala-lang" % "scala-compiler" % "2.12.10" % Provided,
    pomPostProcess := dropScalaLibraries,
    Compile / unmanagedSourceDirectories += baseDirectory.value / ".." / "scaladoc" / "src" / "main" / "scala",
    licenses += licence,
    bintrayVcsUrl := vcsUrl,
    publishArtifact in (Compile, packageDoc) := false,
    publishArtifact in (Compile, packageSrc) := false
  )

lazy val scaladoc213 = project
  .in(file("scaladoc213"))
  .settings(
    organization := seedOrganisation,
    version := seedVersion,
    name := "seed-scaladoc",
    scalaVersion := "2.13.1",
    libraryDependencies += "org.scala-lang" % "scala-compiler" % "2.13.1" % Provided,
    pomPostProcess := dropScalaLibraries,
    Compile / unmanagedSourceDirectories += baseDirectory.value / ".." / "scaladoc" / "src" / "main" / "scala",
    licenses += licence,
    bintrayVcsUrl := vcsUrl,
    publishArtifact in (Compile, packageDoc) := false,
    publishArtifact in (Compile, packageSrc) := false
  )

// From https://stackoverflow.com/questions/27835740/sbt-exclude-certain-dependency-only-during-publish
import scala.xml.{Node => XmlNode, NodeSeq => XmlNodeSeq, _}
import scala.xml.transform.{RewriteRule, RuleTransformer}
def dropScalaLibraries(node: XmlNode): XmlNode =
  new RuleTransformer(new RewriteRule {
    override def transform(node: XmlNode): XmlNodeSeq = node match {
      case e: Elem
          if e.label == "dependency" && e.child.exists(
            child => child.label == "groupId" && child.text == "org.scala-lang"
          ) =>
        XmlNodeSeq.Empty
      case _ => node
    }
  }).transform(node).head
