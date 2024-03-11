# scalajs-esbuild

scalajs-esbuild is a module bundler for Scala.js projects that use npm packages: it bundles the .js files emitted by the
Scala.js compiler with their npm dependencies into a single .js file using [esbuild](https://esbuild.github.io/).

[![sbt-scalajs-esbuild Scala version support](https://index.scala-lang.org/ptrdom/scalajs-esbuild/sbt-scalajs-esbuild/latest.svg)](https://index.scala-lang.org/ptrdom/scalajs-esbuild/sbt-scalajs-esbuild)

## Getting started

Plugin should feel quite familiar to the users of well known [scalajs-bundler](https://scalacenter.github.io/scalajs-bundler),
with the main difference being that there is no special handling of `npmDependencies` - they must be provided through
`package.json` placed within `esbuild` directory in project's base.

Implementation intentionally exposes only a few settings for configuration - provided scripts should be enough to get started with typical projects,
but any advanced configuration should be provided by modifying said scripts through their sbt settings - `esbuildBundleScript` and `esbuildServeScript`.

All sbt tasks that depend on Scala.js stages can be scoped both implicitly and explicitly, for example `esbuildBundle` will use `scalaJSStage` or the stage
can be provided within the command - `fastLinkJS/esbuildBundle`/`fullLinkJS/esbuildBundle`.

Comparing to [scalajs-vite](https://github.com/ptrdom/scalajs-vite), the main difference comes from the fact that [Vite](https://vitejs.dev/) is not a bundler, 
it is a build tool - it uses esbuild in dev server implementation and [Rollup](https://rollupjs.org/) for production bundles, and it has no concept of
development bundles. Because in Scala.js projects these development bundles are actually useful in certain workflows, 
particularly in the implementation of tests, scalajs-esbuild brings esbuild's performance into every aspect of Scala.js development workflow.

### Base plugin

Base plugin is designed for Node or browser libraries, CLIs or standalone scripts - for web apps please use [web plugin](#web-plugin).

#### Basic setup

1. Setup project layout, following is a minimal example:

   ```
   src
     main
      scala
        example
          Main.scala # Scala.js entry point
   esbuild
     package.json # devDependencies must provide esbuild package
   ```

1. Add plugin to sbt project:

   ```scala
   addSbtPlugin("me.ptrdom" % "sbt-scalajs-esbuild" % pluginVersion)
   ```

1. Enable plugin in `build.sbt`:

   ```
   enablePlugins(ScalaJSEsbuildPlugin)
   ```

1. Specify that Scala.js project is an application with an entry point:

   ```
   scalaJSUseMainModuleInitializer := true
   ```

1. Use sbt tasks to compile Scala.js code and run esbuild:
   - `esbuildBundle`
     - Bundles are produced in `/target/${scalaVersion}/esbuild/main/out` directory.
   - `run`/`test`
     - `esbuildBundle` output will be fed to `jsEnvInput`.

See [examples](sbt-scalajs-esbuild/examples) for project templates.

### Web plugin

Web plugin is designed for web apps. Because esbuild does not have a full-fledged build-in dev server and HTML entry point 
transformations like Vite, this plugin attempts to provide good enough stand-ins to enable typical workflows.

#### Basic setup

1. Setup project layout, following is a minimal example:

   ```
   src
     main
      scala
        example
          Main.scala # Scala.js entry point
   esbuild
     index.html # esbuild HTML entry point
     package.json # devDependencies must provide esbuild and parse5 packages
   ```

1. Add plugin to sbt project:

   ```scala
   addSbtPlugin("me.ptrdom" % "sbt-scalajs-esbuild-web" % pluginVersion)
   ```

1. Enable plugin in `build.sbt`:

   ```scala
   enablePlugins(ScalaJSEsbuildWebPlugin)
   ```

1. Specify that Scala.js project is an application with an entry point:

   ```scala
   scalaJSUseMainModuleInitializer := true
   ```

   Such configuration would allow `main.js` bundle to be used in esbuild HTML entry point:

   ```html
   <script src="/main.js"></script>
   ```

   Entry points can be configured with `esbuildBundleHtmlEntryPoints` setting.

1. Use sbt tasks to compile Scala.js code and run esbuild:
    - `esbuildBundle`
      - In addition to base plugin behavior, web implementation also does HTML entry point transformation - injection Scala.js entry points and CSS files into HTML.
    - `esbuildServeStart;~esbuildCompile;esbuildServeStop`
      - Starts dev server on port `8000`.
      - `esbuildCompile` will recompile Scala.js code and update any resources on changes.
      - CSS can be hot reloaded, anything else will cause a page reload.

See [examples](sbt-scalajs-esbuild-web/examples) for project templates.

### Electron plugin

Electron plugin is designed to enable [Electron](https://www.electronjs.org/) app development with Scala.js. Besides
bundling for tests and production builds, the plugin also provides a dev server implementation.

#### Basic setup

1. Setup project layout, following is a minimal example:

   ```
   src
     main
      scala
        example
          Main.scala # main process
          Preload.scala # preload script
          Renderer.scala # renderer process
   esbuild
     index.html # esbuild HTML entry point
     package.json # devDependencies must provide esbuild, parse5 and electron packages
   ```

1. Add plugin to sbt project:

   ```scala
   addSbtPlugin("me.ptrdom" % "sbt-scalajs-esbuild-electron" % pluginVersion)
   ```

1. Enable plugin in `build.sbt`:

   ```scala
   enablePlugins(ScalaJSEsbuildElectronPlugin)
   ```

1. Configure Scala.js entry points and Electron process model components:

   ```scala
   scalaJSModuleInitializers := Seq(
     ModuleInitializer
       .mainMethodWithArgs("example.Main", "main")
       .withModuleID("main"),
     ModuleInitializer
       .mainMethodWithArgs("example.Preload", "main")
       .withModuleID("preload"),
     ModuleInitializer
       .mainMethodWithArgs("example.Renderer", "main")
       .withModuleID("renderer")
   )
   
   Compile / esbuildElectronProcessConfiguration := new EsbuildElectronProcessConfiguration(
     "main",
     Set("preload"),
     Set("renderer")
   )   
   ```

   Such configuration would allow `renderer.js` bundle to be used in esbuild HTML entry point:

   ```html
   <script src="./renderer.js"></script>
   ```

   Entry points can be configured with `esbuildBundleHtmlEntryPoints` setting.

1. Web plugin sbt tasks can be used to compile Scala.js code and run esbuild.

See [examples](sbt-scalajs-esbuild-electron/examples) for project templates.

### Integrating with sbt-web

1. Add plugin to sbt project:

   ```scala
   addSbtPlugin("me.ptrdom" % "sbt-web-scalajs-esbuild" % pluginVersion)
   ```

1. Enable plugin in `build.sbt`:

   ```scala
   lazy val server = project
     .settings(
       scalaJSProjects := Seq(client),
       pipelineStages := Seq(scalaJSPipeline)
     )
     .enablePlugins(SbtWebScalaJSEsbuildPlugin)
    
   lazy val client = project.enablePlugins(ScalaJSEsbuildPlugin)
   ```

See [examples](sbt-web-scalajs-esbuild/examples) for project templates.

## Package managers

Plugins use [npm](https://www.npmjs.com/) by default, but provided `PackageManager` abstraction allows configuration of other
package managers.

```scala
//for yarn
esbuildPackageManager := new PackageManager {
  override def name = "yarn"
  override def lockFile = "yarn.lock"
  override def installCommand = "install"
}

// for pnpm
esbuildPackageManager := new PackageManager {
  override def name = "pnpm"
  override def lockFile = "pnpm-lock.yaml"
  override def installCommand = "install"
}
```

## License

This software is licensed under the MIT license
