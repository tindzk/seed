package seed.config

import java.io.File

import minitest.SimpleTestSuite
import java.nio.file.{Files, Path, Paths}

import org.apache.commons.io.FileUtils
import seed.{Log, LogLevel}
import seed.config.util.TomlUtils
import seed.generation.util.BuildUtil
import seed.model.Build.{JavaDep, Resolvers, ScalaDep, VersionTag}
import seed.model.Platform.{JVM, JavaScript, Native}
import BuildUtil.tempPath
import seed.cli.util.Ansi
import seed.config.BuildConfig.{Build, Result}
import seed.model.{Build, Organisation, Platform}

import scala.collection.mutable.ListBuffer

object BuildConfigSpec extends SimpleTestSuite {
  test("Set default values and inherit settings") {
    val original = Map(
      "base" -> Build.Module(
        scalaVersion = Some("2.12.8"),
        targets = List(Platform.JVM),
        javaDeps = List(JavaDep("org.postgresql", "postgresql", "42.2.5"))
      ),
      "example" -> Build.Module(
        scalaVersion = Some("2.12.8"),
        moduleDeps = List("base"),
        targets = List(Platform.JVM)
      )
    )

    val inherited = Map(
      "base" -> Build.Module(
        scalaVersion = Some("2.12.8"),
        scalaOrganisation = Some(Organisation.Lightbend.packageName),
        targets = List(Platform.JVM),
        javaDeps = List(JavaDep("org.postgresql", "postgresql", "42.2.5")),
        jvm = Some(
          Build.Module(
            scalaVersion = Some("2.12.8"),
            scalaOrganisation = Some(Organisation.Lightbend.packageName),
            javaDeps = List(JavaDep("org.postgresql", "postgresql", "42.2.5"))
          )
        )
      ),
      "example" -> Build.Module(
        scalaVersion = Some("2.12.8"),
        scalaOrganisation = Some(Organisation.Lightbend.packageName),
        moduleDeps = List("base"),
        targets = List(Platform.JVM),
        jvm = Some(
          Build.Module(
            scalaVersion = Some("2.12.8"),
            scalaOrganisation = Some(Organisation.Lightbend.packageName),
            moduleDeps = List("base")
          )
        )
      )
    )

    assertEquals(
      original.mapValues(BuildConfig.inheritSettings(Build.Module())),
      inherited
    )
  }

  test("Test module") {
    val original = Map(
      "example" -> Build.Module(
        scalaVersion = Some("2.12.8"),
        targets = List(Platform.JVM),
        jvm = Some(Build.Module(moduleDeps = List("base"))),
        test = Some(Build.Module(jvm = Some(Build.Module())))
      )
    )

    val inherited = Map(
      "example" -> Build.Module(
        scalaVersion = Some("2.12.8"),
        scalaOrganisation = Some(Organisation.Lightbend.packageName),
        targets = List(Platform.JVM),
        jvm = Some(
          Build.Module(
            scalaVersion = Some("2.12.8"),
            scalaOrganisation = Some(Organisation.Lightbend.packageName),
            moduleDeps = List("base")
          )
        ),
        test = Some(
          Build.Module(
            scalaVersion = Some("2.12.8"),
            scalaOrganisation = Some(Organisation.Lightbend.packageName),
            targets = List(Platform.JVM),
            jvm = Some(
              Build.Module(
                scalaVersion = Some("2.12.8"),
                scalaOrganisation = Some(Organisation.Lightbend.packageName),
                moduleDeps = List("base")
              )
            )
          )
        )
      )
    )

    assertEquals(
      original.mapValues(BuildConfig.inheritSettings(Build.Module())),
      inherited
    )
  }

  def parseBuild(toml: String, log: Log = Log.urgent, fail: Boolean = false)(
    f: Path => String
  ): Build = {
    val parsed = TomlUtils.parseBuildToml(Paths.get("."))(toml)
    val build = BuildConfig.processBuild(
      parsed.right.get,
      Paths.get("."), { path =>
        val build = parseBuild(f(path))(f)
        Some(Result(Paths.get("."), Resolvers(), build))
      },
      Log.urgent
    )

    val valid = build.forall {
      case (name, module) =>
        BuildConfig.checkModule(build, name, module.module, log)
    }

    assertEquals(valid, !fail)
    build
  }

  test("Minimal native build") {
    val fooToml = """
      |[module.demo.native]
      |scalaVersion       = "2.11.11"
      |scalaNativeVersion = "0.3.7"
      |sources            = ["src/"]
    """.stripMargin

    val build = parseBuild(fooToml)(_ => "")

    assertEquals(build("demo").module.targets, List(Platform.Native))
    assertEquals(build("demo").module.native.get.scalaVersion, Some("2.11.11"))
    assertEquals(
      build("demo").module.native.get.scalaNativeVersion,
      Some("0.3.7")
    )
    assertEquals(
      build("demo").module.native.get.sources,
      List(Paths.get("src/"))
    )
  }

  test("Resolve absolute project path") {
    FileUtils.write(
      tempPath.resolve("a.toml").toFile,
      """
        |[project]
        |scalaVersion = "2.12.8"
        |
        |[module.example.jvm]
        |sources = ["src"]
      """.stripMargin,
      "UTF-8"
    )

    val config = BuildConfig.load(tempPath.resolve("a.toml"), Log.urgent).get
    assertEquals(config.projectPath, tempPath)
    assertEquals(config.build("example").path, tempPath)
  }

  test("Resolve relative project path") {
    FileUtils.write(
      new File("test/a.toml"),
      """
        |[project]
        |scalaVersion = "2.12.8"
        |
        |[module.example.jvm]
        |sources = ["src"]
      """.stripMargin,
      "UTF-8"
    )

    val config = BuildConfig.load(Paths.get("test/a.toml"), Log.urgent).get
    assertEquals(config.projectPath, Paths.get("test"))
    assertEquals(config.build("example").path, Paths.get("test"))
    Files.delete(Paths.get("test/a.toml"))
  }

  test("Import module") {
    Files.createDirectories(tempPath.resolve("seed-root").resolve("child"))

    FileUtils.write(
      tempPath
        .resolve("seed-root")
        .resolve("child")
        .resolve("build.toml")
        .toFile,
      """
        |[project]
        |scalaVersion = "2.12.8"
        |
        |[module.child.jvm]
        |sources = ["src"]
      """.stripMargin,
      "UTF-8"
    )

    FileUtils.write(
      tempPath.resolve("seed-root").resolve("build.toml").toFile,
      """
        |import = ["child"]
        |
        |[project]
        |scalaVersion = "2.12.8"
        |
        |[module.root.jvm]
        |sources = ["src"]
      """.stripMargin,
      "UTF-8"
    )

    val config =
      BuildConfig.load(tempPath.resolve("seed-root"), Log.urgent).get
    assertEquals(
      config.build.mapValues(_.path),
      Map(
        "root"  -> tempPath.resolve("seed-root"),
        "child" -> tempPath.resolve("seed-root").resolve("child")
      )
    )
  }

  test("Set target platforms on test modules") {
    val toml = """
      |[project]
      |scalaVersion   = "2.12.8"
      |scalaJsVersion = "0.6.26"
      |testFrameworks = ["minitest.runner.Framework"]
      |
      |[module.example]
      |sources = ["shared/src"]
      |targets = ["js", "jvm"]
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

    val build = parseBuild(toml)(_ => "")
    assertEquals(
      build("example").module.test.get.targets,
      List(JavaScript, JVM)
    )
    assert(build("example").module.test.get.js.isDefined)
    assert(build("example").module.test.get.jvm.isDefined)
  }

  test("Set target platforms on test modules (2)") {
    val toml =
      """
        |[project]
        |scalaVersion   = "2.13.0"
        |scalaJsVersion = "0.6.28"
        |testFrameworks = ["minitest.runner.Framework"]
        |
        |[module.example]
        |sources = ["shared/src"]
        |mainClass = "a.b"
        |scalaDeps = [
        |  ["org.scalameta", "interactive", "4.1.0", "full"]
        |]
        |
        |[module.example.js]
        |[module.example.jvm]
        |
        |[module.example.test]
        |sources = ["shared/test/"]
      """.stripMargin

    val build = parseBuild(toml)(_ => "")
    assertEquals(
      build("example").module.test.get.targets,
      List(JVM, JavaScript)
    )
    assert(build("example").module.test.get.js.isDefined)
    assert(build("example").module.test.get.jvm.isDefined)
    assert(build("example").module.test.get.mainClass.isEmpty)
    assert(build("example").module.test.get.scalaDeps.isEmpty)
    assert(build("example").module.test.get.js.get.mainClass.isEmpty)
    assert(build("example").module.test.get.js.get.scalaDeps.isEmpty)
  }

  test("Override target platforms on test module") {
    val toml =
      """
        |[project]
        |scalaVersion       = "2.11.11"
        |scalaJsVersion     = "0.6.28"
        |scalaNativeVersion = "0.3.7"
        |
        |[module.example]
        |sources = ["src/"]
        |targets = ["jvm", "js", "native"]
        |
        |[module.example.test]
        |sources = ["src/"]
        |targets = ["jvm", "js"]
      """.stripMargin

    val build = parseBuild(toml)(_ => "")
    assertEquals(build("example").module.targets, List(JVM, JavaScript, Native))
    assertEquals(
      build("example").module.test.get.targets,
      List(JVM, JavaScript)
    )
    assertEquals(build("example").module.test.get.native, None)
  }

  test("Set Scala version on test module") {
    val toml =
      """
        |[project]
        |scalaVersion       = "2.13.0"
        |scalaNativeVersion = "0.3.9"
        |
        |[module.example]
        |sources = ["shared/src"]
        |targets = ["jvm", "native"]
        |
        |[module.example.native]
        |scalaVersion = "2.11.12"
        |sources      = ["native/src"]
        |
        |[module.example.test.native]
      """.stripMargin

    val build = parseBuild(toml)(_ => "")
    assertEquals(build("example").module.targets, List(Native, JVM))
    assertEquals(
      build("example").module.native.get.scalaVersion,
      Some("2.11.12")
    )
    assertEquals(build("example").module.test.get.targets, List(Native, JVM))
    assertEquals(
      build("example").module.test.get.native.get.scalaVersion,
      Some("2.11.12")
    )
    assertEquals(build("example").module.test.get.native.get.targets, List())
  }

  test("Set Scala version on test module (2)") {
    val toml =
      """
        |[project]
        |scalaVersion       = "2.13.0"
        |scalaNativeVersion = "0.3.9"
        |
        |[module.example]
        |sources = ["shared/src"]
        |targets = ["jvm", "native"]
        |
        |[module.example.native]
        |scalaVersion = "2.11.11"
        |sources      = ["native/src"]
        |
        |[module.example.test.native]
        |scalaVersion = "2.11.12"
      """.stripMargin

    val build = parseBuild(toml)(_ => "")
    assertEquals(build("example").module.targets, List(Native, JVM))
    assertEquals(
      build("example").module.native.get.scalaVersion,
      Some("2.11.11")
    )
    assertEquals(build("example").module.test.get.targets, List(Native, JVM))
    assertEquals(
      build("example").module.test.get.native.get.scalaVersion,
      Some("2.11.12")
    )
    assertEquals(build("example").module.test.get.native.get.targets, List())
  }

  test("Set Scala version on test module (3)") {
    val toml =
      """
        |[project]
        |scalaVersion       = "2.13.0"
        |scalaNativeVersion = "0.3.9"
        |
        |[module.example]
        |sources = ["shared/src"]
        |targets = ["jvm", "native"]
        |
        |[module.example.native]
        |scalaVersion = "2.11.11"
        |sources      = ["native/src"]
        |
        |[module.example.test.native]
        |scalaVersion = "2.11.12"
      """.stripMargin

    val build = parseBuild(toml)(_ => "")
    assertEquals(build("example").module.targets, List(Native, JVM))
    assertEquals(
      build("example").module.native.get.scalaVersion,
      Some("2.11.11")
    )
    assertEquals(build("example").module.test.get.targets, List(Native, JVM))
    assertEquals(
      build("example").module.test.get.native.get.scalaVersion,
      Some("2.11.12")
    )
    assertEquals(build("example").module.test.get.native.get.targets, List())

    assertEquals(
      build("example").module.scalaOrganisation,
      Some("org.scala-lang")
    )
    assertEquals(
      build("example").module.test.get.scalaOrganisation,
      Some("org.scala-lang")
    )
    assertEquals(
      build("example").module.test.get.jvm.get.scalaOrganisation,
      Some("org.scala-lang")
    )
    assertEquals(
      build("example").module.test.get.native.get.scalaOrganisation,
      Some("org.scala-lang")
    )
  }

  test("Set Scala organisation on test module") {
    val toml =
      """
        |[project]
        |scalaVersion      = "2.11.11-bin-typelevel-4"
        |scalaJsVersion    = "0.6.28"
        |scalaOrganisation = "org.typelevel"
        |
        |[module.example]
        |sources = ["shared/src"]
        |targets = ["js", "jvm"]
        |
        |[module.example.test]
        |sources = ["shared/test"]
        |targets = ["js", "jvm"]
        |
        |[module.example.test.js]
        |sources = ["js/src"]
      """.stripMargin

    val build = parseBuild(toml)(_ => "")
    assertEquals(
      build("example").module.scalaOrganisation,
      Some("org.typelevel")
    )
    assertEquals(
      build("example").module.test.get.scalaOrganisation,
      Some("org.typelevel")
    )
    assertEquals(
      build("example").module.test.get.js.get.scalaOrganisation,
      Some("org.typelevel")
    )
  }

  test("Set Scala organisation on test module (2)") {
    val toml =
      """
        |[module.example]
        |scalaVersion      = "2.11.11-bin-typelevel-4"
        |scalaJsVersion    = "0.6.28"
        |scalaOrganisation = "org.typelevel"
        |sources           = ["shared/src"]
        |targets           = ["js", "jvm"]
        |
        |[module.example.test]
        |sources = ["shared/test"]
        |targets = ["js", "jvm"]
        |
        |[module.example.test.js]
        |sources = ["js/src"]
      """.stripMargin

    val build = parseBuild(toml)(_ => "")
    assertEquals(
      build("example").module.scalaOrganisation,
      Some("org.typelevel")
    )
    assertEquals(
      build("example").module.test.get.js.get.scalaOrganisation,
      Some("org.typelevel")
    )
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

    val build = parseBuild(toml)(_ => "")
    assertEquals(
      build("example").module.jvm.get.scalaDeps,
      List(ScalaDep("org.scalameta", "interactive", "4.1.0", VersionTag.Full))
    )
  }

  test("Inherit compilerDeps from project definition and base modules") {
    val fooToml = """
      |import = ["bar"]
      |
      |[project]
      |scalaVersion = "2.12.8"
      |scalaJsVersion = "0.6.26"
      |compilerDeps = [
      |  ["foo", "foo", "1.0", "full"]
      |]
      |
      |[module.foo]
      |sources = ["foo/src"]
      |
      |[module.foo.js]
      |sources = ["foo-js/src"]
      |compilerDeps = [
      |  ["foo-js", "foo-js", "1.0", "full"]
      |]
      |
      |[module.foo2]
      |sources = ["foo2/src"]
      |compilerDeps = [
      |  ["foo", "foo", "2.0", "full"]
      |]
      |
      |[module.foo2.js]
      |compilerDeps = [
      |  ["foo", "foo", "3.0", "full"]
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
      |scalaJsVersion = "0.6.26"
      |targets = ["js"]
      |sources = ["bar/src"]
    """.stripMargin

    val build = parseBuild(fooToml) {
      case p if p == Paths.get("bar") => barToml
    }

    assertEquals(
      build("foo").module.compilerDeps,
      List(ScalaDep("foo", "foo", "1.0", VersionTag.Full))
    )

    assertEquals(
      build("foo").module.js.get.compilerDeps,
      List(
        ScalaDep("foo", "foo", "1.0", VersionTag.Full),
        ScalaDep("foo-js", "foo-js", "1.0", VersionTag.Full)
      )
    )

    assertEquals(
      build("foo2").module.compilerDeps,
      List(ScalaDep("foo", "foo", "2.0", VersionTag.Full))
    )

    assertEquals(
      build("foo2").module.js.get.compilerDeps,
      List(ScalaDep("foo", "foo", "3.0", VersionTag.Full))
    )

    assertEquals(
      build("bar").module.compilerDeps,
      List(ScalaDep("bar", "bar", "1.0", VersionTag.Full))
    )

    assertEquals(
      build("bar").module.js.get.compilerDeps,
      List(ScalaDep("bar", "bar", "1.0", VersionTag.Full))
    )
  }

  test("Inheritance of settings") {
    val fooToml = """
      |[project]
      |scalaVersion = "2.12.8"
      |testFrameworks = ["a.b"]
      |scalaOptions = ["-deprecation"]
      |
      |[module.foo]
      |scalaVersion = "2.11.11"
      |sources = ["foo/src"]
      |
      |[module.foo.js]
      |scalaJsVersion = "0.6.26"
      |sources = ["foo-js/src"]
      |testFrameworks = ["c.d"]
      |
      |[module.bar]
      |targets = ["jvm"]
      |sources = ["foo/src"]
      |scalaOptions = ["-language:existentials"]
      |testFrameworks = ["a.b"]
    """.stripMargin

    val build = parseBuild(fooToml)(_ => "")

    assertEquals(build("foo").module.targets, List(Platform.JavaScript))
    assertEquals(build("foo").module.scalaVersion, Some("2.11.11"))
    assertEquals(build("foo").module.testFrameworks, List("a.b"))
    assertEquals(build("foo").module.scalaOptions, List("-deprecation"))

    assertEquals(build("foo").module.js.get.scalaVersion, Some("2.11.11"))
    assertEquals(build("foo").module.js.get.testFrameworks, List("a.b", "c.d"))
    assertEquals(build("foo").module.js.get.scalaOptions, List("-deprecation"))

    assertEquals(build("bar").module.scalaVersion, Some("2.12.8"))
    assertEquals(
      build("bar").module.scalaOptions,
      List("-deprecation", "-language:existentials")
    )
  }

  test("Platform compatibility when inheriting") {
    val buildToml = """
      |[project]
      |scalaJsVersion     = "0.6.26"
      |scalaNativeVersion = "0.3.7"
      |
      |[module.foo]
      |scalaVersion = "2.11.11"
      |sources      = ["foo/"]
      |targets      = ["js"]
      |
      |[module.bar]
      |scalaVersion = "2.11.11"
      |moduleDeps   = ["foo"]
      |sources      = ["bar/"]
      |targets      = ["js", "native"]
    """.stripMargin

    val messages = ListBuffer[String]()
    val log      = new Log(messages += _, identity, LogLevel.Error, false)
    parseBuild(buildToml, log, fail = true)(_ => "")
    assert(
      messages.exists(
        _.contains(
          s"Module ${Ansi.italic("foo")} has missing target platform ${Ansi
            .italic("native")} required by ${Ansi.italic("bar")}"
        )
      )
    )
  }

  test("Platform compatibility when inheriting (2)") {
    val buildToml = """
      |[project]
      |scalaJsVersion     = "0.6.26"
      |scalaNativeVersion = "0.3.7"
      |
      |[module.foo.js]
      |scalaVersion = "2.11.11"
      |sources      = ["foo/"]
      |
      |[module.bar.native]
      |scalaVersion = "2.11.11"
      |moduleDeps   = ["foo"]
      |sources      = ["bar/"]
    """.stripMargin

    val messages = ListBuffer[String]()
    val log      = new Log(messages += _, identity, LogLevel.Error, false)
    parseBuild(buildToml, log, fail = true)(_ => "")
    assert(
      messages.exists(
        _.contains(
          s"Module ${Ansi.italic("foo")} has missing target platform ${Ansi
            .italic("native")} required by ${Ansi.italic("bar")}"
        )
      )
    )
  }

  test("Custom build targets do not need to set any platforms") {
    val buildToml = """
      |[project]
      |scalaVersion = "2.11.11"
      |
      |[module.template.target.scss]
      |root    = "scss"
      |command = "yarn install && yarn run gulp"
      |
      |[module.app.jvm]
      |moduleDeps = ["template"]
      |sources    = ["src/"]
    """.stripMargin

    val messages = ListBuffer[String]()
    val log      = new Log(messages += _, identity, LogLevel.Error, false)
    val build    = parseBuild(buildToml, log)(_ => "")
    assertEquals(build("app").module.targets, List(JVM))
  }

  test("Scala version compatibility") {
    val buildToml = """
      |[module.foo.jvm]
      |scalaVersion = "2.11.11"
      |sources = ["foo/src"]
      |
      |[module.bar.jvm]
      |moduleDeps = ["foo"]
      |scalaVersion = "2.12.8"
      |sources = ["bar/src"]
    """.stripMargin

    val messages = ListBuffer[String]()
    val log      = new Log(messages += _, identity, LogLevel.Error, false)
    parseBuild(buildToml, log, fail = true)(_ => "")
    assert(
      messages.exists(
        _.contains(
          s"Scala version of ${Ansi.italic("bar:jvm")} (2.12.8) is incompatible with ${Ansi.italic("foo:jvm")} (2.11.11)"
        )
      )
    )
  }

  test("Detect wrong test module syntax") {
    val buildToml = """
      |[module.foo]
      |sources = ["src"]
      |scalaVersion = "2.11.11"
      |
      |[module.foo.jvm.test]
      |sources = ["test"]
    """.stripMargin

    val messages = ListBuffer[String]()
    val log      = new Log(messages += _, identity, LogLevel.Error, false)
    parseBuild(buildToml, log, fail = true)(_ => "")
    assert(
      messages.exists(
        _.contains(
          s"A test module cannot be defined on the platform module ${Ansi
            .italic("foo:jvm")}. Did you mean ${Ansi.italic("[module.foo.test.jvm]")}?"
        )
      )
    )
  }

  test("Detect cyclic module dependency") {
    val buildToml = """
      |[module.foo]
      |moduleDeps = ["foo"]
      |scalaVersion = "2.11.11"
      |sources = ["src"]
      |targets = ["jvm"]
    """.stripMargin

    val messages = ListBuffer[String]()
    val log      = new Log(messages += _, identity, LogLevel.Error, false)
    parseBuild(buildToml, log, fail = true)(_ => "")
    assert(
      messages.exists(
        _.contains(s"Module ${Ansi.italic("foo")} cannot depend on itself")
      )
    )
  }

  test("Detect cyclic module dependency (2)") {
    val buildToml = """
      |[module.foo.jvm]
      |moduleDeps = ["foo"]
      |scalaVersion = "2.11.11"
      |sources = ["src"]
    """.stripMargin

    val messages = ListBuffer[String]()
    val log      = new Log(messages += _, identity, LogLevel.Error, false)
    parseBuild(buildToml, log, fail = true)(_ => "")
    assert(
      messages.exists(
        _.contains(s"Module ${Ansi.italic("foo")} cannot depend on itself")
      )
    )
  }

  test("Detect cyclic module dependency (3)") {
    val buildToml = """
      |[project]
      |scalaVersion = "2.11.11"
      |
      |[module.a]
      |moduleDeps = ["b"]
      |sources = ["a/src/"]
      |targets = ["jvm"]
      |
      |[module.b]
      |moduleDeps = ["a"]
      |sources = ["b/src/"]
      |targets = ["jvm"]
      """.stripMargin

    val messages = ListBuffer[String]()
    val log      = new Log(messages += _, identity, LogLevel.Error, false)
    parseBuild(buildToml, log, fail = true)(_ => "")
    assert(
      messages.exists(
        _.contains(
          s"Cycle detected in dependencies of module ${Ansi.italic("a")}"
        )
      )
    )
  }

  test("Detect cyclic module dependency (4)") {
    val buildToml = """
      |[project]
      |scalaVersion = "2.11.11"
      |
      |[module.a.jvm]
      |moduleDeps = ["b"]
      |sources = ["a/src/"]
      |
      |[module.b.jvm]
      |moduleDeps = ["a"]
      |sources = ["b/src/"]
    """.stripMargin

    val messages = ListBuffer[String]()
    val log      = new Log(messages += _, identity, LogLevel.Error, false)
    parseBuild(buildToml, log, fail = true)(_ => "")
    assert(
      messages.exists(
        _.contains(
          s"Cycle detected in dependencies of module ${Ansi.italic("a")}"
        )
      )
    )
  }
}
