package seed.cli

import java.nio.file.Path

import seed.{Log, model}
import seed.Cli.Command
import seed.generation.{Bloop, Idea}
import seed.artefact.ArtefactResolution
import seed.config.BuildConfig

object Build {
  def ui(seedConfig: model.Config,
         projectPath: Path,
         build: model.Build,
         command: Command.Build
        ): Unit = {
    if (!build.module.toList.forall { case (name, module) =>
      BuildConfig.checkModule(build, name, module, Log)
    }) sys.exit(1)

    val compilerDeps = ArtefactResolution.allCompilerDeps(build)
    val platformDeps = ArtefactResolution.allPlatformDeps(build)
    val libraryDeps  = ArtefactResolution.allLibraryDeps(build)

    val (isBloop, isIdea) = command match {
      case _: Command.Bloop => (true, false)
      case _: Command.Idea => (false, true)
      case _: Command.All => (true, true)
    }

    val (_, platformResolution, compilerResolution) =
      ArtefactResolution.resolution(seedConfig, build, command.packageConfig,
        optionalArtefacts = isIdea, platformDeps ++ libraryDeps,
        compilerDeps)

    val tmpfs = command.packageConfig.tmpfs || seedConfig.build.tmpfs
    if (isBloop) Bloop.build(projectPath, build, platformResolution,
      compilerResolution, tmpfs)
    if (isIdea) Idea.build(projectPath, projectPath, build, platformResolution,
      compilerResolution, tmpfs)
  }
}
