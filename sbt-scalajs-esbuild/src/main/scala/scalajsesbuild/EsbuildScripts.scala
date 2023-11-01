package scalajsesbuild

object EsbuildScripts {

  private[scalajsesbuild] def esbuildOptions = {
    // language=JS
    """const esbuildOptions = (
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
      |      entryPoints,
      |      outDirectory,
      |      outputFilesDirectory,
      |      hashOutputFiles,
      |      minify
      |    )
      |  );
      |
      |  fs.writeFileSync(metaFileName, JSON.stringify(result.metafile));
      |
      |  return result.metafile;
      |};
      |""".stripMargin
  }
}
