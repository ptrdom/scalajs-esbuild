const jsdom = require("jsdom")
const { JSDOM } = jsdom;

const htmlTransform = (htmlString) => {
  const dom = new JSDOM(htmlString);
  dom.window.document.querySelector("title").textContent = "test"
  return dom.serialize();
}

module.exports = { htmlTransform }