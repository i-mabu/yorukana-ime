#!/usr/bin/env bash
# よるかな IME - オフライン手動ビルド (Gradle/AGPを使わず aapt2 + kotlinc + R8/D8 でAPKを作る)
# v1.3.0 から UI は Jetpack Compose。Compose 化に伴うパイプラインの要点
# (compose-spike で実証済みの手順):
#   * Compose コンパイラは Maven 版 kotlin-compose-compiler-plugin を -Xplugin で使う
#     (kotlinc 同梱の compose-compiler.jar は embeddable コンパイラ向けで使えない)
#   * ライブラリ (libs/, tools/resolve.py で解決) の AAR リソースも aapt2 で
#     コンパイルし、--auto-add-overlay + --extra-packages でリンクする
#   * dex 化は build-tools 34 の d8 ではなく単体 R8 8.7.18 の D8 CLI を使う
#     (34 の d8 は匿名内部クラスで NPE クラッシュするため)。マルチ dex 必須。
#   * AAR 内の .so (androidx.graphics.path) は lib/<abi>/ に同梱する
#   * AAR のマニフェストはマージしない (AGP が無いため)。androidx.startup 等の
#     ライブラリ側コンポーネントは無効のまま (Compose UI の動作には影響しない)
# 必要: JDK 21 (~/workspace/tools/jdk-21)、Kotlin コンパイラ 2.0.21
#       (~/workspace/tools/kotlin)、Android SDK (platforms;android-34, build-tools;34.0.0)
#       ※ Compose コンパイラプラグインはコンパイラのバージョンと完全一致が
#         必須のため、本体ビルドは 2.0.21 を使う。単体テスト
#         (tests/run-tests.sh) は純ロジックのみで従来のコンパイラ指定のまま。
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

KOTLINC_HOME="${KOTLIN_HOME:-$HOME/workspace/tools/kotlin}"
KOTLINC="$KOTLINC_HOME/bin/kotlinc"
STDLIB="$KOTLINC_HOME/lib/kotlin-stdlib.jar"
[ -x "$KOTLINC" ] || { echo "kotlinc が見つかりません: $KOTLINC" >&2; exit 1; }
[ -f "$STDLIB" ] || { echo "kotlin-stdlib.jar が見つかりません: $STDLIB" >&2; exit 1; }

COMPOSE_PLUGIN="tools/kotlin-compose-compiler-plugin-2.0.21.jar"
[ -f "$COMPOSE_PLUGIN" ] || { echo "Compose コンパイラプラグインが見つかりません: $COMPOSE_PLUGIN" >&2; exit 1; }
R8_JAR="tools/r8-8.7.18.jar"
[ -f "$R8_JAR" ] || { echo "R8 jar が見つかりません: $R8_JAR" >&2; exit 1; }
[ -f libs/artifacts.txt ] || { echo "libs/artifacts.txt がありません (python3 tools/resolve.py で依存を解決してください)" >&2; exit 1; }

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
JAVAC="${JAVA_BIN}javac"; KEYTOOL="${JAVA_BIN}keytool"
command -v "$JAVAC" >/dev/null 2>&1 || JAVAC=javac
command -v "$KEYTOOL" >/dev/null 2>&1 || KEYTOOL=keytool

BUILD=build/manual
AARS=build/aars
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/libres" "$AARS"

echo "[1/8] unit tests"
./tests/run-tests.sh

# ---- ライブラリ jar の収集 (libs/artifacts.txt の順序どおり) ----
CP_JARS=()    # コンパイル用クラスパス
DEX_JARS=()   # dex 入力
AAR_DIRS=()   # 展開済み AAR ディレクトリ
while IFS= read -r f; do
  case "$f" in kotlin-stdlib*) continue;; esac   # stdlib は kotlinc 同梱を使う
  path="libs/$f"
  [ -f "$path" ] || { echo "依存アーティファクトがありません: $path (python3 tools/resolve.py)" >&2; exit 1; }
  if [[ "$f" == *.aar ]]; then
    d="$AARS/${f%.aar}"
    AAR_DIRS+=("$d")
    if [ ! -d "$d" ]; then mkdir -p "$d"; unzip -qo "$path" -d "$d"; fi
    if [ -f "$d/classes.jar" ]; then
      CP_JARS+=("$d/classes.jar"); DEX_JARS+=("$d/classes.jar")
    fi
  else
    # クラスを含まない KMP ルート jar は除外する (重複クラス回避)
    if [ "$(unzip -l "$path" | grep -c '\.class' || true)" -gt 0 ]; then
      CP_JARS+=("$path"); DEX_JARS+=("$path")
    fi
  fi
done < libs/artifacts.txt
CP="$(IFS=:; echo "${CP_JARS[*]}")"
echo "libraries: ${#CP_JARS[@]} jars with classes"

echo "[2/8] aapt2 compile (app + library resources)"
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

echo "[3/8] aapt2 link (--auto-add-overlay, --extra-packages)"
# 手動ビルドでは manifest に package 属性が要るため、一時コピーに注入して使う
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android"|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="jp.yorukana.ime"|' \
  app/src/main/AndroidManifest.xml > "$BUILD/AndroidManifest.xml"
EXTRA_ARG=()
if [ "${#EXTRA_PKGS[@]}" -gt 0 ]; then
  EXTRA_ARG=(--extra-packages "$(IFS=:; echo "${EXTRA_PKGS[*]}")")
fi
"$BT/aapt2" link -o "$BUILD/app-unsigned.apk" -I "$AJAR" \
  --manifest "$BUILD/AndroidManifest.xml" "${RES_ZIPS[@]}" \
  --java "$BUILD/gen" --auto-add-overlay "${EXTRA_ARG[@]}" \
  --min-sdk-version 26 --target-sdk-version 34

echo "[4/8] kotlinc (+ compose-compiler plugin)"
find app/src/main/java -name '*.kt' > "$BUILD/sources.txt"
"$KOTLINC" -cp "$AJAR:$CP" -d "$BUILD/classes" \
  -Xplugin="$COMPOSE_PLUGIN" \
  @"$BUILD/sources.txt" $(find "$BUILD/gen" -name '*.java')

echo "[5/8] javac R.java (app + libraries)"
"$JAVAC" --release 17 -encoding UTF-8 -cp "$AJAR" -d "$BUILD/classes" \
  $(find "$BUILD/gen" -name '*.java')

echo "[6/8] d8 (standalone R8 8.7.18, multidex)"
# ヒープ上限を明示する (無指定だと共有 VM で他プロセスと合わせて
# メモリを食い潰し、OOM kill されることがあるため)。変更は環境変数で。
R8_JAVA_OPTS="${R8_JAVA_OPTS:--Xmx2g}"
# shellcheck disable=SC2086
"${JAVA_BIN}java" $R8_JAVA_OPTS -cp "$R8_JAR" com.android.tools.r8.D8 \
  --min-api 26 --lib "$AJAR" --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name '*.class') "${DEX_JARS[@]}" "$STDLIB"
ls "$BUILD/dex"

echo "[7/8] package (dex + native libs) + zipalign"
cp "$BUILD/app-unsigned.apk" "$BUILD/app-with-dex.apk"
( cd "$BUILD/dex" && zip -q -uj ../app-with-dex.apk classes*.dex )
# AAR 内の .so は lib/<abi>/ に無圧縮で同梱し、zipalign -p でページ境界に揃える
while IFS= read -r so; do
  rel="${so#*jni/}"   # <abi>/<lib>.so
  mkdir -p "$BUILD/nativelib/lib/$(dirname "$rel")"
  cp "$so" "$BUILD/nativelib/lib/$rel"
done < <(find "${AAR_DIRS[@]}" -path '*/jni/*' -name '*.so')
if [ -d "$BUILD/nativelib" ]; then
  ( cd "$BUILD/nativelib" && zip -q -0 -r ../app-with-dex.apk lib )
fi
"$BT/zipalign" -f -p 4 "$BUILD/app-with-dex.apk" "$BUILD/app-aligned.apk"

echo "[8/8] sign ($MODE)"
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
