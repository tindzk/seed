package seed.build

import java.nio.file.Path

import seed.Log
import seed.cli.util.BloopCli
import seed.model.{Build, Platform}
import seed.process.ProcessHelper

object Link {
  type Module = (String, Option[Platform])

  def link(build: Build,
           projectPath: Path,
           modules: List[Module],
           watch: Boolean,
           log: Log,
           onStdOut: String => Unit
          ): Option[ProcessHelper.Process] = {
    val moduleNames = modules.flatMap { case (name, platform) =>
      BloopCli.bloopModuleNames(build, name, platform, log)
    }

    if (moduleNames.isEmpty) None
    else {
      val args = "link" +: ((if (!watch) List() else List("--watch")) ++ moduleNames)
      Some(ProcessHelper.runBloop(projectPath, silent = true, onStdOut)(args: _*))
    }
  }
}
