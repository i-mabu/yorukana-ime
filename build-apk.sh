#!/usr/bin/env bash
# よるかな IME - オフライン手動ビルド (Gradle/AGPを使わず aapt2 + kotlinc + d8 でAPKを作る)
# 必要: JDK 21 (~/workspace/tools/jdk-21)、Kotlin コンパイラ
#       (~/workspace/tools/kotlin/kotlinc)、Android SDK (platforms;android-34, build-tools;34.0.0)
# 使い方:
#   ./build-apk.sh           単体テスト → ビルド → デバッグ署名 (dist/yorukana.apk)
#   ./build-apk.sh release   単体テスト → ビルド → リリース署名 (dist/yorukana-release.apk)
#     リリース署名は環境変数で指定する (スクリプト内に秘密情報は書かない):
#       RELEASE_KEYSTORE / RELEASE_KEYSTORE_PASS / RELEASE_KEY_ALIAS
#       RELEASE_KEY_PASS (省略時は RELEASE_KEYSTORE_PASS と同じ)
set -euo pipefail
cd "$(dirname "$0")"
export LANG="${LANG:-C.UTF-8}" LC_ALL="${LC_ALL:-C.UTF-8}"
export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH" # d8 / kotlinc 等が java を PATH から探すため

# ビルドモード (引数なし=debug)
MODE="${1:-debug}"
case "$MODE" in
  debug|release) ;;
  *) echo "使い方: $0 [debug|release]" >&2; exit 1 ;;
esac
# release の場合は署名情報を最初に検証する (ビルドが終わってから気づかないように)
if [ "$MODE" = "release" ]; then
  if [ -z "${RELEASE_KEYSTORE:-}" ] || [ -z "${RELEASE_KEYSTORE_PASS:-}" ] || [ -z "${RELEASE_KEY_ALIAS:-}" ]; then
    echo "release 署名には環境変数 RELEASE_KEYSTORE / RELEASE_KEYSTORE_PASS / RELEASE_KEY_ALIAS が必要です (RELEASE_KEY_PASS は省略時はストアパスワードと同じ)" >&2
    exit 1
  fi
  [ -f "$RELEASE_KEYSTORE" ] || { echo "キーストアが見つかりません: $RELEASE_KEYSTORE" >&2; exit 1; }
fi

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/workspace/tools/android/sdk}}"
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
[ -f "$AJAR" ] || { echo "android.jar が見つかりません: $AJAR" >&2; exit 1; }
[ -x "$BT/aapt2" ] || { echo "aapt2 が見つかりません: $BT" >&2; exit 1; }

KOTLINC_HOME="${KOTLIN_HOME:-$HOME/workspace/tools/kotlin/kotlinc}"
KOTLINC="$KOTLINC_HOME/bin/kotlinc"
STDLIB="$KOTLINC_HOME/lib/kotlin-stdlib.jar"
[ -x "$KOTLINC" ] || { echo "kotlinc が見つかりません: $KOTLINC" >&2; exit 1; }
[ -f "$STDLIB" ] || { echo "kotlin-stdlib.jar が見つかりません: $STDLIB" >&2; exit 1; }

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
JAVAC="${JAVA_BIN}javac"; KEYTOOL="${JAVA_BIN}keytool"
command -v "$JAVAC" >/dev/null 2>&1 || JAVAC=javac
command -v "$KEYTOOL" >/dev/null 2>&1 || KEYTOOL=keytool

BUILD=build/manual
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "[1/7] unit tests"
./tests/run-tests.sh

echo "[2/7] aapt2 compile"
"$BT/aapt2" compile --dir app/src/main/res -o "$BUILD/compiled.zip"

echo "[3/7] aapt2 link"
# 手動ビルドでは manifest に package 属性が要るため、一時コピーに注入して使う
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android"|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="jp.yorukana.ime"|' \
  app/src/main/AndroidManifest.xml > "$BUILD/AndroidManifest.xml"
"$BT/aapt2" link -o "$BUILD/app-unsigned.apk" -I "$AJAR" \
  --manifest "$BUILD/AndroidManifest.xml" "$BUILD/compiled.zip" \
  --java "$BUILD/gen" --min-sdk-version 26 --target-sdk-version 34

echo "[4/7] kotlinc (+ javac for generated R.java)"
find app/src/main/java -name '*.kt' > "$BUILD/sources.txt"
"$KOTLINC" -cp "$AJAR" -d "$BUILD/classes" @"$BUILD/sources.txt"
find "$BUILD/gen" -name '*.java' > "$BUILD/r-sources.txt"
if [ -s "$BUILD/r-sources.txt" ]; then
  "$JAVAC" --release 17 -encoding UTF-8 -cp "$AJAR" -d "$BUILD/classes" @"$BUILD/r-sources.txt"
fi

echo "[5/7] d8 (app classes + kotlin-stdlib)"
"$BT/d8" --min-api 26 --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name '*.class') "$STDLIB"

echo "[6/7] package + zipalign"
cp "$BUILD/app-unsigned.apk" "$BUILD/app-with-dex.apk"
( cd "$BUILD/dex" && zip -q -uj ../app-with-dex.apk classes.dex )
"$BT/zipalign" -f -p 4 "$BUILD/app-with-dex.apk" "$BUILD/app-aligned.apk"

echo "[7/7] sign ($MODE)"
if [ "$MODE" = "release" ]; then
  KS="$RELEASE_KEYSTORE"
  KS_PASS="$RELEASE_KEYSTORE_PASS"
  KEY_ALIAS="$RELEASE_KEY_ALIAS"
  KEY_PASS="${RELEASE_KEY_PASS:-$KS_PASS}"
  OUT="yorukana-release.apk"
else
  KS="debug.keystore"
  KS_PASS="android"; KEY_ALIAS="androiddebugkey"; KEY_PASS="android"
  if [ ! -f "$KS" ]; then
    "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=YoruKana,C=JP" >/dev/null 2>&1
  fi
  OUT="yorukana.apk"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --ks-key-alias "$KEY_ALIAS" --out "$BUILD/$OUT" "$BUILD/app-aligned.apk"
"$BT/apksigner" verify --print-certs "$BUILD/$OUT" | head -3
mkdir -p dist
cp "$BUILD/$OUT" "dist/$OUT"
echo "=== build complete ($MODE) ==="
echo "APK: dist/$OUT"
echo "size: $(stat -c%s "dist/$OUT") bytes"
echo "sha256: $(sha256sum "dist/$OUT" | awk '{print $1}')"
