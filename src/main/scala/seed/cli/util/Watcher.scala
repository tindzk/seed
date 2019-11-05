package seed.cli.util

import java.nio.file.{Files, Path, StandardWatchEventKinds, WatchEvent}

import org.slf4j.LoggerFactory
import zio._
import zio.stream._
import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.methvin.watcher.DirectoryChangeListener
import io.methvin.watcher.DirectoryWatcher
import io.methvin.watcher.hashing.FileHasher
import org.apache.commons.io.FilenameUtils
import org.slf4j.Logger

import scala.collection.JavaConverters
import scala.concurrent.ExecutionContext

object Watcher {
  val Extensions = Array("scala", "java")

  // System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE")

  def watchPaths(
    paths: List[Path],
    onStarted: () => Unit = () => ()
  ): Stream[Throwable, Unit] =
    Stream.effectAsyncM[Throwable, Unit] { e =>
      val logger = LoggerFactory.getLogger("watcher")
      val (p, f) = paths.partition(Files.isDirectory(_))
      val watcher = new CustomRecursiveFileMonitor(p, f, logger = logger) {
        override def onCreate(file: Path, count: Int): Unit =
          if (Extensions.contains(FilenameUtils.getExtension(file.toString)))
            e(Task.succeed(()))
        override def onModify(file: Path, count: Int): Unit =
          if (Extensions.contains(FilenameUtils.getExtension(file.toString)))
            e(Task.succeed(()))
        override def onDelete(file: Path, count: Int): Unit = {}
      }

      Task.descriptorWith { d =>
        val ec = d.executor.asEC
        UIO {
          watcher.start()(ec)
          onStarted()
        }.onTermination(_ => UIO(watcher.close()))
      }
    }
}

/**
  * Adapted from https://github.com/gmethvin/directory-watcher/
  *
  * Original class: io.methvin.better.files.RecursiveFileMonitor
  */
abstract class CustomRecursiveFileMonitor(
  val paths: List[Path],
  val files: List[Path],
  val fileHasher: Option[FileHasher] = Some(FileHasher.DEFAULT_FILE_HASHER),
  val logger: Logger
) {
  protected[this] val watcher: DirectoryWatcher = DirectoryWatcher.builder
    .paths(JavaConverters.seqAsJavaListConverter(paths).asJava)
    .files(JavaConverters.seqAsJavaListConverter(files).asJava)
    .listener(new DirectoryChangeListener {
      override def onEvent(event: DirectoryChangeEvent): Unit =
        event.eventType match {
          case EventType.OVERFLOW =>
          case et =>
            CustomRecursiveFileMonitor.this.onEvent(
              et.getWatchEventKind.asInstanceOf[WatchEvent.Kind[Path]],
              event.path,
              event.count
            )
        }
      override def onException(e: Exception): Unit = e.printStackTrace()
    })
    .fileHasher(fileHasher.orNull)
    .logger(logger)
    .build()

  def onEvent(eventType: WatchEvent.Kind[Path], file: Path, count: Int): Unit =
    eventType match {
      case StandardWatchEventKinds.ENTRY_CREATE => onCreate(file, count)
      case StandardWatchEventKinds.ENTRY_MODIFY => onModify(file, count)
      case StandardWatchEventKinds.ENTRY_DELETE => onDelete(file, count)
    }

  def start()(implicit executionContext: ExecutionContext): Unit =
    executionContext.execute(() => watcher.watch())

  def close(): Unit = watcher.close()

  def onCreate(file: Path, count: Int): Unit
  def onModify(file: Path, count: Int): Unit
  def onDelete(file: Path, count: Int): Unit
}
