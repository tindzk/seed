package seed.build

import java.net.Socket
import java.util.concurrent.{
  CompletableFuture,
  Executor,
  Executors,
  RejectedExecutionException,
  TimeUnit
}

import com.google.gson.JsonObject
import org.eclipse.lsp4j.jsonrpc.Launcher
import seed.cli.util.{
  Ansi,
  BloopCli,
  ColourScheme,
  ConsoleOutput,
  ProgressBar,
  Watcher
}
import ch.epfl.scala.bsp4j._
import com.google.gson.{Gson, JsonElement}

import scala.collection.{JavaConverters, mutable}
import java.nio.file.{Files, Path}

import org.newsclub.net.unix.{AFUNIXSocket, AFUNIXSocketAddress}
import seed.Log
import seed.config.BuildConfig
import seed.config.BuildConfig.Build
import seed.generation.util.PathUtil
import seed.process.ProcessHelper
import seed.model.{BuildEvent, Platform}
import zio._
import zio.clock.Clock
import zio.stream._
import zio.duration._

import scala.concurrent.CancellationException

class BloopClient(
  consoleOutput: ConsoleOutput,
  progress: Boolean,
  projectPath: Path,
  build: Build,
  allModules: List[(String, Platform)],
  onBuildEvent: BuildEvent => Unit
) extends BuildClient {
  import consoleOutput.log

  private val gson: Gson = new Gson()

  // Cannot use ListMap here since updating it changes the order of the elements
  private val modules = mutable.ListBuffer[(String, ProgressBar.Line)]()
  reset()

  def reset(): Unit = {
    modules.clear()
    modules ++= allModules.map {
      case (m, p) =>
        BuildConfig.targetName(build, m, p) -> ProgressBar
          .Line(
            0,
            ProgressBar.Result.Waiting,
            m + " (" + p.caption + ")",
            0,
            0
          )
    }

    lastDiagnosticFilePath = ""
  }

  /** Modules compiled, the upcoming notifications are related to running */
  def compiled: Boolean = consoleOutput.isFlushed

  def printPb(): Unit =
    if (!compiled && progress)
      consoleOutput.write(ProgressBar.printAll(modules), sticky = true)

  def updatePb(): Unit = {
    modules.zipWithIndex.foreach {
      case ((id, line), i) =>
        modules.update(i, id -> line.copy(tick = line.tick + 1))
    }

    printPb()
  }

  override def onBuildShowMessage(params: ShowMessageParams): Unit = {
    require(!params.getMessage.endsWith("\n"))
    log.infoRetainColour("[build] " + params.getMessage)
  }

  override def onBuildLogMessage(params: LogMessageParams): Unit =
    // Compilation failures of modules is already indicated in the progress bar
    if (!params.getMessage.startsWith("Failed to compile ") &&
        !params.getMessage.startsWith("Deduplicating compilation of ")) {
      require(!params.getMessage.endsWith("\n"))
      log.infoRetainColour(params.getMessage)
      printPb()
    }

  import scala.collection.JavaConverters._

  private var lastDiagnosticFilePath = ""

  override def onBuildPublishDiagnostics(
    params: PublishDiagnosticsParams
  ): Unit = {
    val uri     = params.getBuildTarget.getUri
    val bloopId = uri.split('\u003d').last
    val parsed  = BloopCli.parseBloopModule(build, bloopId)
    val id      = Ansi.italic(parsed._1)

    val absolutePath =
      params.getTextDocument.getUri.stripPrefix("file://")

    val filePath =
      Ansi.bold(
        if (absolutePath.startsWith(projectPath.toString))
          absolutePath.drop(projectPath.toAbsolutePath.toString.length + 1)
        else
          absolutePath
      )

    params.getDiagnostics.asScala.foreach { diag =>
      val lines = diag.getMessage.linesIterator.toList
      val lineInfo =
        s"[${diag.getRange.getStart.getLine + 1}:${diag.getRange.getStart.getCharacter + 1}]: "
      val message = lineInfo + lines.head

      if (diag.getSeverity == DiagnosticSeverity.ERROR) {
        if (filePath != lastDiagnosticFilePath) log.error(filePath + s" ($id)")
        log.error(message, detail = true)
        lines.tail.foreach(
          l => log.error((" " * lineInfo.length) + l, detail = true)
        )
      } else if (diag.getSeverity == DiagnosticSeverity.INFORMATION || diag.getSeverity == DiagnosticSeverity.HINT) {
        if (filePath != lastDiagnosticFilePath) log.info(filePath + s" ($id)")
        log.info(message, detail = true)
        lines.tail.foreach(
          l => log.info((" " * lineInfo.length) + l, detail = true)
        )
      } else if (diag.getSeverity == DiagnosticSeverity.WARNING) {
        if (filePath != lastDiagnosticFilePath) log.warn(filePath + s" ($id)")
        log.warn(message, detail = true)
        lines.tail.foreach(
          l => log.warn((" " * lineInfo.length) + l, detail = true)
        )
      }
    }

    lastDiagnosticFilePath = filePath
    printPb()
  }

  override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = ()

  override def onBuildTaskStart(params: TaskStartParams): Unit =
    if (!compiled) {
      val uri = params.getData
        .asInstanceOf[JsonObject]
        .get("target")
        .asInstanceOf[JsonObject]
        .get("uri")
        .getAsString
      val bloopId = uri.split('\u003d').last
      val parsed  = BloopCli.parseBloopModule(build, bloopId)
      onBuildEvent(BuildEvent.Compiling(parsed._1, parsed._2))
    }

  override def onBuildTaskProgress(params: TaskProgressParams): Unit = {
    val uri = params.getData
      .asInstanceOf[JsonObject]
      .get("target")
      .asInstanceOf[JsonObject]
      .get("uri")
      .getAsString
    val bloopId = uri.split('\u003d').last

    val index = modules.indexWhere(_._1 == bloopId)
    if (index != -1) {
      modules.update(
        index,
        bloopId -> modules(index)._2.copy(
          result = ProgressBar.Result.InProgress,
          step = params.getProgress.toInt,
          total = params.getTotal.toInt
        )
      )

      printPb()
    }
  }

  override def onBuildTaskFinish(params: TaskFinishParams): Unit =
    params.getDataKind match {
      case TaskDataKind.COMPILE_REPORT =>
        val json   = params.getData.asInstanceOf[JsonElement]
        val report = gson.fromJson[CompileReport](json, classOf[CompileReport])

        val uri     = report.getTarget.getUri
        val bloopId = uri.split('\u003d').last
        val parsed  = BloopCli.parseBloopModule(build, bloopId)

        val r =
          if (report.getErrors == 0) {
            if (report.getWarnings > 0)
              ProgressBar.Result.Warnings
            else
              ProgressBar.Result.Success
          } else ProgressBar.Result.Failure

        val index = modules.indexWhere(_._1 == bloopId)
        if (index != -1) {
          if (report.getErrors == 0) {
            if (!compiled)
              onBuildEvent(BuildEvent.Compiled(parsed._1, parsed._2))
            modules.update(
              index,
              bloopId -> modules(index)._2
                .copy(result = r, step = 100, total = 100)
            )

            if (progress) printPb()
            else log.info("Module " + Ansi.italic(parsed._1) + " compiled")
          } else {
            if (!compiled) onBuildEvent(BuildEvent.Failed(parsed._1, parsed._2))
            modules.update(index, bloopId -> modules(index)._2.copy(result = r))

            if (progress) printPb()
            else
              log.error(
                "Module " + Ansi.italic(parsed._1) + " could not be compiled"
              )
          }
        }

      case TaskDataKind.TEST_REPORT =>
        val json   = params.getData.asInstanceOf[JsonElement]
        val report = gson.fromJson[TestReport](json, classOf[TestReport])
      // TODO implement

      case _ =>
    }
}

trait BloopServer extends BuildServer with ScalaBuildServer

class BspProcess(socketPath: Path, val fiber: Fiber[Nothing, Unit]) {
  def await(): IO[Nothing, Unit] = fiber.join

  // Bloop does not remove the socket file when the server stops
  def deleteSocketFile(): UIO[Unit] = UIO(Files.deleteIfExists(socketPath))
}

object Bsp {
  def connect(
    client: BloopClient,
    socket: Socket,
    projectPath: Path
  ): BloopServer = {
    val es = Executors.newCachedThreadPool()

    val launcher = new Launcher.Builder[BloopServer]()
      .setRemoteInterface(classOf[BloopServer])
      .setExecutorService(es)
      .setInput(socket.getInputStream)
      .setOutput(socket.getOutputStream)
      .setLocalService(client)
      .create()

    launcher.startListening()

    val server = launcher.getRemoteProxy
    client.onConnectWithServer(server)

    val bspVersion = "2.0.0-M4"

    import seed.BuildInfo

    val initialiseParams =
      new InitializeBuildParams(
        "bloop",
        BuildInfo.Bloop,
        bspVersion,
        projectPath.toUri.toString,
        new BuildClientCapabilities(java.util.Arrays.asList("scala"))
      )

    server.buildInitialize(initialiseParams).get()
    server.onBuildInitialized()
    server
  }

  def shutdown(
    bspProcess: BspProcess,
    socket: Socket,
    server: BloopServer
  ): UIO[Unit] =
    for {
      _ <- fromCompletableFuture(server.buildShutdown()).ignore
      _ <- UIO(socket.close())
      _ <- bspProcess.await()
      _ <- bspProcess.deleteSocketFile()
    } yield ()

  def scalacOptions(
    server: BloopServer,
    build: Build,
    projectPath: Path,
    allModules: List[(String, Platform)]
  ): Task[ScalacOptionsResult] = {
    val params =
      new ScalacOptionsParams(
        JavaConverters
          .seqAsJavaListConverter(allModules.map {
            case (m, p) =>
              val id = BuildConfig.targetName(build, m, p)
              new BuildTargetIdentifier(
                s"file://${projectPath.toAbsolutePath}/?id=$id"
              )
          })
          .asJava
      )

    fromCompletableFuture(server.buildTargetScalacOptions(params))
  }

  def classDirectories(
    server: BloopServer,
    build: Build,
    projectPath: Path,
    allModules: List[(String, Platform)]
  ): Task[Map[(String, Platform), String]] = {
    val bloopModuleMap = allModules.map {
      case (m, p) =>
        val id = BuildConfig.targetName(build, m, p)
        s"file://${projectPath.toAbsolutePath}/?id=$id" -> (m, p)
    }.toMap

    scalacOptions(server, build, projectPath, allModules).map { result =>
      import JavaConverters._
      result.getItems.asScala.map { item =>
        bloopModuleMap(item.getTarget.getUri) -> item.getClassDirectory
          .stripPrefix("file://")
      }.toMap
    }
  }

  def buildModules(
    server: BloopServer,
    build: Build,
    projectPath: Path,
    allModules: List[(String, Platform)]
  ): Task[CompileResult] = {
    val compileParams =
      new CompileParams(
        JavaConverters
          .seqAsJavaListConverter(allModules.map {
            case (m, p) =>
              val id = BuildConfig.targetName(build, m, p)
              new BuildTargetIdentifier(
                s"file://${projectPath.toAbsolutePath}/?id=$id"
              )
          })
          .asJava
      )

    fromCompletableFuture(server.buildTargetCompile(compileParams))
  }

  def runModule(
    client: BloopClient,
    server: BloopServer,
    build: Build,
    projectPath: Path,
    consoleOutput: ConsoleOutput,
    module: String,
    platform: Platform,
    progress: Boolean
  ): UIO[Unit] =
    for {
      _ <- compile(
        client,
        server,
        consoleOutput,
        progress,
        build,
        projectPath,
        List(module -> platform)
      )
      _ <- {
        val id = BuildConfig.targetName(build, module, platform)
        val target = new BuildTargetIdentifier(
          s"file://${projectPath.toAbsolutePath}/?id=$id"
        )
        fromCompletableFuture(server.buildTargetRun(new RunParams(target))).option
          .map(_.map(_.getStatusCode))
      }
    } yield ()

  def fromCompletableFuture[T](future: => CompletableFuture[T]): Task[T] =
    Task.descriptorWith(
      d =>
        ZIO
          .effect(future)
          .flatMap(
            f =>
              Task
                .effectAsync { (cb: Task[T] => Unit) =>
                  f.whenCompleteAsync(
                    (v: T, e: Throwable) =>
                      if (e == null) cb(Task.succeed(v))
                      else {
                        if (!e.isInstanceOf[CancellationException]) {
                          e.printStackTrace()
                          cb(Task.fail(e))
                        }
                      },
                    new Executor {
                      override def execute(r: Runnable): Unit =
                        if (!d.executor.submit(r))
                          throw new RejectedExecutionException(
                            "Rejected: " + r.toString
                          )
                    }
                  )
                }
                .onTermination { _ =>
                  if (!f.isDone) f.cancel(true)
                  UIO(())
                }
          )
    )

  def runBspServer(
    projectPath: Path,
    log: Log,
    onStdOut: String => Unit
  ): (Path, UIO[Unit]) = {
    val socketPath = PathUtil.TemporaryFolder.resolve(
      "seed-bsp-" + System.currentTimeMillis() + ".socket"
    )
    val process = ProcessHelper.runBloop(projectPath, log, onStdOut)(
      "bsp",
      "--socket",
      socketPath.toString
    )
    (socketPath, process)
  }

  def establishBspConnection(
    log: Log,
    socketPath: Path
  ): ZIO[Any with Clock, Throwable, AFUNIXSocket] = {
    val socket = AFUNIXSocket.newInstance()
    IO {
      log.debug("Connecting to BSP...")
      socket.connect(new AFUNIXSocketAddress(socketPath.toFile))
      socket
    }.retry(Schedule.exponential(Duration(50, TimeUnit.MILLISECONDS)))
  }

  def runBspServerAndConnect(
    client: BloopClient,
    projectPath: Path,
    log: Log
  ): ZIO[Any, Nothing, (BspProcess, Socket, BloopServer)] = {
    val (bspSocketPath, bspProcess) = runBspServer(
      projectPath,
      new Log(log.f, log.map, log.level, log.unicode) {
        override def error(message: String, detail: Boolean = false): Unit =
          // This message is printed to stderr, but it is not an error, therefore
          // change log level to 'debug'
          if (message.contains("BSP server cancelled, closing socket..."))
            // Remove "[E] " from message
            debug(message.dropWhile(_ != ' ').tail)
          else
            super.error(message, detail)
      },
      message => log.debug(Ansi.bold("[BSP] ") + message)
    )

    var connectionFiber: Option[Fiber[Throwable, AFUNIXSocket]] = None

    // Interrupt connection fiber if `bloop bsp` failed (i.e. the Bloop server
    // was not started)
    def interrupt: ZIO[Any, Nothing, Any] =
      connectionFiber.map(_.interrupt).getOrElse(UIO.unit)

    val result = for {
      process    <- bspProcess.onTermination(_ => interrupt).fork
      connection <- establishBspConnection(log, bspSocketPath).fork
      _ = connectionFiber = Some(connection)
      socket <- connection.join
    } yield (
      new BspProcess(bspSocketPath, process),
      socket,
      Bsp.connect(client, socket, projectPath)
    )

    val runtime = new DefaultRuntime {}
    result
      .provide(runtime.Environment)
      .either
      .map(_.right.get)
  }

  def interruptIfParentFails[T](
    parent: IO[Nothing, Unit],
    child: UIO[T]
  ): ZIO[Any, Nothing, T] =
    for {
      f <- child.fork
      _ <- parent.onInterrupt(f.interrupt)
      r <- f.join
    } yield r

  private def progressBarUpdater(
    client: BloopClient,
    consoleOutput: ConsoleOutput
  ) = {
    val effect    = RIO.effect(client.updatePb())
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
    client: BloopClient,
    consoleOutput: ConsoleOutput,
    zio: ZIO[Any, Nothing, Option[StatusCode]]
  ): ZIO[Any, Nothing, Unit] =
    UIO(client.printPb()).flatMap(
      _ =>
        progressBarUpdater(client, consoleOutput).flatMap(
          pb =>
            zio.flatMap(
              result =>
                for {
                  _ <- pb.interrupt.map(_ => ())
                  _ <- UIO(consoleOutput.flushSticky())
                  _ <- if (result.exists(_ != StatusCode.OK)) IO.interrupt
                  else IO.unit
                } yield ()
            )
        )
    )

  def compile(
    client: BloopClient,
    server: BloopServer,
    consoleOutput: ConsoleOutput,
    progress: Boolean,
    build: Build,
    projectPath: Path,
    bloopModules: List[(String, Platform)]
  ): UIO[Unit] = {
    consoleOutput.log.info(
      s"Compiling ${Ansi.bold(bloopModules.length.toString)} modules..."
    )

    val b = Bsp
      .buildModules(server, build, projectPath, bloopModules)
      .option
      .map(_.map(_.getStatusCode))

    if (progress) withProgressBar(client, consoleOutput, b)
    else
      b.flatMap(
        result =>
          if (result.exists(_ != StatusCode.OK)) IO.interrupt else IO.unit
      )
  }

  def watchAction(
    build: Build,
    client: BloopClient,
    consoleOutput: ConsoleOutput,
    resolvedModules: List[(String, Platform)],
    action: UIO[Unit],
    wait: Boolean,
    runInitially: Boolean
  ): UIO[Unit] = {
    import consoleOutput.log

    val allSourcePaths = BuildConfig.sourcePaths(build, resolvedModules)

    // Otherwise, the watcher will throw an exception
    val sourcePaths = allSourcePaths.filter(Files.exists(_))

    val runtime = new DefaultRuntime {}

    var current: Option[Fiber[Nothing, Unit]] = None

    for {
      _ <- UIO {
        log.info(
          s"Watching ${Ansi.bold(sourcePaths.length.toString)} source paths..."
        )
        sourcePaths.foreach(path => log.debug(path.toString))
      }
      r <- if (runInitially) action.ignore.fork.map(Some(_)) else UIO(None)
      _ = current = r
      _ <- Watcher
        .watchPaths(sourcePaths)
        .throttleEnforce(1, 3.seconds)(_ => 1)
        .aggregate(Sink.drain)
        .foreach { _ =>
          for {
            _ <- current match {
              case None    => UIO(())
              case Some(c) => if (wait) c.join else c.interrupt.map(_ => ())
            }

            // Reset must happen after Fiber has been interrupted, otherwise all
            // progress bars will be reset to 0.
            _ <- UIO(client.reset())
            _ <- UIO(consoleOutput.reset())

            r <- action.ignore.fork
            _ <- {
              current = Some(r)
              UIO(())
            }
          } yield r
        }
        .ignore
        .provide(runtime.Environment)
    } yield ()
  }
}
