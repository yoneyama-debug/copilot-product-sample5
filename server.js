const crypto = require("node:crypto");
const path = require("node:path");
const express = require("express");

const LOG_LEVELS = ["debug", "info", "warn", "error"];
const TERMINAL_DATA_TYPES = new Set([0x00, 0x50, 0x53, 0x54, 0xa0, 0xeb]);
const TIMESTAMP_REGEX = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}$/;
const HEX_REGEX = /^[0-9a-fA-F]+$/;

function log(level, msg, extra = {}) {
  if (!LOG_LEVELS.includes(level)) {
    level = "info";
  }
  const payload = {
    level,
    time: new Date().toISOString(),
    msg,
    ...extra,
  };
  process.stdout.write(`${JSON.stringify(payload)}\n`);
}

function validateConfig(env) {
  const errors = [];

  const parsePositiveInteger = (value, name, defaultValue) => {
    const parsed = Number.parseInt(value ?? defaultValue, 10);
    if (!Number.isInteger(parsed) || parsed < 1) {
      errors.push(`${name} must be a positive integer`);
    }
    return parsed;
  };

  const port = Number.parseInt(env.PORT ?? "3000", 10);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    errors.push("PORT must be an integer between 1 and 65535");
  }

  const host = env.HOST ?? "0.0.0.0";
  if (typeof host !== "string" || host.trim().length === 0) {
    errors.push("HOST must be a non-empty string");
  }

  const nodeEnv = env.NODE_ENV ?? "production";
  if (nodeEnv !== "development" && nodeEnv !== "production") {
    errors.push("NODE_ENV must be development or production");
  }

  const logLevel = env.LOG_LEVEL ?? "info";
  if (!LOG_LEVELS.includes(logLevel)) {
    errors.push("LOG_LEVEL must be debug, info, warn, or error");
  }

  const bodyLimit = env.BODY_LIMIT ?? "64kb";
  if (typeof bodyLimit !== "string" || bodyLimit.trim().length === 0) {
    errors.push("BODY_LIMIT must be a non-empty string");
  }

  const historyMax = parsePositiveInteger(env.HISTORY_MAX, "HISTORY_MAX", "100");
  const sseKeepaliveMs = parsePositiveInteger(
    env.SSE_KEEPALIVE_MS,
    "SSE_KEEPALIVE_MS",
    "30000",
  );
  const sseMaxClients = parsePositiveInteger(
    env.SSE_MAX_CLIENTS,
    "SSE_MAX_CLIENTS",
    "100",
  );
  const rateLimitRps = parsePositiveInteger(
    env.RATE_LIMIT_RPS,
    "RATE_LIMIT_RPS",
    "50",
  );

  const trustProxyRaw = env.TRUST_PROXY ?? "false";
  if (!["true", "false"].includes(String(trustProxyRaw).toLowerCase())) {
    errors.push("TRUST_PROXY must be true or false");
  }
  const trustProxy = String(trustProxyRaw).toLowerCase() === "true";

  if (errors.length > 0) {
    for (const error of errors) {
      log("error", "Invalid environment variable", { error });
    }
    throw new Error("Invalid environment configuration");
  }

  return {
    port,
    host,
    nodeEnv,
    logLevel,
    bodyLimit,
    historyMax,
    sseKeepaliveMs,
    sseMaxClients,
    rateLimitRps,
    trustProxy,
  };
}

const config = validateConfig(process.env);
const app = express();
app.set("trust proxy", config.trustProxy);

function apiError(res, status, code, message) {
  return res.status(status).json({
    error: {
      code,
      message,
    },
  });
}

function decodeDemoData(hex) {
  try {
    const buffer = Buffer.from(hex, "hex");
    const decodedItems = [];
    let offset = 0;

    while (offset < buffer.length) {
      const dataType = buffer[offset];
      offset += 1;

      if (dataType === 0x01 || dataType === 0x02) {
        if (offset + 4 > buffer.length) {
          throw new Error("insufficient float bytes");
        }
        const value = buffer.readFloatBE(offset);
        offset += 4;
        if (!Number.isFinite(value)) {
          throw new Error("non-finite float");
        }
        decodedItems.push({
          type: dataType === 0x01 ? "temperature" : "humidity",
          value,
        });
        continue;
      }

      const restHex = buffer.subarray(offset).toString("hex");
      if (TERMINAL_DATA_TYPES.has(dataType)) {
        decodedItems.push({ type: "bytes", hex: restHex });
      } else {
        decodedItems.push({ type: "unknown", dataType, hex: restHex });
      }
      offset = buffer.length;
    }

    const validItems =
      decodedItems.length > 0 &&
      decodedItems.every((item) => item.type === "temperature" || item.type === "humidity");

    const temperatureItem = validItems
      ? decodedItems.find((item) => item.type === "temperature")
      : null;
    const humidityItem = validItems ? decodedItems.find((item) => item.type === "humidity") : null;

    return {
      temperature: temperatureItem ? temperatureItem.value : null,
      humidity: humidityItem ? humidityItem.value : null,
      decodedItems,
    };
  } catch {
    return { temperature: null, humidity: null, decodedItems: [] };
  }
}

function validateProductionPayload(payload) {
  if (!TIMESTAMP_REGEX.test(payload.timestamp)) {
    return "timestamp must match yyyy-MM-ddTHH:mm:ss.SSS";
  }

  if (!HEX_REGEX.test(payload.data)) {
    return "data must be a hex string";
  }

  if (payload.data.length % 2 !== 0) {
    return "data length must be even";
  }

  if (payload.data.length < 2 || payload.data.length > 154) {
    return "data length must be between 2 and 154";
  }

  if (!Number.isInteger(payload.dataSize)) {
    return "dataSize must be an integer";
  }

  if (payload.dataSize !== payload.data.length / 2) {
    return "dataSize must match data length in bytes";
  }

  if (payload.dataSize < 1 || payload.dataSize > 77) {
    return "dataSize must be between 1 and 77";
  }

  return null;
}

const history = [];
const sseClients = { size: 0 };
const broadcastSensorData = () => {};
const postRateCounters = new Map();

const csp = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'self' data:",
  "connect-src 'self'",
  "object-src 'none'",
  "base-uri 'self'",
].join("; ");

app.use((req, res, next) => {
  const requestId = req.get("X-Request-Id") || crypto.randomUUID();
  req.requestId = requestId;
  res.setHeader("X-Request-Id", requestId);
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("Referrer-Policy", "no-referrer");
  res.setHeader("Content-Security-Policy", csp);
  next();
});

app.use((req, res, next) => {
  const start = process.hrtime.bigint();
  const requestPath = req.originalUrl || req.url;
  log("debug", "Request received", {
    requestId: req.requestId,
    method: req.method,
    path: requestPath,
    ip: req.ip,
  });
  res.on("finish", () => {
    const durationMs = Number(process.hrtime.bigint() - start) / 1_000_000;
    log("info", "access", {
      requestId: req.requestId,
      method: req.method,
      path: requestPath,
      status: res.statusCode,
      durationMs: Number(durationMs.toFixed(3)),
      ip: req.ip,
    });
  });
  next();
});

function requireJsonContentType(req, res, next) {
  if (!req.is("application/json")) {
    log("warn", "Validation failed", {
      requestId: req.requestId,
      code: "UNSUPPORTED_MEDIA_TYPE",
      reason: "Content-Type must be application/json",
    });
    return apiError(
      res,
      415,
      "UNSUPPORTED_MEDIA_TYPE",
      "Content-Type must be application/json",
    );
  }
  next();
}

function limitPostRps(req, res, next) {
  const nowSec = Math.floor(Date.now() / 1000);
  const key = req.ip;
  const current = postRateCounters.get(key);

  if (!current || current.sec !== nowSec) {
    postRateCounters.set(key, { sec: nowSec, count: 1 });
    return next();
  }

  if (current.count >= config.rateLimitRps) {
    log("warn", "Rate limit exceeded", {
      requestId: req.requestId,
      code: "RATE_LIMITED",
      ip: req.ip,
      limit: config.rateLimitRps,
    });
    return apiError(res, 429, "RATE_LIMITED", "Too many requests");
  }

  current.count += 1;
  next();
}

const jsonBodyParser = express.json({ limit: config.bodyLimit });

app.post("/api/data", requireJsonContentType, limitPostRps, jsonBodyParser, (req, res) => {
  const { timestamp, data, dataSize } = req.body ?? {};

  if (
    typeof req.body !== "object" ||
    req.body === null ||
    Array.isArray(req.body) ||
    typeof timestamp !== "string" ||
    typeof data !== "string" ||
    typeof dataSize !== "number"
  ) {
    log("warn", "Validation failed", {
      requestId: req.requestId,
      code: "VALIDATION_ERROR",
      reason: "timestamp, data, dataSize are required",
    });
    return apiError(
      res,
      400,
      "VALIDATION_ERROR",
      "timestamp, data, and dataSize are required with valid types",
    );
  }

  const isCompatibilityTest = timestamp === "TEST" && data === "test" && dataSize === 4;
  if (isCompatibilityTest) {
    return res.status(200).end();
  }

  const validationError = validateProductionPayload({ timestamp, data, dataSize });
  if (validationError) {
    log("warn", "Validation failed", {
      requestId: req.requestId,
      code: "VALIDATION_ERROR",
      reason: validationError,
    });
    return apiError(res, 400, "VALIDATION_ERROR", validationError);
  }

  const decoded = decodeDemoData(data);
  if (decoded.decodedItems.length === 0 && data.length >= 6) {
    log("warn", "Decode failed", {
      requestId: req.requestId,
      code: "DECODE_FAILED",
      dataPrefix: data.slice(0, 16),
    });
  }

  const event = {
    timestamp,
    data,
    dataSize,
    temperature: decoded.temperature,
    humidity: decoded.humidity,
    decodedItems: decoded.decodedItems,
  };

  history.unshift(event);
  if (history.length > config.historyMax) {
    history.pop();
  }

  broadcastSensorData(event);

  return res.status(200).end();
});

app.get("/api/history", (req, res) => {
  res.json(history.slice(0, config.historyMax));
});

app.get("/healthz", (req, res) => {
  res.json({
    status: "ok",
    uptimeSec: Math.floor(process.uptime()),
    sseClients: sseClients.size,
    historyCount: history.length,
    version: "1.0.0",
  });
});

app.all("/api/data", (req, res) => {
  res.setHeader("Allow", "POST");
  return apiError(res, 405, "METHOD_NOT_ALLOWED", "Method not allowed");
});

app.all("/api/history", (req, res) => {
  res.setHeader("Allow", "GET");
  return apiError(res, 405, "METHOD_NOT_ALLOWED", "Method not allowed");
});

app.all("/healthz", (req, res) => {
  res.setHeader("Allow", "GET");
  return apiError(res, 405, "METHOD_NOT_ALLOWED", "Method not allowed");
});

app.use(
  "/drawable",
  express.static(path.join(__dirname, "drawable"), {
    maxAge: "1h",
    setHeaders: (res, filePath) => {
      if (filePath.endsWith(".svg")) {
        res.type("image/svg+xml");
      }
    },
  }),
);

app.use(
  "/",
  express.static(path.join(__dirname, "public"), {
    maxAge: "1h",
    setHeaders: (res, filePath) => {
      if (filePath.endsWith("index.html")) {
        res.setHeader("Cache-Control", "no-cache");
      }
    },
  }),
);

app.use((err, req, res, next) => {
  if (err?.type === "entity.too.large") {
    return apiError(res, 413, "PAYLOAD_TOO_LARGE", "Payload exceeds BODY_LIMIT");
  }

  if (err instanceof SyntaxError && "body" in err) {
    return apiError(res, 400, "INVALID_JSON", "Malformed JSON body");
  }

  return next(err);
});

app.use((req, res) => apiError(res, 404, "NOT_FOUND", "Resource not found"));

app.use((err, req, res, _next) => {
  log("error", "Unhandled error", {
    requestId: req.requestId,
    error: err?.message ?? "unknown",
  });
  return apiError(res, 500, "INTERNAL_ERROR", "Internal server error");
});

const server = app.listen(config.port, config.host, () => {
  log("info", "Server started", {
    requestId: crypto.randomUUID(),
    host: config.host,
    port: config.port,
    nodeEnv: config.nodeEnv,
    logLevel: config.logLevel,
    bodyLimit: config.bodyLimit,
    historyMax: config.historyMax,
    rateLimitRps: config.rateLimitRps,
    trustProxy: config.trustProxy,
  });
});

let shuttingDown = false;
function gracefulShutdown(signal) {
  if (shuttingDown) return;
  shuttingDown = true;
  log("info", "Shutdown signal received", {
    requestId: crypto.randomUUID(),
    signal,
  });

  const forceExitTimer = setTimeout(() => {
    log("warn", "Forced shutdown after timeout", {
      requestId: crypto.randomUUID(),
    });
    process.exit(0);
  }, 10_000);

  server.close(() => {
    clearTimeout(forceExitTimer);
    log("info", "Server stopped gracefully", {
      requestId: crypto.randomUUID(),
    });
    process.exit(0);
  });
}

process.on("SIGINT", () => gracefulShutdown("SIGINT"));
process.on("SIGTERM", () => gracefulShutdown("SIGTERM"));

module.exports = { app, server, log, validateConfig, decodeDemoData, apiError };
