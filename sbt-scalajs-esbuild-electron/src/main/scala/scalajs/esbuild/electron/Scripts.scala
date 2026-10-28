package scalajs.esbuild.electron

object Scripts {

  private[esbuild] def electronServe = {
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
       |        let respawnPending = false;
       |        const spawnElectron = () => {
       |          electronProcess = {
       |            handle: spawn(electron, [path.join(electronBuildOutputDirectory, mainEntryPoint), '.'], { stdio: 'inherit' }),
       |            closeListener: () => process.exit()
       |          };
       |          electronProcess.handle.on('exit', electronProcess.closeListener);
       |        };
       |        build.onEnd(() => {
       |          if (electronProcess == null) {
       |            spawnElectron();
       |          } else if (!respawnPending) {
       |            // Spawn the new process only once the old one has exited, so
       |            // they don't race for what the old one still holds (ports,
       |            // single-instance locks). Rebuilds landing meanwhile need no
       |            // extra respawn - the new process loads the latest bundle.
       |            respawnPending = true;
       |            electronProcess.handle.removeListener('exit', electronProcess.closeListener);
       |            electronProcess.handle.once('exit', () => {
       |              respawnPending = false;
       |              spawnElectron();
       |            });
       |            electronProcess.handle.kill();
       |          }
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
