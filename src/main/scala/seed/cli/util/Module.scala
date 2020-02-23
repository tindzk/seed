package seed.cli.util

import seed.model.Platform

object Module {
  def format(module: String, platform: Platform): String =
    module + " (" + platform.caption + ")"
}
