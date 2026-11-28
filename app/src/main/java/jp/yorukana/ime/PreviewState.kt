package jp.yorukana.ime

// プレビュー表示状態 (オーバーレイ用)。
// フリックプレビューは横並びストリップ (Simeji 準拠)。items はストリップの
// 並び順 (左方向・中央・上・右・下 の順で割り当て済みのものだけ)。
// 位置はルート左上からの px オフセット。
class FlickItem(val dir: Int, val ch: String)

class FlickUiState(
    val items: List<FlickItem>,
    val leftPx: Int,
    val topPx: Int,
    val dir: Int,
)

class KeyUiState(
    val text: String,
    val leftPx: Int,
    val topPx: Int,
)
