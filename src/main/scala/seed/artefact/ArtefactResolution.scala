package seed.artefact

import java.nio.file.Path

import seed.Cli.PackageConfig
import MavenCentral.{CompilerVersion, PlatformVersion}
import seed.model.Build.{Dep, JavaDep, Module, Resolvers, ScalaDep}
import seed.model.Platform.{JVM, JavaScript, Native}
import seed.model.{Artefact, Config, Platform, Resolution}
import seed.{Cli, Log}
import seed.artefact.Coursier.ResolutionResult
import seed.config.BuildConfig
import seed.config.BuildConfig.Build

object ArtefactResolution {
  def javaDepFromScalaDep(
    dep: ScalaDep,
    platform: Platform,
    platformVersion: PlatformVersion,
    compilerVersion: CompilerVersion
  ): JavaDep =
    JavaDep(
      dep.organisation,
      MavenCentral.formatArtefactName(
        dep.artefact,
        dep.versionTag,
        platform,
        platformVersion,
        compilerVersion
      ),
      dep.version
    )

  def javaDepFromArtefact(
    artefact: Artefact,
    version: String,
    platform: Platform,
    platformVersion: PlatformVersion,
    compilerVersion: CompilerVersion
  ): JavaDep =
    JavaDep(
      artefact.organisation,
      artefact.versionTag.fold(artefact.name)(
        vt =>
          MavenCentral.formatArtefactName(
            artefact.name,
            vt,
            platform,
            platformVersion,
            compilerVersion
          )
      ),
      version
    )

  def scalaLibraryDeps(
    scalaOrganisation: String,
    scalaVersion: String
  ): Set[JavaDep] =
    Set(
      JavaDep(scalaOrganisation, "scala-library", scalaVersion),
      JavaDep(scalaOrganisation, "scala-reflect", scalaVersion)
    )

  def jvmPlatformDeps(module: Module): Set[JavaDep] =
    scalaLibraryDeps(module.scalaOrganisation.get, module.scalaVersion.get)

  def jsPlatformDeps(module: Module): Set[JavaDep] = {
    val scalaVersion   = module.scalaVersion.get
    val scalaJsVersion = module.scalaJsVersion.get

    Set(
      Artefact.ScalaJsLibrary
    ).map(
      artefact =>
        javaDepFromArtefact(
          artefact,
          scalaJsVersion,
          JavaScript,
          scalaJsVersion,
          scalaVersion
        )
    ) ++ scalaLibraryDeps(module.scalaOrganisation.get, module.scalaVersion.get)
  }

  def nativePlatformDeps(module: Module): Set[JavaDep] = {
    val scalaVersion       = module.scalaVersion.get
    val scalaNativeVersion = module.scalaNativeVersion.get

    Set(
      Artefact.ScalaNativeJavalib,
      Artefact.ScalaNativeScalalib,
      Artefact.ScalaNativeNativelib,
      Artefact.ScalaNativeAuxlib
    ).map(
      artefact =>
        javaDepFromArtefact(
          artefact,
          scalaNativeVersion,
          Native,
          scalaNativeVersion,
          scalaVersion
        )
    ) ++ scalaLibraryDeps(module.scalaOrganisation.get, module.scalaVersion.get)
  }

  def nativeLibraryDep(module: Module): JavaDep = {
    val scalaVersion       = module.scalaVersion.get
    val scalaNativeVersion = module.scalaNativeVersion.get

    javaDepFromArtefact(
      Artefact.ScalaNativeNativelib,
      scalaNativeVersion,
      Native,
      scalaNativeVersion,
      scalaVersion
    )
  }

  def jvmArtefacts(module: Module): Set[(Platform, Dep)] =
    (module.scalaDeps ++ module.javaDeps).map(dep => JVM -> dep).toSet

  def jvmDeps(module: Module): Set[JavaDep] =
    module.scalaDeps
      .map(
        dep =>
          javaDepFromScalaDep(
            dep,
            JVM,
            module.scalaVersion.get,
            module.scalaVersion.get
          )
      )
      .toSet ++
      module.javaDeps.toSet

  def jsArtefacts(module: Module): Set[(Platform, Dep)] =
    module.scalaDeps.map(dep => JavaScript -> dep).toSet

  def jsDeps(module: Module): Set[JavaDep] =
    module.scalaDeps
      .map(
        dep =>
          javaDepFromScalaDep(
            dep,
            JavaScript,
            module.scalaJsVersion.get,
            module.scalaVersion.get
          )
      )
      .toSet

  def nativeArtefacts(module: Module): Set[(Platform, Dep)] =
    module.scalaDeps.map(Native -> _).toSet

  def nativeDeps(module: Module): Set[JavaDep] =
    module.scalaDeps
      .map(
        dep =>
          javaDepFromScalaDep(
            dep,
            Native,
            module.scalaNativeVersion.get,
            module.scalaVersion.get
          )
      )
      .toSet

  def compilerDeps(module: Module, platform: Platform): Set[JavaDep] = {
    def f(platformModule: Module, platform: Platform): Set[JavaDep] = {
      val platformVer  = BuildConfig.platformVersion(platformModule, platform)
      val compilerVer  = platformModule.scalaVersion.get
      val organisation = platformModule.scalaOrganisation.get

      val scalaDeps = Set(
        Artefact.scalaCompiler(organisation) -> compilerVer,
        Artefact.scalaLibrary(organisation)  -> compilerVer,
        Artefact.scalaReflect(organisation)  -> compilerVer
      ) ++ (
        if (platform == Platform.Native)
          Set(Artefact.ScalaNativePlugin -> platformVer)
        else if (platform == Platform.JavaScript)
          Set(Artefact.ScalaJsCompiler -> platformVer)
        else Set()
      )

      scalaDeps.map {
        case (artefact, version) =>
          javaDepFromArtefact(
            artefact,
            version,
            platform,
            platformVer,
            compilerVer
          )
      } ++ platformModule.compilerDeps.map(
        dep => javaDepFromScalaDep(dep, platform, platformVer, compilerVer)
      )
    }

    BuildConfig.platformModule(module, platform) match {
      case None                 => Set()
      case Some(platformModule) => f(platformModule, platform)
    }
  }

  sealed trait Type
  case object Regular extends Type
  case object Test    extends Type

  /** @return runtime libraries from module and its transitive module dependencies */
  def allRuntimeLibs(
    build: Build,
    name: String,
    platform: Platform,
    tpe: Type
  ): Set[JavaDep] = {
    val m  = build(name).module
    val pm = BuildConfig.platformModule(m, platform).get
    val modules = BuildConfig
      .collectModuleDeps(
        build,
        // TODO should be pm, but module deps are not inherited
        m,
        Set(platform)
      )
      .map(m => build(m).module)
      .toSet ++ Set(m)

    if (tpe == Test)
      modules.flatMap(
        _.test.fold(Set[JavaDep]())(t => libraryDeps(t, Set(platform)))
      )
    else
      modules.flatMap(libraryDeps(_, Set(platform))) ++
        platformDeps(pm, platform)
  }

  /** Perform resolution for all modules separately */
  type ModuleRef = (String, Platform, Type)
  def allRuntimeLibs(build: Build): Map[ModuleRef, Set[JavaDep]] = {
    val all = build.toList
      .flatMap { case (n, m) => m.module.targets.map(n -> _) }
      .flatMap {
        case (m, p) =>
          List(
            (m, p, Regular: Type) -> allRuntimeLibs(build, m, p, Regular),
            (m, p, Test: Type)    -> allRuntimeLibs(build, m, p, Test)
          )
      }

    val r = all.toMap
    require(all.length == r.size)
    r
  }

  type ScalaOrganisation = String
  type ScalaVersion      = String

  def allCompilerLibs(
    build: Build
  ): Set[(ScalaOrganisation, ScalaVersion, JavaDep)] =
    build.values
      .map(_.module)
      .flatMap(
        m =>
          m.targets.toSet.flatMap { p =>
            val pm = BuildConfig.platformModule(m, p).get
            compilerDeps(m, p)
              .map(d => (pm.scalaOrganisation.get, pm.scalaVersion.get, d))
          }
      )
      .toSet

  def platformDeps(module: Module, platform: Platform): Set[JavaDep] =
    if (platform == JavaScript) jsPlatformDeps(module)
    else if (platform == Native) nativePlatformDeps(module)
    else jvmPlatformDeps(module)

  def libraryDeps(
    module: Module,
    platforms: Set[Platform] = Set(JVM, JavaScript, Native)
  ): Set[JavaDep] =
    (if (!platforms.contains(JVM)) Set()
     else module.jvm.toSet.flatMap(jvmDeps)) ++
      (if (!platforms.contains(JavaScript)) Set()
       else module.js.toSet.flatMap(jsDeps)) ++
      (if (!platforms.contains(Native)) Set()
       else module.native.toSet.flatMap(nativeDeps))

  def libraryArtefacts(module: Module): Set[(Platform, Dep)] =
    module.jvm.toSet.flatMap(jvmArtefacts) ++
      module.js.toSet.flatMap(jsArtefacts) ++
      module.native.toSet.flatMap(nativeArtefacts) ++
      module.test.toSet.flatMap(libraryArtefacts)

  def allLibraryArtefacts(build: Build): Map[Platform, Set[Dep]] =
    build.values.toSet
      .flatMap(m => libraryArtefacts(m.module))
      .groupBy(_._1)
      .mapValues(_.map(_._2))

  def isScalaLibrary(javaDep: JavaDep): Boolean =
    javaDep.artefact == "scala-library" ||
      javaDep.artefact == "scala-reflect"

  def resolveScalaCompiler(
    resolution: CompilerResolution,
    scalaOrganisation: String,
    scalaVersion: String,
    userLibraries: List[Resolution.Artefact],
    classPath: List[Path]
  ): Resolution.ScalaCompiler = {
    require(classPath.length == classPath.distinct.length)

    val compilerDep = JavaDep(scalaOrganisation, "scala-compiler", scalaVersion)

    val r = resolution.find(Coursier.hasDep(_, compilerDep))
    require(r.isDefined, s"Could not find dependency $compilerDep")

    val compiler = Coursier.localArtefacts(r.get, Set(compilerDep), false)

    Resolution.ScalaCompiler(
      scalaOrganisation,
      scalaVersion,
      userLibraries,
      classPath,
      compiler.map(_.libraryJar)
    )
  }

  def ivyPath(
    seedConfig: seed.model.Config,
    packageConfig: PackageConfig
  ): Path =
    packageConfig.ivyPath.getOrElse(seedConfig.resolution.ivyPath)

  def cachePath(
    seedConfig: seed.model.Config,
    packageConfig: PackageConfig
  ): Path =
    packageConfig.cachePath.getOrElse(seedConfig.resolution.cachePath)

  def resolution(
    seedConfig: seed.model.Config,
    resolvers: Resolvers,
    packageConfig: PackageConfig,
    dependencies: Set[JavaDep],
    forceScala: (ScalaOrganisation, ScalaVersion),
    optionalArtefacts: Boolean,
    log: Log
  ): Coursier.ResolutionResult =
    Coursier.resolveAndDownload(
      dependencies,
      forceScala,
      resolvers,
      ivyPath(seedConfig, packageConfig),
      cachePath(seedConfig, packageConfig),
      optionalArtefacts,
      packageConfig.silent || seedConfig.resolution.silent,
      log
    )

  type RuntimeResolution = Map[ModuleRef, Coursier.ResolutionResult]

  /**
    * Coursier merges all library artefacts that occur multiple times in the
    * dependency tree, choosing their latest version. Therefore, resolve
    * libraries from each module separately.
    */
  def runtimeResolution(
    build: Build,
    seedConfig: seed.model.Config,
    resolvers: Resolvers,
    packageConfig: PackageConfig,
    optionalArtefacts: Boolean,
    log: Log
  ): RuntimeResolution = {
    val all = allRuntimeLibs(build)
    all.flatMap {
      case (path, libs) =>
        val (n, p, t) = path
        val m         = build(n).module
        val pm =
          if (t == Regular) BuildConfig.platformModule(m, p)
          else
            m.test match {
              case None    => BuildConfig.platformModule(m, p)
              case Some(t) => BuildConfig.platformModule(t, p)
            }

        pm.map { pm =>
          val (scalaOrg, scalaVer) =
            (pm.scalaOrganisation.get, pm.scalaVersion.get)

          path -> resolution(
            seedConfig,
            resolvers,
            packageConfig,
            libs ++ (if (t == Regular) Set() else all((n, p, Regular))),
            (scalaOrg, scalaVer),
            optionalArtefacts,
            log
          )
        }.toList
    }
  }

  type CompilerResolution = List[Coursier.ResolutionResult]

  def compilerResolution(
    build: Build,
    seedConfig: seed.model.Config,
    resolvers: Resolvers,
    packageConfig: PackageConfig,
    optionalArtefacts: Boolean,
    log: Log
  ): CompilerResolution =
    allCompilerLibs(build).toList
      .map {
        case (scalaOrganisation, scalaVersion, dep) =>
          resolution(
            seedConfig,
            resolvers,
            packageConfig,
            Set(dep),
            (scalaOrganisation, scalaVersion),
            optionalArtefacts,
            log
          )
      }

  /** @return If there are two dependencies with the same organisation and
    *         artefact name, only retain the last one, regardless of its version.
    */
  def mergeDeps[T <: Dep](deps: List[T]): List[T] =
    deps.foldLeft(List[T]()) {
      case (acc, cur) =>
        acc.find(
          d => d.organisation == cur.organisation && d.artefact == cur.artefact
        ) match {
          case None           => acc :+ cur
          case Some(previous) => acc.diff(List(previous)) :+ cur
        }
    }

  case class Resolved(
    resolution: ResolutionResult,
    deps: Map[JavaDep, List[(coursier.Classifier, Coursier.Artefact)]]
  )

  def resolvePackageArtefacts(
    seedConfig: Config,
    packageConfig: Cli.PackageConfig,
    resolvers: Resolvers,
    build: Build,
    moduleName: String,
    platform: Platform,
    log: Log
  ): Resolved = {
    val m  = build(moduleName).module
    val pm = BuildConfig.platformModule(m, platform).get

    val (scalaOrg, scalaVer) = (pm.scalaOrganisation.get, pm.scalaVersion.get)

    val libs = allRuntimeLibs(build, moduleName, platform, Regular)

    val r =
      resolution(
        seedConfig,
        resolvers,
        packageConfig,
        libs,
        (scalaOrg, scalaVer),
        optionalArtefacts = false,
        log
      )

    val resolvedLibs =
      Coursier.resolveSubset(r.resolution, libs, optionalArtefacts = false)
    Resolved(r, resolvedLibs)
  }
}
