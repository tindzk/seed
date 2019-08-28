package seed.cli.util

import java.nio.file.Files

import minitest.SimpleTestSuite
import org.apache.commons.io.FileUtils
import seed.generation.util.BuildUtil
import zio.IO

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global

object WatcherSpec extends SimpleTestSuite {
  testAsync("Detect new file in root path") {
    val rootPath = BuildUtil.tempPath.resolve("watcher")
    Files.createDirectories(rootPath)

    val collected = mutable.ListBuffer[Unit]()
    var stop      = false

    val watcher = Watcher
      .watchPaths(
        List(rootPath),
        () => {
          // Only consider Scala/Java source files
          FileUtils.write(rootPath.resolve("test.html").toFile, "test", "UTF-8")
          stop = true
          FileUtils
            .write(rootPath.resolve("test.scala").toFile, "test", "UTF-8")
        }
      )
      .foreachWhile { v =>
        IO.effectTotal {
          collected += v
          !stop
        }
      }

    RTS.unsafeRunToFuture(watcher).map(_ => assertEquals(collected, List(())))
  }

  testAsync("Detect new file in sub-directory") {
    val rootPath         = BuildUtil.tempPath.resolve("watcher2")
    val subDirectoryPath = rootPath.resolve("sub")
    Files.createDirectories(subDirectoryPath)

    val collected = mutable.ListBuffer[Unit]()
    var stop      = false

    val watcher = Watcher
      .watchPaths(List(rootPath), { () =>
        stop = true
        FileUtils.write(rootPath.resolve("test.scala").toFile, "test", "UTF-8")
      })
      .foreachWhile { v =>
        IO.effectTotal {
          collected += v
          !stop
        }
      }

    RTS.unsafeRunToFuture(watcher).map(_ => assertEquals(collected, List(())))
  }

  testAsync("Watch file path") {
    val rootPath = BuildUtil.tempPath.resolve("watcher3")
    Files.createDirectories(rootPath)
    val filePath = rootPath.resolve("test.scala")
    FileUtils.write(filePath.toFile, "test", "UTF-8")

    val collected = mutable.ListBuffer[Unit]()
    var stop      = false

    val watcher = Watcher
      .watchPaths(List(filePath), { () =>
        stop = true
        FileUtils.write(filePath.toFile, "test2", "UTF-8")
      })
      .foreachWhile { v =>
        IO.effectTotal {
          collected += v
          !stop
        }
      }

    RTS.unsafeRunToFuture(watcher).map(_ => assertEquals(collected, List(())))
  }
}
