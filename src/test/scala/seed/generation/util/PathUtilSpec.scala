package seed.generation.util

import java.nio.file.Paths

import minitest.SimpleTestSuite

object PathUtilSpec extends SimpleTestSuite {
  test("Default build paths") {
    val projectPath = Paths.get("test/compiler-options")

    assertEquals(
      PathUtil.buildPath(projectPath, tmpfs = false),
      Paths.get("test/compiler-options/build"))
    assertEquals(
      PathUtil.buildPath(projectPath, tmpfs = true),
      Paths.get("/tmp/build-compiler-options"))
  }
}
