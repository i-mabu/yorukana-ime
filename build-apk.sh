#!/usr/bin/env bash
# よるかな IME - 手動ビルド (Gradle なし): 単体テスト + aapt2 + kotlinc
# (+ Compose プラグイン) + D8 (単体 R8)。
# 手順上の要点:
#   * Compose コンパイラプラグインは Maven の PLAIN 成果物を使う
#     (-embeddable 版は不可)。取得時に SHA-256 を検証する
#   * dex 化は build-tools 34 の d8 ではなく単体 R8 8.7.18 の D8 CLI を使う
#   * AAR の res は aapt2 --auto-add-overlay でリンクし、R.txt のある AAR は
#     --extra-packages で R を生成して javac する
#   * classes*.dex は全部入れる (マルチ dex 必須)
#   * ネイティブ .so は lib/<abi>/ へ入れ、zipalign -p でページ整列する
#   * 仕上げに aapt2 dump badging で package / version / SDK / 権限を検証する
# 依存 (libs/) とツール jar が無い場合は自動で取得する (初回のみネット必須)。
# 引数: [debug|release] (既定 debug)。release は環境変数
#   RELEASE_KEYSTORE / RELEASE_KEYSTORE_PASS / RELEASE_KEY_ALIAS
#   (/ RELEASE_KEY_PASS) で署名し、dist/yorukana-release.apk を出す。
set -euo pipefail
cd "$(dirname "$0")"
export LANG="${LANG:-C.UTF-8}" LC_ALL="${LC_ALL:-C.UTF-8}"
export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/workspace/tools/android/sdk}}"
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
[ -f "$AJAR" ] || { echo "android.jar not found: $AJAR" >&2; exit 1; }
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
JAVAC="${JAVA_BIN}javac"; KEYTOOL="${JAVA_BIN}keytool"
command -v "$JAVAC" >/dev/null 2>&1 || JAVAC=javac
command -v "$KEYTOOL" >/dev/null 2>&1 || KEYTOOL=keytool

# Kotlin 2.0.21 の解決 (tests/run-tests.sh と共通)
. "$(pwd)/tools/kotlin-env.sh"
[ "$KOTLIN_MODE" = "kotlinc" ] || { echo "kotlinc (2.0.21) が必要です" >&2; exit 1; }
KOTLINC="$KOTLINC_BIN"
STDLIB="$KOTLIN_STDLIB_JAR"

MODE="${1:-debug}"
case "$MODE" in debug|release) ;; *) echo "usage: $0 [debug|release]" >&2; exit 1;; esac

# ---- [0/7] 単体テスト -------------------------------------------------------
if [ "${SKIP_TESTS:-0}" = "1" ]; then
  echo "[0/7] unit tests (skipped: SKIP_TESTS=1)"
else
  echo "[0/7] unit tests"
  ./tests/run-tests.sh
fi

# ---- 依存とツール jar の自動取得 -------------------------------------------
if [ ! -f libs/artifacts.txt ]; then
  echo "[0/7] 依存を解決しています (tools/resolve.py)"
  python3 tools/resolve.py || { echo "依存の解決に失敗しました (tools/resolve.py)" >&2; exit 1; }
fi
COMPOSE_PLUGIN="tools/kotlin-compose-compiler-plugin-2.0.21.jar"
PLUGIN_SHA256="7a38399c652ef449075aa437c5dbaf0ea02915276a54ee008326dfb6af5b273f"
if [ ! -f "$COMPOSE_PLUGIN" ]; then
  echo "[0/7] Compose コンパイラプラグインを取得しています"
  curl -fsSL -o "$COMPOSE_PLUGIN" "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compose-compiler-plugin/2.0.21/kotlin-compose-compiler-plugin-2.0.21.jar"
fi
echo "$PLUGIN_SHA256  $COMPOSE_PLUGIN" | sha256sum -c - >/dev/null \
  || { echo "Compose プラグインの SHA-256 が一致しません" >&2; exit 1; }
R8_JAR="tools/r8-8.7.18.jar"
R8_SHA256="58366f77067207c39a17d469de7b05701d2877212a9c55201bcb0af43e59e903"
if [ ! -f "$R8_JAR" ]; then
  echo "[0/7] R8 (D8) を取得しています"
  curl -fsSL -o "$R8_JAR" "https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.7.18/r8-8.7.18.jar"
fi
echo "$R8_SHA256  $R8_JAR" | sha256sum -c - >/dev/null \
  || { echo "R8 の SHA-256 が一致しません" >&2; exit 1; }

BUILD=build/manual
AARS=build/aars
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/libres" "$AARS"

# ---- ライブラリ jar の収集 -------------------------------------------------
CP_JARS=()
DEX_JARS=()
AAR_DIRS=()
while IFS= read -r f; do
  case "$f" in kotlin-stdlib*) continue;; esac   # stdlib は kotlinc 配布物を使う
  path="libs/$f"
  [ -f "$path" ] || { echo "missing artifact: $path (run tools/resolve.py)" >&2; exit 1; }
  if [[ "$f" == *.aar ]]; then
    d="$AARS/${f%.aar}"
    AAR_DIRS+=("$d")
    if [ ! -d "$d" ]; then mkdir -p "$d"; unzip -qo "$path" -d "$d"; fi
    if [ -f "$d/classes.jar" ]; then
      CP_JARS+=("$d/classes.jar"); DEX_JARS+=("$d/classes.jar")
    fi
  else
    # クラスを含まない KMP ルート jar 等は dex 化対象から外す
    if [ "$(unzip -l "$path" | grep -c '\.class' || true)" -gt 0 ]; then
      CP_JARS+=("$path"); DEX_JARS+=("$path")
    fi
  fi
done < libs/artifacts.txt
CP="$(IFS=:; echo "${CP_JARS[*]}")"
echo "libraries: ${#CP_JARS[@]} jars with classes"

# ---- [1/7] aapt2 compile: アプリ + 全 AAR の res ------------------------------
echo "[1/7] aapt2 compile (app + library resources)"
"$BT/aapt2" compile --dir app/src/main/res -o "$BUILD/libres/app.zip"
RES_ZIPS=("$BUILD/libres/app.zip")
EXTRA_PKGS=()
for d in "${AAR_DIRS[@]}"; do
  if [ -d "$d/res" ] && [ -n "$(find "$d/res" -type f -print -quit)" ]; then
    out="$BUILD/libres/$(basename "$d").zip"
    "$BT/aapt2" compile --dir "$d/res" -o "$out"
    RES_ZIPS+=("$out")
  fi
  if [ -f "$d/R.txt" ]; then
    pkg="$(grep -o 'package="[^"]*"' "$d/AndroidManifest.xml" | head -1 | cut -d'"' -f2)"
    [ -n "$pkg" ] && EXTRA_PKGS+=("$pkg")
  fi
done
echo "  resource sets: ${#RES_ZIPS[@]}, R packages: ${#EXTRA_PKGS[@]}"

# ---- [2/7] aapt2 link -------------------------------------------------------
echo "[2/7] aapt2 link (--auto-add-overlay, --extra-packages)"
EXTRA_ARG=()
if [ "${#EXTRA_PKGS[@]}" -gt 0 ]; then
  EXTRA_ARG=(--extra-packages "$(IFS=:; echo "${EXTRA_PKGS[*]}")")
fi
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android"|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="jp.yorukana.ime"|' \
  app/src/main/AndroidManifest.xml > "$BUILD/AndroidManifest.xml"
"$BT/aapt2" link -o "$BUILD/app-unsigned.apk" -I "$AJAR" \
  --manifest "$BUILD/AndroidManifest.xml" "${RES_ZIPS[@]}" \
  --java "$BUILD/gen" --auto-add-overlay "${EXTRA_ARG[@]}" \
  --min-sdk-version 26 --target-sdk-version 34

# ---- [3/7] kotlinc + Compose プラグイン --------------------------------------
echo "[3/7] kotlinc (+ compose-compiler plugin)"
"$KOTLINC" -cp "$AJAR:$CP" -d "$BUILD/classes" \
  -Xplugin="$COMPOSE_PLUGIN" \
  $(find app/src/main/java -name '*.kt' | sort) $(find "$BUILD/gen" -name '*.java' | sort)

# ---- [4/7] javac: 生成された R クラス群 --------------------------------------
echo "[4/7] javac R.java (app + libraries)"
"$JAVAC" --release 17 -encoding UTF-8 -cp "$AJAR" -d "$BUILD/classes" \
  $(find "$BUILD/gen" -name '*.java' | sort)
[ -f "$BUILD/classes/jp/yorukana/ime/YoruKanaService.class" ] || { echo "YoruKanaService.class missing" >&2; exit 1; }

# ---- [5/7] D8 (単体 R8 8.7.18、マルチ dex) ------------------------------------
echo "[5/7] d8 (standalone R8 8.7.18)"
# ヒープ上限と単一スレッド: 共有 VM で他ビルドと並走すると OOM Kill されるため絞る
R8_JAVA_OPTS="${R8_JAVA_OPTS:--Xmx1536m}"
"${JAVA_BIN}java" $R8_JAVA_OPTS -cp "$R8_JAR" com.android.tools.r8.D8 \
  --thread-count 1 --min-api 26 --lib "$AJAR" --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name '*.class' | sort) "${DEX_JARS[@]}" "$STDLIB"
ls "$BUILD/dex"

# ---- [6/7] パッケージ (dex 全部 + ネイティブ lib) + zipalign + 署名 --------------
echo "[6/7] package + zipalign + sign ($MODE)"
cp "$BUILD/app-unsigned.apk" "$BUILD/app-with-dex.apk"
( cd "$BUILD/dex" && zip -q -uj ../app-with-dex.apk classes*.dex )
while IFS= read -r so; do
  rel="${so#*jni/}"
  mkdir -p "$BUILD/nativelib/lib/$(dirname "$rel")"
  cp "$so" "$BUILD/nativelib/lib/$rel"
done < <(find "${AAR_DIRS[@]}" -path '*/jni/*' -name '*.so')
if [ -d "$BUILD/nativelib" ]; then
  ( cd "$BUILD/nativelib" && zip -q -0 -r ../app-with-dex.apk lib )
fi
"$BT/zipalign" -f -p 4 "$BUILD/app-with-dex.apk" "$BUILD/app-aligned.apk"
mkdir -p dist
if [ "$MODE" = "release" ]; then
  KS="${RELEASE_KEYSTORE:?release には RELEASE_KEYSTORE が必要です}"
  KS_PASS="${RELEASE_KEYSTORE_PASS:?release には RELEASE_KEYSTORE_PASS が必要です}"
  KEY_ALIAS="${RELEASE_KEY_ALIAS:?release には RELEASE_KEY_ALIAS が必要です}"
  KEY_PASS="${RELEASE_KEY_PASS:-$KS_PASS}"
  OUT_APK="dist/yorukana-release.apk"
else
  KS="debug.keystore"
  KS_PASS="android"
  KEY_ALIAS="androiddebugkey"
  KEY_PASS="android"
  if [ ! -f "$KS" ]; then
    "$KEYTOOL" -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KEY_PASS" \
      -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=YoruKana,C=JP" >/dev/null 2>&1
  fi
  OUT_APK="dist/yorukana.apk"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --ks-key-alias "$KEY_ALIAS" --out "$BUILD/yorukana-signed.apk" "$BUILD/app-aligned.apk"
cp "$BUILD/yorukana-signed.apk" "$OUT_APK"

# ---- [7/7] badging 検証 -------------------------------------------------------
echo "[7/7] verify badging"
BADGING="$("$BT/aapt2" dump badging "$OUT_APK")"
echo "$BADGING" | head -4
echo "$BADGING" | grep -q "package: name='jp.yorukana.ime'" || { echo "badging: package mismatch" >&2; exit 1; }
echo "$BADGING" | grep -q "versionCode='1'" || { echo "badging: versionCode mismatch" >&2; exit 1; }
echo "$BADGING" | grep -q "versionName='1.0.0'" || { echo "badging: versionName mismatch" >&2; exit 1; }
echo "$BADGING" | grep -q "sdkVersion:'26'" || { echo "badging: minSdk mismatch" >&2; exit 1; }
echo "$BADGING" | grep -q "targetSdkVersion:'34'" || { echo "badging: targetSdk mismatch" >&2; exit 1; }
echo "$BADGING" | grep -q "uses-permission: name='android.permission.VIBRATE'" || { echo "badging: VIBRATE missing" >&2; exit 1; }
if echo "$BADGING" | grep -q "android.permission.INTERNET"; then
  echo "badging: INTERNET permission must not be present" >&2; exit 1
fi
unzip -l "$OUT_APK" | grep -q "classes.dex" || { echo "classes.dex missing" >&2; exit 1; }
"$BT/apksigner" verify "$OUT_APK"
echo "OK: $OUT_APK"
ls -la "$OUT_APK"
sha256sum "$OUT_APK"
