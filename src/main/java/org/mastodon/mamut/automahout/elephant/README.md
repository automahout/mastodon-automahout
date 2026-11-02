# ELEPHANT Integration

このパッケージは [ELEPHANT](https://elephant-track.github.io/) （深層学習ベースの細胞追跡システム）と Automahout を統合します。

## 機能

- **✅ Spot検出** (`predictSpots()`) — HTTP API で ELEPHANT サーバーにリクエスト、検出結果をグラフに追加
- **✅ Spot リンク** (`linkSpots()`) — 最近傍法で時系列 Spot を連結（HTTP API 実装済み）

## 必要な設定

### 1. ELEPHANT サーバーの起動

```bash
# ELEPHANT サーバーをローカルで起動（ポート 8080）
docker run -p 8080:8080 elephant-server:latest
```

### 2. Mastodon での設定

1. **Edit → Preferences** を開く
2. **ELEPHANT Server Settings** を探す
3. Server URL を設定（例：`http://localhost:8080`）
4. Dataset と Model を選択

## 使用方法

### Automahout ループから自動実行

AutomahoutLoop が各フレームで自動的に ELEPHANT spot 検出を実行します：

```
フレーム設定 (500ms)
  ↓
ELEPHANT HTTP API リクエスト → POST /api/seg/predict
  ↓
検出結果（JSON）をパース
  ↓
各 Spot をグラフに追加
  ↓
次フレームへ (300ms)
```

### プログラム的な使用

```java
ElephantIntegration elephant = new ElephantIntegration(toolContext);
if (elephant.init()) {
    elephant.predictSpots();      // 現在のフレームで spot 検出
    elephant.linkSpots();         // spot をリンク
    elephant.predictAndLink();    // 両方実行
}
```

## HTTP API 実装

### POST /api/seg/predict — Spot検出

**リクエスト:**
```json
{
  "dataset": "default",
  "model": "default",
  "t_start": 0,
  "t_end": 0,
  "prob_threshold": 0.5,
  "min_radius": 1.0,
  "max_radius": 50.0,
  "voxel_size_xy": 1.0,
  "voxel_size_z": 1.0
}
```

**レスポンス:**
```json
{
  "completed": true,
  "spots": [
    {
      "pos": [x, y, z],
      "covariance": [9 floats],
      "t": 0,
      "quality": 0.95
    },
    ...
  ]
}
```

**処理フロー:**
1. Unirest で POST リクエスト送信
2. JSON レスポンスをパース
3. `spots` 配列から各 Spot を抽出
4. `addSpotFromJson()` で Mastodon グラフに追加（Write ロック + Undo point）

### POST /api/link/nn — NN リンク

**リクエスト:**
```json
{
  "dataset": "default",
  "model": "default",
  "t_start": 0,
  "t_end": 1,
  "distance_threshold": 100.0,
  "max_edges": 5,
  "search_depth": 2
}
```

**レスポンス:**
```json
{
  "completed": true,
  "links": [
    {"t": 0, "spot_id_from": 123, "spot_id_to": 456},
    ...
  ]
}
```

**処理フロー:**
1. Unirest で POST リクエスト送信
2. JSON レスポンスをパース
3. `links` 配列から各リンクを抽出
4. グラフに Edge を追加（実装予定）

## トラブルシューティング

### "Failed to predict spots" エラー

1. **ELEPHANT サーバーが起動しているか確認**
   ```bash
   curl http://localhost:8080/health
   ```

2. **サーバー URL が正しいか確認**
   - Mastodon Preferences で設定を確認
   - ログに "Sending POST request to:" が表示されているか確認

3. **ログで詳細を確認**
   - View → Preferences → Logging Level を DEBUG に設定
   - Windows → Show → Logcat で詳細ログを表示
   - リクエスト/レスポンスの JSON を確認

### HTTP ステータスエラー

- 404: `/api/seg/predict` エンドポイントが存在しない（ELEPHANT バージョンを確認）
- 500: サーバー内部エラー（ELEPHANT ログを確認）
- Connection refused: サーバーが起動していない

## 実装状況

### ✅ 実装済み
- [x] HTTP API リクエスト送信 (Unirest)
- [x] JSON パース (org.json)
- [x] Spot 追加（Write ロック + Undo point）
- [x] エラーハンドリング

### 🔄 実装予定
- [ ] Edge リンク作成（`linkSpots()` レスポンス処理）
- [ ] 座標変換（voxel → world space）
- [ ] ELEPHANT 設定値の動的読み込み（dataset, model）
- [ ] 非同期処理（バックグラウンドスレッド）
- [ ] Spot コバリアンス情報の活用
