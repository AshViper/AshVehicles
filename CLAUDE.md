# AshVehicles

Minecraft 1.21.1 / NeoForge 21.1.234 の車両・航空機 MOD。Java 21、GeckoLib 4.9.2、JEI・Distant Horizons 対応。`mod_id = ashvehicles`、`com.ashvehicles`。

## ビルドと実行

```
gradlew build        # ビルド
gradlew runClient    # クライアント起動
gradlew runServer    # サーバ起動
gradlew runData      # データ生成（定義の検証はこれで通す）
```

- **ゲーム起動中に Gradle は走らない**（neoforge の jar をクライアントが掴む）。詳細は記憶ノート `gradle-blocked-while-game-runs`
- `runData` は新しい機体・兵器を足したときの一次検証。通らないものはコミットしない
- 戦闘 AI の判断ロジックは `python tool/ai/check/run_checks.py` で確かめる（javac を直に使うのでゲーム起動中も走る。確かめるのは判断の算術までで、試合の中の挙動ではない）

## 構造

| 場所 | 中身 |
|---|---|
| `src/main/java/com/ashvehicles/` | 全 394 クラス。`AshVehicles` / `AshVehiclesClient` / `Config` が入口 |
| `client/` (143) | 描画・HUD・カメラ・音。`ghost` `model` `renderer` `screen` `particle` `sound` `item` |
| `entity/` (31) | `VehicleEntityBase` → `AircraftEntity` / `GroundVehicleEntity`、投射体、チャンク読み込み系 |
| `weapon/` (18) `sensor/` (4) | 兵装定義とシーカー |
| `match/` (8) `command/` (2) | チームデスマッチの帳簿と進行、AI の名簿と補充、`/tdm`（`/tdm ai` を含む） |
| `ai/` (89) | 地上車両と航空機の戦闘 AI（航空機は `AirPilot` と `ai/air`、`aircraft-bots-strike-from-passes`）。車両に付く操縦役（`bots-are-a-pilot-object-on-the-vehicle`）を、観測・地図・拠点・判断・戦術・経路・射撃・操縦・記録・版の層に分けてある（`battle-ai-is-layered-with-a-discrete-action-policy`、仕様書 `85 戦闘AI`）。試合を重ねて覚える地図はワールドに保存する（`map-memory-is-counted-not-trained`） |
| `mixin/` (24) | `ashvehicles.mixins.json`。チャンク送信・生成・描画順に手を入れている |
| `network/` (21) `registry/` (8) `data/` (6) | パケット、登録、定義読み込みとコンテンツパック |
| `tool/vehicle-editor.html` | 当たり判定箱を作るブラウザツール |
| `tool/dump_definitions.py` | 定義ファイルから実装一覧（機体・兵装の数値表）を Obsidian の `データ/` に生成 |
| `tool/make_weapon_icon.py` | 兵装の geo から 16×16 のアイテムアイコンを起こす。手描きの下地 |
| `tool/ai/` | 戦闘 AI の学習の段取りをゲームの外で回す Python（版の比較 `evaluate.py`・次の候補 `propose_version.py`・学習用 CSV `export_dataset.py`・覚えた地図の画像 `map_memory.py`）、覚えた地図と戦闘をワールドの地形に重ねて見るブラウザのビューア `viewer/index.html`、判断ロジックの合成試験 `check/` |

**コンテンツは定義ファイル駆動**。`src/main/resources/data/ashvehicles/` に aircraft 29 / vehicle 23（うち艦 2） / weapon 42 / ammunition 10 / rack 5 / equipment 3。**登録の本体は Java ではなく定義ファイル**なので、新規追加はまずここを見る（`what-a-new-machine-needs` / `what-a-new-weapon-needs`）。

## 記憶と記録（重要）

このプロジェクトの設計判断・ハマりどころは **記憶ノート**に蓄積してある。セッション開始時に索引 `MEMORY.md` が読み込まれる。

- 実体: `C:\Users\nagis\.claude\projects\F--Java-AshVehicles\memory\`
- 同じものが Obsidian から見える: `G:\Obsidian\AshVehicle Vault\memory\`（ジャンクション、双方向）

**運用ルールは記憶ノート側に置いてある**。以下は毎回そこを正とすること:

| 判断 | 読むノート |
|---|---|
| 何かを記憶に残すべきか | `what-to-remember-and-what-to-drop` |
| いつ勝手に記憶を書いてよいか | `when-to-write-memory-unprompted` |
| コードを変えたとき何を記録するか | `what-to-record-when-code-changes` |
| 設計判断を残すときの書式 | `adr-format-for-decisions` |

人間向けの案内（分野別マップ・検索の仕方・テンプレート）はヴォルト直下の `00 使い方` `01 分野別マップ` `24 Obsidian 検索ルール`。

**仕様書はヴォルトの `仕様書/` にある**（`G:\Obsidian\AshVehicle Vault\仕様書\`、入口は `00 索引`）。記憶ノートとは役割が違う:

| 知りたいこと | 見る場所 |
|---|---|
| 何がどこにあるか・どう作られているか | `仕様書/`（`10 全体構成` `20 機体と車両` `30 兵装と投射体` `40 センサー・照準・計器` `50 ワールド読み込みと描画` `60 ネットワークと同期` `70 定義ファイル仕様` `80 チームデスマッチ` `85 戦闘AI`） |
| 実装済みの数値 | `仕様書/データ/`（`python tool/dump_definitions.py` で再生成。手で編集しない） |
| NeoForge のどこへ手を掛けているか | `NeoForge/`（ビルド・登録・イベント・ミキシン・パック。入口は `00 NeoForge 一覧`） |
| なぜその形なのか・どこで転ぶか | `memory/` |

**構造を変えたら該当する仕様書ノートを直す。定義ファイルを足したらデータを再生成する。**

## 作業上の約束

- 新規 Java ファイルや `'` を含む本文は **Write/Edit で書く**。Bash heredoc は壊れる（`bash-heredoc-breaks-on-apostrophes`）
- 記憶ノートを 1 件足したら **`MEMORY.md` と `01 分野別マップ` の両方**に行を足す
- 既存の記憶と矛盾する事実を見つけたら、新規に書くのではなく**該当ノートを直す**
