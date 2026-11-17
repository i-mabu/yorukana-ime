# よるかな IME (YoruKana) v1.0

Android用オリジナル日本語IME。完全オフライン・ネットワーク権限なし・外部依存ゼロ。
名前は「終夜（よる）」の世界観から。夜テーマ（濃紺）を既定にした、育つIMEです。

- パッケージ: `jp.yorukana.ime`
- versionName 1.0 / versionCode 1
- minSdk 26 / targetSdk 34、Java、Gradle/AGPを使わない手動ビルド

## 機能

### 入力方式（4種、モード切替キーで循環）
1. **ローマ字入力（QWERTY）**: ローマ字→ひらがな変換しながら composing 表示。
   促音（子音重ね・xtu/ltu・tch）、撥音 n（nn / n' 対応、後続が母音なら な行）、
   拗音（kya/sha/cha/ja 等）、外来音（fa/fi/fe/fo、ti→てぃ、di→でぃ、wi/we 等）、
   小書き（xa/xya/xtu 等）を含む変換テーブル。
2. **かな入力（12キー・トグル打ち）**: あ/か/さ/た/な/は/ま/や/ら/わ の10キー。
   タップ回数で段が循環（あ→い→う→え→お、や行は や→ゆ→よ、わ行は わ→を→ん→ー→〜）。
   「゛゜小」キーで濁点・半濁点・小書きを循環（か→が、は→ば→ぱ、つ→づ→っ、あ→ぁ 等）。
3. **英数入力（QWERTY直接入力）**: composing を経ず直接コミット。数字行・シフト（大文字）対応。
4. **記号**: 記号グリッド（、。！？・：；（）「」『』＠＃￥％＆＊＋－＝＜＞＿〜…、顔文字向け括弧、☆★○●△▲ 等）。

### 変換エンジン
- 候補順: ①辞書の漢字候補（学習頻度でソート） ②カタカナ ③ひらがな原文 ④ローマ字原文（重複排除）。
- 予測候補: 前方一致する辞書語を候補バーの後ろに表示。
- 内蔵辞書: 読み 3,501 語（明示エントリ＋動詞・形容詞の活用形を生成登録）。
  日常語（挨拶・時間・学校・天気・家族・食べ物・地名・IT用語 等）を幅広く収録。
  単漢字フォールバック付き。
- **学習機能**: 確定した候補の頻度を端末内（SharedPreferences）に記録し、次回から上位表示。
- 末尾助詞（は/が/を/に/へ/と/の/も/か/ね/よ）の簡易分離変換
  （例: 「がっこうは」→「学校は」）。

### 操作
- 候補バー（横スクロール）: タップで確定。スペース/変換キーで候補ハイライトを順送り。
- Enter: ハイライト中は候補を確定、composing 中はそのまま確定、それ以外は入力先の
  アクション/改行に素直に渡す。
- 削除キー: 長押しで連続削除。カーソル左右キー付き。
- 設定画面: 有効化ガイド＋「入力方法の設定を開く」ボタン、テーマ夜/昼切替、
  バイブ ON/OFF、既定の入力方式、学習リセット。

## 使い方（有効化手順）
1. APK をインストールし、アプリ「よるかな IME」を開く。
2. 「入力方法の設定を開く」から「よるかな IME」を有効化する。
3. 文字入力画面でキーボード切替から「よるかな IME」を選ぶ。

## ビルド方法
前提: JDK 21（`~/workspace/tools/jdk-21`）と Android SDK
（`~/workspace/tools/android/sdk`、platforms;android-34、build-tools;34.0.0）。

```sh
./build-apk.sh        # aapt2 + javac + d8 + zipalign + apksigner (debug keystore)
# 成果物: dist/yorukana.apk
```

検証コマンド例:

```sh
~/workspace/tools/android/sdk/build-tools/34.0.0/aapt2 dump badging dist/yorukana.apk
~/workspace/tools/android/sdk/build-tools/34.0.0/apksigner verify dist/yorukana.apk
```

## テスト
純ロジック（RomajiConverter / KanaUtils / Dictionary / ConversionEngine）の単体テスト。

```sh
./tests/run-tests.sh  # javac + java で実行、全100件
```

## プライバシー
- **INTERNET 権限なし**（Manifest に uses-permission 自体が無い）。
  入力内容を外部に送信することは一切ありません。
- 学習データは端末内の SharedPreferences にのみ保存され、設定画面からいつでもリセットできます。

## 既知の制限（正直なところ）
- **文節変換は簡易版**: v1.0 は単一文節（全体変換）のみ。複数文節への分割や
  文脈を考慮した変換はしません（末尾助詞の分離のみ対応）。
- 辞書は内蔵の日常語中心（約3,500読み）で、固有名詞・専門用語は少なめです。
  未登録語はカタカナ/ひらがな候補で代替してください。
- 人名・地名の学習は候補単位のみで、ユーザー辞書登録 UI はありません。
- かな入力はトグル打ちのみ（フリック入力は未実装）。
- 音声入力、絵文字パレット、手書き入力はありません。
- 署名は debug keystore です（配布用ではありません）。

## 未検証事項
- **実機検証は未実施**です。本環境（Linux VM）ではビルド・署名・aapt2 検証と
  純ロジックの単体テストまでを確認しています。実機での有効化・入力動作・
  各アプリとの相性は未確認です。
- 手動ビルドの d8（R8 8.2.2-dev）が非 static 内部クラスのクラスファイルで
  NPE を起こすため、削除リピート用 Runnable は static 内部クラスで実装しています。

## 構成
```
app/src/main/java/jp/yorukana/ime/
  YoruKanaService.java    IME本体 (InputMethodService、コード生成UI)
  SettingsActivity.java   設定画面
  RomajiConverter.java    ローマ字→ひらがな変換 (純ロジック)
  KanaUtils.java          かな変換・トグル表 (純ロジック)
  Dictionary.java         辞書 (活用形生成) (純ロジック)
  DictionaryData.java     辞書データ
  ConversionEngine.java   候補生成・予測・助詞分離 (純ロジック)
  LearningStore.java      学習ストア IF / MemoryLearningStore.java メモリ実装
app/src/main/res/xml/method.xml  input-method 定義 (ローマ字/かな subtype)
tests/                    単体テスト
build-apk.sh              手動ビルドスクリプト
```
