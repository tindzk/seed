package seed.cli

import java.nio.file.Path

import seed.{Cli, Log, model}
import seed.Cli.Command
import seed.generation.{Bloop, Idea}
import seed.artefact.ArtefactResolution
import seed.config.BuildConfig.Build
import seed.model.Build.Resolvers

object Generate {
  def ui(
    seedConfig: model.Config,
    projectPath: Path,
    outputPath: Path,
    resolvers: Resolvers,
    build: Build,
    command: Command.Generate,
    log: Log
  ): Unit = {
    Cli.showResolvers(seedConfig, resolvers, command.packageConfig, log)

    val (isBloop, isIdea) = command match {
      case _: Command.Bloop => (true, false)
      case _: Command.Idea  => (false, true)
      case _: Command.All   => (true, true)
    }

    val optionalArtefacts = isIdea || seedConfig.resolution.optionalArtefacts

    val runtimeResolution = ArtefactResolution.runtimeResolution(
      build,
      seedConfig,
      resolvers,
      command.packageConfig,
      optionalArtefacts,
      log
    )
    val compilerResolution = ArtefactResolution.compilerResolution(
      build,
      seedConfig,
      resolvers,
      command.packageConfig,
      optionalArtefacts,
      log
    )

    val tmpfs = command.packageConfig.tmpfs || seedConfig.build.tmpfs
    if (isBloop)
      Bloop.build(
        projectPath,
        outputPath,
        build,
        runtimeResolution,
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
        runtimeResolution,
        compilerResolution,
        tmpfs,
        log
      )
  }
}
