#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
JAVA="$JAVA_HOME/bin/java"
export PATH="$JAVA_HOME/bin:$PATH"
. "$(pwd)/tools/kotlin-env.sh"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
kotlin_compile "$OUT" "" \
  app/src/main/java/jp/yorukana/ime/RomajiConverter.kt \
  app/src/main/java/jp/yorukana/ime/KanaUtils.kt \
  app/src/main/java/jp/yorukana/ime/FlickResolver.kt \
  app/src/main/java/jp/yorukana/ime/EnFlickResolver.kt \
  app/src/main/java/jp/yorukana/ime/DictionaryData.kt \
  app/src/main/java/jp/yorukana/ime/Dictionary.kt \
  app/src/main/java/jp/yorukana/ime/UserDictionary.kt \
  app/src/main/java/jp/yorukana/ime/LearningStore.kt \
  app/src/main/java/jp/yorukana/ime/MemoryLearningStore.kt \
  app/src/main/java/jp/yorukana/ime/ConversionEngine.kt \
  app/src/main/java/jp/yorukana/ime/SafetyGuard.kt \
  app/src/main/java/jp/yorukana/ime/SymbolData.kt \
  tests/TestMain.kt
"$JAVA" -Dfile.encoding=UTF-8 -cp "$OUT:$KOTLIN_STDLIB_JAR" TestMainKt
