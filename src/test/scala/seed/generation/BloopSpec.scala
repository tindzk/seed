package seed.generation

import java.nio.file.{Files, Path}

import bloop.config.ConfigCodecs
import minitest.SimpleTestSuite
import org.apache.commons.io.FileUtils
import seed.generation.util.BloopUtil
import seed.generation.util.BuildUtil.tempPath

object BloopSpec extends SimpleTestSuite {
  test("Inherit javaDeps in child modules") {
    val projectPath = tempPath.resolve("inherit-javadeps")
    Files.createDirectory(projectPath)

    val bloopPath = projectPath.resolve(".bloop")
    val build     = util.ProjectGeneration.generateJavaDepBloopProject(projectPath)

    assertEquals(build("example").module.jvm.get.moduleDeps, List("base"))

    val base = BloopUtil.readJson(bloopPath.resolve("base.json"))
    assert(
      base.project.classpath
        .exists(_.toString.contains("/org/postgresql/postgresql/"))
    )

    val example = BloopUtil.readJson(bloopPath.resolve("example.json"))
    assert(
      example.project.classpath
        .exists(_.toString.contains("/org/postgresql/postgresql/"))
    )

    val exampleTest = BloopUtil.readJson(bloopPath.resolve("example-test.json"))
    assert(
      exampleTest.project.classpath
        .exists(_.toString.contains("/org/postgresql/postgresql/"))
    )
  }
}
