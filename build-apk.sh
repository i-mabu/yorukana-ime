#!/usr/bin/env bash
# よるかな IME - オフライン手動ビルド (Gradle/AGPを使わず aapt2 + javac + d8 でAPKを作る)
# 必要: JDK 21 (~/workspace/tools/jdk-21) と Android SDK (platforms;android-34, build-tools;34.0.0)
set -euo pipefail
cd "$(dirname "$0")"
export LANG="${LANG:-C.UTF-8}" LC_ALL="${LC_ALL:-C.UTF-8}"
export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH" # d8 等が java を PATH から探すため

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/workspace/tools/android/sdk}}"
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
[ -f "$AJAR" ] || { echo "android.jar が見つかりません: $AJAR" >&2; exit 1; }
[ -x "$BT/aapt2" ] || { echo "aapt2 が見つかりません: $BT" >&2; exit 1; }

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
JAVAC="${JAVA_BIN}javac"; KEYTOOL="${JAVA_BIN}keytool"
command -v "$JAVAC" >/dev/null 2>&1 || JAVAC=javac
command -v "$KEYTOOL" >/dev/null 2>&1 || KEYTOOL=keytool

BUILD=build/manual
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir app/src/main/res -o "$BUILD/compiled.zip"

echo "[2/6] aapt2 link"
# 手動ビルドでは manifest に package 属性が要るため、一時コピーに注入して使う
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android"|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="jp.yorukana.ime"|' \
  app/src/main/AndroidManifest.xml > "$BUILD/AndroidManifest.xml"
"$BT/aapt2" link -o "$BUILD/app-unsigned.apk" -I "$AJAR" \
  --manifest "$BUILD/AndroidManifest.xml" "$BUILD/compiled.zip" \
  --java "$BUILD/gen" --min-sdk-version 26 --target-sdk-version 34

echo "[3/6] javac"
find app/src/main/java "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
"$JAVAC" --release 17 -encoding UTF-8 -cp "$AJAR" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "[4/6] d8"
"$BT/d8" --min-api 26 --output "$BUILD/dex" $(find "$BUILD/classes" -name '*.class')

echo "[5/6] package + zipalign"
cp "$BUILD/app-unsigned.apk" "$BUILD/app-with-dex.apk"
( cd "$BUILD/dex" && zip -q -uj ../app-with-dex.apk classes.dex )
"$BT/zipalign" -f -p 4 "$BUILD/app-with-dex.apk" "$BUILD/app-aligned.apk"

echo "[6/6] sign (debug keystore)"
KS="debug.keystore"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=YoruKana,C=JP" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$BUILD/yorukana.apk" "$BUILD/app-aligned.apk"
"$BT/apksigner" verify --print-certs "$BUILD/yorukana.apk" | head -3
mkdir -p dist
cp "$BUILD/yorukana.apk" dist/yorukana.apk
echo "OK: dist/yorukana.apk"
