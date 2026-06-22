# TS-E1 Analyzer web

TS-E1 センサーデータを受信し、履歴表示・SSE 配信・グラフ表示する Web アプリです。

## 前提

- Node.js 18 以上
- npm

## セットアップ

```bash
npm install
```

## 起動方法

```bash
npm start
```

アクセス先: `http://localhost:3000`

## テスト実行

```bash
npm test
```

## Docker

### ビルド

```bash
docker build -t ts-e1-web .
```

### 起動

```bash
docker run --rm -p 3000:3000 ts-e1-web
```

## 主な環境変数

| 変数名 | 既定値 | 説明 |
|---|---:|---|
| `PORT` | `3000` | リッスンポート |
| `HOST` | `0.0.0.0` | バインドホスト |
| `NODE_ENV` | `production` | 実行環境 (`development` / `production`) |
| `LOG_LEVEL` | `info` | ログレベル (`debug` / `info` / `warn` / `error`) |
| `BODY_LIMIT` | `64kb` | JSON ボディ上限 |
| `HISTORY_MAX` | `100` | 履歴保持件数 |
| `SSE_KEEPALIVE_MS` | `30000` | SSE keep-alive 間隔 (ms) |
| `SSE_MAX_CLIENTS` | `100` | SSE 同時接続上限 |
| `RATE_LIMIT_RPS` | `50` | IP ごとの POST `/api/data` レート上限 (req/s) |
| `TRUST_PROXY` | `false` | `true` で `X-Forwarded-*` を信頼 |
