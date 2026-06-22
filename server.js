const crypto = require("node:crypto");
const path = require("node:path");
const express = require("express");

const LOG_LEVELS = ["debug", "info", "warn", "error"];

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

  if (errors.length > 0) {
    for (const error of errors) {
      log("error", "Invalid environment variable", { error });
    }
    throw new Error("Invalid environment configuration");
  }

  return { port, host, nodeEnv, logLevel };
}

const config = validateConfig(process.env);
const app = express();

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
  log("info", "Request received", {
    requestId: req.requestId,
    method: req.method,
    path: requestPath,
    ip: req.ip,
  });
  res.on("finish", () => {
    const durationMs = Number(process.hrtime.bigint() - start) / 1_000_000;
    log("info", "Request completed", {
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

app.use("/", express.static(path.join(__dirname, "public")));

const server = app.listen(config.port, config.host, () => {
  log("info", "Server started", {
    requestId: crypto.randomUUID(),
    host: config.host,
    port: config.port,
    nodeEnv: config.nodeEnv,
    logLevel: config.logLevel,
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

module.exports = { app, server, log, validateConfig };
