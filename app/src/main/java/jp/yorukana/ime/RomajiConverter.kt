package jp.yorukana.ime

// ===== 1. ローマ字→ひらがな変換 (純ロジック / Android非依存) =====
// composing 入力途中の未確定ローマ字も扱う: 変換済み部分 (kana) と
// まだ確定できない末尾ローマ字 (pending) を分けて返す。
object RomajiConverter {

    // ----- 1.1 結果型 -----
    class Result(
        @JvmField val kana: String,    // 変換済みのひらがな
        @JvmField val pending: String, // 未確定の末尾ローマ字 (例: "k", "ky", "n", "sh")
    )

    private val TABLE: MutableMap<String, String> = HashMap()

    private fun put(roma: String, kana: String) {
        TABLE[roma] = kana
    }

    init {
        // 基本 (五段)
        put("a", "あ"); put("i", "い"); put("u", "う"); put("e", "え"); put("o", "お")
        put("ka", "か"); put("ki", "き"); put("ku", "く"); put("ke", "け"); put("ko", "こ")
        put("ga", "が"); put("gi", "ぎ"); put("gu", "ぐ"); put("ge", "げ"); put("go", "ご")
        put("sa", "さ"); put("shi", "し"); put("si", "し"); put("su", "す"); put("se", "せ"); put("so", "そ")
        put("za", "ざ"); put("ji", "じ"); put("zi", "じ"); put("zu", "ず"); put("ze", "ぜ"); put("zo", "ぞ")
        put("ta", "た"); put("chi", "ち"); put("tsu", "つ"); put("tu", "つ"); put("te", "て"); put("to", "と")
        put("da", "だ"); put("du", "づ"); put("de", "で"); put("do", "ど")
        put("na", "な"); put("ni", "に"); put("nu", "ぬ"); put("ne", "ね"); put("no", "の")
        put("ha", "は"); put("hi", "ひ"); put("fu", "ふ"); put("hu", "ふ"); put("he", "へ"); put("ho", "ほ")
        put("ba", "ば"); put("bi", "び"); put("bu", "ぶ"); put("be", "べ"); put("bo", "ぼ")
        put("pa", "ぱ"); put("pi", "ぴ"); put("pu", "ぷ"); put("pe", "ぺ"); put("po", "ぽ")
        put("ma", "ま"); put("mi", "み"); put("mu", "む"); put("me", "め"); put("mo", "も")
        put("ya", "や"); put("yu", "ゆ"); put("yo", "よ")
        put("ra", "ら"); put("ri", "り"); put("ru", "る"); put("re", "れ"); put("ro", "ろ")
        put("wa", "わ"); put("wo", "を")
        // 拗音
        put("kya", "きゃ"); put("kyu", "きゅ"); put("kyo", "きょ")
        put("gya", "ぎゃ"); put("gyu", "ぎゅ"); put("gyo", "ぎょ")
        put("sha", "しゃ"); put("shu", "しゅ"); put("sho", "しょ")
        put("sya", "しゃ"); put("syu", "しゅ"); put("syo", "しょ")
        put("ja", "じゃ"); put("ju", "じゅ"); put("jo", "じょ")
        put("jya", "じゃ"); put("jyu", "じゅ"); put("jyo", "じょ")
        put("zya", "じゃ"); put("zyu", "じゅ"); put("zyo", "じょ")
        put("cha", "ちゃ"); put("chu", "ちゅ"); put("cho", "ちょ")
        put("tya", "ちゃ"); put("tyu", "ちゅ"); put("tyo", "ちょ")
        put("cya", "ちゃ"); put("cyu", "ちゅ"); put("cyo", "ちょ")
        put("nya", "にゃ"); put("nyu", "にゅ"); put("nyo", "にょ")
        put("hya", "ひゃ"); put("hyu", "ひゅ"); put("hyo", "ひょ")
        put("bya", "びゃ"); put("byu", "びゅ"); put("byo", "びょ")
        put("pya", "ぴゃ"); put("pyu", "ぴゅ"); put("pyo", "ぴょ")
        put("mya", "みゃ"); put("myu", "みゅ"); put("myo", "みょ")
        put("rya", "りゃ"); put("ryu", "りゅ"); put("ryo", "りょ")
        put("dya", "ぢゃ"); put("dyu", "ぢゅ"); put("dyo", "ぢょ")
        // 外来音 (SPEC準拠: ti/di は てぃ/でぃ で、di は ぢ ではない。
        // ち は chi、ぢ は dya 系 (dya/dyu/dyo) で入力する)
        put("fa", "ふぁ"); put("fi", "ふぃ"); put("fe", "ふぇ"); put("fo", "ふぉ"); put("fyu", "ふゅ")
        put("ti", "てぃ"); put("di", "でぃ")
        put("wi", "うぃ"); put("we", "うぇ")
        put("va", "ゔぁ"); put("vi", "ゔぃ"); put("vu", "ゔ"); put("ve", "ゔぇ"); put("vo", "ゔぉ")
        put("she", "しぇ"); put("je", "じぇ"); put("che", "ちぇ")
        put("tsa", "つぁ"); put("tsi", "つぃ"); put("tse", "つぇ"); put("tso", "つぉ")
        put("kwa", "くぁ"); put("gwa", "ぐぁ")
        // 小書き (x / l 系)
        put("xa", "ぁ"); put("xi", "ぃ"); put("xu", "ぅ"); put("xe", "ぇ"); put("xo", "ぉ")
        put("la", "ぁ"); put("li", "ぃ"); put("lu", "ぅ"); put("le", "ぇ"); put("lo", "ぉ")
        put("xya", "ゃ"); put("xyu", "ゅ"); put("xyo", "ょ")
        put("lya", "ゃ"); put("lyu", "ゅ"); put("lyo", "ょ")
        put("xtu", "っ"); put("ltu", "っ"); put("xtsu", "っ"); put("ltsu", "っ")
        put("xwa", "ゎ"); put("lwa", "ゎ")
        put("xka", "ヵ"); put("xke", "ヶ")
    }

    private fun isVowel(c: Char): Boolean {
        return c == 'a' || c == 'i' || c == 'u' || c == 'e' || c == 'o'
    }

    private fun isConsonant(c: Char): Boolean {
        return c in 'a'..'z' && !isVowel(c)
    }

    // rest が何らかの変換キーの接頭辞なら「まだ続きがあり得る」= pending
    private fun isPossiblePrefix(rest: String): Boolean {
        if (rest.isEmpty()) return false
        for (key in TABLE.keys) {
            if (key.startsWith(rest)) return true
        }
        return false
    }

    // ----- 1.2 変換本体 -----
    // ローマ字文字列を左から変換し、確定できない末尾を pending として返す。
    @JvmStatic
    fun convert(input: String): Result {
        val out = StringBuilder()
        var pending = ""
        val n = input.length
        var i = 0
        loop@ while (i < n) {
            val c = input[i]
            if (c == '-') { out.append('ー'); i++; continue@loop }
            if (c < 'a' || c > 'z') {
                if (c == '\'') { i++; continue@loop } // 単独の ' は読み飛ばす (n' は n 側で処理)
                out.append(c); i++; continue@loop
            }
            // 促音: "tch" (matcha 等)
            if (c == 't' && input.startsWith("tch", i)) { out.append('っ'); i++; continue@loop }
            // 促音: 子音の重ね (n を除く)
            if (i + 1 < n && input[i + 1] == c && isConsonant(c) && c != 'n') {
                out.append('っ'); i++; continue@loop
            }
            // 撥音 n の処理
            if (c == 'n') {
                if (i + 1 >= n) { pending = "n"; i = n; break@loop }
                val nx = input[i + 1]
                if (nx == '\'') { out.append('ん'); i += 2; continue@loop }
                if (nx == 'n') {
                    if (i + 2 < n && (isVowel(input[i + 2]) || input[i + 2] == 'y')) {
                        out.append('ん'); i += 1; continue@loop // ん + な行 (nna → んな)
                    }
                    out.append('ん'); i += 2; continue@loop
                }
                if (!isVowel(nx) && nx != 'y') { out.append('ん'); i += 1; continue@loop }
                // 母音 / y が続く場合はテーブル (na / nya 等) に委ねる
            }
            // テーブル最長一致 (最長4文字: xtsu / ltsu → っ)
            var matched: String? = null
            var mlen = 0
            for (len in minOf(4, n - i) downTo 1) {
                val k = TABLE[input.substring(i, i + len)]
                if (k != null) { matched = k; mlen = len; break }
            }
            if (matched != null) { out.append(matched); i += mlen; continue@loop }
            // 変換できない位置: 残りがキーの接頭辞なら pending、それ以外は文字を素通し
            val rest = input.substring(i)
            // "tch" (促音) の途中 "tc" はテーブルのキーではないが、接頭辞として
            // pending にする (しないと "matc" の時点で t が素通し表示になる)
            if (rest == "tc" || isPossiblePrefix(rest)) { pending = rest; break@loop }
            out.append(c); i++
        }
        return Result(out.toString(), pending)
    }

    // ----- 1.3 完全変換 (確定時) -----
    // pending の末尾 n は ん に、それ以外の pending はローマ字のまま残す。
    @JvmStatic
    fun convertFully(input: String): String {
        val r = convert(input)
        if (r.pending.isEmpty()) return r.kana
        if (r.pending == "n") return r.kana + "ん"
        return r.kana + r.pending
    }
}
