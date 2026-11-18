package jp.yorukana.ime

// ===== 1. 変換エンジン (純ロジック / Android非依存) =====
// 候補生成順: ⓪ユーザー辞書 (最優先) ①辞書候補 (学習頻度でソート)
// ②カタカナ ③ひらがな原文 ④ローマ字原文。
// 末尾助詞の簡易分離変換と、前方一致の予測候補も提供する。
// 予測・変換は入力の長さに依存しないよう、全体が辞書に無い長い入力では
// 助詞の位置で文節に区切って前半を変換し、末尾セグメントを予測する
// 文節分割も行う (1.4)。
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
            // 文節分割変換: 全体が辞書に無くても、助詞の位置で区切って
            // 変換した候補を出す (長い入力でも変換が効くように)
            val segmented = bestFullConversion(reading, MAX_SEGMENT_DEPTH)
            if (segmented != null) out.add(segmented)
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
        for (e in predictEntries(prefix, limit)) out.add(e[1])
        return out
    }

    // 予測候補を (読み, 候補) ペアで返す (学習記録に正しい読みを使うため)。
    // 入力が長くなっても予測が止まらないよう、全体への前方一致に加えて、
    // 文節に区切った末尾セグメントへの予測も後ろに足す (1.4)。
    fun predictEntries(prefix: String?, limit: Int): List<Array<String>> {
        val out = ArrayList<Array<String>>()
        if (prefix.isNullOrEmpty() || limit <= 0) return out
        val seen = LinkedHashSet<String>()
        addWholePrefixPredictions(prefix, limit, out, seen)
        if (out.size < limit) {
            addSegmentedPredictions(prefix, limit, out, seen)
        }
        return out
    }

    // prefix 全体で始まる読みへの前方一致予測 (ユーザー辞書→内蔵辞書の順)
    private fun addWholePrefixPredictions(
        prefix: String,
        limit: Int,
        out: ArrayList<Array<String>>,
        seen: MutableSet<String>,
    ) {
        // ユーザー辞書の前方一致を最優先で出す (登録した長い語も予測に出る)
        userDictionary?.let { ud ->
            for (r in ud.readingsWithPrefix(prefix)) {
                if (out.size >= limit) return
                val words = ud.lookup(r)
                if (words.isEmpty()) continue
                if (seen.add(words[0])) out.add(arrayOf(r, words[0]))
            }
        }
        val readings = dictionary.readingsWithPrefix(prefix)
        readings.sortWith { a, b ->
            val fa = bestFreq(a)
            val fb = bestFreq(b)
            if (fa != fb) fb.compareTo(fa) else a.length.compareTo(b.length)
        }
        for (r in readings) {
            if (out.size >= limit) return
            val cands = dictionary.lookup(r)
            if (cands.isEmpty()) continue
            val best = sortByFreq(r, cands)[0]
            if (seen.add(best)) out.add(arrayOf(r, best))
        }
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

        // 文節の区切りとして後半の先頭に来うる助詞 (格助詞・係助詞・接続助詞)。
        // か/ね/よ は文末専用で語頭の区切りにはならないため除く
        // (「よる|かな」のような単語内部の誤分割を防ぐ)。
        private const val PARTICLES_BEFORE = "はがをにへとのも"

        // 文節分割の最大深度 (これ以上は区切らない)
        private const val MAX_SEGMENT_DEPTH = 3

        // サ変動詞の活用形の語頭 (名詞+する の区切り判定用)
        private val SURU_HEADS = listOf(
            "する", "しな", "しま", "した", "して", "しよう", "され", "させ", "でき",
        )

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

    // ----- 1.4 文節分割 (長い入力でも変換・予測が効くように) -----
    // フォールバック (カタカナ・ひらがな原文) を含まない「実候補」だけを返す:
    // ユーザー辞書 → 辞書の完全一致 (頻度順) → 末尾助詞の分離候補 の順。
    private fun realCandidates(reading: String): List<String> {
        val out = LinkedHashSet<String>()
        userDictionary?.let { out.addAll(it.lookup(reading)) }
        val dict = dictionary.lookup(reading)
        if (dict.isNotEmpty()) {
            out.addAll(sortByFreq(reading, dict))
        } else if (splitParticleCandidate(reading) != null) {
            val stem = reading.substring(0, reading.length - 1)
            val particle = reading.substring(reading.length - 1)
            out.addAll(sortByFreq(reading, withSuffix(dictionary.lookup(stem), particle)))
        }
        return ArrayList(out)
    }

    // 分割点が文節の区切りとして妥当か: 前半が助詞で終わるか、後半が助詞で
    // 始まる場合のみ区切る (単語の途中では区切らず、誤った候補を抑える)。
    // 判定には PARTICLES_BEFORE のみ使う: か/ね/よ は文末専用のため、
    // 前半の末尾でも後半の先頭でも区切りにならない (「よるか|な」のような
    // 単語内部の誤分割を防ぐ)。
    // 加えて、後半がサ変動詞の活用形 (する/した/して/できる 等) で始まる
    // 場合も区切りとする (「べんきょう|する」のような名詞+する を扱う)。
    private fun isBoundary(head: String, tail: String): Boolean {
        if (PARTICLES_BEFORE.indexOf(head[head.length - 1]) >= 0) return true
        if (PARTICLES_BEFORE.indexOf(tail[0]) >= 0) return true
        for (s in SURU_HEADS) {
            if (tail.startsWith(s)) return true
        }
        return false
    }

    // reading 全体を変換した最良候補を返す (変換不能なら null)。
    // 全体の実候補があればその先頭、無ければ助詞の位置で区切って
    // 前半の変換 + 後半の再帰変換 を連結する。後半が変換できない場合は
    // 後半をひらがなのまま残す (部分的にでも変換が進む形)。
    private fun bestFullConversion(reading: String, depth: Int): String? {
        val exact = realCandidates(reading)
        if (exact.isNotEmpty()) return exact[0]
        if (depth <= 0 || reading.length < 4) return null
        // 前半が長い分割を優先する (より多くが変換済みになる)
        for (split in reading.length - 2 downTo 2) {
            val head = reading.substring(0, split)
            val tail = reading.substring(split)
            if (!isBoundary(head, tail)) continue
            val headBest = realCandidates(head).firstOrNull() ?: continue
            val tailConv = bestFullConversion(tail, depth - 1) ?: tail
            return headBest + tailConv
        }
        return null
    }

    // 文節分割予測: 入力を [変換できる前半][末尾セグメント] に区切り、
    // 末尾セグメントの候補・予測を前半の変換結果に続けた形で出す。
    // 入力全体が1語として完結したり辞書より長くなったりしても、
    // 末尾で入力中の語への予測が続く。
    private fun addSegmentedPredictions(
        reading: String,
        limit: Int,
        out: ArrayList<Array<String>>,
        seen: MutableSet<String>,
    ) {
        if (reading.length < 3) return
        // 前半が長い分割を優先する (末尾セグメント = 入力中の語に近い)
        for (split in reading.length - 1 downTo 2) {
            if (out.size >= limit) return
            val head = reading.substring(0, split)
            val tail = reading.substring(split)
            if (!isBoundary(head, tail)) continue
            val headBest = realCandidates(head).firstOrNull() ?: continue
            for (opt in tailOptions(tail)) {
                val full = headBest + opt[1]
                if (seen.add(full)) out.add(arrayOf(head + opt[0], full))
                if (out.size >= limit) return
            }
        }
    }

    // 末尾セグメントの (読み, 候補) 候補: 完全一致 → 前方一致予測 の順。
    // セグメントが助詞で始まる場合は、その助詞は前半に付くものとして
    // 「助詞 + 残りの変換/予測」も候補にする (例: をべんきょう → を勉強)。
    // 助詞1文字だけのセグメントには候補を出さない。
    private fun tailOptions(tail: String): List<Array<String>> {
        val out = ArrayList<Array<String>>()
        val seen = HashSet<String>()
        if (tail.length == 1 && PARTICLES.indexOf(tail[0]) >= 0) return out
        // 完全一致候補 (最大3件)
        for (c in realCandidates(tail)) {
            if (out.size >= 3) break
            if (seen.add(c)) out.add(arrayOf(tail, c))
        }
        // 前方一致予測 (最大3件)
        val sub = ArrayList<Array<String>>()
        addWholePrefixPredictions(tail, 3, sub, HashSet())
        for (e in sub) {
            if (out.size >= 6) break
            if (seen.add(e[1])) out.add(e)
        }
        // 先頭が助詞なら、助詞を残して残り (3文字以上) を変換/予測する
        if (tail.length >= 4 && PARTICLES_BEFORE.indexOf(tail[0]) >= 0) {
            val particle = tail.substring(0, 1)
            val rest = tail.substring(1)
            val conv = bestFullConversion(rest, MAX_SEGMENT_DEPTH - 1)
            if (conv != null && seen.add(particle + conv)) {
                out.add(arrayOf(tail, particle + conv))
            }
            val sub2 = ArrayList<Array<String>>()
            addWholePrefixPredictions(rest, 2, sub2, HashSet())
            for (e in sub2) {
                if (out.size >= 8) break
                val full = particle + e[1]
                if (seen.add(full)) out.add(arrayOf(particle + e[0], full))
            }
        }
        return out
    }
}
