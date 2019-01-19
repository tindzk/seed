package seed.config

import java.nio.file.{Files, Path, Paths}

import seed.cli.util.{Ansi, ColourScheme}
import seed.model.Build.{Dep, Module, Project}
import seed.model.Platform.{JVM, JavaScript, Native}
import seed.model.{Build, Platform}
import seed.Log
import seed.config.util.TomlUtils

object BuildConfig {
  def fixPath(projectPath: Path, path: Path): Path =
    if (path.toString.startsWith("/")) path
    else projectPath.resolve(path).normalize()

  def load(path: Path): (Path, Build) = {
    if (!Files.exists(path)) {
      Log.error(s"Invalid path to build file provided: ${Ansi.italic(path.toString)}")
      sys.exit(1)
    }

    val (projectPath, projectFile) =
      if (Files.isRegularFile(path)) (path.toAbsolutePath.getParent, path)
      else (path, path.resolve("build.toml"))

    Log.info(s"Loading project ${Ansi.italic(projectFile.toString)}...")

    if (!Files.exists(projectFile)) {
      Log.error(s"The file ${Ansi.italic(projectFile.toString)} could not be found")
      Log.error("You can create a new build file using:")
      Log.error(Ansi.foreground(ColourScheme.green2)("$ seed init"))
      sys.exit(1)
    }

    import toml._
    import toml.Codecs._
    import seed.config.util.TomlUtils.Codecs._

    implicit val pCodec = pathCodec(fixPath(projectPath, _))

    val parsed = TomlUtils.parseFile(projectFile, Toml.parseAs[Build](_), "build file") match { case p =>
      p.copy(module = p.module.mapValues { module =>
        val parentTargets = moduleTargets(module)
        module.copy(
          targets = parentTargets,
          test = module.test.map { module =>
            val testTargets = moduleTargets(module)
            module.copy(
              targets =
                if (testTargets.nonEmpty) testTargets
                else parentTargets)
          }
        )
      })
    }

    val imported = parsed.`import`.map { path =>
      val (_, imp) = load(projectPath.resolve(path).normalize())

      parsed.module.keySet.intersect(imp.module.keySet).foreach(name =>
        Log.error(s"Module name ${Ansi.italic(name)} is not unique"))

      imp
    }

    (projectPath.normalize(), parsed.copy(
      project = parsed.project.copy(testFrameworks =
        (parsed.project.testFrameworks ++
         imported.flatMap(_.project.testFrameworks)
        ).distinct
      ),
      module = parsed.module ++ imported.flatMap(_.module)))
  }

  def moduleTargets(module: Build.Module): List[Platform] = (
    module.targets ++
    (if (module.jvm.nonEmpty) List(JVM) else List()) ++
    (if (module.js.nonEmpty) List(JavaScript) else List()) ++
    (if (module.native.nonEmpty) List(Native) else List())
  ).distinct

  def platformModule(module: Build.Module,
                     platform: Platform
                    ): Option[Build.Module] =
    platform match {
      case JVM => module.jvm
      case JavaScript => module.js
      case Native => module.native
    }

  def buildTargets(build: Build): Set[Platform] =
    build.module.flatMap { case (_, module) => module.targets }.toSet

  def checkModule(build: Build, name: String, module: Build.Module): Unit = {
    def error(message: String): Unit = {
      Log.error(message)
      sys.exit(1)
    }

    val invalidModuleDeps =
      module.moduleDeps.filter(!build.module.isDefinedAt(_))

    val moduleName = Ansi.italic(name)

    if (module.targets.isEmpty)
      error(s"No target platforms were set on module $moduleName. Example: ${Ansi.italic("""targets = ["js"]""")}")
    else if (module.sources.isEmpty && module.js.exists(_.sources.isEmpty))
      error(s"Source paths must be set on root or JavaScript module $moduleName")
    else if (module.sources.isEmpty && module.jvm.exists(_.sources.isEmpty))
      error(s"Source paths must be set on root or JVM module $moduleName")
    else if (module.sources.isEmpty && module.native.exists(_.sources.isEmpty))
      error(s"Source paths must be set on root or native module $moduleName")
    else if (module.targets.contains(JavaScript) && build.project.scalaJsVersion.isEmpty)
      error(s"Module $moduleName has JavaScript target, but Scala.js version not set")
    else if (module.targets.contains(Native) && build.project.scalaNativeVersion.isEmpty)
      error(s"Module $moduleName has native target, but Scala Native version not set")
    else if (module.test.exists(_.test.nonEmpty))
      error(s"Test module $moduleName cannot contain another test module")
    else if (module.output.nonEmpty || module.jvm.exists(_.output.nonEmpty))
      error(s"Output path can be only set on native and JavaScript modules (affected module: $moduleName)")
    else if (module.js.exists(_.javaDeps.nonEmpty))
      error(s"JavaScript module $moduleName cannot have `javaDeps` set")
    else if (module.native.exists(_.javaDeps.nonEmpty))
      error(s"Native module $moduleName cannot have `javaDeps` set")
    else if (module.js.isDefined && !module.targets.contains(JavaScript))
      error(s"Module $moduleName has JavaScript target, but `targets` does not contain `js`")
    else if (module.jvm.isDefined && !module.targets.contains(JVM))
      error(s"Module $moduleName has JVM target, but `targets` does not contain `jvm`")
    else if (module.native.isDefined && !module.targets.contains(Native))
      error(s"Module $moduleName has native target, but `targets` does not contain `native`")
    else if (module.test.exists(_.js.exists(_.root.nonEmpty)))
      error(s"`root` cannot be set on JavaScript test module $moduleName")
    else if (module.test.exists(_.jvm.exists(_.root.nonEmpty)))
      error(s"`root` cannot be set on JVM test module $moduleName")
    else if (module.test.exists(_.native.exists(_.root.nonEmpty)))
      error(s"`root` cannot be set on native test module $moduleName")
    else if (invalidModuleDeps.nonEmpty)
      error(s"Module dependencies of $moduleName not found in scope: ${invalidModuleDeps.mkString(", ")}")
  }

  def scalaVersion(project: Project, stack: List[Module]): String =
    stack.find(_.scalaVersion.isDefined)
      .flatMap(_.scalaVersion)
      .getOrElse(project.scalaVersion)

  def isCrossBuild(module: Module): Boolean = module.targets.toSet.size > 1

  def targetName(build: Build, name: String, platform: Platform): String =
    if (!isCrossBuild(build.module(name))) name else name + "-" + platform.id

  def platformModule(build: Build, name: String, platform: Platform): Option[Module] =
    if (platform == JavaScript) build.module(name).js
    else if (platform == JVM) build.module(name).jvm
    else if (platform == Native) build.module(name).native
    else throw new IllegalArgumentException()

  def targetNames(build: Build, name: String, platform: Platform): List[String] =
    if (!isCrossBuild(build.module(name))) List(name)
    else if (platformModule(build, name, platform).isEmpty) List(name)
    else List(name, name + "-" + platform.id)

  def jsModuleDeps(module: Module): List[String] =
    module.moduleDeps ++ module.js.map(_.moduleDeps).getOrElse(List())

  def nativeModuleDeps(module: Module): List[String] =
    module.moduleDeps ++ module.native.map(_.moduleDeps).getOrElse(List())

  def jvmModuleDeps(module: Module): List[String] =
    module.moduleDeps ++ module.jvm.map(_.moduleDeps).getOrElse(List())

  def collectJsModuleDeps(build: Build, module: Module): List[String] =
    jsModuleDeps(module).flatMap(m =>
      List(m) ++ collectJsModuleDeps(build, build.module(m)))

  def collectNativeModuleDeps(build: Build, module: Module): List[String] =
    nativeModuleDeps(module).flatMap(m =>
      List(m) ++ collectNativeModuleDeps(build, build.module(m)))

  def collectJvmModuleDeps(build: Build, module: Module): List[String] =
    jvmModuleDeps(module).flatMap(m =>
      List(m) ++ collectJvmModuleDeps(build, build.module(m)))

  def collectJsClassPath(buildPath: Path,
                         build: Build,
                         module: Module): List[Path] =
    jsModuleDeps(module).flatMap(name =>
      buildPath.resolve(targetName(build, name, JavaScript)) +:
      collectJsClassPath(buildPath, build, build.module(name)))

  def collectNativeClassPath(buildPath: Path,
                             build: Build,
                             module: Module): List[Path] =
    nativeModuleDeps(module).flatMap(name =>
      buildPath.resolve(targetName(build, name, Native)) +:
      collectNativeClassPath(buildPath, build, build.module(name)))

  def collectJvmClassPath(buildPath: Path,
                          build: Build,
                          module: Module): List[Path] =
    jvmModuleDeps(module).flatMap(name =>
      buildPath.resolve(targetName(build, name, JVM)) +:
      collectJvmClassPath(buildPath, build, build.module(name)))

  def collectJsDeps(build: Build, module: Module): List[Dep] =
    module.scalaDeps ++ module.js.map(_.scalaDeps).getOrElse(List()) ++
    jsModuleDeps(module).flatMap(m => collectJsDeps(build, build.module(m)))

  def collectNativeDeps(build: Build, module: Module): List[Dep] =
    module.scalaDeps ++ module.native.map(_.scalaDeps).getOrElse(List()) ++
    nativeModuleDeps(module).flatMap(m => collectNativeDeps(build, build.module(m)))

  def collectJvmScalaDeps(build: Build, module: Module): List[Dep] =
    module.scalaDeps ++ module.jvm.map(_.scalaDeps).getOrElse(List()) ++
    jvmModuleDeps(module).flatMap(m => collectJvmScalaDeps(build, build.module(m)))

  def collectJvmJavaDeps(build: Build, module: Module): List[Dep] =
    module.javaDeps ++ module.jvm.map(_.javaDeps).getOrElse(List()) ++
    jvmModuleDeps(module).flatMap(m => collectJvmJavaDeps(build, build.module(m)))

  def tmpfsPath(projectPath: Path): Path = {
    val name = projectPath.toAbsolutePath.getFileName.toString
    Log.info("Build path set to tmpfs")
    Log.warn(s"Please ensure that there is no other project with the name ${Ansi.italic(name)} that also compiles to tmpfs")
    Paths.get("/tmp").resolve("build-" + name)
  }
}
