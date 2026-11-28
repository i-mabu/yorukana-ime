#!/usr/bin/env bash
# よるかな IME - ワンコマンドビルド (単体テスト + APK ビルド + badging 検証)
# 使い方: ./build.sh [--skip-tests] [debug|release]
#   --skip-tests  単体テストを省略する
#   debug         デバッグ署名で dist/yorukana.apk を作る (既定)
#   release       RELEASE_* 環境変数のキーストアで署名し
#                 dist/yorukana-release.apk を作る
set -euo pipefail
cd "$(dirname "$0")"

MODE="debug"
SKIP_TESTS=0
for arg in "$@"; do
  case "$arg" in
    --skip-tests) SKIP_TESTS=1 ;;
    debug|release) MODE="$arg" ;;
    *) echo "unknown argument: $arg" >&2; exit 1 ;;
  esac
done

if [ "$SKIP_TESTS" -eq 1 ]; then
  export SKIP_TESTS=1
fi
echo "== build APK ($MODE) =="
./build-apk.sh "$MODE"
echo "== done =="
echo "artifact: $(pwd)/dist/yorukana.apk"
if [ "$MODE" = "release" ]; then
  echo "artifact: $(pwd)/dist/yorukana-release.apk"
fi
