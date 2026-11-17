#!/usr/bin/env bash
# よるかな IME 純ロジック単体テスト
# 本体 (Kotlin) を kotlinc でコンパイルし、Java のテストハーネス (TestMain.java) を
# Java 相互運用 (@JvmStatic / @JvmField) 経由で実行する。
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"
KOTLINC_HOME="${KOTLIN_HOME:-$HOME/workspace/tools/kotlin/kotlinc}"
KOTLINC="$KOTLINC_HOME/bin/kotlinc"
STDLIB="$KOTLINC_HOME/lib/kotlin-stdlib.jar"
[ -x "$KOTLINC" ] || { echo "kotlinc が見つかりません: $KOTLINC" >&2; exit 1; }
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
SRC=app/src/main/java/jp/yorukana/ime
"$KOTLINC" -d "$OUT/classes" \
  "$SRC/RomajiConverter.kt" \
  "$SRC/KanaUtils.kt" \
  "$SRC/DictionaryData.kt" \
  "$SRC/Dictionary.kt" \
  "$SRC/LearningStore.kt" \
  "$SRC/MemoryLearningStore.kt" \
  "$SRC/FlickResolver.kt" \
  "$SRC/UserDictionary.kt" \
  "$SRC/ConversionEngine.kt"
"$JAVAC" -encoding UTF-8 -cp "$OUT/classes:$STDLIB" -d "$OUT/test-classes" \
  tests/TestMain.java
"$JAVA" -Dfile.encoding=UTF-8 -cp "$OUT/test-classes:$OUT/classes:$STDLIB" TestMain
