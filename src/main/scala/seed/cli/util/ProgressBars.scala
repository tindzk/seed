package seed.cli.util

import scala.collection.mutable
import seed.config.BuildConfig
import seed.config.BuildConfig.Build
import seed.model.Platform
import zio._
import zio.stream._
import zio.duration._

case class ProgressBarItem(id: String, caption: String)

class ProgressBars(
  consoleOutput: ConsoleOutput,
  items: List[ProgressBarItem]
) {
  // Cannot use ListMap here since updating it changes the order of the elements
  private val itemsProgress = mutable.ListBuffer[(String, ProgressBar.Line)]()
  reset()

  def reset(): Unit = {
    itemsProgress.clear()
    itemsProgress ++= items.map {
      case ProgressBarItem(id, caption) =>
        id -> ProgressBar.Line(0, ProgressBar.Result.Waiting, caption, 0, 0)
    }
  }

  /** If modules were compiled, the upcoming notifications are related to the
    * next build stage (e.g. running the program)
    */
  def compiled: Boolean = consoleOutput.isFlushed

  def printPb(): Unit =
    if (!compiled)
      consoleOutput.writeSticky(ProgressBar.printAll(itemsProgress))

  def updatePb(): Unit = {
    itemsProgress.zipWithIndex.foreach {
      case ((id, line), i) =>
        itemsProgress.update(i, id -> line.copy(tick = line.tick + 1))
    }

    printPb()
  }

  def update(id: String, f: ProgressBar.Line => ProgressBar.Line): Unit = {
    val index = itemsProgress.indexWhere(_._1 == id)
    if (index != -1) {
      itemsProgress.update(index, id -> f(itemsProgress(index)._2))
      printPb()
    }
  }
}

object ProgressBars {
  private def progressBarUpdater(
    progressBars: ProgressBars,
    consoleOutput: ConsoleOutput
  ) = {
    val effect    = RIO.effect(progressBars.updatePb())
    val scheduler = Schedule.spaced(150.millis)
    val runtime   = new DefaultRuntime {}

    Stream
      .fromEffect(effect)
      .repeat(scheduler)
      .provide(runtime.Environment)
      .runDrain
      .fork
  }

  def withProgressBar(
    progressBars: ProgressBars,
    consoleOutput: ConsoleOutput,
    zio: ZIO[Any, Nothing, Boolean]
  ): ZIO[Any, Nothing, Unit] =
    UIO(progressBars.printPb()).flatMap(
      _ =>
        progressBarUpdater(progressBars, consoleOutput).flatMap(
          pb =>
            zio.flatMap(
              result =>
                for {
                  _ <- pb.interrupt.map(_ => ())
                  _ <- UIO(consoleOutput.flushSticky())
                  _ <- if (result) IO.unit else IO.interrupt
                } yield ()
            )
        )
    )
}
