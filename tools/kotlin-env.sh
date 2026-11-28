# Kotlin コンパイラ 2.0.21 の解決 (build-apk.sh / tests/run-tests.sh 共通)
# 優先順位: (1) $KOTLIN_HOME/bin/kotlinc (明示指定を最優先)
#           (2) ~/workspace/tools/kotlin の kotlinc
#           (3) PATH 上の kotlinc (シンボリックリンクを解決して stdlib を探す)
#           (4) embeddable jars (Gradle 同梱)
#   バージョンが 2.0.21 でない kotlinc は採用しない。CI ランナーには素性不明の
#   kotlinc が /usr/bin にあることがあり、それに引きずられて落ちるのを防ぐため。
# 提供するもの:
#   KOTLIN_STDLIB_JAR : kotlin-stdlib jar のパス (d8 / 実行時 classpath 用)
#   kotlin_compile <out_dir> <extra_classpath> <source...> : 一括コンパイル
# このファイルは source して使う。見つからない場合はエラーを出して非ゼロで戻る。

KOTLIN_VERSION="2.0.21"
KOTLIN_MODE=""
KOTLINC_BIN=""
KOTLIN_EMBEDDABLE_CP=""
KOTLIN_STDLIB_JAR=""

# kotlinc 候補を検証し、使えるなら KOTLIN_MODE 等を設定して 0 を返す
_try_kotlinc() {
    local kc="$1"
    [ -x "$kc" ] || return 1
    local real dir home cand ver
    real="$(readlink -f "$kc" 2>/dev/null || echo "$kc")"
    dir="$(dirname "$real")"
    home="$(cd "$dir/.." 2>/dev/null && pwd)"
    for cand in \
        "$home/lib/kotlin-stdlib.jar" \
        "$home"/lib/kotlin-stdlib-*.jar \
        "$home/share/kotlin/lib/kotlin-stdlib.jar" \
        "$dir/../share/kotlin/lib/kotlin-stdlib.jar" \
        "/usr/share/kotlin/lib/kotlin-stdlib.jar" \
        "/usr/share/java/kotlin-stdlib.jar"; do
        if [ -f "$cand" ]; then
            ver="$("$kc" -version 2>&1 | head -1)"
            case "$ver" in
                *"$KOTLIN_VERSION"*) ;;
                *) return 1 ;;
            esac
            KOTLIN_STDLIB_JAR="$cand"
            KOTLIN_MODE="kotlinc"
            KOTLINC_BIN="$kc"
            return 0
        fi
    done
    return 1
}

_kotlin_resolve() {
    if [ -n "${KOTLIN_HOME:-}" ] && _try_kotlinc "$KOTLIN_HOME/bin/kotlinc"; then
        return 0
    fi
    if _try_kotlinc "$HOME/workspace/tools/kotlin/bin/kotlinc"; then
        return 0
    fi
    if command -v kotlinc >/dev/null 2>&1 && _try_kotlinc "$(command -v kotlinc)"; then
        return 0
    fi
    local dir=""
    if [ -n "${KOTLIN_EMBEDDABLE_DIR:-}" ] && [ -f "$KOTLIN_EMBEDDABLE_DIR/kotlin-compiler-embeddable-$KOTLIN_VERSION.jar" ]; then
        dir="$KOTLIN_EMBEDDABLE_DIR"
    elif [ -f "$HOME/workspace/tools/android/gradle/lib/kotlin-compiler-embeddable-$KOTLIN_VERSION.jar" ]; then
        dir="$HOME/workspace/tools/android/gradle/lib"
    fi
    if [ -n "$dir" ]; then
        local jars=(
            "$dir/kotlin-compiler-embeddable-$KOTLIN_VERSION.jar"
            "$dir/trove4j-1.0.20200330.jar"
            "$dir/annotations-24.0.1.jar"
            "$dir/kotlin-stdlib-$KOTLIN_VERSION.jar"
            "$dir/kotlin-daemon-embeddable-$KOTLIN_VERSION.jar"
        )
        local j
        for j in "${jars[@]}"; do
            if [ ! -f "$j" ]; then
                echo "エラー: embeddable コンパイラ用 jar が不足しています: $j" >&2
                return 1
            fi
        done
        KOTLIN_EMBEDDABLE_CP="$(IFS=:; echo "${jars[*]}")"
        KOTLIN_STDLIB_JAR="$dir/kotlin-stdlib-$KOTLIN_VERSION.jar"
        KOTLIN_MODE="embeddable"
        return 0
    fi
    echo "エラー: Kotlin コンパイラ $KOTLIN_VERSION が見つかりません。" >&2
    echo "  対処のいずれかを行ってください:" >&2
    echo "    - KOTLIN_HOME に Kotlin $KOTLIN_VERSION のインストール先を設定する" >&2
    echo "    - kotlinc ($KOTLIN_VERSION) を PATH に入れる" >&2
    echo "    - KOTLIN_EMBEDDABLE_DIR に kotlin-compiler-embeddable-$KOTLIN_VERSION.jar 等のあるディレクトリを指定する" >&2
    return 1
}

if ! _kotlin_resolve; then
    return 1 2>/dev/null || exit 1
fi

kotlin_compile() {
    local out="$1"
    local cp="$2"
    shift 2
    mkdir -p "$out"
    local fullcp="$KOTLIN_STDLIB_JAR"
    if [ -n "$cp" ]; then fullcp="$fullcp:$cp"; fi
    if [ "$KOTLIN_MODE" = "kotlinc" ]; then
        "$KOTLINC_BIN" -no-stdlib -no-reflect -jvm-target 17 -cp "$fullcp" -d "$out" "$@"
    else
        local java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
        command -v "$java_bin" >/dev/null 2>&1 || java_bin="java"
        "$java_bin" -cp "$KOTLIN_EMBEDDABLE_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
            -no-stdlib -no-reflect -jvm-target 17 -cp "$fullcp" -d "$out" "$@"
    fi
}
