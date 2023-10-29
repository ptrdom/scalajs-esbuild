package scalajsesbuild

object EsbuildScripts {

  private[scalajsesbuild] def htmlTransform = {
    // language=JS
    s"""const htmlTransform = (
       |  htmlString,
       |  outDirectory,
       |  meta
       |) => {
       |  const path = require('path');
       |  const jsdom = require("jsdom")
       |  const { JSDOM } = jsdom;
       |
       |  if (!meta.outputs) {
       |    throw new Error('Meta file missing output metadata');
       |  }
       |
       |  const workingDirectory = __dirname;
       |
       |  const toHtmlPath = (filePath) => filePath.split(path.sep).join(path.posix.sep);
       |
       |  const dom = new JSDOM(htmlString);
       |  dom.window.document.querySelectorAll("script").forEach((el) => {
       |    let output;
       |    let outputBundle;
       |    Object.keys(meta.outputs).every((key) => {
       |      const maybeOutput = meta.outputs[key];
       |      if (el.src.endsWith(maybeOutput.entryPoint)) {
       |        output = maybeOutput;
       |        outputBundle = key;
       |        return false;
       |      }
       |      return true;
       |    })
       |    if (output) {
       |     let absolute = el.src.startsWith("/");
       |     el.src = toHtmlPath(el.src.replace(output.entryPoint, path.relative(outDirectory, path.join(workingDirectory, outputBundle))));
       |     if (output.cssBundle) {
       |       const link = dom.window.document.createElement("link");
       |       link.rel = "stylesheet";
       |       link.href = (absolute ? "/" : "") + toHtmlPath(path.relative(outDirectory, path.join(workingDirectory, output.cssBundle)));
       |       el.parentNode.insertBefore(link, el.nextSibling);
       |     }
       |    }
       |  });
       |  return dom.serialize();
       |}""".stripMargin
  }

  private[scalajsesbuild] def esbuildLiveReload = {
    // language=JS
    s"""const esbuildLiveReload = (
       |  htmlString
       |) => {
       |  return htmlString
       |    .toString()
       |    .replace("</head>", `
       |      <script type="text/javascript">
       |        // Based on https://esbuild.github.io/api/#live-reload
       |        new EventSource('/esbuild').addEventListener('change', e => {
       |          const { added, removed, updated } = JSON.parse(e.data)
       |
       |          if (!added.length && !removed.length && updated.length === 1) {
       |            for (const link of document.getElementsByTagName('link')) {
       |              const url = new URL(link.href)
       |
       |              if (url.host === location.host && url.pathname === updated[0]) {
       |                const next = link.cloneNode()
       |                next.href = updated[0] + '?' + Math.random().toString(36).slice(2)
       |                next.onload = () => link.remove()
       |                link.parentNode.insertBefore(next, link.nextSibling)
       |                return
       |              }
       |            }
       |          }
       |
       |          location.reload()
       |        })
       |      </script>
       |    </head>
       |    `);
       |}
       |""".stripMargin
  }

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
      |    outdir: outDirectory,
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
      |    loader: knownAssetTypes.reduce((a, b) => ({...a, [b]: `file`}), {}),
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
      |};
      |""".stripMargin
  }

  private[scalajsesbuild] def transformHtmlEntryPoints = {
    """
      |
      |""".stripMargin
  }
}
