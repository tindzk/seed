package seed.config

import java.nio.file.{Files, Path, Paths}

import seed.cli.util.{Ansi, ColourScheme}
import seed.model.Build.{JavaDep, Module, Project, ScalaDep}
import seed.model.Platform.{JVM, JavaScript, Native}
import seed.model.{Build, Platform}
import seed.Log
import seed.config.util.TomlUtils

import scala.collection.mutable

object BuildConfig {
  import TomlUtils.parseBuildToml

  case class Result(build: Build, projectPath: Path, moduleProjectPaths: Map[String, Path])

  def load(path: Path, log: Log): Option[Result] =
    loadInternal(path, log).filter(result =>
      result.build.module.toList.forall { case (name, module) =>
        checkModule(result.build, name, module, log)
      })

  private def loadInternal(path: Path, log: Log): Option[Result] = {
    if (!Files.exists(path)) {
      log.error(s"Invalid path to build file provided: ${Ansi.italic(path.toString)}")
      None
    } else {
      def parentOf(path: Path): Path = {
        val p = path.getParent
        if (p != null) p else path.toAbsolutePath.getParent
      }

      val (projectPath, projectFile) =
        if (Files.isRegularFile(path)) (parentOf(path), path)
        else (path, path.resolve("build.toml"))

      log.info(s"Loading project ${Ansi.italic(projectFile.toString)}...")

      if (!Files.exists(projectFile)) {
        log.error(s"The file ${Ansi.italic(projectFile.toString)} could not be found")
        log.error("You can create a new build file using:")
        log.error(Ansi.foreground(ColourScheme.green2)("$ seed init"))
        None
      } else {
        TomlUtils.parseFile(
          projectFile, parseBuildToml(projectPath), "build file", log
        ).map { parsed =>
          val (build, moduleProjectPaths) = processBuild(parsed, projectPath, { path =>
            loadInternal(path, log).map { case Result(build, projectPath, moduleProjectPaths) =>
              parsed.module.keySet.intersect(build.module.keySet).foreach(name =>
                log.error(s"Module name ${Ansi.italic(name)} is not unique"))
              (build, moduleProjectPaths)
            }
          })

          Result(build, projectPath.normalize(), moduleProjectPaths)
        }
      }
    }
  }

  def processBuild(build: Build, projectPath: Path, parse: Path => Option[(Build, Map[String, Path])]): (Build, Map[String, Path]) = {
    val parsed = build.copy(
      module = build.module.mapValues { module =>
        val parentTargets = moduleTargets(module, module.targets)
        module.copy(
          targets = parentTargets,
          test = module.test.map { module =>
            module.copy(
              targets = moduleTargets(
                module,
                if (module.targets.isEmpty) parentTargets else module.targets))
          }
        )
      }
    )

    val imported = parsed.`import`.flatMap(parse(_))

    val parsedModules = parsed.module.mapValues(inheritCompilerDeps(parsed.project))

    val importedModules = imported.foldLeft(Map.empty[String, Module]) {
      case (acc, (importedBuild, importedPaths)) =>
        acc ++ importedBuild.module.mapValues(inheritCompilerDeps(importedBuild.project))
    }

    val combinedBuild = parsed.copy(
      project = parsed.project.copy(
        testFrameworks = (parsed.project.testFrameworks ++ imported.flatMap(_._1.project.testFrameworks)).distinct
      ),
      module = parsedModules ++ importedModules)


    val moduleProjectPaths =
      imported.flatMap(_._2).toMap ++
      parsed.module.keys.map(m => m -> projectPath).toMap

    (combinedBuild, moduleProjectPaths)
  }

  def inheritCompilerDeps(project: Project)(module: Module): Module =
    module.copy(
      compilerDeps = (project.compilerDeps ++ module.compilerDeps).distinct,
      jvm = module.jvm.map(inheritCompilerDeps(project)),
      js = module.js.map(inheritCompilerDeps(project)),
      native = module.native.map(inheritCompilerDeps(project)))

  def moduleTargets(module: Build.Module,
                    otherTargets: List[Platform]): List[Platform] = (
    otherTargets ++
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

  def checkModule(build: Build, name: String, module: Build.Module, log: Log): Boolean = {
    def error(message: String): Boolean = {
      log.error(message)
      false
    }

    val invalidModuleDeps =
      module.moduleDeps.filter(!build.module.isDefinedAt(_))
    val invalidTargetModules =
      module
        .target
        .toList
        .flatMap(_._2.`class`)
        .map(_.module.module)
        .filter(!build.module.isDefinedAt(_))
    val invalidTargetModules2 =
      module
        .target
        .keys
        .filter(id => Platform.All.keys.exists(_.id == id))

    val moduleName = Ansi.italic(name)

    if (module.targets.isEmpty && module.target.isEmpty)
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
    else if (invalidTargetModules.nonEmpty)
      error(s"Invalid module(s) referenced in $moduleName: ${invalidTargetModules.mkString(", ")}")
    else if (invalidTargetModules2.nonEmpty)
      error(s"A target module in `$moduleName` has the same name as a Scala platform")
    else true
  }

  def scalaVersion(project: Project, stack: List[Module]): String =
    stack.find(_.scalaVersion.isDefined)
      .flatMap(_.scalaVersion)
      .getOrElse(project.scalaVersion)

  def platformVersion(build: Build, module: Module, platform: Platform): String =
    platform match {
      case JVM => BuildConfig.scalaVersion(build.project, List(module))
      case JavaScript => build.project.scalaJsVersion.get
      case Native => build.project.scalaNativeVersion.get
    }

  def isCrossBuild(module: Module): Boolean = module.targets.toSet.size > 1

  def hasTarget(build: Build, name: String, platform: Platform): Boolean =
    build.module(name).targets.contains(platform)

  def targetName(build: Build, name: String, platform: Platform): String =
    if (!isCrossBuild(build.module(name))) name else name + "-" + platform.id

  def buildTargets(build: Build, module: String): List[String] = {
    val m = build.module(module)
    val p = m.targets
    p.map(p => targetName(build, module, p))
  }

  def linkTargets(build: Build, module: String): List[String] = {
    val m = build.module(module)
    val p = m.targets.diff(List(JVM))
    p.map(p => targetName(build, module, p))
  }

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

  def collectModuleDeps(build: Build, module: Module, platform: Platform): List[String] =
    platform match {
      case JVM => collectJvmModuleDeps(build, module)
      case JavaScript => collectJsModuleDeps(build, module)
      case Native => collectNativeModuleDeps(build, module)
    }

  def collectModuleDeps(build: Build, module: Module): List[String] =
    Platform.All.keys.toList
      .flatMap(p => collectModuleDeps(build, module, p))
      .distinct

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

  def collectJsDeps(build: Build, module: Module): List[ScalaDep] =
    module.scalaDeps ++ module.js.map(_.scalaDeps).getOrElse(List()) ++
    jsModuleDeps(module).flatMap(m => collectJsDeps(build, build.module(m)))

  def collectNativeDeps(build: Build, module: Module): List[ScalaDep] =
    module.scalaDeps ++ module.native.map(_.scalaDeps).getOrElse(List()) ++
    nativeModuleDeps(module).flatMap(m => collectNativeDeps(build, build.module(m)))

  def collectJvmScalaDeps(build: Build, module: Module): List[ScalaDep] =
    module.scalaDeps ++ module.jvm.map(_.scalaDeps).getOrElse(List()) ++
    jvmModuleDeps(module).flatMap(m => collectJvmScalaDeps(build, build.module(m)))

  def collectJvmJavaDeps(build: Build, module: Module): List[JavaDep] =
    module.javaDeps ++ module.jvm.map(_.javaDeps).getOrElse(List()) ++
    jvmModuleDeps(module).flatMap(m => collectJvmJavaDeps(build, build.module(m)))
}
