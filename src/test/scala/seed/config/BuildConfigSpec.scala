package seed.config

import java.io.File

import minitest.SimpleTestSuite
import java.nio.file.Paths

import org.apache.commons.io.FileUtils
import seed.model.Build
import seed.model.Build.{Project, ScalaDep, VersionTag}
import seed.model.Platform.{JVM, JavaScript}

object BuildConfigSpec extends SimpleTestSuite {
  test("Resolve absolute project path") {
    FileUtils.write(new File("/tmp/a.toml"),
      """
        |[project]
        |scalaVersion = "2.12.8"
        |
        |[module.example.jvm]
        |sources = ["src"]
      """.stripMargin, "UTF-8")

    val (projectPath, _) = BuildConfig.load(Paths.get("/tmp/a.toml"))
    assertEquals(projectPath, Paths.get("/tmp"))
  }

  test("Resolve relative project path") {
    FileUtils.write(new File("test/a.toml"),
      """
        |[project]
        |scalaVersion = "2.12.8"
        |
        |[module.example.jvm]
        |sources = ["src"]
      """.stripMargin, "UTF-8")

    val (projectPath, _) = BuildConfig.load(Paths.get("test/a.toml"))
    assertEquals(projectPath, Paths.get("test"))
  }

  test("Set target platforms on test modules") {
    val toml = """
      |[project]
      |scalaVersion      = "2.12.4-bin-typelevel-4"
      |scalaJsVersion    = "0.6.26"
      |scalaOrganisation = "org.typelevel"
      |testFrameworks    = ["minitest.runner.Framework"]
      |
      |[module.example]
      |root       = "shared"
      |sources    = ["shared/src"]
      |targets    = ["js", "jvm"]
      |
      |[module.example.test]
      |sources   = ["shared/test"]
      |scalaDeps = [
      |  ["io.monix", "minitest", "2.3.2"]
      |]
      |
      |[module.example.test.js]
      |sources = ["js/test"]
    """.stripMargin

    val buildRaw = BuildConfig.parseToml(Paths.get("."))(toml)
    val build = BuildConfig.processBuild(buildRaw.right.get, _ =>
      Build(project = Project(scalaVersion = "2.12.8"), module = Map()))

    assertEquals(
      build.module("example").test.get.targets,
      List(JavaScript, JVM))
  }

  test("Parse TOML with full Scala dependency") {
    val toml = """
      |[project]
      |scalaVersion = "2.12.8"
      |
      |[module.example.jvm]
      |sources = ["shared/src"]
      |scalaDeps = [
      |  ["org.scalameta", "interactive", "4.1.0", "full"]
      |]
    """.stripMargin

    val buildRaw = BuildConfig.parseToml(Paths.get("."))(toml)
    val build = BuildConfig.processBuild(buildRaw.right.get, _ =>
      Build(project = Project(scalaVersion = "2.12.8"), module = Map()))

    assertEquals(
      build.module("example").jvm.get.scalaDeps,
      List(ScalaDep("org.scalameta", "interactive", "4.1.0", VersionTag.Full)))
  }
}
