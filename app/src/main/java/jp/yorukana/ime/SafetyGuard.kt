package jp.yorukana.ime

// セーフティ判定 (純ロジック、Android 非依存)。
// 起動が完了する前にプロセスが死ぬ「起動クラッシュ」が連続すると、
// 次回起動は段階的に機能を落としてでも起動できるようにする。
// 永続化と例外記録は SafetyNet (Android 側) が担い、判定と記録の整形だけを
// ここに切り出して単体テスト可能にする。
enum class SafetyMode {
    NORMAL,  // 通常動作
    SAFE,    // 学習の永続化・ユーザー辞書・バイブを止める
    MINIMAL, // 変換エンジンも止め、直接入力だけで起動する
}

object SafetyGuard {

    // 連続起動失敗がこの回数以上でセーフモード。
    const val SAFE_THRESHOLD = 2

    // 連続起動失敗がこの回数以上で最小モード。
    const val MINIMAL_THRESHOLD = 4

    // 連続起動失敗回数から起動モードを決める。
    @JvmStatic
    fun modeFor(consecutiveStartupFailures: Int): SafetyMode {
        return when {
            consecutiveStartupFailures >= MINIMAL_THRESHOLD -> SafetyMode.MINIMAL
            consecutiveStartupFailures >= SAFE_THRESHOLD -> SafetyMode.SAFE
            else -> SafetyMode.NORMAL
        }
    }

    // 起動開始時点の失敗カウンタ。前回起動が未完了 (pending) のまま
    // 終わっていたら、前回は起動中に死んだとみなして +1 する。
    @JvmStatic
    fun failuresAtLaunch(previousFailures: Int, previousLaunchPending: Boolean): Int =
        if (previousLaunchPending) previousFailures + 1 else previousFailures

    // クラッシュ記録を保存用文字列に整形する (時刻 / 例外クラス /
    // メッセージ / スタック先頭)。際限なく長くならないよう切り詰める。
    @JvmStatic
    fun formatCrashRecord(
        timeMillis: Long,
        errorClass: String,
        message: String?,
        stackTop: String,
    ): String {
        val sb = StringBuilder()
        sb.append(timeMillis).append('\n')
        sb.append(errorClass).append('\n')
        sb.append((message ?: "").take(200)).append('\n')
        sb.append(stackTop.take(1200))
        return sb.toString()
    }

    // 記録を [時刻ミリ, 例外クラス, メッセージ] に分解する。
    // 記録が無い・壊れている場合は null。
    @JvmStatic
    fun parseCrashRecord(record: String?): Array<String>? {
        if (record.isNullOrEmpty()) return null
        val lines = record.split("\n", limit = 4)
        if (lines.size < 2) return null
        return arrayOf(
            lines[0],
            lines[1],
            if (lines.size > 2) lines[2] else "",
        )
    }
}
