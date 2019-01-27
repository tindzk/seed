package seed.config

import java.io.File

import minitest.SimpleTestSuite
import java.nio.file.Paths

import org.apache.commons.io.FileUtils

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
}
