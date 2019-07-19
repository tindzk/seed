package seed.config

import java.io.File

import minitest.SimpleTestSuite
import java.nio.file.{Files, Paths}

import org.apache.commons.io.FileUtils
import seed.Log
import seed.config.util.TomlUtils
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

    val BuildConfig.Result(_, projectPath, moduleProjectPaths) =
      BuildConfig.load(Paths.get("/tmp/a.toml"), Log.urgent).get
    assertEquals(projectPath, Paths.get("/tmp"))
    assertEquals(moduleProjectPaths, Map("example" -> Paths.get("/tmp")))
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

    val BuildConfig.Result(_, projectPath, moduleProjectPaths) =
      BuildConfig.load(Paths.get("test/a.toml"), Log.urgent).get
    assertEquals(projectPath, Paths.get("test"))
    assertEquals(moduleProjectPaths, Map("example" -> Paths.get("test")))
  }

  test("Import module") {
    Files.createDirectories(Paths.get("/tmp/seed-root/child"))

    FileUtils.write(new File("/tmp/seed-root/child/build.toml"),
      """
        |[project]
        |scalaVersion = "2.12.8"
        |
        |[module.child.jvm]
        |sources = ["src"]
      """.stripMargin, "UTF-8")

    FileUtils.write(new File("/tmp/seed-root/build.toml"),
      """
        |import = ["child"]
        |
        |[project]
        |scalaVersion = "2.12.8"
        |
        |[module.root.jvm]
        |sources = ["src"]
      """.stripMargin, "UTF-8")

    val BuildConfig.Result(_, projectPath, moduleProjectPaths) =
      BuildConfig.load(Paths.get("/tmp/seed-root"), Log.urgent).get
    assertEquals(moduleProjectPaths, Map(
      "root"  -> Paths.get("/tmp/seed-root"),
      "child" -> Paths.get("/tmp/seed-root/child")))
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

    val buildRaw = TomlUtils.parseBuildToml(Paths.get("."))(toml)
    val (build, _) = BuildConfig.processBuild(buildRaw.right.get, Paths.get("."), _ =>
      Some((Build(project = Project(scalaVersion = "2.12.8"), module = Map()), Map())))

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

    val buildRaw = TomlUtils.parseBuildToml(Paths.get("."))(toml)
    val (build, _) = BuildConfig.processBuild(buildRaw.right.get, Paths.get("."), _ =>
      Some(Build(project = Project(scalaVersion = "2.12.8"), module = Map()), Map()))

    assertEquals(
      build.module("example").jvm.get.scalaDeps,
      List(ScalaDep("org.scalameta", "interactive", "4.1.0", VersionTag.Full)))
  }

  test("Copy compilerDeps from project definitions to modules") {
    val fooToml = """
      |import = ["bar"]
      |
      |[project]
      |scalaVersion = "2.12.8"
      |compilerDeps = [
      |  ["foo", "foo", "1.0", "full"]
      |]
      |
      |[module.foo]
      |sources = ["foo-jvm/src"]
      |[module.foo.js]
      |sources = ["foo-js/src"]
      |compilerDeps = [
      |  ["foo-js", "foo-js", "1.0", "full"]
      |]
    """.stripMargin

    val barToml = """
      |[project]
      |scalaVersion = "2.12.8"
      |compilerDeps = [
      |  ["bar", "bar", "1.0", "full"]
      |]
      |
      |[module.bar]
      |sources = ["bar/src"]
    """.stripMargin

    val buildRaw = TomlUtils.parseBuildToml(Paths.get("."))(fooToml)
    val (build, _) = BuildConfig.processBuild(buildRaw.right.get, Paths.get("."),
      _ => TomlUtils.parseBuildToml(Paths.get("."))(barToml).toOption.map(build => build -> Map.empty))

    assertEquals(
      build.module("foo").compilerDeps,
      List(ScalaDep("foo", "foo", "1.0", VersionTag.Full)))

    assertEquals(
      build.module("foo").js.get.compilerDeps,
      List(
        ScalaDep("foo", "foo", "1.0", VersionTag.Full),
        ScalaDep("foo-js", "foo-js", "1.0", VersionTag.Full))
    )

    assertEquals(
      build.module("bar").compilerDeps,
      List(ScalaDep("bar", "bar", "1.0", VersionTag.Full))
    )
  }
}
