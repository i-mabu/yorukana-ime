#!/usr/bin/env bash
# よるかな IME 純ロジック単体テスト (javac + java)
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$JAVAC" -encoding UTF-8 -d "$OUT" \
  app/src/main/java/jp/yorukana/ime/RomajiConverter.java \
  app/src/main/java/jp/yorukana/ime/KanaUtils.java \
  app/src/main/java/jp/yorukana/ime/DictionaryData.java \
  app/src/main/java/jp/yorukana/ime/Dictionary.java \
  app/src/main/java/jp/yorukana/ime/LearningStore.java \
  app/src/main/java/jp/yorukana/ime/MemoryLearningStore.java \
  app/src/main/java/jp/yorukana/ime/ConversionEngine.java \
  tests/TestMain.java
"$JAVA" -Dfile.encoding=UTF-8 -cp "$OUT" TestMain
