package seed.generation

import minitest.SimpleTestSuite

import java.nio.file.Paths

import seed.Cli.PackageConfig
import seed.artefact.ArtefactResolution
import seed.model.Build.{Module, Project}
import seed.model.Platform.JVM
import seed.model.Build

object IdeaSpec extends SimpleTestSuite {
  test("Normalise paths") {
    assertEquals(
      Idea.normalisePath(Idea.ModuleDir, Paths.get("/tmp"))(Paths.get("/tmp")),
      "$MODULE_DIR$/")

    assertEquals(
      Idea.normalisePath(Idea.ModuleDir, Paths.get("/tmp/.idea/modules"))(
        Paths.get("/tmp/src")
      ), "$MODULE_DIR$/../../src")

    assertEquals(
      Idea.normalisePath(Idea.ModuleDir, Paths.get(".idea/modules"))(Paths.get("/tmp/build")),
      "/tmp/build")
  }

  test("Generate modules") {
    val build =
      Build(
        project = Project("2.12.8"),
        module = Map(
          "a" -> Module(
            targets = List(JVM),
            jvm = Some(Module(
              root = Some(Paths.get("a")),
              sources = List(Paths.get("a/src"))))),
          "b" -> Module(
            targets = List(JVM),
            jvm = Some(Module(
              root = Some(Paths.get("b")),
              sources = List(Paths.get("b/src")))))))

    val projectPath = Paths.get(".")
    val outputPath = Paths.get("/tmp")
    val packageConfig = PackageConfig(false, false, None, None)
    val compilerDeps0 = ArtefactResolution.allCompilerDeps(build)
    val (_, platformResolution, compilerResolution) =
      ArtefactResolution.resolution(seed.model.Config(), build, packageConfig,
        optionalArtefacts = false, Set(), compilerDeps0)

    Idea.build(projectPath, outputPath, build, platformResolution,
      compilerResolution, false)
  }
}
