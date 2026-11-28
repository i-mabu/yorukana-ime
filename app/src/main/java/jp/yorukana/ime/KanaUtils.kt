package jp.yorukana.ime

// かなユーティリティ (純ロジック、Android 非依存)。
// ひらがな⇔カタカナ変換、濁点/半濁点/小書きのトグル、12キー段循環表を持つ。
object KanaUtils {

    // ぁ (0x3041) 〜 ゖ (0x3096) と ァ (0x30A1) 〜 ヶ (0x30F6) は 0x60 差で対応する。
    @JvmStatic
    fun toKatakana(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(if (c in 'ぁ'..'ゖ') (c.code + 0x60).toChar() else c)
        }
        return sb.toString()
    }

    @JvmStatic
    fun toHiragana(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(if (c in 'ァ'..'ヶ') (c.code - 0x60).toChar() else c)
        }
        return sb.toString()
    }

    // ユーザー辞書の「よみ」検証。許す文字は ひらがな (ぁ〜ゖ)、長音 ー、々、ゝ、ゞ のみ。
    // 英字・漢字・数字・カタカナが混ざっていたら false。空文字列も false。
    @JvmStatic
    fun isValidReading(s: String?): Boolean {
        if (s.isNullOrEmpty()) return false
        for (c in s) {
            if (c in 'ぁ'..'ゖ') continue
            if (c == 'ー' || c == '々' || c == 'ゝ' || c == 'ゞ') continue
            return false
        }
        return true
    }

    // 濁点・半濁点・小書きのトグル表: 1文字を次の形へ循環させる。
    // 例: か→が→か / は→ば→ぱ→は / つ→づ→っ→つ / あ→ぁ→あ / う→ゔ→ぅ→う
    private val toggleMap = HashMap<Char, Char>()

    private fun cycle(vararg chars: Char) {
        for (i in chars.indices) {
            toggleMap[chars[i]] = chars[(i + 1) % chars.size]
        }
    }

    init {
        cycle('か', 'が'); cycle('き', 'ぎ'); cycle('く', 'ぐ'); cycle('け', 'げ'); cycle('こ', 'ご')
        cycle('さ', 'ざ'); cycle('し', 'じ'); cycle('す', 'ず'); cycle('せ', 'ぜ'); cycle('そ', 'ぞ')
        cycle('た', 'だ'); cycle('ち', 'ぢ'); cycle('て', 'で'); cycle('と', 'ど')
        cycle('は', 'ば', 'ぱ'); cycle('ひ', 'び', 'ぴ'); cycle('ふ', 'ぶ', 'ぷ')
        cycle('へ', 'べ', 'ぺ'); cycle('ほ', 'ぼ', 'ぽ')
        cycle('つ', 'づ', 'っ')
        cycle('う', 'ゔ', 'ぅ')
        cycle('あ', 'ぁ'); cycle('い', 'ぃ'); cycle('え', 'ぇ'); cycle('お', 'ぉ')
        cycle('や', 'ゃ'); cycle('ゆ', 'ゅ'); cycle('よ', 'ょ')
        cycle('わ', 'ゎ')
    }

    // 対象外の文字はそのまま返す。
    @JvmStatic
    fun toggleDakuten(c: Char): Char = toggleMap[c] ?: c

    @JvmStatic
    fun canToggle(c: Char): Boolean = toggleMap.containsKey(c)

    // 12キー (トグル打ち) の段循環表。行の先頭文字がキーの代表で、
    // タップ回数でこの順に循環する。
    @JvmField
    val TOGGLE_ROWS: Array<String> = arrayOf(
        "あいうえお",
        "かきくけこ",
        "さしすせそ",
        "たちつてと",
        "なにぬねの",
        "はひふへほ",
        "まみむめも",
        "やゆよ",
        "らりるれろ",
        "わをんー〜",
    )

    @JvmField
    val TOGGLE_KEY_LABELS: Array<String> = arrayOf(
        "あ", "か", "さ", "た", "な", "は", "ま", "や", "ら", "わ",
    )

    // 文字が属する行のインデックス (属さなければ -1)。
    @JvmStatic
    fun rowIndexOf(c: Char): Int {
        for (r in TOGGLE_ROWS.indices) {
            if (TOGGLE_ROWS[r].indexOf(c) >= 0) return r
        }
        return -1
    }

    // 行内での次の文字 (末尾なら先頭へ。current が行に無い文字なら先頭を返す)。
    @JvmStatic
    fun nextInRow(row: Int, current: Char): Char {
        val r = TOGGLE_ROWS[row]
        val idx = r.indexOf(current)
        if (idx < 0) return r[0]
        return r[(idx + 1) % r.length]
    }
}
