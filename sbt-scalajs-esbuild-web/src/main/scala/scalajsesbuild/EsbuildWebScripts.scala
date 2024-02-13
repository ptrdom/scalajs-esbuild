package scalajsesbuild

object EsbuildWebScripts {

  private[scalajsesbuild] def htmlTransform = {
    // language=JS
    """
      |const htmlTransform = (
      |  htmlString,
      |  outDirectory,
      |  meta
      |) => {
      |  const path = require('path');
      |  const parse5 = require("parse5");
      |
      |  if (!meta.outputs) {
      |    throw new Error('Meta file missing output metadata');
      |  }
      |
      |  const workingDirectory = __dirname;
      |
      |  const toHtmlPath = (filePath) => filePath.split(path.sep).join(path.posix.sep);
      |
      |  const html = parse5.parse(htmlString.toString());
      |
      |  const walkHtml = (node) => {
      |    (node.childNodes ? node.childNodes : []).forEach((childNode, idx, theseChildNodes) => {
      |      if (childNode.tagName === 'script') {
      |        const srcAttr = childNode.attrs.find((attr) => attr.name === 'src');
      |        if (srcAttr) {
      |          let output;
      |          let outputBundle;
      |          Object.keys(meta.outputs).every((key) => {
      |            const maybeOutput = meta.outputs[key];
      |            if (srcAttr.value.endsWith(maybeOutput.entryPoint)) {
      |              output = maybeOutput;
      |              outputBundle = key;
      |              return false;
      |            }
      |            return true;
      |          });
      |          if (output) {
      |            srcAttr.value = toHtmlPath(srcAttr.value.replace(output.entryPoint, path.relative(outDirectory, path.join(workingDirectory, outputBundle))));
      |            if (output.cssBundle) {
      |              const absolute = srcAttr.value.startsWith("/");
      |              const link = {
      |                tagName: 'link',
      |                namespaceURI: parse5.html.NS['HTML'],
      |                attrs: [
      |                  {
      |                    name: 'rel',
      |                    value: 'stylesheet'
      |                  },
      |                  {
      |                    name: 'href',
      |                    value: (absolute ? "/" : "") + toHtmlPath(path.relative(outDirectory, path.join(workingDirectory, output.cssBundle)))
      |                  }
      |                ]
      |              }
      |              theseChildNodes.splice(idx + 1, 0, link);
      |            }
      |          }
      |        }
      |      }
      |      if (childNode.childNodes) {
      |        walkHtml(childNode);
      |      }
      |    })
      |  };
      |  walkHtml(html);
      |
      |  return parse5.serialize(html);
      |}""".stripMargin
  }

  private[scalajsesbuild] def transformHtmlEntryPoints = {
    // language=JS
    """
      |const transformHtmlEntryPoints = (htmlEntryPoints, outDirectory, metafile) => {
      |  const fs = require('fs');
      |  const path = require('path');
      |
      |  htmlEntryPoints
      |   .forEach((htmlEntryPoint) => {
      |      const html = fs.readFileSync(htmlEntryPoint);
      |      const transformedHtml = htmlTransform(html, outDirectory, metafile);
      |      const relativePath = path.relative(__dirname, htmlEntryPoint);
      |      fs.writeFileSync(path.join(outDirectory, relativePath), transformedHtml);
      |    });
      |};
      |""".stripMargin
  }

  // TODO make dev server return this as a script to fix CSP issue in Electron plugin
  private[scalajsesbuild] def esbuildLiveReload = {
    // language=JS
    """const esbuildLiveReload = (
       |  htmlString
       |) => {
       |  return htmlString
       |    .toString()
       |    .replace("</head>", `
       |      <script type="text/javascript" src="/@dev-server/live-reload"></script>
       |    </head>
       |    `);
       |}
       |""".stripMargin
  }

  private[scalajsesbuild] def serve = {
    // language=JS
    """
      |const serve = async (
      |  entryPoints,
      |  outDirectory,
      |  outputFilesDirectory,
      |  metaFileName,
      |  serverPort,
      |  serverProxyPort,
      |  htmlEntryPoints
      |) => {
      |  const http = require('http');
      |  const esbuild = require('esbuild');
      |  const fs = require('fs');
      |  const path = require('path');
      |  const EventEmitter = require('events');
      |
      |  const reloadEventEmitter = new EventEmitter();
      |  
      |  const plugins = [{
      |    name: 'metafile-plugin',
      |    setup(build) {
      |      build.onEnd(result => {
      |        if (!result.metafile) {
      |          console.warn("Metafile missing in build result")
      |          fs.writeFileSync(metaFileName, '{}');
      |        } else {
      |          fs.writeFileSync(metaFileName, JSON.stringify(result.metafile));
      |        }
      |      });
      |    }
      |  }];
      |
      |  const ctx  = await esbuild.context({
      |    ...esbuildOptions(
      |      'browser',
      |      entryPoints,
      |      outDirectory,
      |      outputFilesDirectory,
      |      false,
      |      false
      |    ),
      |    plugins: plugins
      |  });
      |
      |  await ctx.watch();
      |
      |  const { host, port } = await ctx.serve({
      |    servedir: outDirectory,
      |    port: serverPort
      |  });
      |
      |  const proxy = http.createServer((req, res) => {
      |    const metaPath = path.join(__dirname, metaFileName);
      |    let meta;
      |    try {
      |      meta = JSON.parse(fs.readFileSync(metaPath));
      |    } catch (error) {
      |      res.writeHead(500);
      |      res.end('META file ['+metaPath+'] not found');
      |    }
      |
      |    if (meta) {
      |      const forwardRequest = (path) => {
      |        const options = {
      |          hostname: host,
      |          port,
      |          path,
      |          method: req.method,
      |          headers: req.headers
      |        };
      |
      |        const multipleEntryPointsFound = htmlEntryPoints.length !== 1;
      |
      |        if (multipleEntryPointsFound && path === "/") {
      |          res.writeHead(500);
      |          res.end('Multiple html entry points defined, unable to pick single root');
      |        } else {
      |          if (path === '/' || path.endsWith('.html')) {
      |            let htmlFilePath;
      |            if (path === '/') {
      |              htmlFilePath = htmlEntryPoints[0];
      |            } else {
      |              htmlFilePath = path;
      |            }
      |            if (htmlFilePath.startsWith('/')) {
      |              htmlFilePath = `.${htmlFilePath}`;
      |            }
      |
      |            if (fs.existsSync(htmlFilePath)) {
      |              try {
      |                res.writeHead(200, {"Content-Type": "text/html"});
      |                res.end(htmlTransform(esbuildLiveReload(fs.readFileSync(htmlFilePath)), outDirectory, meta));
      |              } catch (error) {
      |                res.writeHead(500);
      |                res.end('Failed to transform html ['+error+']');
      |              }
      |            } else {
      |              res.writeHead(404);
      |              res.end('HTML file ['+htmlFilePath+'] not found');
      |            }
      |          } else if (path === '/@dev-server/live-reload'){
      |            res.writeHead(200);
      |            res.end(`
      |              // Based on https://esbuild.github.io/api/#live-reload
      |              const eventSource = new EventSource('/esbuild')
      |              eventSource.addEventListener('change', e => {
      |                const { added, removed, updated } = JSON.parse(e.data)
      |
      |                if (!added.length && !removed.length && updated.length === 1) {
      |                  for (const link of document.getElementsByTagName('link')) {
      |                    const url = new URL(link.href)
      |
      |                    if (url.host === location.host && url.pathname === updated[0]) {
      |                      const next = link.cloneNode()
      |                      next.href = updated[0] + '?' + Math.random().toString(36).slice(2)
      |                      next.onload = () => link.remove()
      |                      link.parentNode.insertBefore(next, link.nextSibling)
      |                      return
      |                    }
      |                  }
      |                }
      |
      |                location.reload()
      |              });
      |              eventSource.addEventListener('reload', () => {
      |                location.reload();
      |              });
      |            `);
      |          } else {
      |            const proxyReq = http.request(options, (proxyRes) => {
      |              if (proxyRes.statusCode === 404 && !multipleEntryPointsFound) {
      |                // If esbuild 404s the request, assume it's a route needing to
      |                // be handled by the JS bundle, so forward a second attempt to `/`.
      |                return forwardRequest("/");
      |              }
      |
      |              // Otherwise esbuild handled it like a champ, so proxy the response back.
      |              res.writeHead(proxyRes.statusCode, proxyRes.headers);
      |
      |              if (req.method === 'GET' && req.url === '/esbuild' && req.headers.accept === 'text/event-stream') {
      |                const reloadCallback = () => {
      |                  res.write('event: reload\ndata: reload\n\n');
      |                };
      |                reloadEventEmitter.on('reload', reloadCallback);
      |                res.on('close', () => {
      |                  reloadEventEmitter.removeListener('reload', reloadCallback);
      |                });
      |              }
      |              proxyRes.pipe(res, { end: true });
      |            });
      |
      |            req.pipe(proxyReq, { end: true });
      |          }
      |        }
      |      };
      |      // When we're called pass the request right through to esbuild.
      |      forwardRequest(req.url);
      |    }
      |  });
      |
      |  // Start our proxy server at the specified `listen` port.
      |  proxy.listen(serverProxyPort);
      |
      |  console.log(`Started esbuild serve process [http://localhost:${serverProxyPort}]`);
      |
      |  return reloadEventEmitter;
      |};
      |""".stripMargin
  }
}
