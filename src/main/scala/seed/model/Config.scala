package seed.model

import java.nio.file.Path

import seed.artefact.Coursier

case class Config(build: Config.Build = Config.Build(),
                  resolution: Config.Resolution = Config.Resolution())

object Config {
  case class Build(tmpfs: Boolean = false)
  case class Resolution(silent: Boolean = false,
                        ivyPath: Path = Coursier.DefaultIvyPath,
                        cachePath: Path = Coursier.DefaultCachePath)
}
