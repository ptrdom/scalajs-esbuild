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
      |  entryPointsByPlatform,
      |  outDirectory,
      |  outputFilesDirectory,
      |  hashOutputFiles,
      |  minify
      |) => {
      |  const esbuild = require('esbuild');
      |
      |  return await Promise.all(
      |    Object.keys(entryPointsByPlatform).reduce((acc, platform) => {
      |      const platformMetafilePromise = esbuild
      |        .build(
      |          esbuildOptions(
      |            platform,
      |            entryPointsByPlatform[platform],
      |            outDirectory,
      |            outputFilesDirectory,
      |            hashOutputFiles,
      |            minify
      |          )
      |        )
      |        .then((result) => {
      |          return {[platform]: result.metafile};
      |        });
      |      acc.push(platformMetafilePromise);
      |      return acc;
      |    }, [])
      |  );
      |};
      |""".stripMargin
  }
}
