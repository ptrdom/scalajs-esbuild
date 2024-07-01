package scalajs.esbuild

object Scripts {

  private[esbuild] def esbuildOptions = {
    // language=JS
    """const esbuildOptions = (
      |  platform,
      |  entryPoints,
      |  outDirectory,
      |  outputFilesDirectory,
      |  hashOutputFiles,
      |  minify,
      |  additionalOptions
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
      |    packages: "bundle",
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
      |    ...publicPathOption,
      |    ...(additionalOptions ? additionalOptions : {})
      |  };
      |}
      |""".stripMargin
  }

  private[esbuild] def bundle = {
    // language=JS
    """const bundle = async (
      |  platform,
      |  entryPoints,
      |  outDirectory,
      |  outputFilesDirectory,
      |  hashOutputFiles,
      |  minify,
      |  additionalEsbuildOptions
      |) => {
      |  const esbuild = require('esbuild');
      |  const fs = require('fs');
      |  const path = require('path');
      |
      |  const result = await esbuild.build(
      |    esbuildOptions(
      |      platform,
      |      entryPoints,
      |      outDirectory,
      |      outputFilesDirectory,
      |      hashOutputFiles,
      |      minify,
      |      additionalEsbuildOptions
      |    )
      |  );
      |
      |  const output = Object.keys(result.metafile.outputs).reduce((acc, key) => {
      |    const output = result.metafile.outputs[key];
      |    if (output.entryPoint) {
      |      return `${acc}${output.entryPoint}${path.delimiter}${key}\n`;
      |    } else {
      |      return acc;
      |    }
      |  }, "");
      |  if (output) {
      |    fs.writeFileSync('sbt-scalajs-esbuild-bundle-output.txt', output);
      |  }
      |
      |  return result.metafile;
      |};
      |""".stripMargin
  }
}
