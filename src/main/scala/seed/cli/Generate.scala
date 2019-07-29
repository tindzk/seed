package seed.cli

import java.nio.file.Path

import seed.{Log, model}
import seed.Cli.Command
import seed.generation.{Bloop, Idea}
import seed.artefact.ArtefactResolution

object Generate {
  def ui(
    seedConfig: model.Config,
    projectPath: Path,
    outputPath: Path,
    build: model.Build,
    command: Command.Generate,
    log: Log
  ): Unit = {
    val compilerDeps = ArtefactResolution.allCompilerDeps(build)
    val platformDeps = ArtefactResolution.allPlatformDeps(build)
    val libraryDeps  = ArtefactResolution.allLibraryDeps(build)

    val (isBloop, isIdea) = command match {
      case _: Command.Bloop => (true, false)
      case _: Command.Idea  => (false, true)
      case _: Command.All   => (true, true)
    }

    val optionalArtefacts = isIdea || seedConfig.resolution.optionalArtefacts

    val (_, platformResolution, compilerResolution) =
      ArtefactResolution.resolution(
        seedConfig,
        build,
        command.packageConfig,
        optionalArtefacts,
        platformDeps ++ libraryDeps,
        compilerDeps,
        log
      )

    val tmpfs = command.packageConfig.tmpfs || seedConfig.build.tmpfs
    if (isBloop)
      Bloop.build(
        projectPath,
        outputPath,
        build,
        platformResolution,
        compilerResolution,
        tmpfs,
        optionalArtefacts,
        log
      )
    if (isIdea)
      Idea.build(
        projectPath,
        outputPath,
        build,
        platformResolution,
        compilerResolution,
        tmpfs,
        log
      )
  }
}
