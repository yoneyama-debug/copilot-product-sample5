# TS-E1 Analyzer Web アプリ 仕様書（実装仕様拡張版）

## 1. 目的

Android アプリ TS-E1 Analyzer（lsimpledev の demo フレーバー）が送信する受信データを、
Web アプリでリアルタイム表示する。

この仕様は、下記ソースコード実装を正とする。

- lsimpledev/src/main/java/jp/co/sstinc/lsimpledev/LSimpleFragment.kt
- lsimpledev/src/main/java/jp/co/sstinc/lsimpledev/LSimpleViewModel.kt
- lsimpledev/src/main/java/jp/co/sstinc/lsimpledev/DemoFormatUtil.kt
- lsimpledev/src/main/java/jp/co/sstinc/net/EventPoster.kt
- lsimpledev/src/main/java/jp/co/sstinc/lsimpledev/ServerSettingsFragment.kt
- lsimpledev/src/test/java/jp/co/sstinc/lsimpledev/DemoFormatUtilKtTest.kt

UI デザイン参考: sample_ui.png

---

## 2. スコープ

- 対象クライアント: TS-E1 Analyzer（demo フレーバー）
- 対象通信: HTTP POST + SSE
- 対象表示: 温度・湿度・受信履歴
- 永続化: サーバー再起動で初期化されるインメモリのみ

非スコープ:

- 認証/認可
- DB 永続化
- internal フレーバー固有表示（fid/sid の詳細表示等）

---

## 3. 技術スタック

| 区分 | 採用技術 |
|------|---------|
| バックエンド | Node.js 18 以上 + Express |
| フロントエンド | HTML5 / CSS3 / Vanilla JavaScript |
| グラフ描画 | Chart.js（本番は `public/vendor/` に同梱、開発時のみ CDN 可） |
| リアルタイム通信 | Server-Sent Events（SSE） |
| 永続化 | インメモリ（最大 100 件保持） |

---

## 4. ディレクトリ構成

```text
project-root/
├── package.json
├── server.js
├── drawable/
│   ├── app_icon.svg
│   ├── thermometer.svg
│   └── humidity.svg
└── public/
    ├── index.html
    ├── style.css
    ├── app.js
    └── vendor/
        └── chart.umd.min.js  # 本番では Chart.js を同梱推奨
```

静的配信:

- `public/` は `/` に配信する。
- `drawable/` は `/drawable/` に配信する。
- SVG アイコンは `<img src="/drawable/app_icon.svg">` のように絶対パスで参照する。
- 本番では Chart.js を `public/vendor/chart.umd.min.js` に同梱して読み込む。CDN 読み込みは開発・試作時のみ許容する。

---

## 5. Android 側送信仕様（demo 実装準拠）

### 5.1 送信トリガー

- LSimpleFragment の onEvent で type == DECODE_OK のときのみ送信する。
- demo フレーバーでは fid == 0b11 かつ sid == 0x00 以外は破棄される。

### 5.2 送信先 URL

- ユーザー設定画面で入力した URL に直接 POST する。
- 推奨設定値: http://<server-host>:3000/api/data

### 5.3 本番送信ペイロード

```json
{
    "timestamp": "2026-05-15T12:34:56.789",
    "data": "0141c80000",
    "dataSize": 5
}
```

- timestamp:
    - 形式: yyyy-MM-dd'T'HH:mm:ss.SSS
    - タイムゾーン情報なし（ローカル日時文字列）
- data:
    - ByteArray を小文字 16 進で連結した文字列
    - 例: 0141c80000
- dataSize:
    - 元 ByteArray の長さ（バイト数）
    - 範囲: **1〜77**（仕様上の最大ペイロード長）
    - data.length / 2 と一致する想定

### 5.4 接続テストペイロード（重要）

Android の「データ送信テスト」は同一 URL に以下を送る。

```json
{
    "timestamp": "TEST",
    "data": "test",
    "dataSize": 4
}
```

この値は本番バリデーション（ISO 8601・16進文字列）に適合しない。
したがってサーバーは互換モードとして上記 TEST ペイロードを受理し、2xx を返すこと。

### 5.5 Android の成功判定

- HTTP ステータスが 2xx なら成功。
- 2xx 以外は失敗扱い（レスポンスボディは参照しない）。
- 同時送信はアプリ側で最大 5 並列に制限される（6 件目以降は送信失敗として扱われる）。

---

## 6. API 仕様

### 6.1 POST /api/data

Android からデータ受信。

#### リクエスト

- Method: POST
- Content-Type: application/json（charset 付き許容）
- Body: JSON

#### 入力スキーマ

| フィールド | 型 | 必須 | 説明 |
|-----------|-----|------|------|
| timestamp | string | 必須 | 本番: yyyy-MM-ddTHH:mm:ss.SSS / テスト: TEST |
| data | string | 必須 | 本番: 16進小文字推奨 / テスト: test |
| dataSize | number | 必須 | 本番: data のバイト長（1〜77） / テスト: 4 |

#### バリデーション

1. Content-Type が JSON 以外 -> 415
2. JSON パース失敗 -> 400
3. 必須フィールド欠落 / 型不一致 -> 400
4. TEST 互換判定（本番判定より**先に**評価する）:
     - `timestamp === "TEST"` かつ `data === "test"` かつ `dataSize === 4`（厳密一致）
     - 200 を返す（ボディなし）
     - 履歴保存はしない
     - SSE 配信はしない
5. 本番判定:
     - `timestamp` が string で、正規表現 `/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}$/` に一致
     - `data` が string で、正規表現 `/^[0-9a-fA-F]+$/` に一致（空文字は不可）
     - `data.length` が偶数（1 文字以上）
    - `data.length` が **2 以上 154 以下**（1〜77 byte を hex 表現した長さ）
     - `dataSize` が number 型（整数）で `dataSize === data.length / 2`
     - `dataSize` が **1 以上 77 以下**（範囲外は 400）
     - 上記 3 フィールド以外の余分なフィールドは無視する
     - いずれか違反で 400（§19 のエラースキーマで理由を返す）
6. リクエストボディが `BODY_LIMIT`（既定 64KB）超過 -> 413

#### 正常時処理

1. data をデコードし、temperature/humidity を計算
2. history 先頭に追加（最大 100 件保持）
3. SSE クライアントへ sensorData をブロードキャスト
4. 200 を返す（ボディなし）

#### レスポンス

| ステータス | 意味 |
|-----------|------|
| 200 | 受信成功 |
| 400 | 入力不正 |
| 413 | ボディサイズ上限超過 |
| 415 | Media Type 不正 |
| 429 | レート上限超過 |
| 500 | サーバー内部エラー |

### 6.2 GET /events

SSE ストリーム。

ヘッダ:

- Content-Type: text/event-stream
- Cache-Control: no-cache
- Connection: keep-alive

動作:

- 接続確立時に `retry: 3000`（ミリ秒）を送信し、クライアントの再接続間隔を示唆する
- `SSE_KEEPALIVE_MS`（既定 30 秒）ごとに `: keep-alive` コメントを送信する
- 同時接続は `SSE_MAX_CLIENTS`（既定 100）を上限とし、超過時は 503 を返す
- クライアント切断（`req.on("close")`）時に接続リストから除去し、タイマーを解放する
- 新規データ受信時に以下イベントを送る

```text
event: sensorData
data: {"timestamp":"2026-05-15T12:34:56.789","data":"0141c80000","dataSize":5,"temperature":25.0,"humidity":null,"decodedItems":[{"type":"temperature","value":25.0}]}

```

SSE の `data` は §8 の `SensorEvent` と同一スキーマとする。

### 6.3 GET /api/history

- 最新 100 件を新しい順で返す
- レスポンス配列要素は SSE data と同一スキーマ

```json
[
    {
        "timestamp": "2026-05-15T12:34:56.789",
        "data": "0141c80000",
        "dataSize": 5,
        "temperature": 25.0,
        "humidity": null,
        "decodedItems": [
            {"type": "temperature", "value": 25.0}
        ]
    }
]
```

### 6.4 GET /

public/index.html を返す。

### 6.5 GET /drawable/*

UI 用 SVG アイコンを返す静的ファイル配信。

| パス | 用途 |
|------|------|
| `/drawable/app_icon.svg` | アプリロゴ |
| `/drawable/thermometer.svg` | Temperature カードのアイコン |
| `/drawable/humidity.svg` | Humidity カードのアイコン |

- `Content-Type: image/svg+xml` で配信されること。
- キャッシュヘッダは `Cache-Control: public, max-age=3600` とする。
- SVG の内容は信頼済みリポジトリアセットのみを配信対象とし、ユーザー入力由来の SVG は配信しない。

---

## 7. デコード仕様（DemoFormatUtil 準拠）

### 7.1 基本ルール

`data`（hex）をバイト列（1〜77 byte）に変換後、以下をバイト列を消費しながら繰り返す。

- 先頭 1 byte を type として読む
- type に応じて payload を読む
- バイト列が空になるまでループする（温度・湿度以外の type で打ち切りになる場合を除く）

| type | 意味 | payload |
|------|------|--------|
| 0x01 | 温度 | float32 4byte（IEEE754, Big Endian） |
| 0x02 | 湿度 | float32 4byte（IEEE754, Big Endian） |
| 0x00, 0x50, 0x53, 0x54, 0xA0, 0xEB | 任意データ | 残り全バイト |
| その他 | 不明データ | 残り全バイト |

注意:

- 任意データまたは不明データを読んだ時点で残りバイトを一括消費するため、そこで解析ループ終了。
- float32 読み取り中にバイト不足（残り < 4 byte）になった場合はデコード失敗として処理全体を中断する。
- 合計バイト数は 1〜77 の範囲。
- 温度・湿度の固定長要素だけで構成できる最大件数は 15 件（5 byte × 15 = 75 byte）。
- 76〜77 byte のデータは、固定長要素 15 件に加えて余剰バイトを含む可能性がある。余剰が任意データまたは不明データとして解釈された場合は §7.2 の抽出規則により temperature/humidity は null になる。

### 7.2 温湿度の抽出規則

- Android demo フレーバーの表示条件に合わせるため、以下を満たす場合のみ温湿度を有効値として扱う。
    - decodedItems が 1 件以上
    - 全要素が temperature または humidity
- 上記を満たさない場合（bytes/unknown を含む場合を含む）は temperature/humidity ともに null とする。
- デコード失敗時も temperature/humidity ともに null とする。

### 7.2.1 値の確定・丸め・非数値の扱い

- `temperature` フィールドには decodedItems 内で**最初に出現した** `temperature` 要素の値を採用する。`humidity` も同様に最初の `humidity` 要素の値を採用する。
- 採用候補の float32 値が `NaN` / `Infinity` / `-Infinity` の場合は当該要素を無効とみなし、そのフィールドを null とする。
- 表示用の丸めはフロントエンドで行い（小数 3 桁）、API・履歴・SSE では**丸めない生の数値**を保持する。
  - 例: `41c80000` → 値 `25` を保持し、UI で `25.000` と表示する。
- `decodedItems` には NaN/Infinity を含む要素を保存しない。該当要素は `{ type: "unknown", dataType: <type>, hex: <payload> }` には変換せず、デコード失敗扱いにする。

### 7.3 例

1) data=0141c80000

- 0x01 + 41c80000
- temperature=25.000
- humidity=null

2) data=0242480000

- 0x02 + 42480000
- temperature=null
- humidity=50.000

3) data=0141c8（不正）

- float32 が 4byte 未満
- デコード失敗
- temperature=null, humidity=null

---

## 8. サーバー内部データモデル

```ts
type SensorEvent = {
    timestamp: string;
    data: string;
    dataSize: number;
    temperature: number | null;
    humidity: number | null;
    decodedItems: Array<
        | { type: "temperature"; value: number }
        | { type: "humidity"; value: number }
        | { type: "bytes"; hex: string }
        | { type: "unknown"; dataType: number; hex: string }
    >;
};
```

- history は SensorEvent[]
- push 時に先頭追加
- 長さ > 100 の場合は末尾削除

---

## 9. フロントエンド仕様

### 9.1 画面構成

- ヘッダー
    - ロゴ（`drawable/app_icon.svg`）
    - タイトル: TS-E1 Analyzer web
- 左カラム
    - Temperature カード（アイコン: `drawable/thermometer.svg`）
    - Humidity カード（アイコン: `drawable/humidity.svg`）
- 右カラム
    - 選択中のカードに対応するグラフ（折れ線チャート）
- 下段
    - Received data テーブル

### 9.2 表示ルール

- 最新値
    - temperature/humidity は小数 3 桁表示
    - null は -- 表示
- グラフ切り替え（カード選択）
    - 左カラムの Temperature カードまたは Humidity カードをクリックすると、右カラムのグラフが対応するグラフに切り替わる
    - デフォルトは Temperature カードが選択状態
    - 選択中のカードは選択状態を視覚的に示す（例: ボーダーを強調、背景色を変える）
    - 非選択のカードは通常の表示のまま（数値・ミニグラフ・ゲージは常に表示し続ける）
- 温度グラフ
    - 最新 50 件
    - temperature==null はプロットしない
- 湿度グラフ
    - 最新 50 件
    - humidity==null はプロットしない
    - Y 軸: 湿度 (%)、自動スケーリング（データ範囲 ±1% の余白）
    - 線の色: 水色 (`#00d4ff`)
- 受信テーブル
    - 新着先頭
    - 最大 100 件
    - Data Size は N byte 形式
- XSS 対策
    - セル更新は textContent を使用

### 9.3 初期化とリアルタイム

1. 起動時
     - GET /api/history
     - 履歴を描画
     - Temperature カードを選択状態に設定（デフォルト）
     - 右カラムに Temperature グラフを表示
     - GET /events 接続
2. カードクリック時
     - クリックされたカードを選択状態にし、もう一方を非選択状態にする
     - 右カラムのグラフを選択されたカードに対応するグラフに切り替える（同一 `<canvas>` を Chart.js で再描画するか、表示/非表示を切り替える）
3. sensorData 受信時
     - カード更新（両カードの最新値・ミニグラフ・ゲージを常に更新）
     - 選択中のグラフにデータポイントを追加
     - 非選択グラフのデータも内部配列に保持しておき、切り替え時に即時描画できるようにする
     - テーブル更新
4. 切断時
     - 3 秒ごと再接続（最大 10 回）

---

### 9.4 デザイン基本方針（`sample_ui.png` 参照）

| 要素 | 値 |
|------|-----|
| テーマ | ダーク |
| 背景色 | `#0d0d2b`（深い紺色） |
| カード背景 | `#12122a` |
| アクセントカラー | 紫 `#6a3de8` / 水色 `#00d4ff` / 緑 `#00ff88` |
| フォント（数値・コード） | `monospace` |
| フォント（その他） | `sans-serif` |
| カードボーダー（非選択） | 角丸 + 紫グロウ `box-shadow: 0 0 10px #6a3de8` |
| カードボーダー（Temperature 選択） | 角丸 + 水色グロウ `box-shadow: 0 0 16px #00d4ff` |
| カードボーダー（Humidity 選択） | 角丸 + 緑グロウ `box-shadow: 0 0 16px #00ff88` |

---

### 9.5 レイアウト構成

```
┌──────────────────────────────────────────────────────┐
│  [app_icon.svg]  TS-E1 Analyzer web                  │  ← ヘッダー
├─────────────────────┬────────────────────────────────┤
│  [thermometer.svg]  │                               │
│  Temperature カード  │  選択中のカードに対応するグラフ  │
│  （クリックで選択）  │  Temperature グラフ or         │
├─────────────────────┤  Humidity グラフ               │
│  [humidity.svg]     │  （折れ線チャート）             │
│  Humidity カード     │                               │
│  （クリックで選択）  │                               │
├──────────────────────┴────────────────────────────────┤
│  Received data テーブル                               │
│  Time Stamp | Data | Data Size                       │
└──────────────────────────────────────────────────────┘
```

左カラム（全幅の約 1/3）: Temperature カード + Humidity カード（縦に並べる）
右カラム（全幅の約 2/3）: 選択カードに対応するグラフ

---

### 9.6 ヘッダー

- 左端に `drawable/app_icon.svg` をロゴとして表示（円形または正方形バッジ）
- ロゴ右に「**TS-E1 Analyzer web**」テキスト（見出しフォント）
- 背景はページ背景色と同色

---

### 9.7 Temperature カード（左カラム上段）

| 要素 | 仕様 |
|------|------|
| アイコン | `drawable/thermometer.svg` |
| ラベル | 「Temperature」（水色バッジ）|
| 数値 | 最新の `temperature` を小数点 3 桁で表示（例: `24.452`）|
| 単位 | `°C` |
| ミニグラフ | 直近 20 件の温度推移を折れ線で表示（Canvas 要素）|
| データ未受信時 | 数値部分に `--` を表示 |
| 非選択状態 | 紫グロウエフェクト付き角丸ボーダー |
| 選択状態 | 水色グロウ `box-shadow: 0 0 16px #00d4ff`、背景を僅かに明るく |
| クリック操作 | カード全体がクリッカブル（`cursor: pointer`）|

---

### 9.8 Humidity カード（左カラム下段）

| 要素 | 仕様 |
|------|------|
| アイコン | `drawable/humidity.svg` |
| ラベル | 「Humidity」（緑系バッジ）|
| 数値 | 最新の `humidity` を小数点 3 桁で表示（例: `46.287`）|
| 単位 | `%` |
| 円弧ゲージ | 0〜100% の範囲で現在値を水色円弧で表示（Canvas 要素） |
| データ未受信時 | 数値部分に `--` を表示 |
| 非選択状態 | 紫グロウエフェクト付き角丸ボーダー |
| 選択状態 | 緑グロウ `box-shadow: 0 0 16px #00ff88`、背景を僅かに明るく |
| クリック操作 | カード全体がクリッカブル（`cursor: pointer`）|

---

### 9.9 右カラムグラフ（共通仕様）

右カラムには選択中のカードに対応するグラフを 1 つ表示する。
実装は 2 つの `<canvas>` を用意し、CSS の `display` で切り替える。

| 要素 | Temperature グラフ | Humidity グラフ |
|------|------------------|--------------------|
| ライブラリ | Chart.js Line | Chart.js Line |
| タイトル | 「Temperature」 | 「Humidity」 |
| X 軸 | 受信時刻（`HH:mm:ss`）、最新 50 件 | 受信時刻（`HH:mm:ss`）、最新 50 件 |
| Y 軸 | 温度 (°C)、自動スケーリング（±0.5°C 余白） | 湿度 (%)、自動スケーリング（±1% 余白） |
| 線の色 | オレンジ `#ff8c42` | 水色 `#00d4ff` |
| データポイント | 半径 3px 円マーカー | 半径 3px 円マーカー |
| グラフ背景 | `#1a1a3e` | `#1a1a3e` |
| グリッド線 | `rgba(255,255,255,0.1)` | `rgba(255,255,255,0.1)` |
| null データ | プロットしない | プロットしない |
| 初期表示 | **表示**（デフォルト選択） | 非表示 |

---

### 9.10 Received data テーブル（下部全幅）

| 要素 | 仕様 |
|------|------|
| タイトル | 「Received data」（太字） |
| カラム構成 | Time Stamp（左） / Data（中央） / Data Size（右） |
| 並び順 | 新着を先頭に追加（降順） |
| 最大表示件数 | 100 件 |
| コンテナ高さ | 最大 300px、超過分は縦スクロール |
| Data Size の表示形式 | `<N> byte`（例: `5 byte`） |
| XSS 対策 | 全カラムを `element.textContent` で設定（`innerHTML` 使用禁止）|

---

## 10. 非機能要件

- ローカル LAN 想定
- サーバー時刻依存の補正はしない（timestamp は Android 値をそのまま表示）

---

## 11. エラーハンドリング

- POST 入力不正は 400（エラー理由を JSON で返してよい）
- SSE 送信失敗クライアントは接続リストから除外
- デコード失敗時も 200 で受理し、temperature/humidity を null にして配信

---

## 12. セキュリティ要件

- 入力バリデーションは必須
- body サイズ上限を設ける（例: 64KB）
- CORS は必要最小限（同一オリジン推奨）
- innerHTML 禁止
- SVG は静的ファイルとして配信し、`innerHTML` や文字列連結でインライン展開しない。
- HTTP セキュリティヘッダを付与する。
    - `X-Content-Type-Options: nosniff`
    - `Referrer-Policy: no-referrer`
    - `Content-Security-Policy` は最低限以下を満たすこと。

```text
default-src 'self';
script-src 'self';
style-src 'self';
img-src 'self' data:;
connect-src 'self';
object-src 'none';
base-uri 'self';
```

- Chart.js を CDN から読む開発構成の場合のみ、`script-src` に利用CDN（例: `https://cdn.jsdelivr.net`）を追加する。本番では `public/vendor/chart.umd.min.js` を使い、CSP は `'self'` 中心で運用する。

---

## 13. 受け入れ基準

1. Android demo 実機から DECODE_OK 時に 2xx が返る。
2. Android の「データ送信テスト」で成功表示になる（TEST 互換）。
3. data=0141c80000 受信時に温度 25.000 を表示できる。
4. data=0242480000 受信時に湿度 50.000 を表示できる。
5. data=0141c8 受信時に UI が落ちず、温湿度は -- になる。
6. 履歴は 100 件を超えると古い順に削除される。
7. SSE 切断後に自動再接続する。
8. ページ初期表示時に Temperature カードが選択状態になり、右カラムに Temperature グラフが表示される。
9. Humidity カードをクリックすると右カラムが Humidity グラフに切り替わり、Temperature カードの選択が解除される。
10. グラフ切り替え後も両カードの最新値・ミニグラフ・ゲージが正しく更新され続ける。

---

## 14. 起動方法

```bash
npm install
npm start
```

デフォルトポート: 3000

```bash
PORT=8080 npm start
```

---

## 15. 実装メモ

- server.js には decodeDemoData(hex) を実装し、DemoFormatUtil.kt と同じ分岐で処理すること。
- TEST ペイロードは解析対象外として即時 200 を返す実装が最も Android 互換性が高い。
- 今後 internal フレーバー対応を追加する場合は、fid/sid を API スキーマに拡張する。

---

## 16. 構成パラメータ（環境変数）

すべて環境変数で上書き可能とし、未設定時は既定値を用いる。設定値は起動時にバリデーションし、
不正値（範囲外・型不正）の場合はエラーログを出力して**起動を中止**する。

| 環境変数 | 既定値 | 説明 |
|----------|--------|------|
| `PORT` | `3000` | 待ち受けポート（1–65535） |
| `HOST` | `0.0.0.0` | バインドアドレス |
| `NODE_ENV` | `production` | `development` / `production` |
| `BODY_LIMIT` | `64kb` | POST ボディ上限。超過は 413 |
| `HISTORY_MAX` | `100` | 履歴保持件数 |
| `SSE_KEEPALIVE_MS` | `30000` | SSE キープアライブ間隔 (ms) |
| `SSE_MAX_CLIENTS` | `100` | SSE 同時接続上限。超過は 503 |
| `CORS_ORIGIN` | （空） | 許可オリジン。空なら CORS ヘッダを付与しない（同一オリジン運用） |
| `RATE_LIMIT_RPS` | `50` | `POST /api/data` の 1 IP あたり毎秒許容数。超過は 429 |
| `LOG_LEVEL` | `info` | `debug` / `info` / `warn` / `error` |
| `TRUST_PROXY` | `false` | リバースプロキシ配下で `true`。クライアント IP を `X-Forwarded-For` から取得 |

---

## 17. ログ・可観測性

- ログは **stdout への 1 行 JSON（構造化ログ）** とし、プロセスマネージャ／コンテナ基盤で収集する。
- 各ログに `level` / `time`(ISO 8601) / `msg` / `requestId` を含める。
- アクセスログに `method` / `path` / `status` / `durationMs` / `ip` を記録する。
- 記録対象イベント:
  - リクエスト受信・完了（access）
  - バリデーション失敗（warn、理由コード付き）
  - デコード失敗（warn、`data` の先頭 16 文字のみ。全量は記録しない）
  - SSE 接続/切断、現在接続数（info）
  - 未捕捉例外・`unhandledRejection`（error、その後プロセスを安全に終了）
- センサーデータ本文（`data`）は個人情報ではないが、ログ肥大化を避けるため全量出力は debug レベル限定とする。
- `requestId` は受信時に UUID を採番（`X-Request-Id` ヘッダがあれば踏襲）。

---

## 18. 運用エンドポイント・ルーティング

### 18.1 `GET /healthz`

ロードバランサ／監視用ヘルスチェック。常に軽量処理で応答する。

```json
{
    "status": "ok",
    "uptimeSec": 1234,
    "sseClients": 3,
    "historyCount": 100,
    "version": "1.0.0"
}
```

- 正常時 200。プロセスが応答可能であること自体が健全性を示す。

### 18.2 ルーティング規約

- 既知パスに対する未対応メソッド → **405**（`Allow` ヘッダに許可メソッドを列挙）
- 未知パス → **404**（§19 のエラースキーマ）
- 静的アセット（`public/` と `drawable/`）はキャッシュヘッダ `Cache-Control: public, max-age=3600` を付与（`index.html` のみ `no-cache`）

---

## 19. エラーレスポンススキーマ

`POST /api/data` を含む API エラー（400/404/405/413/415/429/500/503）は次の形式の JSON を返す。
（成功時 200 はボディなし。SSE エンドポイントは対象外。）

```json
{
    "error": {
        "code": "VALIDATION_ERROR",
        "message": "data must be a hex string"
    }
}
```

| HTTP | code | 発生条件 |
|------|------|----------|
| 400 | `INVALID_JSON` | JSON パース失敗 |
| 400 | `VALIDATION_ERROR` | フィールド欠落・型不正・形式不正 |
| 404 | `NOT_FOUND` | 未知パス |
| 405 | `METHOD_NOT_ALLOWED` | 既知パスへの未対応メソッド |
| 413 | `PAYLOAD_TOO_LARGE` | ボディが `BODY_LIMIT` 超過 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Content-Type が JSON 以外 |
| 429 | `RATE_LIMITED` | レート上限超過 |
| 500 | `INTERNAL_ERROR` | サーバー内部エラー（詳細はログのみ、レスポンスには出さない） |
| 503 | `SSE_CAPACITY` | SSE 接続上限超過 |

> 注: Android は 2xx 以外を一律失敗扱いし本文を参照しないため、エラー本文は人間／開発用途。

---

## 20. グレースフルシャットダウン

- `SIGTERM` / `SIGINT` 受信時:
  1. 新規接続の受付を停止（`server.close()`）
  2. すべての SSE レスポンスに終了イベントを送って `res.end()`
  3. キープアライブ等のタイマーを `clearInterval`
  4. 最大 10 秒待機後、未完了があっても `process.exit(0)`
- これによりコンテナ／systemd のローリング再起動時に接続リークを防ぐ。

---

## 21. デプロイ

### 21.1 実行コマンド

- 本番は `node server.js` を直接起動する（`nodemon` 等の開発ツールは使わない）。
- `package.json` の `start` は `node server.js`、`dev` のみ `nodemon` を使用する。

### 21.2 プロセス管理（いずれか）

- **systemd**: `Restart=always`、`EnvironmentFile` で環境変数注入、`User` を非 root に。
- **pm2**: `pm2 start server.js --name ts-e1 -i 1`（SSE のためクラスタ数は 1 とし、複数化する場合は §21.5 を参照）。
- **Docker**: 下記 Dockerfile を使用。

### 21.3 Dockerfile（参考）

```dockerfile
FROM node:20-alpine
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev
COPY . .
ENV NODE_ENV=production PORT=3000
EXPOSE 3000
USER node
CMD ["node", "server.js"]
```

### 21.4 リバースプロキシ / TLS（nginx 例）

Android は HTTP/HTTPS の双方に対応するため、本番では TLS 終端をプロキシで行う構成を推奨。
**SSE は バッファリング無効化が必須**（無効化しないとイベントが届かない）。

```nginx
location /events {
    proxy_pass http://127.0.0.1:3000;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 1h;
}
location / {
    proxy_pass http://127.0.0.1:3000;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

プロキシ配下では `TRUST_PROXY=true` を設定し、レート制限・ログの IP を正しく取得する。

### 21.5 スケールアウト時の注意

- 履歴・SSE 配信はプロセス内インメモリのため、**複数プロセス/インスタンス化すると配信が分断される**。
- 水平スケールが必要な場合は、共有 Pub/Sub（例: Redis）と外部ストアの導入が前提（本仕様の非スコープ）。
- 当面は単一プロセス運用とする。

---

## 22. テスト戦略

### 22.1 ユニットテスト（decodeDemoData）

| 入力 `data` | 期待 temperature | 期待 humidity | 備考 |
|-------------|------------------|---------------|------|
| `0141c80000` (5 byte) | `25` | `null` | 温度のみ |
| `0242480000` (5 byte) | `null` | `50` | 湿度のみ |
| `0141c800000242480000` (10 byte) | `25` | `50` | 温度+湿度連結 |
| `0141c8` (3 byte) | `null` | `null` | float32 が 4byte 未満 → デコード失敗 |
| `00ff` 等（任意データ type, 2 byte） | `null` | `null` | bytes を含むため null |
| 温湿度だけで構成される 75 byte データ | 最初の temperature 値 | 最初の humidity 値 | 5 byte 要素 × 15 件の上限正常処理 |
| 任意データ/不明データを含む 77 byte データ | `null` | `null` | bytes/unknown を含むため表示値は null |
| 末尾が未完の温度/湿度 type になる 76〜77 byte データ | `null` | `null` | BufferUnderflow 相当 → デコード失敗 |
| `dataSize=78`（上限超過） | — | — | API 層で 400 |
| `` 空 / 奇数長 / 非 hex | — | — | API 層で 400 |

### 22.2 統合テスト（supertest 等）

- `POST /api/data` 正常 → 200 かつ履歴 +1、SSE に配信される
- TEST ペイロード → 200 かつ履歴・配信なし
- 各バリデーション違反 → 期待ステータス & error.code
- `GET /api/history` の件数上限（101 件目で先頭削除）
- `GET /healthz` → 200
- 未知パス/メソッド → 404/405

### 22.3 受け入れテスト

§13 の受け入れ基準 1–10 を E2E で確認する。

### 22.4 フロントエンド/静的アセットテスト

- `/drawable/app_icon.svg` / `/drawable/thermometer.svg` / `/drawable/humidity.svg` が 200 かつ `image/svg+xml` で取得できる。
- 初期表示で Temperature カードに選択状態の class が付与され、Temperature グラフ canvas が表示される。
- Humidity カードクリック後、Humidity カードに選択状態の class が付与され、Humidity グラフ canvas が表示される。
- Chart.js 読み込み失敗時は画面全体を落とさず、グラフ領域にエラー状態を表示し、カードとテーブルは更新を継続する。

---

## 23. package.json（確定）

```json
{
    "name": "ts-e1-analyzer-web",
    "version": "1.0.0",
    "private": true,
    "type": "commonjs",
    "engines": { "node": ">=18" },
    "scripts": {
        "start": "node server.js",
        "dev": "nodemon server.js",
        "test": "node --test"
    },
    "dependencies": {
        "express": "^4.19.2"
    },
    "devDependencies": {
        "nodemon": "^3.1.0",
        "supertest": "^7.0.0"
    }
}
```

- Chart.js はフロントエンド静的アセットとして `public/vendor/chart.umd.min.js` に同梱するため npm 依存には含めない。開発時に CDN を使う場合も、本番ビルドでは同梱版へ切り替える。
- レート制限・CORS・ヘルスチェックは依存追加なしで最小実装可能。外部ライブラリ（`express-rate-limit`, `cors`, `helmet`, `pino`）を使う場合は依存に追加する。

---

## 24. 前提・既知の課題（Assumptions）

- 本リポジトリには §1 で参照する Kotlin ソース（DemoFormatUtil.kt 等）が含まれないため、デコード挙動は本仕様 §7 に**自己完結的に定義**した。両者に差異がある場合は Kotlin 実装を正とし、本仕様を更新する。
- `timestamp` はタイムゾーンを持たないローカル日時文字列。UI ではそのまま表示し、グラフ X 軸ラベルは文字列の `HH:mm:ss` 部分を切り出して用いる（再パースしない）。
- `temperature` / `humidity` が複数要素含まれる場合は「最初の出現値」を採用する（§7.2.1）。実機仕様が異なる場合は要更新。
- 対象ブラウザは SSE（`EventSource`）対応のモダンブラウザ（Chrome / Edge / Firefox / Safari の最新版）とする。
