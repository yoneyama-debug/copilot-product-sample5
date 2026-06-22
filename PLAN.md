# TS-E1 Analyzer Web アプリ 実装計画

SPEC.md の仕様に基づいて 5 フェーズで実装する。
各フェーズは完了確認（Done Criteria）を満たしてから次フェーズへ進む。

---

## フェーズ全体概観

```
Phase 1: プロジェクト基盤セットアップ（ディレクトリ・依存・静的配信）
Phase 2: バックエンド API 実装（POST /api/data・デコード・履歴・ヘルスチェック）
Phase 3: SSE リアルタイム通信実装（GET /events・ブロードキャスト）
Phase 4: フロントエンド実装（UI・グラフ・カード選択・テーブル）
Phase 5: テスト・本番強化（単体/統合テスト・Dockerfile・受け入れ確認）
```

---

## Phase 1 — プロジェクト基盤セットアップ

### 目的
空のワークスペースから、サーバーが起動して静的ファイルを配信できる最小構成を作る。

### 作業内容

#### 1-1. ディレクトリ構造の作成
```
project-root/
├── package.json
├── server.js
├── drawable/
│   ├── app_icon.svg        ← 既存ファイルをプロジェクトルートに配置
│   ├── thermometer.svg
│   └── humidity.svg
└── public/
    ├── index.html          ← 骨格のみ（空ページ）
    ├── style.css           ← 空ファイル
    ├── app.js              ← 空ファイル
    └── vendor/
        └── chart.umd.min.js  ← Chart.js を公式サイトから取得して配置
```

#### 1-2. package.json の作成
- SPEC §23 の確定内容をそのまま使用
- `npm install` で `express@^4.19.2` をインストール

#### 1-3. server.js の骨格実装（静的配信のみ）
```
・環境変数 PORT / HOST / NODE_ENV / LOG_LEVEL を読み込む（SPEC §16）
・起動時に環境変数のバリデーションを実施（不正値は起動を中止）
・express.static で public/ を / に配信
・express.static で drawable/ を /drawable/ に配信
・GET / → public/index.html
・GET /drawable/* → image/svg+xml で配信
・セキュリティヘッダを全レスポンスに付与（SPEC §12）
  - X-Content-Type-Options: nosniff
  - Referrer-Policy: no-referrer
  - Content-Security-Policy（SPEC §12 の設定値）
・SIGTERM / SIGINT でグレースフルシャットダウン（SPEC §20）
```

#### 1-4. index.html の骨格作成
- DOCTYPE / lang / charset / viewport のみ設定
- `<script src="/vendor/chart.umd.min.js">` のロードを確認

#### 1-5. 構造化ログの基盤実装
- `log(level, msg, extra)` ヘルパーを server.js に実装（stdout へ 1 行 JSON 出力）
- `requestId` は `crypto.randomUUID()` で採番

### 完了確認（Done Criteria）
- [ ] `npm install` がエラーなく完了する
- [ ] `npm start` でサーバーが起動し、ポート 3000 で応答する
- [ ] `curl http://localhost:3000/` が HTML を返す
- [ ] `curl http://localhost:3000/drawable/app_icon.svg` が `Content-Type: image/svg+xml` で返る
- [ ] `curl http://localhost:3000/drawable/thermometer.svg` が `Content-Type: image/svg+xml` で返る
- [ ] `curl http://localhost:3000/drawable/humidity.svg` が `Content-Type: image/svg+xml` で返る
- [ ] `curl http://localhost:3000/vendor/chart.umd.min.js` が JS ファイルを返す
- [ ] レスポンスヘッダに `X-Content-Type-Options: nosniff` が含まれる
- [ ] `PORT=8080 npm start` で 8080 ポートで起動する
- [ ] `Ctrl+C` でプロセスが正常終了する

---

## Phase 2 — バックエンド API 実装

### 目的
POST /api/data のバリデーション・デコード・履歴保存と、GET /api/history・GET /healthz を実装する。
SSE 配信はまだ行わない（後フェーズ）。

### 作業内容

#### 2-1. リクエストボディパーサーの設定
- `express.json({ limit: process.env.BODY_LIMIT || '64kb' })` をミドルウェアに設定
- Content-Type 不正（JSON 以外）→ 415 を返すミドルウェアを追加
- ボディ超過 → 413 を返すエラーハンドラを追加

#### 2-2. エラーレスポンスヘルパーの実装
SPEC §19 のスキーマ `{ error: { code, message } }` を返す `apiError(res, status, code, message)` を実装。

対応コード:
| code | HTTP |
|------|------|
| `INVALID_JSON` | 400 |
| `VALIDATION_ERROR` | 400 |
| `NOT_FOUND` | 404 |
| `METHOD_NOT_ALLOWED` | 405 |
| `PAYLOAD_TOO_LARGE` | 413 |
| `UNSUPPORTED_MEDIA_TYPE` | 415 |
| `RATE_LIMITED` | 429 |
| `INTERNAL_ERROR` | 500 |
| `SSE_CAPACITY` | 503 |

#### 2-3. decodeDemoData(hex) の実装
SPEC §7 の DemoFormatUtil 準拠ロジックを実装。

```javascript
function decodeDemoData(hex) {
  // hex → Uint8Array に変換
  // buffer が空になるまでループ:
  //   type byte を読む
  //   0x01 → 次の 4 byte を IEEE754 Big Endian float32 として温度取得
  //   0x02 → 次の 4 byte を IEEE754 Big Endian float32 として湿度取得
  //   0x00,0x50,0x53,0x54,0xA0,0xEB → 残りを bytes として消費・ループ終了
  //   その他 → 残りを unknown として消費・ループ終了
  //   float32 読み取り時にバイト不足 → throw（デコード失敗）
  // decodedItems を返す
}
```

返り値: `{ temperature, humidity, decodedItems }`

- NaN / Infinity / -Infinity は null 扱い（SPEC §7.2.1）
- 抽出規則: decodedItems が 1 件以上かつ全要素が temperature/humidity のみの場合のみ有効値（SPEC §7.2）
- 複数温度/湿度がある場合は最初の出現値を採用（SPEC §7.2.1）
- デコード失敗時は `{ temperature: null, humidity: null, decodedItems: [] }`

#### 2-4. POST /api/data の実装
SPEC §6.1 のバリデーション順序を厳守：
1. Content-Type チェック → 415
2. JSON パース失敗 → 400 (INVALID_JSON)
3. 必須フィールド欠落 / 型不一致 → 400 (VALIDATION_ERROR)
4. TEST 互換判定（先行評価）→ 200 を即時返却
5. 本番バリデーション（timestamp 正規表現 / hex 正規表現 / 偶数長 / data.length 2〜154 / dataSize 一致 / dataSize 1〜77）→ 400
6. decodeDemoData を呼び出す
7. history 配列の先頭に追加（長さ > HISTORY_MAX なら末尾削除）
8. SSE ブロードキャスト（Phase 3 で実装。この時点ではスタブで OK）
9. 200 を返す（ボディなし）

**history 配列のデータ構造:** SPEC §8 の SensorEvent 型

#### 2-5. GET /api/history の実装
- `history.slice(0, HISTORY_MAX)` を JSON で返す
- history は既に新しい順に格納されているので slice するだけ

#### 2-6. GET /healthz の実装（SPEC §18.1）
```json
{ "status": "ok", "uptimeSec": ..., "sseClients": ..., "historyCount": ..., "version": "1.0.0" }
```

#### 2-7. 404 / 405 ハンドラの実装
- 既知パスへの未対応メソッド → 405 + `Allow` ヘッダ
- 未知パス → 404 (NOT_FOUND)

#### 2-8. レート制限の実装（SPEC §16: RATE_LIMIT_RPS）
- POST /api/data にのみ適用
- IP ごとの RPS を Map でカウント、超過時は 429 (RATE_LIMITED)
- TRUST_PROXY=true の場合は `X-Forwarded-For` から IP を取得

#### 2-9. アクセスログの実装（SPEC §17）
- 各リクエストに対して `{ level: "info", time, msg: "access", method, path, status, durationMs, ip, requestId }` を出力

### 完了確認（Done Criteria）
- [ ] `curl -X POST http://localhost:3000/api/data -H "Content-Type: application/json" -d '{"timestamp":"TEST","data":"test","dataSize":4}'` が 200 を返す
- [ ] `curl -X POST http://localhost:3000/api/data -H "Content-Type: application/json" -d '{"timestamp":"2026-05-15T12:34:56.789","data":"0141c80000","dataSize":5}'` が 200 を返す
- [ ] 上記POST後に `curl http://localhost:3000/api/history` が `temperature: 25` を含む JSON を返す
- [ ] `curl -X POST http://localhost:3000/api/data -H "Content-Type: application/json" -d '{"timestamp":"2026-05-15T12:34:56.789","data":"0141c8","dataSize":3}'` が 200 を返し、履歴の temperature が null
- [ ] dataSize=78 の POST が 400 を返す
- [ ] Content-Type なしの POST が 415 を返す
- [ ] 不正な JSON の POST が 400 (INVALID_JSON) を返す
- [ ] `curl http://localhost:3000/healthz` が `status: "ok"` を含む JSON を返す
- [ ] `curl http://localhost:3000/unknown` が 404 を返す
- [ ] `curl -X DELETE http://localhost:3000/api/data` が 405 を返す

---

## Phase 3 — SSE リアルタイム通信実装

### 目的
GET /events エンドポイントと POST /api/data からのブロードキャスト機能を実装し、
リアルタイム配信が動作することを確認する。

### 作業内容

#### 3-1. SSE クライアント管理の実装
```javascript
const sseClients = new Set(); // { id, res, timer } のセット

function addSseClient(req, res) {
  // 上限チェック (SSE_MAX_CLIENTS) → 超過は 503
  // SSE ヘッダを送出
  //   Content-Type: text/event-stream
  //   Cache-Control: no-cache
  //   Connection: keep-alive
  // retry: 3000 を送信
  // keepalive タイマー（SSE_KEEPALIVE_MS ごとに ": keep-alive\n\n" を送信）を起動
  // sseClients に登録
  // req.on("close") でクライアント除去・タイマー解放
}
```

#### 3-2. GET /events の実装
- `addSseClient(req, res)` を呼び出すだけ
- SSE_MAX_CLIENTS 超過時は 503 (SSE_CAPACITY) を返す

#### 3-3. ブロードキャスト関数の実装
```javascript
function broadcastSensorData(event) {
  const payload = JSON.stringify(event);
  for (const client of sseClients) {
    try {
      client.res.write(`event: sensorData\ndata: ${payload}\n\n`);
    } catch {
      // 送信失敗クライアントは除去
      sseClients.delete(client);
    }
  }
}
```

#### 3-4. Phase 2 のスタブを実本実装に差し替え
- POST /api/data 正常処理の手順 8「SSE ブロードキャスト」を `broadcastSensorData(event)` に置き換え

#### 3-5. グレースフルシャットダウンの SSE 対応（SPEC §20）
- SIGTERM / SIGINT 受信時に全 SSE クライアントへ `res.end()` を送信
- keepalive タイマーを全て `clearInterval`

#### 3-6. healthz の sseClients 数を実数反映
- Phase 2 で `sseClients: 0` のスタブにしていた箇所を `sseClients.size` に修正

### 完了確認（Done Criteria）
- [ ] ターミナル A で `curl -N http://localhost:3000/events` を実行（SSE 接続）
- [ ] ターミナル B で `curl -X POST http://localhost:3000/api/data -H "Content-Type: application/json" -d '{"timestamp":"2026-05-15T12:34:56.789","data":"0141c80000","dataSize":5}'` を実行
- [ ] ターミナル A の出力に `event: sensorData` と `temperature":25` が表示される
- [ ] `curl http://localhost:3000/healthz` の `sseClients` が接続数を正確に反映する
- [ ] SSE 接続中に `Ctrl+C` でサーバーを停止しても、クライアントがタイムアウトなく切断される
- [ ] SSE_MAX_CLIENTS を超えて接続しようとすると 503 が返る

---

## Phase 4 — フロントエンド実装

### 目的
index.html / style.css / app.js を実装し、SPEC §9 の全 UI 要件を満たす。

### 作業内容

#### 4-1. index.html の HTML 構造実装
SPEC §9.5 のレイアウトに従ってセマンティックな HTML を作成:
```html
<header>
  <img id="logo" src="/drawable/app_icon.svg" alt="TS-E1">
  <h1>TS-E1 Analyzer web</h1>
</header>
<main>
  <div class="left-column">
    <!-- Temperature カード (クリッカブル) -->
    <div id="card-temp" class="sensor-card selected" role="button" tabindex="0">
      <img src="/drawable/thermometer.svg" alt="Temperature">
      <span class="badge badge-temp">Temperature</span>
      <div class="value" id="temp-value">--</div>
      <span class="unit">°C</span>
      <canvas id="mini-chart-temp"></canvas>
    </div>
    <!-- Humidity カード (クリッカブル) -->
    <div id="card-hum" class="sensor-card" role="button" tabindex="0">
      <img src="/drawable/humidity.svg" alt="Humidity">
      <span class="badge badge-hum">Humidity</span>
      <div class="value" id="hum-value">--</div>
      <span class="unit">%</span>
      <canvas id="gauge-hum"></canvas>
    </div>
  </div>
  <div class="right-column">
    <canvas id="chart-temp"></canvas>  <!-- 初期表示 -->
    <canvas id="chart-hum" style="display:none"></canvas>
  </div>
</main>
<section class="data-table">
  <h2>Received data</h2>
  <div class="table-scroll">
    <table>
      <thead>...</thead>
      <tbody id="table-body"></tbody>
    </table>
  </div>
</section>
```

#### 4-2. style.css の実装
SPEC §9.4 のデザイン仕様を CSS 変数で管理:
```css
:root {
  --bg: #0d0d2b;
  --card-bg: #12122a;
  --accent-purple: #6a3de8;
  --accent-cyan: #00d4ff;
  --accent-green: #00ff88;
  --chart-bg: #1a1a3e;
}
```

主要スタイル:
- ページ全体: ダーク背景、sans-serif
- `.sensor-card`: border-radius + `box-shadow: 0 0 10px var(--accent-purple)`（非選択）
- `.sensor-card.selected` (temp): `box-shadow: 0 0 16px var(--accent-cyan)` + 背景を僅かに明るく
- `.sensor-card.selected` (hum): `box-shadow: 0 0 16px var(--accent-green)` + 背景を僅かに明るく
- `.sensor-card`: `cursor: pointer`
- `.value`: monospace フォント、大きなフォントサイズ
- `.table-scroll`: `max-height: 300px; overflow-y: auto`
- 2カラムグリッド: 左 1/3 / 右 2/3

#### 4-3. app.js — データ管理層の実装
```javascript
// 内部バッファ（最大 100 件、新しい順）
const store = {
  history: [],         // SensorEvent[]
  tempBuffer: [],      // { x: "HH:mm:ss", y: number }[] 最大 50 件（null 除外済み）
  humBuffer: [],       // 同上
  miniTempBuffer: [],  // 最大 20 件
};

function addEvent(event) {
  // history 先頭追加（101件超は末尾削除）
  // tempBuffer / humBuffer / miniTempBuffer への追加
  // グラフ・カード・テーブルの更新を呼び出す
}

function extractTime(timestamp) {
  // "2026-05-15T12:34:56.789" → "12:34:56"（再パースしない・単純に substring）
  return timestamp.substring(11, 19);
}
```

#### 4-4. app.js — Chart.js グラフの実装
- ページロード時に温度グラフ・湿度グラフの両方の `Chart` インスタンスを初期化
- SPEC §9.9 の設定値（色・Y軸余白・グリッド線等）を使用
- `chart.data.labels` / `chart.data.datasets[0].data` を更新後に `chart.update('none')` で再描画
- Chart.js が存在しない場合（読み込み失敗）: グラフ領域にエラー表示を出し、カード・テーブルの動作は継続

温度グラフ設定の要点:
```javascript
{
  type: 'line',
  data: { labels: [], datasets: [{ data: [], borderColor: '#ff8c42', pointRadius: 3 }] },
  options: {
    scales: {
      x: { grid: { color: 'rgba(255,255,255,0.1)' } },
      y: {
        afterDataLimits: (axis) => {
          axis.min -= 0.5;
          axis.max += 0.5;
        }
      }
    },
    backgroundColor: '#1a1a3e'
  }
}
```

#### 4-5. app.js — ミニグラフの実装（Canvas 直描画）
- `<canvas id="mini-chart-temp">` に手動で折れ線を描く（Chart.js は使わない、軽量化のため）
- miniTempBuffer（最大 20 件）をもとに `CanvasRenderingContext2D` で折れ線描画

#### 4-6. app.js — 湿度ゲージの実装（Canvas 直描画）
- `<canvas id="gauge-hum">` に手動で円弧ゲージを描く
- 0〜100% → 円弧の開始・終了角を計算し `ctx.arc()` で描画
- 水色 (`#00d4ff`) の stroke

#### 4-7. app.js — カード選択切り替えの実装
```javascript
function selectCard(type) { // type: 'temp' | 'hum'
  // 選択カードに selected クラスを付与・他カードから除去
  // chart-temp / chart-hum の display を切り替え
  // 非表示グラフのデータバッファは内部配列で保持済みなので再描画するだけ
}

// クリックイベント・キーボードイベント（Enter/Space）を設定
document.getElementById('card-temp').addEventListener('click', () => selectCard('temp'));
document.getElementById('card-hum').addEventListener('click',  () => selectCard('hum'));
```

#### 4-8. app.js — SSE 接続の実装
```javascript
let retryCount = 0;
const MAX_RETRY = 10;

function connectSSE() {
  const es = new EventSource('/events');
  es.addEventListener('sensorData', (e) => {
    retryCount = 0;
    addEvent(JSON.parse(e.data));
  });
  es.onerror = () => {
    es.close();
    if (retryCount < MAX_RETRY) {
      retryCount++;
      setTimeout(connectSSE, 3000);
    } else {
      // エラー表示
    }
  };
}
```

#### 4-9. app.js — 初期化処理の実装
```javascript
window.addEventListener('DOMContentLoaded', async () => {
  initCharts();          // Chart.js グラフ初期化
  selectCard('temp');    // デフォルト選択
  await loadHistory();   // GET /api/history → addEvent を呼び出し
  connectSSE();          // SSE 接続開始
});
```

#### 4-10. app.js — Received data テーブルの実装
- `addEvent()` 内でテーブルの先頭行として `<tr>` を挿入（`tbody.insertBefore`）
- 101 件目は最後の `<tr>` を削除
- セルは全て `textContent` で設定（`innerHTML` 禁止）
- Data Size は `${event.dataSize} byte` 形式

### 完了確認（Done Criteria）
- [ ] ブラウザで `http://localhost:3000` を開くと、ダーク背景のページが表示される
- [ ] 左カラムに Temperature カード（thermometer.svg）と Humidity カード（humidity.svg）が表示される
- [ ] ヘッダーに app_icon.svg ロゴと「TS-E1 Analyzer web」テキストが表示される
- [ ] 初期状態で Temperature カードが選択（水色グロウ）、右カラムに Temperature グラフが表示される
- [ ] Humidity カードをクリックすると右カラムが Humidity グラフに切り替わる（緑グロウ）
- [ ] `curl -X POST http://localhost:3000/api/data ...` でデータを送信するとリロードなしにUIが更新される
- [ ] 温度が null のデータはグラフにプロットされない
- [ ] テーブルに新着データが先頭に追加される
- [ ] ブラウザの開発者ツールの Console にエラーがない
- [ ] サーバーを停止→再起動後、ブラウザが 3 秒以内に自動再接続する（10 回まで）

---

## Phase 5 — テスト・本番強化

### 目的
単体テスト・統合テスト・受け入れテストを実施し、Dockerfile を整備して本番稼働に耐える品質を確保する。

### 作業内容

#### 5-1. ユニットテスト（decodeDemoData）
`test/decode.test.js` を作成し、SPEC §22.1 のテストケースを全て実装:

```javascript
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { decodeDemoData } from '../server.js';

// テストケース（SPEC §22.1 より）
const cases = [
  { input: '0141c80000', temp: 25,   hum: null,  desc: '温度のみ' },
  { input: '0242480000', temp: null,  hum: 50,    desc: '湿度のみ' },
  { input: '0141c800000242480000', temp: 25, hum: 50, desc: '温度+湿度連結' },
  { input: '0141c8',      temp: null,  hum: null,  desc: 'float32不足→失敗' },
  { input: '00ff',        temp: null,  hum: null,  desc: '任意データ→null' },
  // 75 byte (5byte × 15) データ
  // 77 byte (任意データ含む) データ
];
```

#### 5-2. 統合テスト（supertest）
`test/api.test.js` を作成し、SPEC §22.2 のテストケースを全て実装:
- POST /api/data 正常 → 200
- TEST ペイロード → 200 かつ履歴・SSE なし
- 各バリデーション違反 → 期待ステータス & error.code
- GET /api/history の件数上限（101 件目確認）
- GET /healthz → 200
- 未知パス → 404 / 未対応メソッド → 405

#### 5-3. 静的アセットテスト（SPEC §22.4）
`test/assets.test.js` を作成:
- 各 SVG が 200 + `image/svg+xml` で返ること
- CSP ヘッダが存在すること
- chart.umd.min.js が取得できること

#### 5-4. Dockerfile の整備（SPEC §21.3）
- SPEC §21.3 のサンプルを `Dockerfile` として作成
- `.dockerignore` で `node_modules` / `.git` / `test/` を除外
- `docker build -t ts-e1-web .` でビルドできることを確認

#### 5-5. Chart.js ファイルの取得・配置
- Chart.js の公式 UMD ビルドを `public/vendor/chart.umd.min.js` に配置
- バージョンは `package.json` の `devDependencies` にメモとして記録（例: `// chart.js@4.x.x`）

#### 5-6. 受け入れテストの手動実施（SPEC §13 の基準 1–10）
以下の確認を手動で行い、全て Pass を確認する:

| # | 確認内容 |
|---|---------|
| 1 | Android demo 実機からデータを送信して 2xx が返る（または curl で代替確認）|
| 2 | TEST ペイロードで Android 互換を確認 |
| 3 | data=0141c80000 送信後 UI に 25.000 °C が表示される |
| 4 | data=0242480000 送信後 UI に 50.000 % が表示される |
| 5 | data=0141c8 送信後 UI が落ちず、温湿度が -- のまま |
| 6 | 101 件目送信後に履歴が 100 件に保たれる |
| 7 | サーバー停止後、ブラウザが自動再接続する |
| 8 | 初期表示で Temperature カードが選択状態 |
| 9 | Humidity カードクリックでグラフ切り替わる |
| 10 | 切り替え後も両カードの数値が更新される |

#### 5-7. npm test コマンドの整備
- `package.json` の `test` スクリプトを更新
  ```json
  "test": "node --test test/**/*.test.js"
  ```
- CI 実行を想定したクリーンな出力を確認

#### 5-8. README.md の作成（任意）
- 起動手順・Docker 起動手順・環境変数一覧を記載

### 完了確認（Done Criteria）
- [ ] `npm test` が全テストケース Pass で完了する
- [ ] `docker build -t ts-e1-web .` が成功する
- [ ] `docker run -p 3000:3000 ts-e1-web` でコンテナが起動し、ブラウザからアクセスできる
- [ ] SPEC §13 の受け入れ基準 1–10 が全て Pass
- [ ] ブラウザの開発者ツールで CSP 違反の警告が出ない
- [ ] `curl -s -o /dev/null -w "%{http_code}" http://localhost:3000/healthz` が `200` を返す

---

## チェックリスト（フェーズ横断）

| Phase | 主要成果物 | 完了 |
|-------|-----------|------|
| 1 | package.json / server.js（静的配信+ログ+環境変数）/ public・drawable 配置 | ☐ |
| 2 | POST /api/data / decodeDemoData / GET /api/history / GET /healthz / 404・405 | ☐ |
| 3 | GET /events / SSE ブロードキャスト / グレースフルシャットダウン | ☐ |
| 4 | index.html / style.css / app.js（全 UI）| ☐ |
| 5 | test/ / Dockerfile / 受け入れテスト全 Pass | ☐ |

---

## 実装上の注意事項

1. **decodeDemoData は server.js からエクスポートすること** — テストから直接インポートできるようにするため
2. **Phase 2 の POST /api/data は Phase 3 完了まで SSE をスタブにする** — `broadcastSensorData` を空関数にしておき、Phase 3 で差し替える
3. **ミニグラフ・ゲージは Chart.js を使わず Canvas 直描画** — Chart.js の初期化失敗の影響を受けないようにする
4. **SVG は `<img>` タグで参照する** — インライン SVG 埋め込みは XSS リスクがあるため禁止
5. **timestamp の文字列操作は substring で行い、Date.parse() を使わない** — タイムゾーンなし文字列を再パースすると環境依存で壊れる（SPEC §24）
6. **dataSize は `Number.isInteger()` で検証する** — JavaScript の `typeof x === 'number'` は浮動小数も通すため
