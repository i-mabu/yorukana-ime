package jp.yorukana.ime

// ===== 1. ユーザー辞書 (純ロジック / Android非依存) =====
// よみ → 単語 の登録を保持する。変換候補では内蔵辞書・学習より優先される。
// 永続化は encode()/decode() の文字列形式 (1行に "よみ\t単語") で行い、
// SharedPreferences への読み書きは呼び出し側 (Service / 設定画面) が担う。
class UserDictionary {

    private val map: LinkedHashMap<String, MutableList<String>> = LinkedHashMap()

    // 単語を追加する。空文字・重複は無視する。よみ/単語内のタブと改行は除去する。
    fun add(readingIn: String?, wordIn: String?) {
        val reading = sanitize(readingIn)
        val word = sanitize(wordIn)
        if (reading.isEmpty() || word.isEmpty()) return
        var words = map[reading]
        if (words == null) {
            words = ArrayList()
            map[reading] = words
        }
        if (!words.contains(word)) words.add(word)
    }

    // 単語を削除する。削除できたら true。よみが空になったら読み自体も消す。
    fun remove(reading: String, word: String): Boolean {
        val words = map[reading] ?: return false
        val removed = words.remove(word)
        if (words.isEmpty()) map.remove(reading)
        return removed
    }

    // よみに対する単語一覧 (登録順)。無ければ空リスト。
    fun lookup(reading: String): List<String> {
        val words = map[reading]
        return if (words != null) ArrayList(words) else ArrayList()
    }

    fun has(reading: String): Boolean {
        return map.containsKey(reading)
    }

    // (よみ, 単語) ペアの一覧を登録順に返す (設定画面の一覧表示用)。
    fun entries(): List<Array<String>> {
        val out = ArrayList<Array<String>>()
        for ((key, value) in map) {
            for (w in value) {
                out.add(arrayOf(key, w))
            }
        }
        return out
    }

    // 登録単語の総数
    fun size(): Int {
        var n = 0
        for (v in map.values) n += v.size
        return n
    }

    fun clear() {
        map.clear()
    }

    // ----- 永続化形式: "よみ\t単語" を改行で連結 -----
    fun encode(): String {
        val sb = StringBuilder()
        for (e in entries()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(e[0]).append('\t').append(e[1])
        }
        return sb.toString()
    }

    companion object {
        private fun sanitize(s: String?): String {
            if (s == null) return ""
            return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
        }

        @JvmStatic
        fun decode(encoded: String?): UserDictionary {
            val dict = UserDictionary()
            if (encoded.isNullOrEmpty()) return dict
            for (line in encoded.split("\n")) {
                val tab = line.indexOf('\t')
                if (tab <= 0 || tab >= line.length - 1) continue
                dict.add(line.substring(0, tab), line.substring(tab + 1))
            }
            return dict
        }
    }
}
