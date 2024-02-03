package scalajsesbuild.electron

object Scripts {

  private[scalajsesbuild] def electronServe = {
    // language=JS
    """const electronServe = async (
       |  reloadEventEmitter,
       |  rendererBuildServerProxyPort,
       |  mainEntryPoint,
       |  preloadEntryPoints,
       |  electronBuildOutputDirectory
       |) => {
       |  const path = require('path');
       |  const esbuild = require('esbuild');
       |  const { spawn } = require('node:child_process');
       |  const electron = require('electron');
       |  
       |  await (async function () {
       |    const plugins = [{
       |      name: 'renderer-reload-plugin',
       |      setup(build) {
       |        build.onEnd(() => {
       |          reloadEventEmitter.emit('reload');
       |        });
       |      },
       |    }];
       |
       |    const ctx = await esbuild.context({
       |      entryPoints: preloadEntryPoints,
       |      bundle: true,
       |      outdir: electronBuildOutputDirectory,
       |      logOverride: {
       |        'equals-negative-zero': 'silent',
       |      },
       |      logLevel: "info",
       |      entryNames: '[name]',
       |      assetNames: '[name]',
       |      plugins: plugins,
       |      platform: 'node',
       |      external: ['electron'],
       |    });
       |
       |    ctx.watch();
       |  })();
       |
       |  await (async function () {
       |    const plugins = [{
       |      name: 'main-reload-plugin',
       |      setup(build) {
       |        let electronProcess = null;
       |        build.onEnd(() => {
       |          if (electronProcess != null) {
       |            electronProcess.handle.removeListener('exit', electronProcess.closeListener);
       |            electronProcess.handle.kill();
       |            electronProcess = null;
       |          }
       |          electronProcess = {
       |            handle: spawn(electron, [path.join(electronBuildOutputDirectory, mainEntryPoint), '.'], { stdio: 'inherit' }),
       |            closeListener: () => process.exit()
       |          };
       |          electronProcess.handle.on('exit', electronProcess.closeListener);
       |        });
       |      },
       |    }];
       |
       |    const ctx = await esbuild.context({
       |      entryPoints: [mainEntryPoint],
       |      bundle: true,
       |      outdir: electronBuildOutputDirectory,
       |      logOverride: {
       |        'equals-negative-zero': 'silent',
       |      },
       |      logLevel: "info",
       |      entryNames: '[name]',
       |      assetNames: '[name]',
       |      plugins: plugins,
       |      platform: 'node',
       |      external: ['electron'],
       |    });
       |
       |    ctx.watch();
       |  })();
       |
       |  Object.assign(process.env, {
       |    DEV_SERVER_URL: `http://localhost:${rendererBuildServerProxyPort}`,
       |  })
       |};""".stripMargin
  }
}
