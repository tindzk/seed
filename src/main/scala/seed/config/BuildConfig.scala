package seed.config

import java.nio.file.{Files, Path}

import seed.cli.util.{Ansi, ColourScheme}
import seed.model.Build.{JavaDep, Module, ScalaDep}
import seed.model.Platform.{JVM, JavaScript, Native}
import seed.model.{Build, Organisation, Platform, TomlBuild}
import seed.Log
import seed.artefact.{ArtefactResolution, SemanticVersioning}
import seed.config.util.TomlUtils

object BuildConfig {
  import TomlUtils.parseBuildToml

  case class ModuleConfig(module: Module, path: Path)
  type Build = Map[String, ModuleConfig]

  case class Result(projectPath: Path, resolvers: Build.Resolvers, build: Build)

  def load(path: Path, log: Log): Option[Result] =
    loadInternal(path, log).filter(
      result =>
        result.build.toList.forall {
          case (name, module) =>
            checkModule(result.build, name, module.module, log)
        }
    )

  private def loadInternal(path: Path, log: Log): Option[Result] =
    if (!Files.exists(path)) {
      log.error(
        s"Invalid path to build file provided: ${Ansi.italic(path.toString)}"
      )
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
        log.error(
          s"The file ${Ansi.italic(projectFile.toString)} could not be found"
        )
        log.error("You can create a new build file using:")
        log.error(Ansi.foreground(ColourScheme.green2)("$ seed init"))
        None
      } else {
        TomlUtils
          .parseFile(
            projectFile,
            parseBuildToml(projectPath),
            "build file",
            log
          )
          .map { parsed =>
            val modules = processBuild(
              parsed,
              projectPath,
              path => loadInternal(path, log),
              log
            )
            Result(projectPath.normalize(), parsed.resolvers, modules)
          }
      }
    }

  def processBuild(
    build: TomlBuild,
    projectPath: Path,
    parse: Path => Option[Result],
    log: Log
  ): Build = {
    val modules =
      build.module.mapValues(inheritSettings(build.project.toModule))
    val imported = build.`import`.flatMap(parse(_))

    modules.keySet
      .intersect(imported.flatMap(_.build).map(_._1).toSet)
      .foreach(
        name => log.error(s"Module name ${Ansi.italic(name)} is not unique")
      )

    (imported.flatMap(_.build) ++ modules.mapValues(
      ModuleConfig(_, projectPath)
    )).toMap
  }

  /** @return Take all settings from `parent` and override them with values
    *         from `m`
    */
  def inherit(parent: Module)(m: Module): Module = {
    val inheritTargets = if (m.targets.isEmpty) parent.targets else m.targets

    m.copy(
      targets = (targetsFromPlatformModules(m) ++ inheritTargets).distinct,
      scalaVersion = m.scalaVersion.orElse(parent.scalaVersion),
      scalaJsVersion = m.scalaJsVersion.orElse(parent.scalaJsVersion),
      scalaNativeVersion =
        m.scalaNativeVersion.orElse(parent.scalaNativeVersion),
      scalaOptions = (parent.scalaOptions ++ m.scalaOptions).distinct,
      scalaOrganisation = m.scalaOrganisation.orElse(parent.scalaOrganisation),
      compilerDeps =
        ArtefactResolution.mergeDeps(parent.compilerDeps ++ m.compilerDeps),
      testFrameworks = (parent.testFrameworks ++ m.testFrameworks).distinct,
      mainClass = m.mainClass.orElse(parent.mainClass),
      moduleDeps = (parent.moduleDeps ++ m.moduleDeps).distinct,
      scalaDeps = ArtefactResolution.mergeDeps(parent.scalaDeps ++ m.scalaDeps),
      javaDeps = ArtefactResolution.mergeDeps(parent.javaDeps ++ m.javaDeps)
    )
  }

  def defaultSettings(m: Module): Module =
    m.copy(
      scalaOrganisation =
        m.scalaOrganisation.orElse(Some(Organisation.Lightbend.packageName)),
      jvm = m.jvm.map(defaultSettings),
      js = m.js.map(defaultSettings),
      native = m.native.map(defaultSettings),
      test = m.test.map(defaultSettings)
    )

  def inheritSettings(parent: Module)(module: Module): Module = {
    val mergedModule = inherit(parent)(module)

    val result = mergedModule.copy(
      jvm = module.jvm
        .orElse(
          if (!module.targets.contains(Platform.JVM)) None
          else Some(Module())
        )
        .map(inherit(mergedModule))
        .map(_.copy(targets = List())),
      js = module.js
        .orElse(
          if (!module.targets.contains(Platform.JavaScript)) None
          else Some(Module())
        )
        .map(inherit(mergedModule))
        .map(_.copy(targets = List())),
      native = module.native
        .orElse(
          if (!module.targets.contains(Platform.Native)) None
          else Some(Module())
        )
        .map(inherit(mergedModule))
        .map(_.copy(targets = List()))
    )

    // For test modules, some settings should not be inherited at this stage yet
    def stripSettings(module: Module): Module =
      module.copy(
        scalaDeps = List(),
        javaDeps = List(),
        compilerDeps = List(),
        mainClass = None
      )

    defaultSettings(
      result.copy(
        test = result.test
          .map(inheritSettings(stripSettings(result)))
          .map(
            t =>
              t.copy(
                jvm =
                  (if (!t.targets.contains(Platform.JVM)) None
                   else Some(result.test.flatMap(_.jvm).getOrElse(Module())))
                    .map(inherit(result.test.getOrElse(Module())))
                    .map(inherit(stripSettings(result.jvm.getOrElse(Module()))))
                    .map(inherit(stripSettings(result)))
                    .map(_.copy(targets = List())),
                js = (if (!t.targets.contains(Platform.JavaScript)) None
                      else Some(result.test.flatMap(_.js).getOrElse(Module())))
                  .map(inherit(result.test.getOrElse(Module())))
                  .map(inherit(stripSettings(result.js.getOrElse(Module()))))
                  .map(inherit(stripSettings(result)))
                  .map(_.copy(targets = List())),
                native =
                  (if (!t.targets.contains(Platform.Native)) None
                   else Some(result.test.flatMap(_.native).getOrElse(Module())))
                    .map(inherit(result.test.getOrElse(Module())))
                    .map(
                      inherit(stripSettings(result.native.getOrElse(Module())))
                    )
                    .map(inherit(stripSettings(result)))
                    .map(_.copy(targets = List()))
              )
          )
      )
    )
  }

  def checkModule(
    build: Build,
    name: String,
    module: Build.Module,
    log: Log
  ): Boolean = {
    import SemanticVersioning.majorMinorVersion

    def error(message: String): Boolean = {
      log.error(message)
      false
    }

    val invalidModuleDeps =
      module.moduleDeps.filter(!build.isDefinedAt(_))
    val invalidTargetModules =
      module.target.toList
        .flatMap(_._2.`class`)
        .map(_.module.module)
        .filter(!build.isDefinedAt(_))
    val invalidTargetModules2 =
      module.target.keys
        .filter(id => Platform.All.keys.exists(_.id == id))

    val incompatibleScalaVersion = {
      def f(platform: Platform) = platformModule(module, platform).flatMap {
        pm =>
          pm.scalaVersion.flatMap(
            v =>
              pm.moduleDeps
                .find { m =>
                  val version = build
                    .get(m)
                    .map(_.module)
                    .flatMap(
                      m => platformModule(m, platform).flatMap(_.scalaVersion)
                    )
                  version
                    .fold(false)(majorMinorVersion(_) != majorMinorVersion(v))
                }
                .map(
                  m =>
                    (
                      platform,
                      v,
                      m,
                      platformModule(build(m).module, platform).get.scalaVersion.get
                    )
                )
          )
      }

      Platform.All.keys.flatMap(f).headOption
    }

    val moduleName = Ansi.italic(name)

    if (module.targets.isEmpty && module.target.isEmpty)
      error(
        s"No target platforms were set on module $moduleName. Example: ${Ansi.italic("""targets = ["js"]""")}"
      )
    else if (module.sources.isEmpty && module.js.exists(_.sources.isEmpty))
      error(s"Source paths must be set on JavaScript module $moduleName")
    else if (module.sources.isEmpty && module.jvm.exists(_.sources.isEmpty))
      error(s"Source paths must be set on JVM module $moduleName")
    else if (module.sources.isEmpty && module.native.exists(_.sources.isEmpty))
      error(s"Source paths must be set on native module $moduleName")
    else if (module.targets.contains(JVM) && !module.jvm.exists(
               _.scalaVersion.isDefined
             ))
      error(s"Scala version must be set on JVM module $moduleName")
    else if (module.targets.contains(JavaScript) && !module.js.exists(
               _.scalaVersion.isDefined
             ))
      error(s"Scala version must be set on JavaScript module $moduleName")
    else if (module.targets.contains(Native) && !module.native.exists(
               _.scalaVersion.isDefined
             ))
      error(s"Scala version must be set on native module $moduleName")
    else if (module.targets.contains(JavaScript) && !module.js.exists(
               _.scalaJsVersion.isDefined
             ))
      error(
        s"Module $moduleName has JavaScript target, but Scala.js version was not set"
      )
    else if (module.targets.contains(Native) && !module.native.exists(
               _.scalaNativeVersion.isDefined
             ))
      error(
        s"Module $moduleName has native target, but Scala Native version was not set"
      )
    else if (module.test.exists(_.test.nonEmpty))
      error(s"Test module $moduleName cannot contain another test module")
    else if (module.output.nonEmpty || module.jvm.exists(_.output.nonEmpty))
      error(
        s"Output path can be only set on native and JavaScript modules (affected module: $moduleName)"
      )
    else if (module.js.exists(_.javaDeps.nonEmpty))
      error(s"JavaScript module $moduleName cannot have `javaDeps` set")
    else if (module.native.exists(_.javaDeps.nonEmpty))
      error(s"Native module $moduleName cannot have `javaDeps` set")
    else if (module.js.isDefined && !module.targets.contains(JavaScript))
      error(
        s"Module $moduleName has JavaScript target, but `targets` does not contain `js`"
      )
    else if (module.jvm.isDefined && !module.targets.contains(JVM))
      error(
        s"Module $moduleName has JVM target, but `targets` does not contain `jvm`"
      )
    else if (module.native.isDefined && !module.targets.contains(Native))
      error(
        s"Module $moduleName has native target, but `targets` does not contain `native`"
      )
    else if (module.test.exists(_.js.exists(_.root.nonEmpty)))
      error(s"`root` cannot be set on JavaScript test module $moduleName")
    else if (module.test.exists(_.jvm.exists(_.root.nonEmpty)))
      error(s"`root` cannot be set on JVM test module $moduleName")
    else if (module.test.exists(_.native.exists(_.root.nonEmpty)))
      error(s"`root` cannot be set on native test module $moduleName")
    else if (invalidModuleDeps.nonEmpty)
      error(
        s"Module dependencies of $moduleName not found in scope: ${invalidModuleDeps.mkString(", ")}"
      )
    else if (invalidTargetModules.nonEmpty)
      error(
        s"Invalid module(s) referenced in $moduleName: ${invalidTargetModules.mkString(", ")}"
      )
    else if (invalidTargetModules2.nonEmpty)
      error(
        s"A target module in $moduleName has the same name as a Scala platform"
      )
    else if (incompatibleScalaVersion.nonEmpty)
      error(
        s"Scala version of ${Ansi.italic(
          s"$name:${incompatibleScalaVersion.get._1.id}"
        )} (${incompatibleScalaVersion.get._2}) is incompatible with ${Ansi.italic(
          s"${incompatibleScalaVersion.get._3}:${incompatibleScalaVersion.get._1.id}"
        )} (${incompatibleScalaVersion.get._4})"
      )
    else true
  }

  def platformVersion(module: Module, platform: Platform): String =
    platform match {
      case JVM        => module.scalaVersion.get
      case JavaScript => module.scalaJsVersion.get
      case Native     => module.scalaNativeVersion.get
    }

  def isCrossBuild(module: Module): Boolean = module.targets.toSet.size > 1

  def hasTarget(modules: Build, name: String, platform: Platform): Boolean =
    modules(name).module.targets.contains(platform)

  def targetName(build: Build, name: String, platform: Platform): String =
    if (!isCrossBuild(build(name).module)) name else name + "-" + platform.id

  def buildTargets(build: Build, module: String): List[String] = {
    val m = build(module).module
    val p = m.targets
    p.map(p => targetName(build, module, p))
  }

  def linkTargets(build: Build, module: String): List[String] = {
    val m = build(module).module
    val p = m.targets.diff(List(JVM))
    p.map(p => targetName(build, module, p))
  }

  def targetsFromPlatformModules(module: Build.Module): List[Platform] =
    (if (module.jvm.nonEmpty) List(JVM) else List()) ++
      (if (module.js.nonEmpty) List(JavaScript) else List()) ++
      (if (module.native.nonEmpty) List(Native) else List())

  def buildTargets(build: Build): Set[Platform] =
    build.flatMap { case (_, module) => module.module.targets }.toSet

  def platformModule(
    module: Build.Module,
    platform: Platform
  ): Option[Build.Module] =
    platform match {
      case JVM        => module.jvm
      case JavaScript => module.js
      case Native     => module.native
    }

  def updatePlatformModule(
    module: Module,
    platform: Platform,
    platformModule: Option[Module]
  ): Module =
    platform match {
      case JVM        => module.copy(jvm = platformModule)
      case JavaScript => module.copy(js = platformModule)
      case Native     => module.copy(native = platformModule)
    }

  def targetNames(
    modules: Build,
    name: String,
    platform: Platform
  ): List[String] =
    if (!isCrossBuild(modules(name).module)) List(name)
    else if (platformModule(modules(name).module, platform).isEmpty) List(name)
    else List(name, name + "-" + platform.id)

  def collectJsModuleDeps(modules: Build, module: Module): List[String] =
    module.moduleDeps.flatMap(
      m =>
        List(m) ++ modules(m).module.js.toList
          .flatMap(collectJsModuleDeps(modules, _))
    )

  def collectNativeModuleDeps(build: Build, module: Module): List[String] =
    module.moduleDeps.flatMap(
      m =>
        List(m) ++ build(m).module.native.toList
          .flatMap(collectNativeModuleDeps(build, _))
    )

  def collectJvmModuleDeps(build: Build, module: Module): List[String] =
    module.moduleDeps.flatMap(
      m =>
        List(m) ++ build(m).module.jvm.toList
          .flatMap(collectJvmModuleDeps(build, _))
    )

  def collectModuleDeps(
    build: Build,
    module: Module,
    platform: Platform
  ): List[String] =
    platform match {
      case JVM        => collectJvmModuleDeps(build, module)
      case JavaScript => collectJsModuleDeps(build, module)
      case Native     => collectNativeModuleDeps(build, module)
    }

  def collectModuleDepsBase(
    build: Build,
    module: Module,
    platform: Platform
  ): List[String] = {
    require(module.targets.contains(platform))
    platform match {
      case JVM        => collectJvmModuleDeps(build, module.jvm.get)
      case JavaScript => collectJsModuleDeps(build, module.js.get)
      case Native     => collectNativeModuleDeps(build, module.native.get)
    }
  }

  def collectModuleDeps(build: Build, module: Module): List[String] =
    Platform.All.keys.toList
      .filter(module.targets.contains)
      .flatMap(p => collectModuleDepsBase(build, module, p))
      .distinct

  def collectJsClassPath(
    buildPath: Path,
    build: Build,
    module: Module
  ): List[Path] =
    module.moduleDeps
      .filter(name => hasTarget(build, name, Platform.JavaScript))
      .flatMap(
        name =>
          buildPath
            .resolve(targetName(build, name, JavaScript)) +: collectJsClassPath(
            buildPath,
            build,
            build(name).module
          )
      )
      .distinct

  def collectNativeClassPath(
    buildPath: Path,
    build: Build,
    module: Module
  ): List[Path] =
    module.moduleDeps
      .filter(name => hasTarget(build, name, Platform.Native))
      .flatMap(
        name =>
          buildPath
            .resolve(targetName(build, name, Native)) +: collectNativeClassPath(
            buildPath,
            build,
            build(name).module
          )
      )
      .distinct

  def collectJvmClassPath(
    buildPath: Path,
    build: Build,
    module: Module
  ): List[Path] =
    module.moduleDeps
      .filter(name => hasTarget(build, name, Platform.JVM))
      .flatMap(
        name =>
          buildPath.resolve(targetName(build, name, JVM)) +:
            collectJvmClassPath(
              buildPath,
              build,
              build(name).module
            )
      )
      .distinct

  def collectJsDeps(
    build: Build,
    test: Boolean,
    module: Module
  ): List[ScalaDep] =
    module.scalaDeps ++
      module.moduleDeps
        .flatMap(
          d =>
            if (!test) Some(build(d).module)
            else build(d).module.test
        )
        .flatMap(_.js)
        .flatMap(collectJsDeps(build, test, _))

  def collectNativeDeps(
    build: Build,
    test: Boolean,
    module: Module
  ): List[ScalaDep] =
    module.scalaDeps ++
      module.moduleDeps
        .flatMap(
          d =>
            if (!test) Some(build(d).module)
            else build(d).module.test
        )
        .flatMap(_.native)
        .flatMap(collectNativeDeps(build, test, _))

  def collectJvmScalaDeps(
    build: Build,
    test: Boolean,
    module: Module
  ): List[ScalaDep] =
    module.scalaDeps ++
      module.moduleDeps
        .flatMap(
          d =>
            if (!test) Some(build(d).module)
            else build(d).module.test
        )
        .flatMap(_.jvm)
        .flatMap(collectJvmScalaDeps(build, test, _))

  def collectJvmJavaDeps(
    build: Build,
    test: Boolean,
    module: Module
  ): List[JavaDep] =
    module.javaDeps ++
      module.moduleDeps
        .flatMap(
          d =>
            if (!test) Some(build(d).module)
            else build(d).module.test
        )
        .flatMap(_.jvm)
        .flatMap(collectJvmJavaDeps(build, test, _))

  /**
    * Resolves platform-specific test module and inherits dependencies from
    * regular module. This is needed for Bloop to construct the the classpath.
    */
  def mergeTestModule(
    build: Build,
    module: Module,
    platform: Platform
  ): Module = {
    val newPlatformModule = module.test.flatMap(
      t =>
        platformModule(t, platform).map {
          testPlatformModule =>
            platformModule(module, platform)
              .map { platformModule =>
                testPlatformModule.copy(
                  scalaDeps =
                    if (platform == Platform.JVM)
                      ArtefactResolution.mergeDeps(
                        collectJvmScalaDeps(build, false, platformModule) ++
                          collectJvmScalaDeps(build, true, testPlatformModule)
                      )
                    else if (platform == Platform.JavaScript)
                      ArtefactResolution.mergeDeps(
                        collectJsDeps(build, false, platformModule) ++
                          collectJsDeps(build, true, testPlatformModule)
                      )
                    else
                      ArtefactResolution.mergeDeps(
                        collectNativeDeps(build, false, platformModule) ++
                          collectNativeDeps(build, true, testPlatformModule)
                      ),
                  javaDeps =
                    if (platform != JVM) List()
                    else
                      ArtefactResolution.mergeDeps(
                        collectJvmJavaDeps(build, false, platformModule) ++
                          collectJvmJavaDeps(build, true, testPlatformModule)
                      )
                )
              }
              .getOrElse(testPlatformModule)
        }
    )

    updatePlatformModule(
      module.test.getOrElse(Module()),
      platform,
      newPlatformModule
    )
  }
}
