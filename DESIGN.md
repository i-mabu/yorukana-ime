# 設計メモ (DESIGN)

よるかな IME の再構築（2026-11-28）にあたってのパリティ棚卸し、設計判断、
旧版との意図的な差分、既知の問題を記録する。

## 再構築の方針

- 旧リポジトリ（v1.4.5、versionCode 22）をパリティ基準として読み、
  全ファイルを新規に書き直した（コードの逐語コピーはしていない）
- 例外として、バイナリ資産とデータファイルのみ持ち越した（下記）
- バージョンはフリートの作り直し方針に合わせ **1.0.0 / versionCode 1** にリセット

### 持ち越した資産（記録）

| 資産 | 内容 | 理由 |
|---|---|---|
| `app/src/main/res/mipmap-*` | ランチャーアイコン PNG 一式と adaptive-icon XML（anydpi-v26） | バイナリ資産。再作成しても同一物にしかならないため |
| `debug.keystore` | デバッグ署名鍵（CN=Android Debug, O=YoruKana, C=JP） | 旧 APK と同一証明書（SHA-256: dcc464a5…f13）で署名でき、開発時の上書きインストール互換を保つため |
| `DictionaryData.kt` | 内蔵辞書データ（ENTRIES / GODAN / ICHIDAN / I_ADJ / NA_ADJ / SINGLE_KANJI の文字列配列） | 辞書「データ」であってコードではないため。辞書の読み総数 3,410 件はテストで固定 |

## パリティ棚卸し（旧 v1.4.5 → 再構築版）

### 保持したもの

| 領域 | 内容 |
|---|---|
| パッケージ / 名称 | `jp.yorukana.ime` / 「よるかな IME」。subtype は romaji（ローマ字）・kana（かな）の2種（ja_JP、extra value `mode=romaji` / `mode=kana`） |
| 権限 | VIBRATE のみ。INTERNET なし。allowBackup=false、テーマは `@android:style/Theme.Material` |
| SDK | minSdk 26 / targetSdk 34 / compileSdk 34 |
| 入力方式 | ローマ字 QWERTY / かな12キー（フリック・トグル）/ 英数（フリック・QWERTY）/ 記号・絵文字グリッドの4モード。モードキーは (mode+1)%4 で循環し、ラベルは「切り替わる先」を表示 |
| ローマ字変換 | 変換テーブル全体（促音 tch・子音重ね、撥音 n の規則、拗音、外来音 ti→てぃ / di→でぃ、小書き x/l 系、最長4文字一致、pending の扱い、convertFully の末尾 n→ん） |
| かな入力 | フリック表（や行左右は未割り当て→中央フォールバック、わ行 左=を 上=ん 右=ー 下=〜）、方向判定（ユークリッド距離・しきい値20dp・変位ゼロは必ず中央）、トグル段循環（1秒以内・同キー・行一致で循環）、゛゜小トグル表、文脈キー（123/カナ、顔文字/゛゜小）、、。？！循環キー、変換キー（候補送り / 無入力時は全角スペース） |
| かな配置 | Simeji 準拠の5列×4行、weight [0.75/1/1/1/0.75]、キー高さ58dp、キー表示 22sp、隙間3dp・角丸10dp のインセット描画 |
| 英数フリック | 11グループ表（Simeji 英字12キー準拠）、タップ循環（1秒以内・直前文字の一致確認つき）、caps 連動（プレビュー・入力とも大文字化） |
| 英数 QWERTY | 数字行、シフト（⇧/⇪ トグル）、, / . キー |
| 記号パネル | 6カテゴリ（常用/カッコ/図形/数字/顔文字/絵文字）、列数 [10,10,10,10,3,8]、顔文字18件・絵文字60件、タブは横スクロール |
| 変換エンジン | 候補順（ユーザー辞書→辞書（頻度順）→カタカナ→ひらがな原文→ローマ字原文）、助詞分離（PARTICLES / 語幹2文字以上）、文節分割（最大深度3、PARTICLES_BEFORE、サ変動詞語頭）、予測（全体前方一致→文節分割予測、予測読みは二分探索）、候補上限24件・予測追加8件 |
| 辞書 | 読み 3,410 件。活用生成（五段の GODAN_ROWS と「いく」例外、一段、い形容詞、な形容詞）、単漢字フォールバック、壊れたエントリの読み飛ばし |
| 学習 | 頻度キー `freq:` 接頭辞 + `読み\|候補`、PrefsLearningStore の reload 追随、ひらがな/ローマ字原文の確定は学習しない、予測で選んだ候補は予測側の読みで記録 |
| ユーザー辞書 | encode/decode 形式（`よみ\t単語` の改行連結）、サニタイズ（タブ・改行→空白）、登録順保持、設定画面での検証（ひらがなのみ、カタカナは自動でひらがな化） |
| 設定キーと既定値 | `theme`=night / `vibrate`=true / `default_mode`=romaji / `kana_style`=flick / `en_layout`=flick / `user_dict`。ファイル名 `yorukana_prefs`。旧版のデータをそのまま読める |
| セキュアセッション | パスワード variation（text 3種 + number）と IME_FLAG_NO_PERSONALIZED_LEARNING で判定し、英数固定 + 学習なし |
| セーフティ | 別ファイル `yorukana_safety`（startup_pending / startup_failures / total_crashes / last_crash / last_mode、commit() 同期書き込み）。閾値 SAFE=2 / MINIMAL=4。SAFE は学習永続化・ユーザー辞書・バイブ停止、MINIMAL は変換エンジン・プレビューも停止。クラッシュ記録の整形（メッセージ200字・スタック1200字・先頭10フレーム・スレッド名つき）、未捕捉例外ハンドラは記録のみで既定ハンドラへ委譲。入力ビュー生成完了で起動完了とする |
| 起動の堅牢化 | 初期化を段階ごとに例外で閉じ込め（設定→学習→エンジン、エンジンは1回だけ再構築）、壊れた設定・辞書データでも最小限の入力で起動する |
| Compose ホスト | Service 自身が LifecycleOwner / ViewModelStoreOwner / SavedStateRegistryOwner を兼ね、入力ビューから decorView までの全階層に ViewTree オーナーを付与（実機で確認された parentPanel 起因の即落ち対策）。アタッチ時・表示時にも付け直す |
| プレビュー | フリック = 横並びストリップ（セル44dp×高さ52dp、左・中央・上・右・下の順、現在方向を強調）、文字キー = 52dp のキープレビュー。どちらもオーバーレイ絶対配置でキーボードの高さを変えない。画面端クランプと「上に置けない場合は下」フォールバック |
| キー操作 | 文字キーは DOWN で入力、削除は DOWN で1文字 + 400ms 後から50ms リピート（DeleteRepeater は d8 の都合でトップレベルクラス）、最上段の長押し450ms で数字、候補バーのハイライト追従スクロール、候補バー右端のカーソルボタン、composing 中のカーソルキーは先に確定してから送る |
| その他 | onEvaluateFullscreenMode() = false、バイブ 12ms ワンショット、セッションをまたぐ composing は finish してから内部状態を消す、終了時に Handler コールバックとプレビューを必ず解除 |
| 設定画面 | 有効化ガイド（入力方法の設定 / キーボード選択）、バージョン表示、テーマ（画面配色連動）、バイブ、既定方式、かな方式、英数配列、ユーザー辞書 CRUD、学習リセット、セーフティ表示（モード表示・手動解除・クラッシュ記録の表示と消去）、プライバシー文 |
| 配色 | 夜: root #0B1226 / key #1C2A4A / special #27355C / text #EAF0FF / accent #3D5AFE。昼: root #E8EBF0 / key #FFFFFF / special #D5DBE8 / text #182038 / accent #9DB8F0。設定画面の Material3 スキームも同一値 |
| ビルド | Gradle なしの手動ビルド（aapt2 → kotlinc + Compose プラグイン → javac → 単体 R8 8.7.18 の D8、マルチ dex、.so は lib/<abi>/ に無圧縮格納、zipalign -p）。依存解決は tools/resolve.py、成果物リストは libs/artifacts.txt |

### 意図的に変更したもの（理由つき）

| 変更 | 理由 |
|---|---|
| バージョンを 1.0.0 / versionCode 1 にリセット（旧: 1.4.5 / 22） | フリート全体の作り直し方針。**注意: versionCode が下がるため、旧版が入っている端末では上書きインストールできず、アンインストール→再インストールが必要**（設定・学習・ユーザー辞書は端末から消える。形式自体は互換なので、将来 versionCode が 22 を超えれば再び上書き可能になる） |
| Windows 版（`windows-tsf/` / `windows-companion/`）を本リポジトリに含めない | 本再構築の対象は Android アプリのみ。旧リポジトリでは 2026-11-24 に別リポジトリから統合されたサブディレクトリだった。Windows 版をどうするかは未決（別途判断） |
| テストハーネスを Java（TestMain.java + javac 実行）から Kotlin（TestMain.kt）に変更 | 直近の再構築（yoru-reader）と形式を揃え、保守を単純化するため。テストケースと期待値は旧スイートを踏襲し、記号データのレイアウト整合性チェック 13 件を追加（311 → 324 件） |
| 単体テストの Kotlin を 2.4.21 から 2.0.21 に統一 | 旧 CI はテストだけ 2.4.21 を使っていた（経緯は KOTLIN_HOME の既定値都合）。アプリ本体と同一コンパイラに揃え、再現性を上げるため。挙動差なし |
| Compose プラグインと R8 の取得に SHA-256 ピンを追加 | 旧 CI/スクリプトはハッシュ検証なしでダウンロードしていた。プラグインは PLAIN 成果物（kotlin-compose-compiler-plugin-2.0.21.jar、SHA-256: 7a38399c…b273f）、R8 8.7.18（SHA-256: 58366f77…e903）を固定 |
| D8 のヒープ上限を -Xmx2g から -Xmx1536m + `--thread-count 1` に変更 | 共有 VM で他ビルドと並走した際の OOM Kill 回避（yoru-reader 再構築で得た知見）。R8_JAVA_OPTS で上書き可能 |
| build-apk.sh の最終段に badging 検証を追加 | 旧 SPEC には「ビルド時に package/version/権限を検証する」とあったが旧スクリプトには実装がなかったため、明示的に実装した（package / versionCode / versionName / minSdk / targetSdk / VIBRATE のみ・INTERNET なし / classes.dex / apksigner verify） |
| CI に workflow_dispatch を追加し、Kotlin セットアップにハッシュピンを追加 | フリートの統一ワークフローの必須要件。旧 CI は push/pull_request のみだった |
| SPEC.md を v1.0.0 時点の内容に書き直し、旧 README の古い記述（かな配置図・十字プレビュー等、実装と食い違っていた箇所）は実装（コード）を正として修正 | 旧 README の配置図は v1.1.2 時点のもので、v1.4.5 の Simeji 準拠配置と異なっていた。旧 SPEC の「辞書 3,408 件」表記も実測（テスト固定値）3,410 件に修正 |
| `.githooks` を含めない | 統一ワークフローの方針（フックは使わない） |
| 開発ワークフロー文書（CONTRIBUTING / Issue・PR テンプレート）は dev-workflow-kit の複写ではなく新規作成 | 統一ワークフローの方法制約に従うため。内容は aegis-bot 系の標準形に準拠 |

## 既知の問題・未検証事項

- **実機未検証**: この VM では実機/エミュレータが使えないため、実際の IME 動作（キー操作感、フリック/プレビューの表示位置、候補バーの追従、セーフティの実クラッシュ時挙動、ViewTree オーナー付与の実機確認）は未検証。JVM 単体テスト（324 件）と APK の badging・署名検証までの確認に留まる
- versionCode リセットに伴う再インストールの要否は上記の通り（旧版ユーザーは一度アンインストールが必要）
- Windows 版（TSF / コンパニオン）の扱いは未決。本リポジトリには含めていない
- 旧リポジトリの `chore/adopt-workflow` ブランチは、統一ワークフロー文書のみを追加する1コミットで、アプリ機能の差分は無かった。本再構築では同等の文書を新規作成して main に含めたため、ブランチの内容は実質取り込み済み
