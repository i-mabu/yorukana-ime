package jp.yorukana.ime

// ===== 1. 学習ストア (メモリ実装 / 純ロジック) =====
// テスト用および永続化レイヤの土台。キーは 読み + 区切り + 候補。
open class MemoryLearningStore : LearningStore {

    protected val freq: MutableMap<String, Int> = HashMap()

    override fun getFrequency(reading: String, candidate: String): Int {
        return freq[key(reading, candidate)] ?: 0
    }

    override fun recordSelection(reading: String, candidate: String) {
        val k = key(reading, candidate)
        freq[k] = getFrequency(reading, candidate) + 1
    }

    override fun clear() {
        freq.clear()
    }

    // 永続化レイヤが復元/保存に使う生マップ
    fun rawMap(): Map<String, Int> {
        return freq
    }

    companion object {
        const val SEP = "\u0001"

        @JvmStatic
        fun key(reading: String, candidate: String): String {
            return reading + SEP + candidate
        }
    }
}
