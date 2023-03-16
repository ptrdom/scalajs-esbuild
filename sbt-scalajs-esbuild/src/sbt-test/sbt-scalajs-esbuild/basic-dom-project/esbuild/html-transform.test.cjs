const test = require("node:test")
const assert = require ("node:assert")
const htmlTransform = require("./html-transform.cjs")

test("pass", (t) => {
  const result = htmlTransform.htmlTransform("<html><head><title></title></head><body></body></html>")
  assert.strictEqual(result, "<html><head><title>test</title></head><body></body></html>")
});