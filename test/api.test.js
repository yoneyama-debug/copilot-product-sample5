const test = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");
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

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function openSseAndReceive(localServer, triggerPost) {
  return new Promise((resolve, reject) => {
    const port = localServer.address().port;
    let settled = false;
    const timer = setTimeout(() => {
      if (!settled) {
        settled = true;
        req.destroy();
        reject(new Error("SSE timeout"));
      }
    }, 3000);

    const req = http.request(
      {
        host: "127.0.0.1",
        port,
        path: "/events",
        method: "GET",
        headers: { Accept: "text/event-stream" },
      },
      (res) => {
        res.setEncoding("utf8");
        let triggered = false;
        let buffer = "";

        res.on("data", (chunk) => {
          buffer += chunk;
          if (!triggered) {
            triggered = true;
            Promise.resolve(triggerPost()).catch((error) => {
              if (!settled) {
                settled = true;
                clearTimeout(timer);
                req.destroy();
                reject(error);
              }
            });
          }

          const match = buffer.match(/event: sensorData\ndata: (.+)\n\n/);
          if (match && !settled) {
            settled = true;
            clearTimeout(timer);
            req.destroy();
            resolve(JSON.parse(match[1]));
          }
        });
      },
    );

    req.on("error", (error) => {
      if (!settled) {
        settled = true;
        clearTimeout(timer);
        reject(error);
      }
    });

    req.end();
  });
}

test("POST /api/data 正常: 200・履歴追加・SSE配信", async () => {
  __testing.resetState();
  const localServer = await createServer();
  try {
    const payload = {
      timestamp: "2026-06-22T00:00:00.000",
      data: "0141c80000",
      dataSize: 5,
    };

    const sseEvent = await openSseAndReceive(localServer, async () => {
      const response = await request(localServer).post("/api/data").send(payload);
      assert.equal(response.status, 200);
    });

    assert.equal(sseEvent.timestamp, payload.timestamp);
    assert.equal(sseEvent.data, payload.data);

    const history = await request(localServer).get("/api/history");
    assert.equal(history.status, 200);
    assert.equal(history.body.length, 1);
  } finally {
    await closeServer(localServer);
  }
});

test("TESTペイロード: 200かつ履歴追加なし", async () => {
  __testing.resetState();
  const localServer = await createServer();
  try {
    const response = await request(localServer).post("/api/data").send({
      timestamp: "TEST",
      data: "test",
      dataSize: 4,
    });
    assert.equal(response.status, 200);

    const history = await request(localServer).get("/api/history");
    assert.equal(history.status, 200);
    assert.equal(history.body.length, 0);
  } finally {
    await closeServer(localServer);
  }
});

test("バリデーション違反と関連エラーコード", async () => {
  __testing.resetState();
  const localServer = await createServer();
  try {
    const invalidCases = [
      {
        req: request(localServer).post("/api/data").set("Content-Type", "text/plain").send("x"),
        status: 415,
        code: "UNSUPPORTED_MEDIA_TYPE",
      },
      {
        req: request(localServer)
          .post("/api/data")
          .set("Content-Type", "application/json")
          .send("{"),
        status: 400,
        code: "INVALID_JSON",
      },
      {
        req: request(localServer).post("/api/data").send({ timestamp: "x", data: "0141c80000", dataSize: 5 }),
        status: 400,
        code: "VALIDATION_ERROR",
      },
      {
        req: request(localServer)
          .post("/api/data")
          .set("Content-Type", "application/json")
          .send({ timestamp: "2026-06-22T00:00:00.000", data: `${"a".repeat(131072)}`, dataSize: 65536 }),
        status: 413,
        code: "PAYLOAD_TOO_LARGE",
      },
    ];

    for (const item of invalidCases) {
      const response = await item.req;
      assert.equal(response.status, item.status);
      assert.equal(response.body.error.code, item.code);
    }
  } finally {
    await closeServer(localServer);
  }
});

test("GET /api/history は101件目で100件を維持する", async () => {
  __testing.resetState();
  const localServer = await createServer();
  try {
    for (let index = 0; index < 101; index += 1) {
      if (index > 0 && index % 45 === 0) {
        await sleep(1000);
      }
      const second = String(index % 60).padStart(2, "0");
      const milli = String(index % 1000).padStart(3, "0");
      const response = await request(localServer).post("/api/data").send({
        timestamp: `2026-06-22T00:00:${second}.${milli}`,
        data: "0141c80000",
        dataSize: 5,
      });
      assert.equal(response.status, 200);
    }

    const history = await request(localServer).get("/api/history");
    assert.equal(history.status, 200);
    assert.equal(history.body.length, 100);
  } finally {
    await closeServer(localServer);
  }
});

test("GET /healthz は200を返す", async () => {
  __testing.resetState();
  const localServer = await createServer();
  try {
    const response = await request(localServer).get("/healthz");
    assert.equal(response.status, 200);
    assert.equal(response.body.status, "ok");
  } finally {
    await closeServer(localServer);
  }
});

test("未知パス404 / 未対応メソッド405", async () => {
  __testing.resetState();
  const localServer = await createServer();
  try {
    const notFound = await request(localServer).get("/unknown");
    assert.equal(notFound.status, 404);
    assert.equal(notFound.body.error.code, "NOT_FOUND");

    const methodNotAllowed = await request(localServer).get("/api/data");
    assert.equal(methodNotAllowed.status, 405);
    assert.equal(methodNotAllowed.body.error.code, "METHOD_NOT_ALLOWED");
  } finally {
    await closeServer(localServer);
  }
});
