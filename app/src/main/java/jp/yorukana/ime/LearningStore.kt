package jp.yorukana.ime

// ===== 1. 学習ストア (インターフェース / 純ロジック) =====
// 確定した候補の頻度を保持し、候補ソートに反映するための抽象。
// 永続化 (SharedPreferences) は Service 側の実装が担い、本体ロジックは
// このインターフェース越しにテストできる。
interface LearningStore {
    // (読み, 候補) の選択回数
    fun getFrequency(reading: String, candidate: String): Int

    // 候補が確定されたことを記録する
    fun recordSelection(reading: String, candidate: String)

    // 学習データを全消去する
    fun clear()
}
