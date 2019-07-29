package seed.generation.util

import java.nio.file.Paths

import minitest.SimpleTestSuite
import seed.Log

object PathUtilSpec extends SimpleTestSuite {
  test("Default build paths") {
    val projectPath = Paths.get("test/compiler-options")

    assertEquals(
      PathUtil.buildPath(projectPath, tmpfs = false, Log.urgent),
      Paths.get("test/compiler-options/build")
    )
    assertEquals(
      PathUtil.buildPath(projectPath, tmpfs = true, Log.urgent),
      Paths.get("/tmp/build-compiler-options")
    )
  }

  test("Build file path") {
    assertEquals(
      PathUtil.buildFilePath(Paths.get("/tmp")),
      Paths.get("/tmp", "build.toml")
    )
    assertEquals(
      PathUtil.buildFilePath(Paths.get("/tmp", "build.toml")),
      Paths.get("/tmp", "build.toml")
    )
    assertEquals(
      PathUtil.buildFilePath(Paths.get("/tmp", "test.toml")),
      Paths.get("/tmp", "test.toml")
    )
  }
}
