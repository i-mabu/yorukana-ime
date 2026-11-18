package jp.yorukana.ime

// ===== 1. 内蔵辞書 (純ロジック / Android非依存) =====
// DictionaryData の明示エントリに加え、動詞・形容詞の活用形を生成して登録する。
// 読み → 候補リスト のマップとして保持する。
class Dictionary {

    private val map: MutableMap<String, MutableList<String>> = LinkedHashMap()
    private val singleKanji: MutableMap<String, List<String>> = HashMap()

    // 読みのソート済み一覧 (予測の前方一致を二分探索で切り出すため。
    // 打鍵ごとに全読みを走査すると低速端末で引っかかりになる)
    private var sortedKeys: List<String> = emptyList()

    init {
        build()
        sortedKeys = map.keys.sorted()
    }

    // ----- 1.1 構築 -----
    private fun add(reading: String?, candidate: String?) {
        if (reading.isNullOrEmpty() || candidate.isNullOrEmpty()) return
        val list = map.getOrPut(reading) { ArrayList() }
        if (!list.contains(candidate)) list.add(candidate)
    }

    // データ形式の区切り (= / |) が無い壊れたエントリは、各ループで読み飛ばす
    private fun build() {
        for (e in DictionaryData.ENTRIES) {
            val eq = e.indexOf('=')
            if (eq < 0) continue
            val reading = e.substring(0, eq)
            for (cand in e.substring(eq + 1).split(",")) add(reading, cand)
        }
        for (v in DictionaryData.GODAN) {
            val bar = v.indexOf('|')
            if (bar < 0) continue // 区切りが無い壊れたエントリは読み飛ばす
            genGodan(v.substring(0, bar), v.substring(bar + 1))
        }
        for (v in DictionaryData.ICHIDAN) {
            val bar = v.indexOf('|')
            if (bar < 0) continue
            genIchidan(v.substring(0, bar), v.substring(bar + 1))
        }
        for (a in DictionaryData.I_ADJ) {
            val bar = a.indexOf('|')
            if (bar < 0) continue
            genIAdj(a.substring(0, bar), a.substring(bar + 1))
        }
        for (a in DictionaryData.NA_ADJ) {
            val bar = a.indexOf('|')
            if (bar < 0) continue
            genNaAdj(a.substring(0, bar), a.substring(bar + 1))
        }
        for (e in DictionaryData.SINGLE_KANJI) {
            val eq = e.indexOf('=')
            if (eq < 0) continue
            val list = ArrayList<String>()
            list.addAll(e.substring(eq + 1).split(","))
            singleKanji[e.substring(0, eq)] = list
        }
    }

    private fun genGodan(reading: String, kanji: String) {
        if (reading.length < 2 || kanji.length < 2) return
        val last = reading[reading.length - 1]
        val row = GODAN_ROWS[last] ?: return
        val rs = reading.substring(0, reading.length - 1) // 読みの語幹
        val ks = kanji.substring(0, kanji.length - 1)     // 漢字形の語幹
        add(reading, kanji)                                    // 辞書形
        add(rs + row[0] + "ます", ks + row[0] + "ます")        // ます形
        add(rs + row[0] + "ました", ks + row[0] + "ました")    // ました
        add(rs + row[0] + "たい", ks + row[0] + "たい")        // たい
        add(rs + row[0] + "た", ks + row[0] + "た")            // 連用 (た の前段)
        val taR: String
        val teR: String
        val taK: String
        val teK: String
        if (reading == "いく") { // 行く は例外 (いった/いって)
            taR = "いった"; teR = "いって"; taK = "行った"; teK = "行って"
        } else {
            taR = rs + row[4]; teR = rs + row[5]; taK = ks + row[4]; teK = ks + row[5]
        }
        add(taR, taK)                                          // た形
        add(teR, teK)                                          // て形
        add(rs + row[1] + "ない", ks + row[1] + "ない")        // ない形
        add(rs + row[2] + "る", ks + row[2] + "る")            // 可能形
        add(rs + row[3] + "う", ks + row[3] + "う")            // 意向形
    }

    private fun genIchidan(reading: String, kanji: String) {
        if (reading.length < 2 || !reading.endsWith("る")) return
        val rs = reading.substring(0, reading.length - 1)
        val ks = kanji.substring(0, kanji.length - 1)
        add(reading, kanji)                                    // 辞書形
        add(rs + "ます", ks + "ます")                          // ます形
        add(rs + "ました", ks + "ました")                      // ました
        add(rs + "た", ks + "た")                              // た形
        add(rs + "て", ks + "て")                              // て形
        add(rs + "ない", ks + "ない")                          // ない形
        add(rs + "たい", ks + "たい")                          // たい
        add(rs + "られる", ks + "られる")                      // 可能/受身
        add(rs + "よう", ks + "よう")                          // 意向形
    }

    private fun genIAdj(reading: String, kanji: String) {
        if (reading.length < 2 || !reading.endsWith("い")) return
        val rs = reading.substring(0, reading.length - 1)
        val ks = kanji.substring(0, kanji.length - 1)
        add(reading, kanji)                                    // 終止形
        add(rs + "くない", ks + "くない")                      // 否定
        add(rs + "かった", ks + "かった")                      // 過去
        add(rs + "くて", ks + "くて")                          // て形
        add(rs + "く", ks + "く")                              // 連用
        add(rs + "さ", ks + "さ")                              // 名詞化 (高さ等)
    }

    private fun genNaAdj(reading: String, kanji: String) {
        add(reading, kanji)                                    // 語幹
        add(reading + "な", kanji + "な")                      // 連体
        add(reading + "に", kanji + "に")                      // 連用
        add(reading + "だ", kanji + "だ")                      // 終止
        add(reading + "です", kanji + "です")                  // 丁寧
        add(reading + "だった", kanji + "だった")              // 過去
    }

    // ----- 1.2 検索 -----
    fun has(reading: String): Boolean {
        return map.containsKey(reading)
    }

    // 読みに対する候補 (無ければ空リスト)。単漢字フォールバックも含む。
    fun lookup(reading: String): List<String> {
        val out = ArrayList<String>()
        val hit = map[reading]
        if (hit != null) out.addAll(hit)
        if (out.isEmpty() && reading.length == 1) {
            val sk = singleKanji[reading]
            if (sk != null) out.addAll(sk)
        }
        return out
    }

    // 前方一致する読みの一覧 (予測変換用)。ソート済みキーから二分探索で
    // prefix の範囲を切り出す (結果はソート順で、従来の全走査と同じ集合)。
    fun readingsWithPrefix(prefix: String?): MutableList<String> {
        val out = ArrayList<String>()
        if (prefix.isNullOrEmpty()) return out
        var lo = sortedKeys.binarySearch(prefix)
        if (lo < 0) lo = -lo - 1
        var i = lo
        while (i < sortedKeys.size && sortedKeys[i].startsWith(prefix)) {
            val key = sortedKeys[i]
            if (key.length > prefix.length) out.add(key)
            i++
        }
        return out
    }

    // 登録されている読みの総数 (辞書語数)
    fun size(): Int {
        return map.size
    }

    companion object {
        // 五段動詞の活用行: 語尾 → {い段, あ段, え段, お段, た形語尾, て形語尾}
        private val GODAN_ROWS: Map<Char, Array<String>> = mapOf(
            'く' to arrayOf("き", "か", "け", "こ", "いた", "いて"),
            'ぐ' to arrayOf("ぎ", "が", "げ", "ご", "いだ", "いで"),
            'す' to arrayOf("し", "さ", "せ", "そ", "した", "して"),
            'つ' to arrayOf("ち", "た", "て", "と", "った", "って"),
            'る' to arrayOf("り", "ら", "れ", "ろ", "った", "って"),
            'う' to arrayOf("い", "わ", "え", "お", "った", "って"),
            'ぬ' to arrayOf("に", "な", "ね", "の", "んだ", "んで"),
            'ぶ' to arrayOf("び", "ば", "べ", "ぼ", "んだ", "んで"),
            'む' to arrayOf("み", "ま", "め", "も", "んだ", "んで"),
        )
    }
}
