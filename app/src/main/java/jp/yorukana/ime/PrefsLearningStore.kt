package jp.yorukana.ime

import android.content.SharedPreferences

// 学習ストアの SharedPreferences 永続化実装。
// 頻度は本体の設定ファイル (yorukana_prefs) に "freq:" 接頭辞付きの
// Int として保存する。キーは 読み + "|" + 候補。
class PrefsLearningStore(private val prefs: SharedPreferences) : LearningStore {

    private val freq: MutableMap<String, Int> = HashMap()

    init {
        reload()
    }

    // SharedPreferences から頻度を読み直す。設定画面で学習リセットされた
    // 場合など、外部から prefs が書き換わった内容を反映するために使う。
    // 読み込みに失敗したら頻度は空にして続行する (学習は補助機能であり、
    // ここで落ちて入力自体が使えなくなるのは本末転倒なため)。
    fun reload() {
        try {
            freq.clear()
            for ((key, value) in prefs.all) {
                if (key.startsWith(YoruKanaService.FREQ_PREFIX) && value is Int) {
                    freq[key.substring(YoruKanaService.FREQ_PREFIX.length)] = value
                }
            }
        } catch (e: Exception) {
            freq.clear()
        }
    }

    override fun getFrequency(reading: String, candidate: String): Int =
        freq[key(reading, candidate)] ?: 0

    override fun recordSelection(reading: String, candidate: String) {
        val k = key(reading, candidate)
        val v = getFrequency(reading, candidate) + 1
        freq[k] = v
        try {
            prefs.edit().putInt(YoruKanaService.FREQ_PREFIX + k, v).apply()
        } catch (e: Exception) {
            // 保存に失敗してもメモリ上の頻度は保ち、入力は止めない
        }
    }

    override fun clear() {
        freq.clear()
        val ed = prefs.edit()
        for (k in prefs.all.keys) {
            if (k.startsWith(YoruKanaService.FREQ_PREFIX)) ed.remove(k)
        }
        ed.apply()
    }

    companion object {
        private fun key(reading: String, candidate: String): String = "$reading|$candidate"
    }
}
