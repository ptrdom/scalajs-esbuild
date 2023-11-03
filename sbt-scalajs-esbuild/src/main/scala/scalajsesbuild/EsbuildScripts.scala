package scalajsesbuild

object EsbuildScripts {

  private[scalajsesbuild] def esbuildOptions = {
    // language=JS
    """const esbuildOptions = (
      |  platform,
      |  entryPoints,
      |  outDirectory,
      |  outputFilesDirectory,
      |  hashOutputFiles,
      |  minify
      |) => {
      |  const path = require('path');
      |
      |  const knownAssetTypes = [
      |    // images
      |    'apng',
      |    'png',
      |    'jpe?g',
      |    'jfif',
      |    'pjpeg',
      |    'pjp',
      |    'gif',
      |    'svg',
      |    'ico',
      |    'webp',
      |    'avif',
      |
      |    // media
      |    'mp4',
      |    'webm',
      |    'ogg',
      |    'mp3',
      |    'wav',
      |    'flac',
      |    'aac',
      |    'opus',
      |
      |    // fonts
      |    'woff2?',
      |    'eot',
      |    'ttf',
      |    'otf',
      |
      |    // other
      |    'webmanifest',
      |    'pdf',
      |    'txt'
      |  ];
      |
      |  let minifyOption = {};
      |  if (minify) {
      |    minifyOption = {
      |      minify: true
      |    };
      |  }
      |
      |  let publicPathOption = {};
      |  if (outputFilesDirectory) {
      |    publicPathOption = {
      |      publicPath: '/'
      |    }
      |  }
      |
      |  return {
      |    platform: platform,
      |    entryPoints: entryPoints,
      |    bundle: true,
      |    outdir: path.normalize(outDirectory),
      |    metafile: true,
      |    logOverride: {
      |      'equals-negative-zero': 'silent'
      |    },
      |    logLevel: 'info',
      |    entryNames: path
      |      .join(
      |        outputFilesDirectory ? outputFilesDirectory : '',
      |        `[name]${hashOutputFiles ? '-[hash]' : ''}`
      |      ),
      |    assetNames: path
      |      .join(
      |        outputFilesDirectory ? outputFilesDirectory : '',
      |        `[name]${hashOutputFiles ? '-[hash]' : ''}`
      |      ),
      |    loader: knownAssetTypes.reduce((a, b) => ({...a, [`.${b}`]: `file`}), {}),
      |    ...minifyOption,
      |    ...publicPathOption
      |  };
      |}
      |""".stripMargin
  }

  private[scalajsesbuild] def bundle = {
    // language=JS
    """const bundle = async (
      |  platform,
      |  entryPoints,
      |  outDirectory,
      |  outputFilesDirectory,
      |  hashOutputFiles,
      |  minify,
      |  metaFileName
      |) => {
      |  const esbuild = require('esbuild');
      |  const fs = require('fs');
      |
      |  const result = await esbuild.build(
      |    esbuildOptions(
      |      platform,
      |      entryPoints,
      |      outDirectory,
      |      outputFilesDirectory,
      |      hashOutputFiles,
      |      minify
      |    )
      |  );
      |
      |  if (metaFileName) {
      |    fs.writeFileSync(metaFileName, JSON.stringify(result.metafile));
      |  }
      |
      |  return result.metafile;
      |};
      |""".stripMargin
  }

  private[scalajsesbuild] def bundleByPlatform = {
    // language=JS
    """const bundleByPlatform = async (
      |  entryPointsByPlatform,
      |  outDirectory,
      |  outputFilesDirectory,
      |  hashOutputFiles,
      |  minify
      |) => {
      |  return await Promise
      |    .all(
      |      Object.keys(entryPointsByPlatform).reduce((acc, platform) => {
      |        const platformMetafilePromise =
      |          bundle(
      |            platform,
      |            entryPointsByPlatform[platform],
      |            outDirectory,
      |            outputFilesDirectory,
      |            hashOutputFiles,
      |            minify
      |          )
      |          .then((metafile) => {
      |            return {[platform]: metafile};
      |          });
      |        acc.push(platformMetafilePromise);
      |        return acc;
      |      }, [])
      |    )
      |    .then((results) => results.reduce((acc, result) => ({...acc, ...result}) , {}));
      |};
      |""".stripMargin
  }
}
