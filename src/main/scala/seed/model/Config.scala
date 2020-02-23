package seed.model

import java.nio.file.Path

import seed.LogLevel
import seed.artefact.Coursier

case class Config(
  cli: Config.Cli = Config.Cli(),
  build: Config.Build = Config.Build(),
  resolution: Config.Resolution = Config.Resolution(),
  repository: Config.Repository = Config.Repository()
)

object Config {
  case class Cli(
    level: LogLevel = LogLevel.Debug,
    unicode: Boolean = true,
    progress: Boolean = true
  )
  case class Build(tmpfs: Boolean = false)
  case class Resolution(
    silent: Boolean = false,
    ivyPath: Path = Coursier.DefaultIvyPath,
    cachePath: Path = Coursier.DefaultCachePath,
    optionalArtefacts: Boolean = false
  )
  case class Repository(bintray: BintrayRepository = BintrayRepository())
  case class BintrayRepository(user: String = "", apiKey: String = "")
}
