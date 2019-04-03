package seed.generation.util

import java.nio.file.Path

import seed.artefact.Coursier
import seed.config.BuildConfig
import seed.model.Build.Module
import seed.model.Platform.{JavaScript, Native}
import seed.model.{Artefact, Build, Platform}

object ScalaCompiler {
  def resolveCompiler(build: Build,
                      module: Module,
                      compilerResolution: List[Coursier.ResolutionResult],
                      artefact: Artefact,
                      platform: Platform,
                      compilerVer: String
                     ): Path = {
    val platformVer = BuildConfig.platformVersion(build, module, platform)

    compilerResolution.iterator.flatMap(resolution =>
      Coursier.artefactPath(resolution, artefact, platform,
        platformVer, compilerVer, platformVer)
    ).toList.headOption.getOrElse(
      throw new Exception(s"Artefact '$artefact' missing"))
  }

  def compilerPlugIns(build: Build,
                      module: Module,
                      compilerResolution: List[Coursier.ResolutionResult],
                      platform: Platform,
                      compilerVer: String): List[String] = {
    val moduleDeps = BuildConfig.collectModuleDeps(build, module, platform)
    val modules = moduleDeps.map(build.module) :+ module
    val artefacts =
      (if (platform == JavaScript) List(Artefact.ScalaJsCompiler)
       else if (platform == Native) List(Artefact.ScalaNativePlugin)
       else List()
      ) ++ modules.flatMap(_.compilerDeps.map(Artefact.fromDep))

    artefacts.map(a => resolveCompiler(
      build, module, compilerResolution, a, platform, compilerVer)
    ).map(p => "-Xplugin:" + p)
  }
}
