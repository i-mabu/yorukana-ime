package jp.yorukana.ime

// ===== 1. 学習ストア (メモリ実装 / 純ロジック) =====
// テスト用のインメモリ実装。永続化は Service 側の PrefsLearningStore が
// LearningStore を直接実装して担う (このクラスは継承されていない)。
// キーは 読み + SEP (\u0001) + 候補 (PrefsLearningStore の "|" 区切りとは別形式)。
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

    companion object {
        const val SEP = "\u0001"

        @JvmStatic
        fun key(reading: String, candidate: String): String {
            return reading + SEP + candidate
        }
    }
}
