const jsdom = require("jsdom")
const { JSDOM } = jsdom;
const fs = require("fs");
const path = require("path");

const htmlTransform = (htmlString, outDirectory) => {
  const workingDirectory = __dirname;

  const meta = JSON.parse(fs.readFileSync(path.join(__dirname, "meta.json")));

  const dom = new JSDOM(htmlString);
  dom.window.document.querySelectorAll("script").forEach((el) => {
    let output;
    let outputBundle;
    Object.keys(meta.outputs).every((key) => {
      const maybeOutput = meta.outputs[key];
      if (el.src.endsWith(maybeOutput.entryPoint)) {
        output = maybeOutput;
        outputBundle = key;
        return false;
      }
      return true;
    })
    if (output) {
     let absolute = el.src.startsWith("/");
     el.src = el.src.replace(output.entryPoint, path.relative(outDirectory, path.join(workingDirectory, outputBundle)));
     if (output.cssBundle) {
       const link = dom.window.document.createElement("link");
       link.rel = "stylesheet";
       link.href = (absolute ? "/" : "") + path.relative(outDirectory, path.join(workingDirectory, output.cssBundle));
       el.parentNode.insertBefore(link, el.nextSibling);
     }
    }
  });
  return dom.serialize();
}

module.exports = { htmlTransform }