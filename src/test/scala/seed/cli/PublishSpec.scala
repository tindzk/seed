package seed.cli

import java.io.File
import java.nio.file.{Files, Path}

import minitest.SimpleTestSuite
import org.apache.commons.io.FileUtils
import seed.Log
import seed.generation.util.BuildUtil

import sys.process._

object PublishSpec extends SimpleTestSuite {
  def testVersionDetection(path: File): Unit = {
    Process("git init", path).!!

    FileUtils.write(new File(path, "test.txt"), "test", "UTF-8")
    Process("git add test.txt", path).!!
    Process("git commit . -m import", path).!!
    Process("git tag 0.1.0", path).!! // no 'v' prefix
    assertEquals(
      Publish.getVersion(path.toPath, None, Log.silent),
      Some("0.1.0")
    )

    FileUtils.write(new File(path, "test2.txt"), "test", "UTF-8")
    Process("git add test2.txt", path).!!
    Process("git commit . -m import", path).!!
    Process("git tag v0.1.1", path).!! // 'v' prefix
    assertEquals(
      Publish.getVersion(path.toPath, None, Log.silent),
      Some("0.1.1")
    )
  }

  test("Determine version number (relative path)") {
    val relativePath = new File("temp-git-version")
    if (Files.exists(relativePath.toPath))
      FileUtils.deleteDirectory(relativePath)
    Files.createDirectories(relativePath.toPath)
    testVersionDetection(relativePath)
    FileUtils.deleteDirectory(relativePath)
  }

  test("Determine version number (absolute path)") {
    val relativePath = BuildUtil.tempPath.resolve("git-version")
    if (Files.exists(relativePath))
      FileUtils.deleteDirectory(relativePath.toFile)
    Files.createDirectories(relativePath)
    testVersionDetection(relativePath.toFile)
  }
}
