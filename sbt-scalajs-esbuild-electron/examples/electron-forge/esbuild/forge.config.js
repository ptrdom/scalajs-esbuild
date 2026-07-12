// See https://www.electronforge.io/configuration
module.exports = {
  // esbuild already bundles the app into `out`, so Forge must output
  // elsewhere to avoid clobbering (and recursing into) that directory.
  outDir: "forge-out",
  packagerConfig: {
    // Everything the app needs at runtime is bundled by esbuild into `out`
    // (electron is provided by the runtime), so only `out` and package.json
    // need to be packaged - everything else is build-time scaffolding.
    ignore: (path) =>
      path !== "" && path !== "/package.json" && !/^\/out(\/|$)/.test(path)
  },
  makers: [
    {
      name: "@electron-forge/maker-zip"
    }
  ]
};
