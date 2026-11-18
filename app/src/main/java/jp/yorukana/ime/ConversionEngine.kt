package jp.yorukana.ime

// ===== 1. 変換エンジン (純ロジック / Android非依存) =====
// 候補生成順: ⓪ユーザー辞書 (最優先) ①辞書候補 (学習頻度でソート)
// ②カタカナ ③ひらがな原文 ④ローマ字原文。
// 末尾助詞の簡易分離変換と、前方一致の予測候補も提供する。
class ConversionEngine(
    private val dictionary: Dictionary,
    private val learning: LearningStore?,
    userDictionary: UserDictionary?,
) {

    constructor(dictionary: Dictionary, learning: LearningStore?) : this(dictionary, learning, null)

    // ユーザー辞書を差し替える (IME 側が入力開始時に読み込むため)
    var userDictionary: UserDictionary? = userDictionary

    // 学習頻度が高い順に安定ソート
    private fun sortByFreq(reading: String, cands: List<String>): List<String> {
        val out = ArrayList(cands)
        out.sortWith(compareByDescending { c: String -> learning?.getFrequency(reading, c) ?: 0 })
        return out
    }

    // ----- 1.1 候補生成 -----
    // reading: ひらがな読み / romajiRaw: ローマ字原文 (かな入力時は null でよい)
    fun candidates(reading: String?, romajiRaw: String?): List<String> {
        val out = LinkedHashSet<String>()
        if (reading.isNullOrEmpty()) return ArrayList(out)

        // ⓪ユーザー辞書が最優先 (学習より前、登録順のまま)
        userDictionary?.let {
            out.addAll(it.lookup(reading))
        }

        val dict = dictionary.lookup(reading)
        if (dict.isNotEmpty()) {
            out.addAll(sortByFreq(reading, dict))
        } else {
            // 末尾助詞の簡易分離: 語幹が辞書にあれば 語幹候補+助詞 を提示
            val split = splitParticleCandidate(reading)
            if (split != null) {
                val stem = reading.substring(0, reading.length - 1)
                val particle = reading.substring(reading.length - 1)
                // 頻度キーは語幹ではなく reading 全体: 学習は確定時に
                // (読み全体, 助詞付き候補) の組で記録されるため、それと揃える
                val stemCands = sortByFreq(
                    reading,
                    withSuffix(dictionary.lookup(stem), particle),
                )
                out.addAll(stemCands)
            }
        }
        out.add(KanaUtils.toKatakana(reading)) // ②カタカナ
        out.add(reading)                       // ③ひらがな原文
        if (!romajiRaw.isNullOrEmpty()) {
            out.add(romajiRaw)                 // ④ローマ字原文
        }
        return ArrayList(out)
    }

    // ----- 1.2 予測候補 (前方一致) -----
    // prefix で始まる読みの代表候補を返す。頻度→読みの短さ の順。
    // 候補文字列だけを返す版で、読みも要る場合は predictEntries を使う。
    fun predict(prefix: String?, limit: Int): List<String> {
        val out = ArrayList<String>()
        if (prefix.isNullOrEmpty() || limit <= 0) return out
        val readings = dictionary.readingsWithPrefix(prefix)
        readings.sortWith { a, b ->
            val fa = bestFreq(a)
            val fb = bestFreq(b)
            if (fa != fb) fb.compareTo(fa) else a.length.compareTo(b.length)
        }
        val seen = LinkedHashSet<String>()
        for (r in readings) {
            val cands = dictionary.lookup(r)
            if (cands.isEmpty()) continue
            val best = sortByFreq(r, cands)[0]
            if (seen.add(best)) out.add(best)
            if (out.size >= limit) break
        }
        return out
    }

    // 予測候補を (読み, 候補) ペアで返す (学習記録に正しい読みを使うため)
    fun predictEntries(prefix: String?, limit: Int): List<Array<String>> {
        val out = ArrayList<Array<String>>()
        if (prefix.isNullOrEmpty() || limit <= 0) return out
        val readings = dictionary.readingsWithPrefix(prefix)
        readings.sortWith { a, b ->
            val fa = bestFreq(a)
            val fb = bestFreq(b)
            if (fa != fb) fb.compareTo(fa) else a.length.compareTo(b.length)
        }
        val seen = LinkedHashSet<String>()
        for (r in readings) {
            val cands = dictionary.lookup(r)
            if (cands.isEmpty()) continue
            val best = sortByFreq(r, cands)[0]
            if (seen.add(best)) out.add(arrayOf(r, best))
            if (out.size >= limit) break
        }
        return out
    }

    private fun bestFreq(reading: String): Int {
        if (learning == null) return 0
        var best = 0
        for (c in dictionary.lookup(reading)) {
            best = maxOf(best, learning.getFrequency(reading, c))
        }
        return best
    }

    companion object {
        // 末尾助詞として分離を試みる文字
        private const val PARTICLES = "はがをにへとのかもねよ"

        private fun withSuffix(base: List<String>, suffix: String): List<String> {
            val out = ArrayList<String>(base.size)
            for (b in base) out.add(b + suffix)
            return out
        }
    }

    // ----- 1.3 末尾助詞の分離 -----
    // 分離変換が可能なら語幹を返す (不可なら null)
    private fun splitParticleCandidate(reading: String): String? {
        if (reading.length < 3) return null // 語幹2文字以上を要求
        val last = reading[reading.length - 1]
        if (PARTICLES.indexOf(last) < 0) return null
        val stem = reading.substring(0, reading.length - 1)
        return if (dictionary.has(stem)) stem else null
    }
}
