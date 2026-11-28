package jp.yorukana.ime

// 学習ストアのインメモリ実装 (純ロジック、テスト用)。
// 永続化は Service 側の PrefsLearningStore が担う (このクラスは継承されない)。
// キーは 読み + SEP (\u0001) + 候補 で、PrefsLearningStore の "|" 区切りとは別形式。
open class MemoryLearningStore : LearningStore {

    protected val freq: MutableMap<String, Int> = HashMap()

    override fun getFrequency(reading: String, candidate: String): Int =
        freq[key(reading, candidate)] ?: 0

    override fun recordSelection(reading: String, candidate: String) {
        val k = key(reading, candidate)
        freq[k] = getFrequency(reading, candidate) + 1
    }

    override fun clear() {
        freq.clear()
    }

    companion object {
        const val SEP = "\u0001"

        @JvmStatic
        fun key(reading: String, candidate: String): String = reading + SEP + candidate
    }
}
