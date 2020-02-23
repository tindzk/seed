package seed.cli.util

import seed.config.BuildConfig.Build
import seed.model.Build.Module
import seed.model.{Build, Platform}

object Target {
  case class ModuleRef(name: String, module: Module)
  case class TargetRef(name: String, target: Build.Target)

  case class Parsed(
    module: ModuleRef,
    target: Option[Either[Platform, TargetRef]]
  )

  def parseModuleString(build: Build)(module: String): Either[String, Parsed] =
    if (module.isEmpty) Left("Module name cannot be empty")
    else {
      def invalidName(name: String) =
        Left(s"Invalid module name: ${Ansi
          .italic(name)}. Valid names: ${build.keys.mkString(", ")}")

      if (!module.contains(":")) {
        if (!build.contains(module)) invalidName(module)
        else Right(Parsed(ModuleRef(module, build(module).module), None))
      } else {
        val parts = module.split(":")
        if (parts.length != 2) {
          Left(s"Expected syntax: ${Ansi.italic("<name>:<target>")}")
        } else {
          val (name, target) = (parts(0), parts(1))

          if (!build.contains(name)) invalidName(name)
          else {
            build(name).module.targets
              .find(_.id == target)
              .map(Left(_))
              .orElse(
                build(name).module.target
                  .get(target)
                  .map(tgt => Right(TargetRef(target, tgt)))
              ) match {
              case None =>
                Left(s"Invalid build target ${Ansi
                  .italic(target)} provided on module ${Ansi.italic(name)}")
              case Some(tgt) =>
                Right(Parsed(ModuleRef(name, build(name).module), Some(tgt)))
            }
          }
        }
      }
    }
}
