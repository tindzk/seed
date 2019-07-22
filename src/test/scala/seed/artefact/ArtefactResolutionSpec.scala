package seed.artefact

import java.nio.file.Paths

import minitest.SimpleTestSuite
import seed.model.Build.{JavaDep, Module, Project, ScalaDep, VersionTag}
import seed.model.Platform.JavaScript
import seed.model.Platform.JVM
import seed.model.Build

object ArtefactResolutionSpec extends SimpleTestSuite {
  test("dependencyFromScalaDep() with Scala.js dependency") {
    val scalaDep = ScalaDep("org.scala-js", "scalajs-dom", "0.9.6")
    val javaDep = ArtefactResolution.javaDepFromScalaDep(
      scalaDep, JavaScript, "0.6", "2.12")
    assertEquals(javaDep,
      JavaDep("org.scala-js", "scalajs-dom_sjs0.6_2.12", "0.9.6"))
  }

  test("dependencyFromScalaDep() with Scala JVM dependency (full Scala version)") {
    val scalaDep = ScalaDep(
      "org.scalameta", "interactive", "4.1.0", VersionTag.Full)
    val javaDep = ArtefactResolution.javaDepFromScalaDep(
      scalaDep, JVM, "2.12.8", "2.12.8")
    assertEquals(javaDep, JavaDep("org.scalameta", "interactive_2.12.8", "4.1.0"))
  }

  test("Extract platform dependencies of test module in libraryDeps()") {
    val build =
      Build(
        project = Project("2.12.8", scalaJsVersion = Some("0.6.26")),
        module = Map(
          "a" -> Module(
            targets = List(JVM, JavaScript),
            test = Some(Module(
              sources = List(Paths.get("a/test")),
              scalaDeps = List(ScalaDep("io.monix", "minitest", "2.3.2")))))))

    val libraryDeps = ArtefactResolution.allLibraryDeps(build)
    assertEquals(libraryDeps, Set(
      JavaDep("io.monix", "minitest_2.12", "2.3.2"),
      JavaDep("io.monix", "minitest_sjs0.6_2.12", "2.3.2")))
  }

  test("jvmDeps()") {
    val build = Build(project = Project("2.12.8"), module = Map())
    val module = Module(
      scalaDeps = List(ScalaDep("io.monix", "minitest", "2.3.2")),
      javaDeps = List(JavaDep("net.java.dev.jna", "jna", "4.5.1")))
    val deps = ArtefactResolution.jvmDeps(build, List(module))
    assertEquals(deps, Set(
      JavaDep("io.monix", "minitest_2.12", "2.3.2"),
      JavaDep("net.java.dev.jna", "jna", "4.5.1")))
  }

  test("Inherit compiler dependencies") {
    val build = Build(
      project = Project("2.12.8", scalaJsVersion = Some("0.6.26")),
      module = Map())
    val module = Module(
      targets = List(JVM, JavaScript),
      compilerDeps = List(
        ScalaDep("org.scalamacros", "paradise", "2.1.1", VersionTag.Full)),
      js = Some(Module(
        compilerDeps = List(
          ScalaDep(
            // TODO Set classifier to "bundle"
            "com.softwaremill.clippy", "plugin", "0.6.0", VersionTag.Binary)))))
    val deps = ArtefactResolution.compilerDeps(build, module)

    assertEquals(deps,
      List(
        Set(
          JavaDep("org.scala-lang", "scala-compiler", "2.12.8"),
          JavaDep("org.scala-lang", "scala-library", "2.12.8"),
          JavaDep("org.scala-lang", "scala-reflect", "2.12.8"),
          JavaDep("org.scalamacros", "paradise_2.12.8", "2.1.1")),
        Set(
          JavaDep("org.scala-lang", "scala-compiler", "2.12.8"),
          JavaDep("org.scala-lang", "scala-library", "2.12.8"),
          JavaDep("org.scala-lang", "scala-reflect", "2.12.8"),
          JavaDep("org.scala-js", "scalajs-compiler_2.12.8", "0.6.26"),
          JavaDep("org.scalamacros", "paradise_2.12.8", "2.1.1"),
          JavaDep("com.softwaremill.clippy", "plugin_2.12", "0.6.0"))))
  }

  test("Compiler dependency with overridden version in platform module") {
    val build = Build(
      project = Project("2.12.8", scalaJsVersion = Some("0.6.26")),
      module = Map())
    val module = Module(
      targets = List(JVM, JavaScript),
      compilerDeps = List(
        ScalaDep("org.scalamacros", "paradise", "2.1.0", VersionTag.Full)),
      js = Some(Module(
        compilerDeps = List(
          ScalaDep("org.scalamacros", "paradise", "2.1.1", VersionTag.Full)))))
    val deps = ArtefactResolution.compilerDeps(build, module)

    assertEquals(deps,
      List(
        Set(
          JavaDep("org.scala-lang", "scala-compiler", "2.12.8"),
          JavaDep("org.scala-lang", "scala-library", "2.12.8"),
          JavaDep("org.scala-lang", "scala-reflect", "2.12.8"),
          JavaDep("org.scalamacros", "paradise_2.12.8", "2.1.0")),
        Set(
          JavaDep("org.scala-lang", "scala-compiler", "2.12.8"),
          JavaDep("org.scala-lang", "scala-library", "2.12.8"),
          JavaDep("org.scala-lang", "scala-reflect", "2.12.8"),
          JavaDep("org.scala-js", "scalajs-compiler_2.12.8", "0.6.26"),
          JavaDep("org.scalamacros", "paradise_2.12.8", "2.1.1"))))
  }

  test("mergeDeps()") {
    val deps =
      List(
        ScalaDep("org.scalamacros", "paradise", "2.1.0", VersionTag.Full),
        ScalaDep("org.scalamacros", "paradise", "2.1.1", VersionTag.Full))

    assertEquals(
      ArtefactResolution.mergeDeps(deps),
      List(ScalaDep("org.scalamacros", "paradise", "2.1.1", VersionTag.Full)))
  }
}
