# Seed
[![Gitter](https://badges.gitter.im/seed-scala/community.svg)](https://gitter.im/seed-scala/community?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge)
[![Build Status](http://ci.sparse.tech/api/badges/tindzk/seed/status.svg)](http://ci.sparse.tech/tindzk/seed)
[![](https://images.microbadger.com/badges/image/tindzk/seed.svg)](https://microbadger.com/images/tindzk/seed)
[![](https://images.microbadger.com/badges/version/tindzk/seed.svg)](https://microbadger.com/images/tindzk/seed)
[![](https://img.shields.io/docker/pulls/tindzk/seed.svg)](https://hub.docker.com/r/tindzk/seed)
[![](https://img.shields.io/bintray/v/tindzk/maven/seed.svg)](https://bintray.com/tindzk/maven/seed/)
[![](https://img.shields.io/github/tag/tindzk/seed.svg)](https://github.com/tindzk/seed/releases)

Seed is a user-friendly, fast and flexible build tool for Scala projects. Builds are specified in a single [TOML](https://github.com/toml-lang/toml) file. Seed then handles the dependency resolution, and generates project configurations for the build server [Bloop](https://scalacenter.github.io/bloop/) and for the IDE [IntelliJ](https://www.jetbrains.com/idea/).

Seed's primary focus is to provide a better user experience for defining and managing Scala builds. The TOML format was chosen as a lightweight alternative to a Scala-based DSL. The CLI is equipped with colour support and human-readable messages. A wizard allows to quickly create a new project configuration. There are also commands to package modules and to check for version updates.

Seed was designed with large and modular projects in mind. Modules can reside in external directories and can be imported into the project scope. Thus, there is no need to publish any artefacts with `SNAPSHOT` versions. Furthermore, Seed can create an aggregate IDEA project that contains the project modules including the imported ones, such that there is no need to have multiple IDEA instances running.

The IDEA project is generated in one pass together with the Bloop project. Afterwards, the project can be opened in IDEA right away and is ready to work with. There is no waiting time since the dependencies have already been resolved by Seed and IDEA's build tool integration (sbt/Gradle/Maven) is being completely bypassed. This also allows to work around some bugs IDEA is riddled with.

Further speed improvements in the build pipeline are gained by using a build server for compilation. Seed delegates this responsibility to Bloop which has demonstrated a better performance over traditional Scala build tools. Only one Bloop server instance needs to be running in the background and the communication with the process takes place with one-shot CLI commands, so the memory consumption is considerably lower when working on multiple projects. Bloop also does not suffer from any out-of-memory problems as other build tools.

Another important feature is cross-platform support. Seed targets all Scala platforms (JVM, [JavaScript](https://www.scala-js.org/) and [LLVM](https://scala-native.readthedocs.io/en/latest/)) without the need for plug-ins. Cross-platform builds are first-order citizens which is reflected in the design of the configuration format. The same applies to alternative Scala compilers such as [Typelevel Scala](https://github.com/typelevel/scala).

Customisation is of importance which is why Seed has few defaults. You have to define a directory structure for your own needs. This is especially useful given Seed's cross-platform nature where you may want to share code between different platforms or use custom Scala versions for certain platforms.

Finally, Seed can be used in CI setups. For this, a Docker image is provided which reduces the burden of setting up all system dependencies correctly that are needed for cross-platform builds.

## Demo
In the following screencast, we create a minimal Typelevel Scala project for the JVM, JavaScript and native:

[![asciicast](https://asciinema.org/a/221097.svg)](https://asciinema.org/a/221097)

## Installation
You can either install Seed via Coursier or use a self-contained Docker image.

## Coursier
The following prerequisites are needed:

* **JVM**
* **Bloop**: Bloop serves the function of compiling your projects. The latest version should work since Bloop configurations strive for backward compatibility. If you encounter any problems, you can always install the version Seed is targeting which is indicated in `seed info`.
* **JavaScript:** [Node.js](https://nodejs.org/en/download/) needs to be installed if you want to run the project or its tests.
* **LLVM:** For Scala Native projects to link, LLVM needs to be installed. Please refer to the [Scala Native documentation](http://www.scala-native.org/en/latest/user/setup.html) for more information.

You can create a launcher as follows:

```shell
# Use latest released version
version=$(curl https://api.github.com/repos/tindzk/seed/tags | jq -r '.[0].name')

# Use pre-release version
version=$(curl https://api.bintray.com/packages/tindzk/maven/seed | jq -r '.latest_version')

blp-coursier bootstrap \
    -r bintray:tindzk/maven \
    tindzk:seed_2.12:$version \
    -f -o seed
```

Alternatively, you can use Coursier's `launch` command to run Seed directly.

Note that we use Bloop's version of Coursier. This is to avoid incompatibilities because Seed itself relies on Coursier and its version would be overridden by the launcher otherwise.

## Docker
A [self-contained Docker image](https://hub.docker.com/r/tindzk/seed) based on [Alpine Linux](https://alpinelinux.org/) is provided for all Seed versions. You can pull it from the public Docker Hub registry:

```shell
$ docker pull tindzk/seed:$version
```

It contains a compatible Bloop version and all dependencies needed for cross-platform builds (JVM, Node, Clang/LLVM). You can use the image to build your projects. For example, the [toml-scala](https://github.com/sparsetech/toml-scala) project could be built as follows:

```shell
$ docker run -it tindzk/seed:$version /bin/sh
apk add git
git clone https://github.com/sparsetech/toml-scala.git
cd toml-scala
bloop server &
seed bloop
bloop test toml-js toml-jvm
```

This Docker image can be used in CI setups. [Here](https://github.com/sparsetech/toml-scala/blob/master/.drone.yml) is a [Drone CI](https://drone.io/) configuration for the same project.

## Getting Started
In a previous section, you already saw Seed's project creation wizard (`init`). To illustrate the build format, we will now create a project manually. A complete Scala Native project can be defined in only five lines of TOML:

```toml
[module.demo.native]
root               = "."
scalaVersion       = "2.11.11"
scalaNativeVersion = "0.3.7"
sources            = ["src/"]
```

This build defines a Scala Native module with the name `demo`. Save the content to `build.toml`. Then, create the file `src/Main.scala` containing:

```scala
object Main extends App {
  println("Hello World")
}
```

Now, you can generate the Bloop and IDEA configurations as follows:

```shell
$ seed all
```

This downloads all dependencies to `$HOME/.coursier` and creates projects for IDEA in `.idea/` and for Bloop in `.bloop/`. Instead of `all`, you could have also specified `bloop` or `idea` in order to only generate the respective configuration.

The final step is to compile and run your program:

```shell
$ seed run demo
```

This compiles the module to `build/` and runs it.

## Features
* Succinct build specifications
    * Written in TOML
    * There is only one single configuration file
    * Intuitive syntax without cryptic operators (`scalaDeps` and `javaDeps`, instead of `%%%`, `%%` and `%`)
    * Ability to include other projects rather than publishing dependencies
* Cross-platform modules are first-order citizens with support for all three Scala targets
    * JVM
    * JavaScript
    * Native
* Alternative compilers like Typelevel Scala are fully supported
    * No manual patching of dependencies required
* Fast dependency resolution via [Coursier](https://github.com/coursier/coursier)
    * Once a configuration has been generated, the dependencies do not need to be resolved a second time
    * The user does not need an Internet connection if the dependencies are already locally available
* Shorter compilation times
    * Leverages external Bloop build server that is optimised for compilation speed
    * Optional compilation to tmpfs for in-memory builds
* IDE project generation for IntelliJ IDEA
    * Avoids overhead of sbt/Gradle/Maven projects
    * Better integration of cross-platform projects
* Custom build targets to run external commands or main classes
    * Generate code
    * Build non-Scala artefacts
* Organised file structure
    * There is only one build folder per project (as opposed to `target` folders for every module in sbt)
    * Custom project source structures are easily configured, e.g. `src/` and `test/` instead of the more lengthy `src/{main,test}/scala/`
* Can be used alongside other build tools (e.g. sbt)
    * Default paths were chosen not to conflict in any way
* Can be used in CIs like [Drone](https://drone.io/) using pre-built Docker image
* UX
    * True colour output
    * User-friendly messages
    * Unicode characters
    * Progress bars
* Project creation wizard
* Packaging support
    * Copy over dependencies
* Server mode
    * Expose a WebSocket server
    * Clients can trigger compilation and linking of modules
    * Clients can subscribe to build events
* Check for dependency updates
    * Choose library versions separately for each platform to avoid incompatibilities
* Simple design
    * No distinction between managed/unmanaged/generated sources
    * No ability to define tasks
    * No plug-in infrastructure
    * Tiny code base

## Build Configuration
The default build file is named `build.toml`. You can specify a custom path with the `--build` parameter. A build file corresponds to a *project* which can contain multiple *modules*.

This section explains the components of build configurations and provides examples you can use in your own build files.

### Project
Project-wide settings are defined in the optional `[project]` section and are inherited by all modules defined in the same file:

```toml
[project]
scalaVersion       = "2.12.4-bin-typelevel-4"       # Mandatory; Scala version to be used by all modules
scalaJsVersion     = "0.6.23"                       # Optional; only needed for JavaScript compilation
scalaNativeVersion = "0.3.7"                        # Optional; only needed for native compilation
scalaOrganisation  = "org.typelevel"                # Optional; defaults to `org.scala-lang`
scalaOptions       = ["-Yliteral-types"]            # Optional; empty by default
testFrameworks     = ["minitest.runner.Framework"]  # Entry points for test frameworks
                                                    # Explained in section "Test module" below
```

All of these settings can be overridden by modules.

### Module
A module groups source paths and related settings into a compilation unit. The basic syntax of a module as follows:

```toml
[module.myModule]
targets = ["jvm", "js"]
root    = "src"
sources = ["src"]
```

This defines a *cross-compiled module* with a JVM and JavaScript target. For every module, a list of source paths has to be specified. The source paths can be files and directories. You also have to define a root path (`root` setting) if you would like to generate an IntelliJ project.

Since Seed is platform-agnostic, every module needs to specify a target platform. This is either achieved with the `targets` setting or by including the platform in the module header definition:

```toml
[module.myModule2.jvm]
sources = ["src"]

[module.myModule2.js]
sources = ["src"]
```

These two definitions are called *platform-specific modules*. Both modules are equivalent to `myModule`.

A module can have an optional test module:

```toml
# Cross-compiled
# Inherits all settings from base module (e.g. root, targets and sources)
[module.myModule.test]
sources = ["test"]

# Platform-specific
[module.myModule2.test.jvm]
sources = ["test"]
```

For any module, the following options are available:

```toml
[module.myModule]
scalaVersion       = ""  # Must be set on every module
scalaJsVersion     = ""  # Only used if module has JavaScript target
scalaNativeVersion = ""  # Only used if module has native target
scalaOptions       = []  # Empty by default
scalaOrganisation  = ""  # Defaults to `org.scala-lang`
testFrameworks     = []  # Only used by test module
root               = ""  # Module root path for IntelliJ
sources            = []  # List of source directories and files
scalaDeps          = []  # Module-specific Scala dependencies
compilerDeps       = []  # Compiler plug-ins (specified in the same format as scalaDeps)
moduleDeps         = []  # Module dependencies
mainClass          = ""  # Optional entry point; needed for running/packaging module
targets            = []  # Platform targets
```

Unless overridden in the module, the settings are inherited from the `[project]` section.

### Cross-platform module
A cross-platform module is a module that has one or multiple targets. The target platforms your code can be compiled to is only limited by the language features and libraries your code makes use of. Not all libraries and Scala features are available during JavaScript and native compilation. For most code, cross-platform support will be as simple as adding another target. For other projects, you will have to factor out platform-specific logic into a submodule (see next section).

```toml
[module.myModule]
root    = "jvm"
sources = ["src"]
targets = ["js"]
```

When you generate the Bloop configuration with `seed bloop`, this will create `myModule` as there is only one platform. You can compile it using `bloop compile myModule`. We can specify multiple targets:

```toml
[module.myModule]
# ...
targets = ["js", "jvm", "native"]
```

This is equivalent to defining a separate module for each platform.

In the Bloop configuration, this corresponds to an aggregate module `myModule` as well as three modules with the platform appended to each. You can compile them with `bloop compile myModule-<target>` whereas `<target>` is one of `js`, `jvm` and `native`.

Returning to our initial example from the "Getting Started" section, we can change it as follows to make it compile for JVM and native:

```toml
# Instead of `[module.demo.native]`
[module.demo]
root               = "."
scalaVersion       = "2.11.11"
scalaNativeVersion = "0.3.7"
sources            = ["src/"]
targets            = ["jvm", "native"]  # This line was added
```

### Platform-specific module
A platform-specific module inherits all settings from its parent and extends them:

```toml
[module.myModule]
root    = "shared"
sources = ["shared/src"]
targets = ["jvm", "js"]

[module.myModule.jvm]
root      = "jvm"
sources   = ["jvm/src"]
resources = ["jvm/res"]

[module.myModule.js]
root      = "js"
sources   = ["js/src"]
scalaDeps = [["org.scala-js", "scalajs-dom", "0.9.5"]]
```

Here, we use a cross-platform module in conjunction with two platform-specific modules. This is useful if you have code that should be shared across multiple platforms, and if certain platforms have additional functionality or provide a specific implementation for a feature.

In the base module, the source path is `src` which we extend in the JVM- and JavaScript-specific modules with platform-specific sources, i.e. `jvm/src` and `js/src`. For example, the command `bloop compile myProject-jvm` would compile all sources from the base module and additionally include files from `jvm/src`.

It is possible to set a custom Scala version for modules. This is needed when a platform does not support the latest Scala version yet, as is the case with Scala Native. You can globally set the version to 2.12 which will be then used by JavaScript and JVM, but the Scala Native module will use 2.11:

```toml
[project]
scalaVersion = "2.12.8"

[module.myModule.native]
scalaVersion = "2.11.11"
```

#### JVM
The available JVM options are:

```toml
[module.myModule.jvm]
javaDeps  = []  # Java dependencies
resources = []  # List of resource paths
```

#### JavaScript
The available JavaScript options are:

```toml
[module.myModule.js]
jsdom          = false  # Import the jsdom library in the generated.
                        # JavaScript file. Must be installed via
                        # yarn/npm. Useful for test cases that rely
                        # on DOM operations.
emitSourceMaps = true   # Emit source maps
output         = "myModule.js"  # Path to generated file
                                # Default: <module name>.js
```

#### Native
The available native options are:

```toml
[module.myModule.native]
gc              = "immix"  # Garbage collector. Options: none, immix or boehm
targetTriple    = ""       # See https://clang.llvm.org/docs/CrossCompilation.html#target-triple
clang           = "/usr/bin/clang"
clangpp         = "/usr/bin/clang++"
compilerOptions = []       # Options passed to Clang during compilation
linkerOptions   = []       # Options passed to Clang during linking
linkStubs       = false    # Link or ignore @stub definitions
output          = "myModule.run"  # Path to generated file
                                  # Default: <module name>.js
```

### Dependencies
In all modules, you can specify `scalaDeps` which will be resolved to the corresponding artefacts for each target platform:

```toml
[module.myModule]
root      = "shared"
sources   = ["shared/src"]
targets   = ["js", "jvm"]
scalaDeps = [
  ["io.circe", "circe-parser", "0.9.3"]
]
```

This dependency can be only resolved if `circe-parser` is available for Scala.js and JVM. If a dependency is not available for a platform, you can move it to a platform-specific module:

```toml
[module.myModule.jvm]
scalaDeps = [
  ["org.scalaj", "scalaj-http", "2.4.1"]
]
```

On `scalaDeps`, you can specify a fourth parameter for the version tag. The version tag is the actual difference between a Java and Scala dependency.

The Scala naming conventions stipulate that a suffix be added to the artefact name. Thus, `scalaj-http` becomes [`scalaj-http_2.12`](http://central.maven.org/maven2/org/scalaj/scalaj-http_2.12/).

The previous example could be rewritten as:

```toml
scalaDeps = [
  ["org.scalaj", "scalaj-http", "2.4.1", "platformBinary"]
]
```

The default is `platformBinary` which will suit most libraries. However, some libraries target a specific compiler version or share one artefact with all platforms.

The available options are:

* `binary`: Binary Scala version (e.g. 2.12). This behaves like `full` if the Scala version is a pre-release (e.g. `2.12.8-M3`)
* `full`: Full Scala version (e.g. `2.11.11`)
* `platformBinary`: Platform name including the binary Scala version (`native0.3_2.11`)

`scalaDeps` only works with Scala artefacts. For all other artefacts, you can use `javaDeps`:

```toml
[module.myModule.jvm]
javaDeps = [
  ["com.zaxxer"        , "nuprocess"       , "1.2.3"],
  ["org.apache.commons", "commons-compress", "1.17" ]
]
```

Note that the `javaDeps` setting is only available on JVM projects.

### Test module
To run tests on a certain module, you can add a test module for it:

```toml
[module.myModule.test]
sources   = ["shared/test"]
targets   = ["js", "jvm"]
scalaDeps = [
  ["org.scalatest", "scalatest", "3.0.5"]
]
```

This module is cross-platform and the tests are shared by the two targets.

You can also define a platform-specific test module:

```toml
[module.myModule.test.js]
sources = ["js/test"]
```

If you add a test framework, make sure that `testFrameworks` in `project` contains its qualified class name. Common test frameworks are:

* **ScalaTest:** `org.scalatest.tools.Framework`
* **ScalaCheck:** `org.scalacheck.ScalaCheckFramework`
* **MiniTest:** `minitest.runner.Framework`
* **µTest:** `utest.runner.Framework`

In Bloop, you can run the test suites as follows: `bloop test myModule` which is short for `bloop test myModule-test`. The latter depends on `myModule-js-test` and `myModule-jvm-test`.

Test modules inherit settings from their parents. Therefore, `root` does not need to be set again. IntelliJ will aggregate sources and tests under the same root.

### Module dependencies
Modules can depend on other modules. An example is a full-stack web application with a shared cross-compiled core:

```toml
[module.core]
sources = ["core/src"]
targets = ["js", "jvm"]

[module.webapp.js]
moduleDeps = ["core"]
sources    = ["frontend/src"]

[module.server.jvm]
moduleDeps = ["core"]
sources    = ["server/src"]
```

Setting `moduleDeps` to `core`, gives `webapp` and `server` access to its compiled sources.

`moduleDeps` can refer to any modules available in the scope as long as the target platforms match.

`bloop compile webapp` triggers the compilation of `core-js` whereas `bloop compile server` would compile `core-jvm`.

### Custom build targets
You can specify custom targets on a module, for example to build artefacts (HTML, CSS) or to generate code that dependent modules will compile. A target can run shell commands and main classes.

As an example, we could generate CSS artefacts from SCSS using [Gulp](https://gulpjs.com/). The corresponding entry for spawning the Gulp process would look as follows:

```toml
[module.template.target.scss]
root    = "scss"
command = "yarn run gulp"
```

Since we specified `root`, it will also generate a IDEA module named `template-scss`. We could build the target explicitly with `seed build template:scss`, or simply `seed build template`. If the module `template` has regular Scala targets, the latter would compile their sources too along with all custom targets.

Note that you will have to either use `seed build` or `seed link` since Bloop commands are not aware of your build targets.

When you depend on a module in `moduleDeps`, all of its targets are inherited. Any module that transitively depends on `template`, will run the target command when building.

The current working directory is the path to the module's build file. The environment variable `BUILD_PATH` will point to the build path of the root project. You can access it in the command itself:

```toml
[module.template.target.fonts]
root    = "fonts"
command = "cp -Rv fonts $BUILD_PATH"
```

By default, the process execution is asynchronous. If you need the process to complete first before continuing with the compilation process, you can override the `await` setting which is set to `false` by default:

```toml
[module.template.target.gen-scala]
await   = true
command = "..."
```

If your command has support for a watch mode, you can additionally specify a `watchCommand`:

```toml
[module.template.target.scss]
root         = "scss"
command      = "yarn run gulp"
watchCommand = "yarn run gulp default watch"
```

The watch command is used when the user specifies `--watch`:

* `seed build template:scss --watch`
* `seed link template:scss --watch`

If `--watch` is not provided, Seed will spawn the `command` instead. Note that the `watchCommand` ignores `await` and is always run asynchronously.

Besides spawning commands, you can run any main class defined in the project. For example, to generate a JavaScript file, you could define:

```toml
[module.layouts.target.js-bundle]
root = "layouts"

class = { module = "keyboard:jvm", main = "keyboard.Bundle" }

# or shorter:
# class = ["keyboard:jvm", "keyboard.Bundle"]
```

In addition to `BUILD_PATH`, we also set the environment variable `MODULE_PATH` when running classes. The module path is the root path of the Seed project which contains the referenced module. This environment variable is not set for commands since their current working directory will point to the module path.

For two equivalent examples of using code generation, please refer to these links:

* [Class target](test/custom-class-target/)
* [Command target](test/custom-command-target/)

### Compiler plug-ins
Project and modules can add Scala plug-ins with `compilerDeps`. The setting behaves like `scalaDeps`, but also adds the `-Xplugin` parameter to the Scala compiler when modules are compiled. For example:

```toml
[project]
compilerDeps = [
  ["org.scalameta", "semanticdb-scalac", "4.2.0", "full"]
]

[module.macros.js]
compilerDeps = [
  ["org.scalamacros", "paradise", "2.1.1", "full"]
]
```

Note that project-level plug-ins are inherited by all modules defined under the project, as well as any dependent modules such that `compilerDeps` only needs to be defined on the base project or module.
In the example above, `module.macros.js` inherits the semanticdb plug-in from the *project* and adds a separate dependency on the macro paradise plug-in.

For a complete cross-compiled Macro Paradise example, please refer to [this project](test/example-paradise/).

### Project dependencies
External builds can be imported into the scope by using the `import` setting at the root level. It can point to a build file, or its parent directory in which case it will attempt to load `<path>/build.toml`. From the imported file, Seed will only make its modules accessible. Other project-level settings are being ignored. Multiple projects can be imported as `import` is a list.

Since all modules are imported into the scope, they can be depended on by local modules:

```toml
import = ["foo"]  # Imports `foo/build.toml`

[project]
# ...

[module.bar.jvm]
moduleDeps = ["fooCore"]  # Assuming that the foo project contains a module named fooCore
```

As with regular modules, for each of the imported ones a Bloop file is created. Then, as part of the current build, Bloop will resolve the external project dependencies and compile their sources locally. This is to avoid any binary incompatibilities that could arise if the referenced project used different platform versions or compiler settings.

Note that project dependencies are inherited transitively.

### Paths
#### Directory structure
Seed does not impose any default file structure. Other build tools use the default `src/main/scala` which is unnecessarily verbose and insufficient as soon as cross-platform modules are involved. Therefore, it is entirely up to the user to set a custom structure for the project in Seed.

For projects with only one module, Scala files could be stored in `src/` and tests in `test/`. This change would look as follows:

```toml
[module.app]
root    = "."  # This allows IntelliJ to aggregate the source and test module
sources = ["src"]

[module.app.test]
sources = ["test"]
```

If your project relies on generated Scala/Java files, you can simply add their paths to the `sources` list.

### Resolvers
The default resolvers are as following:

```toml
[resolvers]
maven = ["https://repo1.maven.org/maven2"]
ivy   = []
```

To add an Ivy resolver, the URL must be wrapped in a list:

```toml
[resolvers]
ivy = [
  ["https://repo.typesafe.com/typesafe/ivy-releases"]
]
```

The reason for this is that the Ivy pattern may be omitted. It is short for:

```toml
[resolvers]
ivy = [
  {
    url     = "https://repo.typesafe.com/typesafe/ivy-releases",
    pattern = "[organisation]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]"
  }
]
```

### Sample configurations
You can take some inspiration from the following projects:

* [toml-scala](https://github.com/sparsetech/toml-scala/blob/master/build212.toml) (CI [configuration](https://github.com/sparsetech/toml-scala/blob/master/.drone.yml), [demo](http://ci.sparse.tech/sparsetech/toml-scala))

If you have any Seed configurations you deem worth sharing, please feel free to add them to this list!

## Seed configuration
Seed has a global configuration file which is stored in `~/.config/seed.toml`.

### tmpfs
By default, all modules are compiled to the `build/` directory within the project folder.

On Linux, you can compile your entire project in memory to improve speed and reduce disk wear out. This feature was modelled after [sbt-tmpfs](https://github.com/cuzfrog/sbt-tmpfs).

You can enable it for your builds with the `--tmpfs` flag, or use a global setting:

```toml
[build]
tmpfs = true
```

This setting is also honoured by the generated IntelliJ project.

### Artefact resolution
The following global settings are available for artefact resolution:

```toml
[resolution]
# Do not show the downloaded artefacts
# Useful to shorten logs in CI pipelines
silent = false

# Ivy path
# Can be also set with --ivy-path
ivyPath = "/home/user/.ivy2/local"

# Artefact cache path
# Can be also set with --cache-path
cachePath = "/home/user/.cache/coursier/v1"

# Fetch JavaDoc and source artefacts for all library dependencies. Then,
# populate the resolution section in Bloop. This setting is only needed if you
# use Bloop with IDEs, e.g. Metals or IntelliJ.
# See also https://scalacenter.github.io/bloop/docs/build-tools/sbt#download-dependencies-sources
#
# If you generate an IDEA project, these artefacts will always be downloaded.
optionalArtefacts = false
```

The default values are indicated.

### CLI settings
In the `cli` section, you can find output-related configuration settings:

```toml
[cli]
# Log level
# Possible values: debug, warn, info, error, silent
level = "debug"

# Use Unicode characters to indicate log levels
unicode = true

# Show progress bars when compiling modules
progress = true
```

The default values are indicated.

## Git
### .gitignore
For a Seed project, `.gitignore` only needs to contain these four directories:

```
/.idea/
/.bloop/
/.metals/

/build/
```

### Dependencies
If another build uses Seed and Git for versioning, there is no need for publishing `SNAPSHOT` dependencies. Instead, Git modules would be a simpler solution. All dependencies could be fetched in a subfolder of the current project.

```shell
$ cat .gitmodules
[submodule "otherProject"]
  path = otherProject
  url = https://github.com/org/otherProject.git
```

Then, use the following command to fetch the dependencies:

```shell
$ git submodule update --init --recursive
```

Finally, this project can be embedded using the `include` setting in Seed.

This approach is easier to manage in CI setups as Git modules track a specific commit, whereby the builds are reproducible. Git modules also work with transitive dependencies (`--recursive`).

## Usage
Seed delegates the compilation phase to an external tool. Bloop is an intuitive build server that runs on the same machine as a background service. Its architectural design allows for shorter compilation cycles and lower overall memory consumption when working on multiple projects at the same time.

### Building, linking and running
After having created a Bloop project with `seed bloop`, you can compile, link and run modules directly from Seed:
* `seed build <module>`  This will compile all platform modules
* `seed link <module>`   This will link all platform modules which support linking (JavaScript and Native)
* `seed run <module>`    This will run a compatible platform module (JVM, JavaScript and Native)

You can select a specific platform:
* `seed build <module>:js`  This will compile only the JavaScript module
* `seed link <module>:js`   This will link only the JavaScript module
* `seed run <module>:js`    This will run only the JavaScript module

If you defined a [custom build target](#custom-build-targets), you can use the same syntax to build it:
* `seed build <module>:<target>`

If you run Seed in [server mode](#server-mode), you can connect to your remote Seed instance using the `--connect` parameter:
* `seed build --connect <module>` Trigger compilation on remote Seed instance
* `seed link --connect <module>`  Trigger linking on remote Seed instance
* `seed run --connect <module>`   Trigger running on remote Seed instance

Also, run `seed --help` to acquaint yourself with all the available commands.

As Seed creates a regular Bloop project, the official Bloop CLI can be used as well:

```shell
bloop compile <module>      # Compile module
bloop run <module>          # Run main class
bloop run <module> --watch  # Watch for source changes, recompile and restart
bloop test <module>         # Run test cases
```

For more detailed information, please refer to the official [Bloop user guide](https://scalacenter.github.io/bloop/).

### Server mode
You can run Seed in server mode. By default, it will listen to JSON commands on the WebSocket server `localhost:8275`. It supports several commands:

- Linking modules
- Publishing build status events

You could use the server as a message bus. You can trigger a link from the IDE or the command line. At the same time, there can be multiple event listeners subscribing to build status events. This is useful if you develop a Scala.js application and want to reload the website in the browser after each build.

This can be achieved in three steps:

1. Run `seed server` in the background
2. If you use IntelliJ, configure "Run/Debug Configuration":
  - Create template from "Bash"
    - Script: `/home/user/bin/seed`
    - Interpreter path: `/bin/sh`
    - Program arguments: `link --connect <module>`
    - Working directory: `<project path>`
3. In your Scala.js application, run this code upon start-up:

```scala
/** Subscribe to build notifications. Whenever any module was linked, evaluate
  * `onLinked`.
  */
def watch(onLinked: => Unit): Unit = {
  val client = new WebSocket("ws://localhost:8275")
  client.onopen = _ =>
    client.send(JSON.stringify(new js.Object { val command = "buildEvents" }))
  client.onmessage = message => {
    val event =
      JSON.parse(message.data.asInstanceOf[String]).event.asInstanceOf[String]
    if (event == "linked") onLinked
  }
}

watch(dom.window.location.reload())
```

Press Shift-F10 in IntelliJ to trigger the build. Bloop's output is forwarded and shown in the same window. Once the linking is done, the page in the browser will reload.

## IntelliJ IDEA
Seed has the ability to generate IDEA configurations. It copes better with cross-platform projects than IDEA's sbt support. It has the additional benefit of being faster and having a lower memory consumption since no sbt instance needs to be spawned.

If you are replacing an existing sbt project or creating a new IDEA project with Seed, make sure IDEA is not started and the project loaded. Otherwise, refreshing IDEA projects is as simple as running `seed idea`. IntelliJ will pick up the changes immediately and does not need to be restarted.

When you generate a configuration for IDEA, Seed will also fetch Javadoc and source files as part of the dependency resolution.

A common problem with imported cross-platform projects in IntelliJ is that tests cannot be run and code highlighting for shared modules does not work. Seed works around these bugs by setting up the dependencies correctly (from `<module>-jvm` to `<module>` and vice-versa). Although IDEA will show an error in the settings because it detected a circular dependency, but code highlighting and compilation will still work fine.

The Seed integration also supports compilation to tmpfs which does not suffer from [this bug](https://youtrack.jetbrains.com/issue/SCL-13729).

### File structure and root path
While Bloop does not impose any limitations on how you structure your project files, IDEA does. For a Seed project to be valid in IDEA, a module's source and test files must have a common ancestor. If you have the directories `<module>/src/main/scala` and `<module>/src/test/scala`, `<module>/` would be their common ancestor. By contrast, this structure is not supported by IDEA: `src/<module>` and `test/<module>`.

The common ancestor is the module's *root path*. It needs to be set manually for each module using the `root` option.

```toml
[module.myModule]
root    = "myModule/"
sources = ["myModule/src/main/scala"]
```

Here, the `root` path was chosen to be `myModule/` since this folder may also contain other non-source files.

### Project dependencies
If you embed external projects using `import`, all external modules will be made part of the same IDEA project. This has the advantage that only one IDEA instance needs to be opened. As IDEA does not group those modules by subproject, it is advisable to use a prefix all module names from external projects.

This allows to run tests from different projects and make changes that span beyond project boundaries as well as the ability to use IDEA's refactoring tools.

### JavaScript/native support
IDEA does not support starting JavaScript/native projects or running their tests. However, other features such as syntax highlighting or refactoring work fine.

There are two related issues tracking the testing problems: [issue SCL-8972](https://youtrack.jetbrains.com/issue/SCL-8972) and [issue 743](https://github.com/scalatest/scalatest/issues/743).

As a workaround, you can open a terminal within IntelliJ and use Bloop, for example: `bloop test <module>-js`

## Packaging
In order to distribute your project, you may want to package the compiled sources. The approach chosen in Seed is to bundle them as a JAR file.

To build and package the module `demo`, use the following command:

```shell
seed package demo
```

The default output path is `dist/`.

If the JAR file is supposed to have an entry point, make sure to specify `mainClass` on the module in your build file. Also, note that at the moment, only JVM modules can be packaged.

The library dependencies can be optionally bundled as separate files in the `dist/` folder, with the class path of the JAR file correctly set up. This allows you to have a self-contained version of your project for distribution. You can do so by specifying the `--libs` parameter:

```shell
seed package demo --libs
```

If the module `demo` has a main class, you can run your program as follows:

```shell
java -jar dist/demo.jar
```

### Uber JARs
The approach of bundling dependencies as external files (commonly called Uber JARs or fat JARs) over to including them in the JAR has several advantages which are outlined [here](https://imagej.net/Uber-JAR).

In short, merging external JARs is non-trivial and oftentimes requires the intervention of the developer. For example, [sbt-assembly](https://github.com/sbt/sbt-assembly) needs custom Scala code to resolve conflicts.

Also, Uber JARs are large in size and it becomes difficult to share dependencies across different build versions and other projects on the client side.

Finally, for clients it becomes harder to obtain a list of dependencies and their licences used in the application.

## Updating
There is a command to check for version updates. It attempts to find suitable versions such that there are no incompatibilities between artefacts. For instance, for specific platforms a different version of a library may be required. You can run the command as follows:

```shell
seed update
```

If you would like to use pre-release versions, you can also pass in this parameter:

```shell
seed update --pre-releases
```

## Performance
On average, Bloop project generation and compilation are roughly 3x faster in Seed compared to sbt in non-interactive mode. Seed's startup is 10x faster than sbt's.

Hyperfine 1.5.0 was used for the benchmarks. All tests ran on a [Hetzner CX21](https://www.hetzner.com/cloud) ([cpuinfo](https://gist.github.com/tindzk/ecc4422e406e4cf109fc891992d3bd03)) under Docker. The test project was [toml-scala](https://github.com/sparsetech/toml-scala). You can find the benchmark specification [here](.drone.yml) in order to reproduce the results.

### Startup time
Seed ran 10.56 ± 1.75 times faster than sbt.

| Command | Mean [s] | Min…Max [s] |
|:---|---:|---:|
| `sbt exit` | 16.795 ± 0.995 | 15.230…18.097 | 
| `seed help` | 1.591 ± 0.247 | 1.099…1.879 |

### Generating Bloop configuration
Seed ran 2.95 ± 0.26 times faster than sbt.

| Command | Mean [s] | Min…Max [s] |
|:---|---:|---:|
| `sbt bloopInstall` | 18.540 ± 1.135 | 16.923…19.986 |
| `seed bloop` | 6.286 ± 0.404 | 5.848…7.028 | 

### Compiling project
Bloop ran 3.45 ± 4.08 times faster than sbt.

| Command | Mean [s] | Min…Max [s] |
|:---|---:|---:|
| `sbt "; clean; compile"` | 61.720 ± 8.480 | 56.895…85.524 |
| `bloop clean --propagate --cascade &&` | 17.894 ± 21.018 | 9.193…77.436 | 
| `bloop compile toml-js toml-jvm toml-native` | | |

This benchmark tested the compilation speed of JVM and JavaScript for Scala 2.12 and native for Scala 2.11.

## Limitations
### Plug-ins
Seed does not offer any capability for writing plug-ins. If you would like to react to build events, you could use Seed in the server mode. To model the equivalent of sbt's tasks in Seed, you can define a separate module with [custom build targets](#custom-build-targets).

If some settings of the build are dynamic, you could write a script to generate TOML files from a template. A use case would be to cross-compile your modules for different Scala versions. Cross-compilation between Scala versions may require code changes. It is thinkable to have the `build.toml` point to the latest supported Scala version and have scripts that downgrade the sources, e.g. using a tool like [scalafix](https://scalacenter.github.io/scalafix/).

### Publishing
Publishing libraries is not possible yet, but Bintray/Sonatype support is planned for future versions.

## Design Goals
The three overarching design goals were usability, simplicity and speed. The objective for Seed is to offer a narrow, but well-designed feature set that covers most common use cases for library and application development. This means leaving out features such as plug-ins, shell, tasks and code execution that would have a high footprint on the design.

On the other hand, Seed takes in features that improve a developer's workflow and would require external plug-ins otherwise. For example, the creation of initial projects or checking of updates are not essential features for a build tool, but improve the user experience and reduce time. Such features also take less work to implement than maintaining a plug-in infrastructure.

Seed was designed from the ground up to be cross-platform. Furthermore, Seed does not impose much structure on your project, a folder structure or a certain way of handling modules (mono repositories vs. Git modules vs. published artefacts).

## Contributing
Every new feature should fit into the architecture outlined in the design goals. Particular care should be given to the UI and documentation.

The output could use colours and bold/italic/underlined text. Also, components such as tables or trees may improve user experience. The user should not see any Scala errors, but instead human-readable error messages. As an example, invalid settings in the TOML are recognised and pointed out with a trace:

```
[info] Loading project ./build.toml...
[error] The TOML file could not be parsed
[error] Message: String expected, Num(42) provided
[error] Trace: module → targets
```

## Credits
Seed achieves its simplicity by delegating much of the heavy lifting to external tools and libraries, notably Bloop and Coursier. The decision of using TOML for configuration and the build schema are influenced by [Cargo](https://github.com/rust-lang/cargo).

## Licence
Seed is licenced under the terms of the Apache v2.0 licence.
