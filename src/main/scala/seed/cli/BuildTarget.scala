package seed.cli

import java.nio.file.{Files, Path}

import seed.Log
import seed.cli.util.Ansi
import seed.config.BuildConfig
import seed.config.BuildConfig.Build
import seed.generation.util.PathUtil
import seed.process.ProcessHelper
import zio._

object BuildTarget {
  def buildTargets(
    build: Build,
    modules: List[util.Target.Parsed],
    projectPath: Path,
    watch: Boolean,
    tmpfs: Boolean,
    log: Log
  ): List[Either[UIO[Unit], UIO[Unit]]] = {
    def format(module: String, target: String): String = module + ":" + target
    def formatAll(targets: List[(String, String)]): String =
      targets.map { case (m, t) => Ansi.italic(format(m, t)) }.mkString(", ")

    val targets = modules.flatMap {
      case util.Target.Parsed(m, Some(Right(t))) => List(m.name -> t.name)
      case _                                     => List()
    }.distinct

    val flattenedDeps = modules
      .flatMap {
        case util.Target.Parsed(module, Some(Left(platform))) =>
          BuildConfig.collectModuleDepsBase(build, module.module, platform)
        case util.Target.Parsed(_, Some(Right(_))) => List()
        case util.Target.Parsed(module, None) =>
          module.name +: BuildConfig.collectModuleDeps(build, module.module)
      }

    // Determine direct parents of build targets
    type BuildTarget  = (String, String) // module, target name
    type ParentModule = String
    val parentModules: Map[BuildTarget, List[ParentModule]] =
      flattenedDeps
        .flatMap { parent =>
          BuildConfig
            .allTargets(build, parent)
            .flatMap {
              case (m, t) => BuildConfig.platformModule(build(m).module, t)
            }
            .flatMap(_.moduleDeps)
            .flatMap(
              module =>
                build(module).module.target.keys.toList
                  .map(target => (module, target) -> parent)
            )
        }
        .groupBy(_._1)
        .mapValues(_.map(_._2))

    val inheritedTargets: List[BuildTarget] = flattenedDeps
      .flatMap(
        module =>
          build(module).module.target.keys.toList
            .map(target => (module, target))
      )
      .distinct

    if (targets.nonEmpty)
      log.debug(s"User-specified targets: ${formatAll(targets)}")

    if (inheritedTargets.nonEmpty)
      log.debug(s"Inherited targets: ${formatAll(inheritedTargets)}")

    val allTargets = (targets ++ inheritedTargets).distinct

    val buildPath = PathUtil.buildPath(projectPath, tmpfs, log)
    if (!Files.exists(buildPath)) Files.createDirectories(buildPath)
    log.info(s"Build path: $buildPath")

    allTargets.flatMap {
      case (m, t) =>
        val customLog = log.prefix(Ansi.bold(s"[${format(m, t)}]: "))

        val modulePath = build(m).path
        val target     = build(m).module.target(t)

        val moduleSourcePaths =
          parentModules.getOrElse((m, t), List()).flatMap { module =>
            val targets = BuildConfig.allTargets(build, module)
            targets
              .flatMap {
                case (m, t) => BuildConfig.platformModule(build(m).module, t)
              }
              .flatMap(_.sources)
              .map(_.toAbsolutePath.toString)
          }

        target.`class` match {
          case Some(c) =>
            val bloopName =
              BuildConfig.targetName(build, c.module.module, c.module.platform)
            val args = List("run", bloopName, "-m", c.main)
            val process = ProcessHelper.runBloop(
              projectPath,
              customLog,
              customLog.info(_),
              Some(modulePath.toAbsolutePath.toString),
              moduleSourcePaths,
              Some(buildPath.toAbsolutePath.toString)
            )(args: _*)

            List(if (target.await) Left(process) else Right(process))

          case None =>
            if (watch && target.watchCommand.isDefined)
              target.watchCommand match {
                case None => List()
                case Some(cmd) =>
                  List(
                    Right(
                      ProcessHelper.runShell(
                        modulePath,
                        cmd,
                        moduleSourcePaths,
                        buildPath.toAbsolutePath.toString,
                        customLog,
                        customLog.info(_)
                      )
                    )
                  )
              } else
              target.command match {
                case None => List()
                case Some(cmd) =>
                  val process = ProcessHelper.runShell(
                    modulePath,
                    cmd,
                    moduleSourcePaths,
                    buildPath.toAbsolutePath.toString,
                    customLog,
                    customLog.info(_)
                  )

                  List(if (target.await) Left(process) else Right(process))
              }
        }
    }
  }
}
