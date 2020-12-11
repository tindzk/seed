package seed.util

import seed.Cli.PackageConfig

object TestUtil {
  val packageConfig = PackageConfig(
    tmpfs = false,
    silent = true,
    ivyPath = None,
    cachePath = None
  )
}
