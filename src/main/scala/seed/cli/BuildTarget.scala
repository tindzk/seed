package seed.cli

import java.nio.file.Path

import seed.Log
import seed.config.BuildConfig
import seed.generation.util.PathUtil
import seed.model
import seed.process.ProcessHelper

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global

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
      targets.map { case (m, t) => format(m, t) }.mkString(", ")

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
      val modulePath = moduleProjectPaths(m)
      val target     = build.module(m).target(t)

      target.`class` match {
        case Some(c) =>
          val bloopName = BuildConfig.targetName(build, c.module.module, c.module.platform)
          val args = List("run", bloopName, "-m", c.main)
          val process = ProcessHelper.runBloop(projectPath, log.info,
            Some(modulePath.toAbsolutePath.toString), Some(buildPath.toAbsolutePath.toString))(args: _*)

          if (target.await) {
            log.info(s"[${format(m, t)}]: Awaiting process termination...")
            Await.result(process.termination, Duration.Inf)
            Future.unit
          } else {
            process.termination.map(_ => ())
          }

        case None =>
          if (watch && target.watchCommand.isDefined) {
            target.watchCommand match {
              case None          => Future.unit
              case Some(cmd) =>
                val process = ProcessHelper.runShell(modulePath, cmd,
                  buildPath.toAbsolutePath.toString,
                  output => log.info(s"[${format(m, t)}]: " + output))
                process.termination.map(_ => ())
            }
          } else {
            target.command match {
              case None => Future.unit
              case Some(cmd) =>
                val process =
                  ProcessHelper.runShell(modulePath, cmd, buildPath.toAbsolutePath.toString,
                    output => log.info(s"[${format(m, t)}]: " + output))

                if (target.await) {
                  log.info(s"[${format(m, t)}]: Awaiting process termination...")
                  Await.result(process.termination, Duration.Inf)
                  Future.unit
                } else {
                  process.termination.map(_ => ())
                }
            }
        }
      }
    }
  }
}
