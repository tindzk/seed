package seed.artefact

import java.nio.file.Paths

import minitest.SimpleTestSuite
import seed.config.BuildConfig
import seed.generation.util.ProjectGeneration
import seed.model.Build.{JavaDep, Module, ScalaDep, VersionTag}
import seed.model.Platform.{JVM, JavaScript, Native}

object ArtefactResolutionSpec extends SimpleTestSuite {
  test("dependencyFromScalaDep() with Scala.js dependency") {
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

  test("Extract platform dependencies of test module in libraryDeps()") {
    val modules = Map(
      "a" -> Module(
        scalaVersion = Some("2.12.8"),
        scalaJsVersion = Some("0.6.26"),
        targets = List(JVM, JavaScript),
        test = Some(
          Module(
            sources = List(Paths.get("a/test")),
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

    val libraryDeps = ArtefactResolution.allLibraryDeps(build)
    assertEquals(
      libraryDeps,
      Set(
        JavaDep("io.monix", "minitest_2.12", "2.3.2"),
        JavaDep("io.monix", "minitest_sjs0.6_2.12", "2.3.2")
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

    val deps = ArtefactResolution.compilerDeps(
      BuildConfig.inheritSettings(Module())(module)
    )

    assertEquals(
      deps,
      List(
        Set(
          JavaDep("org.scala-lang", "scala-compiler", "2.12.8"),
          JavaDep("org.scala-lang", "scala-library", "2.12.8"),
          JavaDep("org.scala-lang", "scala-reflect", "2.12.8"),
          JavaDep("org.scala-js", "scalajs-compiler_2.12.8", "0.6.26"),
          JavaDep("org.scalamacros", "paradise_2.12.8", "2.1.1"),
          JavaDep("com.softwaremill.clippy", "plugin_2.12", "0.6.0")
        ),
        Set(
          JavaDep("org.scala-lang", "scala-compiler", "2.12.8"),
          JavaDep("org.scala-lang", "scala-library", "2.12.8"),
          JavaDep("org.scala-lang", "scala-reflect", "2.12.8"),
          JavaDep("org.scalamacros", "paradise_2.12.8", "2.1.1")
        )
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

    val deps = ArtefactResolution.compilerDeps(
      BuildConfig.inheritSettings(Module())(module)
    )

    assertEquals(
      deps,
      List(
        Set(
          JavaDep("org.scala-lang", "scala-compiler", "2.12.8"),
          JavaDep("org.scala-lang", "scala-library", "2.12.8"),
          JavaDep("org.scala-lang", "scala-reflect", "2.12.8"),
          JavaDep("org.scala-js", "scalajs-compiler_2.12.8", "0.6.26"),
          JavaDep("org.scalamacros", "paradise_2.12.8", "2.1.1")
        ),
        Set(
          JavaDep("org.scala-lang", "scala-compiler", "2.12.8"),
          JavaDep("org.scala-lang", "scala-library", "2.12.8"),
          JavaDep("org.scala-lang", "scala-reflect", "2.12.8"),
          JavaDep("org.scalamacros", "paradise_2.12.8", "2.1.0")
        )
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

    val deps = ArtefactResolution.compilerDeps(
      BuildConfig.inheritSettings(Module())(module)
    )

    assertEquals(
      deps,
      List(
        Set(
          JavaDep("org.scala-lang", "scala-compiler", "2.11.11"),
          JavaDep("org.scala-lang", "scala-library", "2.11.11"),
          JavaDep("org.scala-lang", "scala-reflect", "2.11.11"),
          JavaDep("org.scala-native", "nscplugin_2.11.11", "0.3.7")
        )
      )
    )
  }
}
