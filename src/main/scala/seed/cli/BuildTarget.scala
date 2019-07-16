package seed.cli

import java.nio.file.Path

import seed.Log
import seed.cli.util.Ansi
import seed.config.BuildConfig
import seed.generation.util.PathUtil
import seed.model
import seed.process.ProcessHelper

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

object BuildTarget {
  def buildTargets(build: model.Build,
                   modules: List[util.Target.Parsed],
                   projectPath: Path,
                   moduleProjectPaths: Map[String, Path],
                   watch: Boolean,
                   tmpfs: Boolean,
                   log: Log
                  ): List[Future[Unit]] = {
    def format(module: String, target: String): String = module + ":" + target
    def formatAll(targets: List[(String, String)]): String =
      targets.map { case (m, t) => Ansi.italic(format(m, t)) }.mkString(", ")

    val targets = modules.flatMap {
      case util.Target.Parsed(m, Some(Right(t))) => List(m.name -> t.name)
      case _ => List()
    }.distinct

    val inheritedTargets = modules.flatMap {
      case util.Target.Parsed(module, Some(Left(platform))) =>
        BuildConfig.collectModuleDeps(build, module.module, platform)
      case util.Target.Parsed(_, Some(Right(_))) => List()
      case util.Target.Parsed(module, None) =>
        module.name +: BuildConfig.collectModuleDeps(build, module.module)
    }.flatMap(m => build.module(m).target.keys.toList.map(m -> _)).distinct

    if (targets.nonEmpty)
      log.debug(s"User-specified targets: ${formatAll(targets)}")

    if (inheritedTargets.nonEmpty)
      log.debug(s"Inherited targets: ${formatAll(inheritedTargets)}")

    val allTargets = (targets ++ inheritedTargets).distinct

    val buildPath = PathUtil.buildPath(projectPath, tmpfs, log)

    log.info(s"Build path: $buildPath")

    allTargets.map { case (m, t) =>
      val customLog = log.prefix(Ansi.bold(s"[${format(m, t)}]: "))

      val modulePath = moduleProjectPaths(m)
      val target     = build.module(m).target(t)

      target.`class` match {
        case Some(c) =>
          val bloopName = BuildConfig.targetName(build, c.module.module,
            c.module.platform)
          val args = List("run", bloopName, "-m", c.main)
          val process = ProcessHelper.runBloop(projectPath, customLog,
            customLog.info, Some(modulePath.toAbsolutePath.toString),
            Some(buildPath.toAbsolutePath.toString)
          )(args: _*)

          if (target.await) {
            customLog.info("Awaiting process termination...")
            Await.result(process.success, Duration.Inf)
            Future.unit
          } else {
            process.success
          }

        case None =>
          if (watch && target.watchCommand.isDefined)
            target.watchCommand match {
              case None      => Future.unit
              case Some(cmd) =>
                val process = ProcessHelper.runShell(modulePath, cmd,
                  buildPath.toAbsolutePath.toString, customLog, customLog.info)
                process.success
            }
          else
            target.command match {
              case None => Future.unit
              case Some(cmd) =>
                val process =
                  ProcessHelper.runShell(modulePath, cmd,
                    buildPath.toAbsolutePath.toString, customLog,
                    customLog.info)

                if (target.await) {
                  customLog.info("Awaiting process termination...")
                  Await.result(process.success, Duration.Inf)
                  Future.unit
                } else {
                  process.success
                }
            }
      }
    }
  }
}
