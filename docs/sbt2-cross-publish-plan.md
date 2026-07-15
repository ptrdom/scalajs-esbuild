# Cross-publishing scalajs-esbuild for sbt 2

Plan for cross-building and publishing the four plugin modules (`sbt-scalajs-esbuild`, `sbt-scalajs-esbuild-web`, `sbt-scalajs-esbuild-electron`, `sbt-web-scalajs-esbuild`) for both sbt 1.x and sbt 2.x, using [sbt/sbt2-compat](https://github.com/sbt/sbt2-compat).

## Feasibility: green

Every upstream dependency this repo needs already publishes for sbt 2.x, and we are already on the versions that do:

- `sbt-scalajs` 1.22.0 is cross-published for sbt 1.x/2.x since 1.22.0 (already used in `build.sbt` and `project/plugins.sbt`).
- `sbt-web-scalajs` 1.4.0 is cross-built for sbt 1.x/2.x as of v1.4.0 (already used in `build.sbt`); it pulls in sbt-web 1.6.0-M4, which carries sbt 2 support, and requires JDK 17+ under sbt 2 (the Scala Steward runner already moved to JDK 17).
- `sbt2-compat` is at 0.1.0; sbt itself is at 2.0.2.

The publishing plugins (`sbt-ci-release`, `sbt-scalafmt`, `scripted-sbt-sources`) and the local `ExampleVersionPlugin` / `ScriptedSourcesPlugin` all run in the meta-build, which stays on sbt 1.12.13, so they do not need sbt 2 support. Only the four published plugins get cross-built.

## Mechanism

sbt plugin cross-building keys the target sbt version off `scalaBinaryVersion`: Scala 2.12 maps to sbt 1.x, Scala 3 maps to sbt 2.x. Each plugin module gets `crossScalaVersions := Seq("2.12.21", "3.x.y")` plus a `pluginCrossBuild / sbtVersion` mapping. `sbt2-compat` supplies a `sbtcompat.PluginCompat` shim so a single source tree compiles against both. The meta-build sbt version in `project/build.properties` is unchanged.

## Step 1 — build.sbt

Add a shared cross-build block applied to all four plugin modules (not to `scala-steward-hooks`):

```scala
crossScalaVersions := Seq("2.12.21", "3.7.x"), // pin the exact Scala 3 that sbt 2 uses
pluginCrossBuild / sbtVersion := {
  scalaBinaryVersion.value match {
    case "2.12" => "1.5.8"  // floor of the sbt 1 API we support
    case _      => "2.0.2"
  }
},
addSbtPlugin("com.github.sbt" % "sbt2-compat" % "0.1.0")
```

Keep `scalaVersion := "2.12.21"` as the default in `inThisBuild`. Leave `scala-steward-hooks` untouched (it is a Scala.js app with `publish / skip := true`).

## Step 2 — source changes for Scala 3 / sbt 2

The sources are close to cross-compiling already (they use `*` wildcard imports and `Seq[Setting[?]]`). Two confirmed breakages:

- `ScalaJSEsbuildWebPlugin.scala` uses the removed `in` infix: `(onLoad in Global)` becomes the slash syntax `(Global / onLoad)`.
- `SbtWebScalaJSEsbuildPlugin.scala` has `Def.task { mappings: Seq[PathMapping] =>`, which Scala 3 rejects; parenthesize it as `Def.task { (mappings: Seq[PathMapping]) =>`.

Then iterate `sbt +compile` and fix whatever else Scala 3 flags. Likely remaining items: any `x: _*` varargs splices (become `x*`), and the real work in the Scala.js linker report handling in `package.scala` (`unstable.ReportImpl`, `report.publicModules`) and the sbt-web `PathMapping` / `Pipeline` usage, which must compile against the sbt-2 artifacts of those plugins. If any task that produced a `File` output is now modeled by sbt 2 as `HashedVirtualFileRef`, that is where `import sbtcompat.PluginCompat._` and a `FileConverter` come in. A first read suggests the plugin tasks return `FileChanges` / `Seq[Path]` / `Unit` / `String` rather than `File`, so little or none of the compat conversions may be needed. Confirm during compile.

## Step 3 — scripted tests across both sbt versions

This is the largest unknown. Scripted launches each test under the plugin's `pluginCrossBuild / sbtVersion`, so `+scripted` (or the existing `scriptedSequentialPerModule`) would run the example projects under sbt 2 as well. The example and test projects (`examples/**`, generated into `target/generated-sbt-test/**` by `scripted-sbt-sources`) must then work under sbt 2: their `project/plugins.sbt` resolve `sbt-scalajs` / `sbt-web-scalajs` for sbt 2 (fine), but any `in Config` syntax or Scala 2-isms in the example `build.sbt` files need the same treatment as Step 2. Verify that `scripted-sbt-sources` and `ExampleVersionPlugin`'s version regex still generate correct tests when two sbt binary versions are in play.

## Step 4 — CI (test.yml)

The current matrix pins sbt 1.12.13 and includes JDK 8, which is incompatible with sbt 2 (needs JDK 17+). Add a cross dimension: keep the existing JDK 8 / sbt 1 lane as-is, and add a JDK 17+ lane that installs the sbt 2 runner and runs the Scala 3 cross-slice (`sbt ++3.x ... scripted`). The JDK 8 rows must stay sbt-1-only.

## Step 5 — publishing

`sbt-ci-release`'s `ci-release` already runs `+publishSigned`, which honors `crossScalaVersions` and `pluginCrossBuild`, so it cross-publishes both sbt binary versions with no release-workflow change beyond ensuring the release runner is JDK 17+ (consistent with the recent Steward-runner move). Coordinates stay `me.ptrdom % sbt-scalajs-esbuild`; the sbt/Scala suffix differentiates the artifacts.

## Sequencing

1. Add the `build.sbt` cross-build block and `sbt2-compat` on one module (`sbt-scalajs-esbuild`), get `+compile` green.
2. Roll to `-web`, `-electron`, then `sbt-web-scalajs-esbuild` (most likely to need compat shims).
3. Get `+scripted` green locally for one example per module under sbt 2.
4. Wire the CI matrix, then the release workflow.

## Open questions

- Gate on sbt 2.0.2 specifically, or track the latest via Renovate.
- Drop JDK 8 from CI for the sbt 2 lane only (keeping it for sbt 1), or drop JDK 8 entirely.
