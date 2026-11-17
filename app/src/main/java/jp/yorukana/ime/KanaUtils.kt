package jp.yorukana.ime

// ===== 1. かなユーティリティ (純ロジック / Android非依存) =====
// ひらがな⇔カタカナ変換、濁点/半濁点/小書きトグル、12キー段循環表。
object KanaUtils {

    // ----- 1.1 ひらがな ⇔ カタカナ -----
    // ぁ(0x3041)〜ゖ(0x3096) と ァ(0x30A1)〜ヶ(0x30F6) は 0x60 差で対応する。
    @JvmStatic
    fun toKatakana(s: String): String {
        val sb = StringBuilder(s.length)
        for (i in s.indices) {
            var c = s[i]
            if (c >= 'ぁ' && c <= 'ゖ') c = (c.code + 0x60).toChar()
            sb.append(c)
        }
        return sb.toString()
    }

    @JvmStatic
    fun toHiragana(s: String): String {
        val sb = StringBuilder(s.length)
        for (i in s.indices) {
            var c = s[i]
            if (c >= 'ァ' && c <= 'ヶ') c = (c.code - 0x60).toChar()
            sb.append(c)
        }
        return sb.toString()
    }

    // ----- 1.1b ユーザー辞書の「よみ」検証 -----
    // よみとして許す文字: ひらがな (ぁ〜ゖ)、長音 ー、々、ゝ、ゞ のみ。
    // 英字・漢字・数字・カタカナ等が混ざっていたら false (空文字列も false)。
    @JvmStatic
    fun isValidReading(s: String?): Boolean {
        if (s.isNullOrEmpty()) return false
        for (i in s.indices) {
            val c = s[i]
            if (c >= 'ぁ' && c <= 'ゖ') continue
            if (c == 'ー' || c == '々' || c == 'ゝ' || c == 'ゞ') continue
            return false
        }
        return true
    }

    // ----- 1.2 濁点・半濁点・小書きトグル -----
    // 1文字を次の形へ循環させる。対象外の文字はそのまま返す。
    // 例: か→が→か / は→ば→ぱ→は / つ→づ→っ→つ / あ→ぁ→あ / う→ゔ→ぅ→う
    private val TOGGLE: MutableMap<Char, Char> = HashMap()

    private fun cycle(vararg chars: Char) {
        for (i in chars.indices) {
            TOGGLE[chars[i]] = chars[(i + 1) % chars.size]
        }
    }

    init {
        cycle('か', 'が'); cycle('き', 'ぎ'); cycle('く', 'ぐ'); cycle('け', 'げ'); cycle('こ', 'ご')
        cycle('さ', 'ざ'); cycle('し', 'じ'); cycle('す', 'ず'); cycle('せ', 'ぜ'); cycle('そ', 'ぞ')
        cycle('た', 'だ'); cycle('ち', 'ぢ'); cycle('て', 'で'); cycle('と', 'ど')
        cycle('は', 'ば', 'ぱ'); cycle('ひ', 'び', 'ぴ'); cycle('ふ', 'ぶ', 'ぷ')
        cycle('へ', 'べ', 'ぺ'); cycle('ほ', 'ぼ', 'ぽ')
        cycle('つ', 'づ', 'っ')
        cycle('う', 'ゔ', 'ぅ') // う→ゔ→ぅ→う
        cycle('あ', 'ぁ'); cycle('い', 'ぃ')
        cycle('え', 'ぇ'); cycle('お', 'ぉ')
        cycle('や', 'ゃ'); cycle('ゆ', 'ゅ'); cycle('よ', 'ょ')
        cycle('わ', 'ゎ')
    }

    @JvmStatic
    fun toggleDakuten(c: Char): Char {
        val next = TOGGLE[c]
        return next ?: c
    }

    @JvmStatic
    fun canToggle(c: Char): Boolean {
        return TOGGLE.containsKey(c)
    }

    // ----- 1.3 12キー (トグル打ち) の段循環表 -----
    // 行の先頭文字がキー代表。タップ回数でこの順に循環する。
    @JvmField
    val TOGGLE_ROWS: Array<String> = arrayOf(
        "あいうえお", // あ行
        "かきくけこ", // か行
        "さしすせそ", // さ行
        "たちつてと", // た行
        "なにぬねの", // な行
        "はひふへほ", // は行
        "まみむめも", // ま行
        "やゆよ",     // や行
        "らりるれろ", // ら行
        "わをんー〜", // わ行
    )

    @JvmField
    val TOGGLE_KEY_LABELS: Array<String> = arrayOf(
        "あ", "か", "さ", "た", "な", "は", "ま", "や", "ら", "わ",
    )

    // 文字が属する行のインデックス (属さなければ -1)
    @JvmStatic
    fun rowIndexOf(c: Char): Int {
        for (r in TOGGLE_ROWS.indices) {
            if (TOGGLE_ROWS[r].indexOf(c) >= 0) return r
        }
        return -1
    }

    // 行内での次の文字 (末尾なら先頭へ)
    @JvmStatic
    fun nextInRow(row: Int, current: Char): Char {
        val r = TOGGLE_ROWS[row]
        val idx = r.indexOf(current)
        if (idx < 0) return r[0]
        return r[(idx + 1) % r.length]
    }
}
