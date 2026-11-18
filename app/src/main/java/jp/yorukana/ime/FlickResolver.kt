package jp.yorukana.ime

import kotlin.math.abs

// ===== 1. フリック入力の解決 (純ロジック / Android非依存) =====
// 12キーの各行について「行＋方向 → 文字」を解決する。
// 方向は enum ではなく int 定数で表す。
// 標準的な日本語フリック配列: 中央=段の1文字目、左=2、上=3、右=4、下=5。
// や行は左右が空き (未割り当て→中央扱い)、わ行は 左=を 上=ん 右=ー 下=〜。
object FlickResolver {

    const val DIR_CENTER = 0
    const val DIR_LEFT = 1
    const val DIR_UP = 2
    const val DIR_RIGHT = 3
    const val DIR_DOWN = 4

    const val ROW_COUNT = 10

    // FLICK_TABLE[row][dir]。'\0' は未割り当て (や行の左右のみ)。
    // 列の並びは DIR_CENTER, DIR_LEFT, DIR_UP, DIR_RIGHT, DIR_DOWN の順。
    private val FLICK_TABLE: Array<CharArray> = arrayOf(
        charArrayOf('あ', 'い', 'う', 'え', 'お'), // あ行
        charArrayOf('か', 'き', 'く', 'け', 'こ'), // か行
        charArrayOf('さ', 'し', 'す', 'せ', 'そ'), // さ行
        charArrayOf('た', 'ち', 'つ', 'て', 'と'), // た行
        charArrayOf('な', 'に', 'ぬ', 'ね', 'の'), // な行
        charArrayOf('は', 'ひ', 'ふ', 'へ', 'ほ'), // は行
        charArrayOf('ま', 'み', 'む', 'め', 'も'), // ま行
        charArrayOf('や', '\u0000', 'ゆ', '\u0000', 'よ'), // や行 (左右は空き)
        charArrayOf('ら', 'り', 'る', 'れ', 'ろ'), // ら行
        charArrayOf('わ', 'を', 'ん', 'ー', '〜'), // わ行
    )

    // 変位 (dx, dy) としきい値からフリック方向を決める。
    // dy は画面座標系 (下方向が正)。変位の長さ (dx, dy のユークリッド距離、
    // 軸ごとではない) がしきい値未満なら DIR_CENTER。
    // 斜めの場合は絶対値の大きい軸を優先する。
    @JvmStatic
    fun resolveDirection(dx: Float, dy: Float, thresholdPx: Float): Int {
        // 変位ゼロはしきい値が 0 の場合でも必ず中央 (下の軸判定では
        // dx>0 が偽になり左と誤判定されるため、先に弾く)
        if (dx == 0f && dy == 0f) return DIR_CENTER
        if (dx * dx + dy * dy < thresholdPx * thresholdPx) return DIR_CENTER
        if (abs(dx) >= abs(dy)) {
            return if (dx > 0) DIR_RIGHT else DIR_LEFT
        }
        return if (dy > 0) DIR_DOWN else DIR_UP
    }

    // 行＋方向の文字を返す。未割り当ての方向は中央の文字にフォールバックする。
    // 行が範囲外なら '\0'。
    @JvmStatic
    fun charAt(row: Int, dir: Int): Char {
        if (row < 0 || row >= ROW_COUNT) return '\u0000'
        var d = dir
        if (d < DIR_CENTER || d > DIR_DOWN) d = DIR_CENTER
        val c = FLICK_TABLE[row][d]
        return if (c != '\u0000') c else FLICK_TABLE[row][DIR_CENTER]
    }

    // その方向に文字が割り当てられているか (プレビュー表示用)。
    @JvmStatic
    fun isAssigned(row: Int, dir: Int): Boolean {
        if (row < 0 || row >= ROW_COUNT) return false
        if (dir < DIR_CENTER || dir > DIR_DOWN) return false
        return FLICK_TABLE[row][dir] != '\u0000'
    }
}
