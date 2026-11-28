# 更新履歴 (CHANGELOG)

## v1.0.0（2026-11-28）

リポジトリを作り直し、バージョンを 1.0.0 / versionCode 1 にリセットした最初のリリース。
機能は旧 v1.4.5 とのパリティを保っている（詳細な棚卸しは DESIGN.md）。

### 追加

- 統一開発ワークフローの文書一式（CONTRIBUTING.md、Issue / PR テンプレート、workflow_dispatch を含む CI）
- ビルド時の badging 自動検証（package / version / SDK / 権限を build-apk.sh の最終段で確認）
- Compose コンパイラプラグインと R8 のダウンロードに対する SHA-256 ピン
- 単体テストに記号データ（SymbolData）のレイアウト整合性チェックを追加（311 → 324 件）

### 変更

- 全ソースを新規に書き直し（パッケージ名・設定キー・保存形式は旧版と互換を維持）
- テストハーネスを Java から Kotlin に変更し、テスト用 Kotlin も 2.0.21 に統一
- D8 のヒープ上限を -Xmx1536m + 単一スレッドに変更（共有 VM での OOM 回避）
- README / SPEC を実装（コード）を正として書き直し

### 修正

- 旧 SPEC の辞書件数表記（3,408 件）を実測の 3,410 件に修正

### 削除

- Windows 版サブディレクトリ（`windows-tsf/` / `windows-companion/`）を本リポジトリには含めない（Android アプリのみを対象とするため。扱いは未決）
- `.githooks` は採用しない
