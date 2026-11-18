package jp.yorukana.ime

// ===== 1. 英数フリック入力の解決 (純ロジック / Android非依存) =====
// Simeji の英字12キーに準拠したグループ表。「グループ＋方向 → 文字」を
// 解決する。方向の番号は FlickResolver と共通 (中央/左/上/右/下)。
// 列の並びはそのままタップ循環の順でもある (ABC なら a→b→c)。
object EnFlickResolver {

    const val GROUP_COUNT = 11

    // TABLE[group][dir]。'\0' は未割り当て。
    // 列の並びは DIR_CENTER, DIR_LEFT, DIR_UP, DIR_RIGHT, DIR_DOWN の順。
    private val TABLE: Array<CharArray> = arrayOf(
        charArrayOf('0', '/', ':', '_', '@'), // 0: 数字・記号
        charArrayOf('a', 'b', 'c', '\u0000', '\u0000'), // 1: ABC
        charArrayOf('d', 'e', 'f', '\u0000', '\u0000'), // 2: DEF
        charArrayOf('g', 'h', 'i', '\u0000', '\u0000'), // 3: GHI
        charArrayOf('j', 'k', 'l', '\u0000', '\u0000'), // 4: JKL
        charArrayOf('m', 'n', 'o', '\u0000', '\u0000'), // 5: MNO
        charArrayOf('p', 'q', 'r', 's', '\u0000'), // 6: PQRS
        charArrayOf('t', 'u', 'v', '\u0000', '\u0000'), // 7: TUV
        charArrayOf('w', 'x', 'y', 'z', '\u0000'), // 8: WXYZ
        charArrayOf('\'', '"', '&', '-', '\u0000'), // 9: 引用符・記号
        charArrayOf(',', '?', '!', ';', '.'), // 10: 句読点
    )

    // キー表面のラベル
    @JvmField
    val LABELS: Array<String> = arrayOf(
        "0/:_", "ABC", "DEF", "GHI", "JKL", "MNO",
        "PQRS", "TUV", "WXYZ", "'\"&-", ",?!;",
    )

    // グループ＋方向の文字を返す。未割り当ての方向は中央の文字に
    // フォールバックする (FlickResolver と同じ規則)。範囲外は '\0'。
    @JvmStatic
    fun charAt(group: Int, dir: Int): Char {
        if (group < 0 || group >= GROUP_COUNT) return '\u0000'
        var d = dir
        if (d < FlickResolver.DIR_CENTER || d > FlickResolver.DIR_DOWN) {
            d = FlickResolver.DIR_CENTER
        }
        val c = TABLE[group][d]
        return if (c != '\u0000') c else TABLE[group][FlickResolver.DIR_CENTER]
    }

    // その方向に文字が割り当てられているか (プレビュー表示用)。
    @JvmStatic
    fun isAssigned(group: Int, dir: Int): Boolean {
        if (group < 0 || group >= GROUP_COUNT) return false
        if (dir < FlickResolver.DIR_CENTER || dir > FlickResolver.DIR_DOWN) return false
        return TABLE[group][dir] != '\u0000'
    }

    // タップ循環用の文字一覧 (割り当て済みを方向順に並べたもの)。
    @JvmStatic
    fun cycleChars(group: Int): List<Char> {
        if (group < 0 || group >= GROUP_COUNT) return emptyList()
        val out = ArrayList<Char>(5)
        for (d in 0 until 5) {
            val c = TABLE[group][d]
            if (c != '\u0000') out.add(c)
        }
        return out
    }

    // キー表面のラベル。範囲外は空文字。
    @JvmStatic
    fun label(group: Int): String {
        return if (group in 0 until GROUP_COUNT) LABELS[group] else ""
    }
}
