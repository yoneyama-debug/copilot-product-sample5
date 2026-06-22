const test = require("node:test");
const assert = require("node:assert/strict");
const request = require("supertest");
const { app, __testing } = require("../server");

function createServer() {
  return new Promise((resolve) => {
    const localServer = app.listen(0, "127.0.0.1", () => resolve(localServer));
  });
}

function closeServer(localServer) {
  return new Promise((resolve, reject) => {
    localServer.close((error) => {
      if (error) {
        reject(error);
        return;
      }
      resolve();
    });
  });
}

test("SVGアセットは200かつimage/svg+xml", async () => {
  __testing.resetState();
  const localServer = await createServer();
  try {
    const paths = ["/drawable/app_icon.svg", "/drawable/thermometer.svg", "/drawable/humidity.svg"];
    for (const path of paths) {
      const response = await request(localServer).get(path);
      assert.equal(response.status, 200);
      assert.match(response.headers["content-type"], /image\/svg\+xml/);
      assert.ok(response.headers["content-security-policy"]);
    }
  } finally {
    await closeServer(localServer);
  }
});

test("chart.umd.min.js は取得できる", async () => {
  __testing.resetState();
  const localServer = await createServer();
  try {
    const response = await request(localServer).get("/vendor/chart.umd.min.js");
    assert.equal(response.status, 200);
    assert.ok(response.text.length > 0);
    assert.ok(response.headers["content-security-policy"]);
  } finally {
    await closeServer(localServer);
  }
});
