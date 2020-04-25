package seed.artefact

import java.io.File
import java.nio.file.{Files, Path, Paths}

import minitest.SimpleTestSuite
import org.apache.commons.io.FileUtils
import seed.cli
import seed.Cli.Command
import seed.Log
import seed.artefact.ArtefactResolution.{Regular, Test}
import seed.config.BuildConfig.ModuleConfig
import seed.config.{BuildConfig, BuildConfigSpec}
import seed.generation.util.ProjectGeneration
import seed.model.Build.{JavaDep, Module, Resolvers, ScalaDep, VersionTag}
import seed.model.Platform.{JVM, JavaScript, Native}
import seed.generation.util.BuildUtil.tempPath
import seed.model.Config

object ArtefactResolutionSpec extends SimpleTestSuite {
  test("javaDepFromScalaDep() with Scala.js dependency") {
    val scalaDep = ScalaDep("org.scala-js", "scalajs-dom", "0.9.6")
    val javaDep = ArtefactResolution.javaDepFromScalaDep(
      scalaDep,
      JavaScript,
      "0.6",
      "2.12"
    )
    assertEquals(
      javaDep,
      JavaDep("org.scala-js", "scalajs-dom_sjs0.6_2.12", "0.9.6")
    )
  }

  test(
    "dependencyFromScalaDep() with Scala JVM dependency (full Scala version)"
  ) {
    val scalaDep =
      ScalaDep("org.scalameta", "interactive", "4.1.0", VersionTag.Full)
    val javaDep =
      ArtefactResolution.javaDepFromScalaDep(scalaDep, JVM, "2.12.8", "2.12.8")
    assertEquals(
      javaDep,
      JavaDep("org.scalameta", "interactive_2.12.8", "4.1.0")
    )
  }

  test("Derive runtime libraries from test module") {
    val modules = Map(
      "a" -> Module(
        scalaVersion = Some("2.12.8"),
        scalaJsVersion = Some("0.6.26"),
        targets = List(JVM, JavaScript),
        test = Some(
          Module(
            sources = List(Paths.get("a", "test")),
            scalaDeps = List(ScalaDep("io.monix", "minitest", "2.3.2"))
          )
        )
      )
    )

    val build = ProjectGeneration.toBuild(modules)

    val testJvm = build("a").module.test.get.jvm
    assertEquals(
      testJvm,
      Some(
        Module(
          scalaVersion = Some("2.12.8"),
          scalaJsVersion = Some("0.6.26"),
          scalaOrganisation = Some("org.scala-lang"),
          scalaDeps = List(ScalaDep("io.monix", "minitest", "2.3.2"))
        )
      )
    )

    val libs = ArtefactResolution.allRuntimeLibs(build)
    assertEquals(
      libs,
      Map(
        ("a", JVM, Regular) -> Set(
          JavaDep("org.scala-lang", "scala-library", "2.12.8"),
          JavaDep("org.scala-lang", "scala-reflect", "2.12.8")
        ),
        ("a", JVM, Test) -> Set(
          JavaDep("io.monix", "minitest_2.12", "2.3.2")
        ),
        ("a", JavaScript, Regular) -> Set(
          JavaDep("org.scala-js", "scalajs-library_2.12", "0.6.26"),
          JavaDep("org.scala-lang", "scala-library", "2.12.8"),
          JavaDep("org.scala-lang", "scala-reflect", "2.12.8")
        ),
        ("a", JavaScript, Test) -> Set(
          JavaDep("io.monix", "minitest_sjs0.6_2.12", "2.3.2")
        )
      )
    )
  }

  test("Derive runtime libraries from test module (2)") {
    // The test module overrides the target platforms. Therefore, no Scala
    // Native dependencies should be fetched.
    val path = new File("test", "test-module-dep")
    val tomlBuild =
      FileUtils.readFileToString(new File(path, "build.toml"), "UTF-8")
    val build = BuildConfigSpec.parseBuild(tomlBuild)(_ => "")

    val libs = ArtefactResolution.allRuntimeLibs(build)
    assertEquals(
      libs,
      Map(
        ("example", JVM, Regular) -> Set(
          JavaDep("org.scala-lang", "scala-library", "2.11.11"),
          JavaDep("org.scala-lang", "scala-reflect", "2.11.11")
        ),
        ("example", JVM, Test) -> Set(
          JavaDep("org.scalatest", "scalatest_2.11", "3.0.8")
        ),
        ("example", Native, Regular) -> Set(
          JavaDep("org.scala-lang", "scala-reflect", "2.11.11"),
          JavaDep("org.scala-lang", "scala-library", "2.11.11"),
          JavaDep("org.scala-native", "javalib_native0.3_2.11", "0.3.7"),
          JavaDep("org.scala-native", "scalalib_native0.3_2.11", "0.3.7"),
          JavaDep("org.scala-native", "nativelib_native0.3_2.11", "0.3.7"),
          JavaDep("org.scala-native", "auxlib_native0.3_2.11", "0.3.7")
        ),
        ("example", Native, Test) -> Set()
      )
    )
  }

  test("Derive runtime libraries from test module (3)") {
    // Child module should inherit libraries of parent module
    val path = new File("test", "test-inherit-deps")
    val tomlBuild =
      FileUtils.readFileToString(new File(path, "build.toml"), "UTF-8")
    val build = BuildConfigSpec.parseBuild(tomlBuild)(_ => "")

    val libs = ArtefactResolution.allRuntimeLibs(build)
    assertEquals(
      libs,
      Map(
        ("foo", JVM, Regular) -> Set(
          JavaDep("org.scala-lang", "scala-library", "2.13.0"),
          JavaDep("org.scala-lang", "scala-reflect", "2.13.0")
        ),
        ("foo", JVM, Test) -> Set(
          JavaDep("org.scalatest", "scalatest_2.13", "3.0.8")
        ),
        ("bar", JVM, Regular) -> Set(
          JavaDep("org.scala-lang", "scala-library", "2.13.0"),
          JavaDep("org.scala-lang", "scala-reflect", "2.13.0")
        ),
        ("bar", JVM, Test) -> Set(
          JavaDep("org.scalatest", "scalatest_2.13", "3.0.8")
        )
      )
    )
  }

  test("jvmDeps()") {
    val module = Module(
      scalaVersion = Some("2.12.8"),
      scalaDeps = List(ScalaDep("io.monix", "minitest", "2.3.2")),
      javaDeps = List(JavaDep("net.java.dev.jna", "jna", "4.5.1"))
    )
    val deps = ArtefactResolution.jvmDeps(module)
    assertEquals(
      deps,
      Set(
        JavaDep("io.monix", "minitest_2.12", "2.3.2"),
        JavaDep("net.java.dev.jna", "jna", "4.5.1")
      )
    )
  }

  test("Inherit compiler dependencies") {
    val module = Module(
      scalaVersion = Some("2.12.8"),
      scalaJsVersion = Some("0.6.26"),
      targets = List(JVM, JavaScript),
      compilerDeps =
        List(ScalaDep("org.scalamacros", "paradise", "2.1.1", VersionTag.Full)),
      js = Some(
        Module(
          compilerDeps = List(
            ScalaDep(
              // TODO Set classifier to "bundle"
              "com.softwaremill.clippy",
              "plugin",
              "0.6.0",
              VersionTag.Binary
            )
          )
        )
      )
    )

    val jsDeps = ArtefactResolution.compilerDeps(
      BuildConfig.inheritSettings(Module())(module),
      JavaScript
    )
    val jvmDeps = ArtefactResolution.compilerDeps(
      BuildConfig.inheritSettings(Module())(module),
      JVM
    )

    assertEquals(
      jsDeps,
      Set(
        JavaDep("org.scala-lang", "scala-compiler", "2.12.8"),
        JavaDep("org.scala-lang", "scala-library", "2.12.8"),
        JavaDep("org.scala-lang", "scala-reflect", "2.12.8"),
        JavaDep("org.scala-js", "scalajs-compiler_2.12.8", "0.6.26"),
        JavaDep("org.scalamacros", "paradise_2.12.8", "2.1.1"),
        JavaDep("com.softwaremill.clippy", "plugin_2.12", "0.6.0")
      )
    )

    assertEquals(
      jvmDeps,
      Set(
        JavaDep("org.scala-lang", "scala-compiler", "2.12.8"),
        JavaDep("org.scala-lang", "scala-library", "2.12.8"),
        JavaDep("org.scala-lang", "scala-reflect", "2.12.8"),
        JavaDep("org.scalamacros", "paradise_2.12.8", "2.1.1")
      )
    )
  }

  test("Compiler dependency with overridden version in platform module") {
    val module = Module(
      scalaVersion = Some("2.12.8"),
      scalaJsVersion = Some("0.6.26"),
      targets = List(JVM, JavaScript),
      compilerDeps =
        List(ScalaDep("org.scalamacros", "paradise", "2.1.0", VersionTag.Full)),
      js = Some(
        Module(
          compilerDeps = List(
            ScalaDep("org.scalamacros", "paradise", "2.1.1", VersionTag.Full)
          )
        )
      )
    )

    val jsDeps = ArtefactResolution.compilerDeps(
      BuildConfig.inheritSettings(Module())(module),
      JavaScript
    )
    val jvmDeps = ArtefactResolution.compilerDeps(
      BuildConfig.inheritSettings(Module())(module),
      JVM
    )

    assertEquals(
      jsDeps,
      Set(
        JavaDep("org.scala-lang", "scala-compiler", "2.12.8"),
        JavaDep("org.scala-lang", "scala-library", "2.12.8"),
        JavaDep("org.scala-lang", "scala-reflect", "2.12.8"),
        JavaDep("org.scala-js", "scalajs-compiler_2.12.8", "0.6.26"),
        JavaDep("org.scalamacros", "paradise_2.12.8", "2.1.1")
      )
    )

    assertEquals(
      jvmDeps,
      Set(
        JavaDep("org.scala-lang", "scala-compiler", "2.12.8"),
        JavaDep("org.scala-lang", "scala-library", "2.12.8"),
        JavaDep("org.scala-lang", "scala-reflect", "2.12.8"),
        JavaDep("org.scalamacros", "paradise_2.12.8", "2.1.0")
      )
    )
  }

  test("mergeDeps()") {
    val deps =
      List(
        ScalaDep("org.scalamacros", "paradise", "2.1.0", VersionTag.Full),
        ScalaDep("org.scalamacros", "paradise", "2.1.1", VersionTag.Full)
      )

    assertEquals(
      ArtefactResolution.mergeDeps(deps),
      List(ScalaDep("org.scalamacros", "paradise", "2.1.1", VersionTag.Full))
    )
  }

  test("Set parent target when native module is defined") {
    val module = Module(
      native = Some(
        Module(
          scalaVersion = Some("2.11.11"),
          scalaNativeVersion = Some("0.3.7"),
          sources = List(Paths.get("src"))
        )
      )
    )

    val expected = Module(
      targets = List(Native),
      scalaOrganisation = Some("org.scala-lang"),
      native = Some(
        Module(
          scalaOrganisation = Some("org.scala-lang"),
          scalaVersion = Some("2.11.11"),
          scalaNativeVersion = Some("0.3.7"),
          sources = List(Paths.get("src"))
        )
      )
    )

    assertEquals(BuildConfig.inheritSettings(Module())(module), expected)
  }

  test("Resolve Scala Native compiler dependencies") {
    val module = Module(
      targets = List(Native),
      scalaOrganisation = Some("org.scala-lang"),
      native = Some(
        Module(
          scalaOrganisation = Some("org.scala-lang"),
          scalaVersion = Some("2.11.11"),
          scalaNativeVersion = Some("0.3.7"),
          sources = List(Paths.get("src"))
        )
      )
    )

    val nativeDeps = ArtefactResolution.compilerDeps(
      BuildConfig.inheritSettings(Module())(module),
      Native
    )

    assertEquals(
      nativeDeps,
      Set(
        JavaDep("org.scala-lang", "scala-compiler", "2.11.11"),
        JavaDep("org.scala-lang", "scala-library", "2.11.11"),
        JavaDep("org.scala-lang", "scala-reflect", "2.11.11"),
        JavaDep("org.scala-native", "nscplugin_2.11.11", "0.3.7")
      )
    )
  }

  import seed.generation.BloopIntegrationSpec.packageConfig
  import seed.generation.BloopIntegrationSpec.readBloopJson

  test("Resolve library versions requested by modules") {
    val projectPath = Paths.get("test", "resolve-dep-versions")
    val result      = BuildConfig.load(projectPath, Log.urgent).get
    import result._
    val buildPath = tempPath.resolve("resolve-dep-versions")
    Files.createDirectory(buildPath)
    cli.Generate.ui(
      Config(),
      projectPath,
      buildPath,
      resolvers,
      build,
      Command.Bloop(packageConfig),
      Log.urgent
    )

    val bloopPath = buildPath.resolve(".bloop")

    val a        = readBloopJson(bloopPath.resolve("a.json"))
    val b        = readBloopJson(bloopPath.resolve("b.json"))
    val example1 = readBloopJson(bloopPath.resolve("example1.json"))
    val example2 = readBloopJson(bloopPath.resolve("example2.json"))

    def artefact(path: Path): String = path.getFileName.toString

    assertEquals(
      a.project.classpath.map(artefact).sorted,
      List(
        "pine_sjs0.6_2.11-0.1.6.jar",
        "scala-library-2.11.11-bin-typelevel-4.jar",
        "scala-reflect-2.11.11-bin-typelevel-4.jar",
        "scalajs-dom_sjs0.6_2.11-0.9.7.jar",
        "scalajs-library_2.11-0.6.28.jar"
      )
    )
    assertEquals(
      b.project.classpath.map(artefact).sorted,
      List(
        "scala-library-2.11.11-bin-typelevel-4.jar",
        "scala-reflect-2.11.11-bin-typelevel-4.jar",
        "scalajs-dom_sjs0.6_2.11-0.9.8.jar",
        "scalajs-library_2.11-0.6.28.jar"
      )
    )
    assertEquals(
      example1.project.classpath.map(artefact).sorted,
      List(
        "a",
        "pine_sjs0.6_2.11-0.1.6.jar",
        "scala-library-2.11.11-bin-typelevel-4.jar",
        "scala-reflect-2.11.11-bin-typelevel-4.jar",
        "scalajs-dom_sjs0.6_2.11-0.9.7.jar",
        "scalajs-library_2.11-0.6.28.jar"
      )
    )
    assertEquals(
      example2.project.classpath.map(artefact).sorted,
      List(
        "a",
        "b",
        "pine_sjs0.6_2.11-0.1.6.jar",
        "scala-library-2.11.11-bin-typelevel-4.jar",
        "scala-reflect-2.11.11-bin-typelevel-4.jar",
        "scalajs-dom_sjs0.6_2.11-0.9.8.jar",
        "scalajs-library_2.11-0.6.28.jar"
      )
    )
  }

  test("Resolve Typelevel Scala compiler JARs") {
    val scalaOrganisation = "org.typelevel"
    val scalaVersion      = "2.12.4-bin-typelevel-4"

    val build: Map[String, ModuleConfig] = Map(
      "example" -> ModuleConfig(
        BuildConfig.inheritSettings(Module())(
          Module(
            scalaOrganisation = Some(scalaOrganisation),
            scalaVersion = Some(scalaVersion),
            targets = List(JVM)
          )
        ),
        Paths.get(".")
      )
    )

    val compilerResolutions = ArtefactResolution.compilerResolution(
      build,
      seed.model.Config(),
      Resolvers(),
      packageConfig,
      optionalArtefacts = false,
      Log.urgent
    )

    val scalaCompiler = ArtefactResolution.resolveScalaCompiler(
      compilerResolutions,
      scalaOrganisation,
      scalaVersion,
      List(),
      List()
    )

    assertEquals(
      scalaCompiler.compilerJars.map(_.getFileName.toString),
      List(
        "scala-xml_2.12-1.0.6.jar",
        "scala-compiler-2.12.4-bin-typelevel-4.jar",
        "scala-library-2.12.4-bin-typelevel-4.jar",
        "scala-reflect-2.12.4-bin-typelevel-4.jar"
      )
    )
  }
}
