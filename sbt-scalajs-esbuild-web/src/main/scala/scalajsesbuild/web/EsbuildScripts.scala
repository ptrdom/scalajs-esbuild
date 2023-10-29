package scalajsesbuild.web

object EsbuildScripts {

  private[scalajsesbuild] def htmlTransform = {
    // language=JS
    """const htmlTransform = (
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

  private[web] def transformHtmlEntryPoints = {
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
}
