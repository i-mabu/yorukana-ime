package jp.yorukana.ime

// 記号・絵文字データ (カテゴリ別)。
// 記号パネルはカテゴリタブ式 (Simeji 準拠)。カテゴリの並びとページ番号は
// SYMBOL_PAGE_* と対応する。
object SymbolData {

val SYMBOL_CATEGORY_NAMES: Array<String> = arrayOf(
"常用", "カッコ", "図形", "数字", "顔文字", "絵文字",
)

const val SYMBOL_PAGE_COMMON = 0
const val SYMBOL_PAGE_NUMBER = 3
const val SYMBOL_PAGE_KAOMOJI = 4
const val SYMBOL_PAGE_EMOJI = 5

val COMMON_SYMBOLS: Array<String> = arrayOf(
"、", "。", "！", "？", "・", "：", "；", "＠", "＃", "％",
"＆", "＊", "＋", "－", "＝", "＜", "＞", "＿", "〜", "…",
"＾", "｜", "／", "＼", "＂", "＇",
)

val BRACKET_SYMBOLS: Array<String> = arrayOf(
"（", "）", "「", "」", "『", "』", "［", "］", "｛", "｝",
"〈", "〉", "《", "》", "", "〔", "〕",
)

val SHAPE_SYMBOLS: Array<String> = arrayOf(
"☆", "★", "○", "●", "◎", "◇", "◆", "□", "■", "△",
"▲", "▽", "▼", "※", "♪", "♥", "→", "←", "↑", "↓",
"°",
)

val NUMBER_SYMBOLS: Array<String> = arrayOf(
"１", "２", "３", "４", "５", "６", "７", "８", "９", "０",
"￥", "＄", "€", "＋", "－", "＝", "＜", "＞", "％",
)

// 顔文字 (タップで直接コミット)。横幅があるため 3 列で表示する。
val KAOMOJIS: Array<String> = arrayOf(
"(・∀・)", "(´∀｀)", "(´ω｀)",
"(＾ω＾)", "(´・ω・`)", "(ﾟ∀ﾟ)",
"(≧▽≦)", "(´；ω；`)", "(；´Д｀)",
"(´Д｀)", "(ﾟДﾟ)", "(´ε｀)",
"(・_・)", "(；∀；)", "(´▽｀)",
"( ˘ω˘)", "(´_ゝ`)", "(・ω・)",
)

// よく使う絵文字 約60個 (タップで直接コミット)。
val EMOJIS: Array<String> = arrayOf(
"😀", "😁", "😂", "🤣", "😊", "😍", "😘", "😎",
"🤔", "😴", "😭", "😱", "😡", "😇", "🥺", "👍",
"👎", "🙏", "👏", "💪", "🔥", "✨", "⭐", "❤️",
"💔", "💯", "🎉", "🎂", "🍀", "🌸", "🌙", "☀️",
"☔", "❄️", "⚡", "🌈", "🍙", "🍜", "🍣", "☕",
"🍺", "🚗", "🚃", "✈️", "📱", "💻", "📚", "✏️",
"⚽", "🎮", "🎵", "🎧", "🐱", "🐶", "🐰", "🎁",
"⏰", "💤", "📷", "🏫",
)

// カテゴリ別の表示対象と列数 (SYMBOL_CATEGORY_NAMES と同じ順)。
val SYMBOL_CATEGORY_ITEMS: Array<Array<String>> = arrayOf(
COMMON_SYMBOLS, BRACKET_SYMBOLS, SHAPE_SYMBOLS, NUMBER_SYMBOLS,
KAOMOJIS, EMOJIS,
)

val SYMBOL_CATEGORY_COLS: IntArray = intArrayOf(10, 10, 10, 10, 3, 8)
}
