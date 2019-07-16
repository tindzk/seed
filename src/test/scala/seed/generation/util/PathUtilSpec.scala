package seed.generation.util

import java.nio.file.Paths

import minitest.SimpleTestSuite
import seed.Log

object PathUtilSpec extends SimpleTestSuite {
  test("Default build paths") {
    val projectPath = Paths.get("test/compiler-options")

    assertEquals(
      PathUtil.buildPath(projectPath, tmpfs = false, Log.urgent),
      Paths.get("test/compiler-options/build"))
    assertEquals(
      PathUtil.buildPath(projectPath, tmpfs = true, Log.urgent),
      Paths.get("/tmp/build-compiler-options"))
  }
}
