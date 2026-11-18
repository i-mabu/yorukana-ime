package jp.yorukana.ime

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color as AndroidColor
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.max

// ===== 1. よるかな IME 本体 (InputMethodService) =====
// 入力方式4種: ローマ字QWERTY / かな12キー (フリック・トグル) / 英数QWERTY /
// 記号・絵文字グリッド。ユーザー辞書対応。
// UI は Jetpack Compose (入力ビューに ComposeView をホストする)。
// タッチ処理は View 時代のセマンティクスを pointerInput で再現している:
//   - 文字キーは押した瞬間 (DOWN) に入力する (クリック完了を待たない)
//   - かなフリックは DOWN/MOVE/UP の座標から方向を解決し、UP 位置で決め直す
//   - プレビューはレイアウトに参加しないオーバーレイ表示 (キーボードの高さ
//     を変えない。v1.1.2 の全画面化バグ対策を Compose でも維持する)
// composing は setComposingText、確定は commitText を使う。ネットワーク権限なし。
class YoruKanaService : InputMethodService(), LifecycleOwner, ViewModelStoreOwner,
    SavedStateRegistryOwner {

    // ----- 1.1 ComposeView ホスト用のライフサイクル -----
    // InputMethodService のウィンドウには LifecycleOwner がいないため、
    // Service 自身をオーナーとして ComposeView に渡す。
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore
        get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    // ----- 1.2 テーマ色 -----
    private var colRoot = 0
    private var colKey = 0
    private var colSpecial = 0
    private var colText = 0
    private var colAccent = 0
    private var colBarBg = 0

    private val cRoot: Color get() = Color(colRoot)
    private val cKey: Color get() = Color(colKey)
    private val cSpecial: Color get() = Color(colSpecial)
    private val cText: Color get() = Color(colText)
    private val cAccent: Color get() = Color(colAccent)
    private val cBarBg: Color get() = Color(colBarBg)

    // ----- 1.3 状態 -----
    private var learning: LearningStore? = null
    private var prefsLearning: PrefsLearningStore? = null
    private var engine: ConversionEngine? = null
    private var prefs: SharedPreferences? = null
    private var vibrateOn = true

    // セーフティモード (起動クラッシュが連続した場合に段階的に機能を落とす)
    private var safetyMode: SafetyMode = SafetyMode.NORMAL

    // 現在の入力ビュー (onStartInputView で階層へのオーナー付与を
    // やり直すために保持する)
    private var inputViewRef: View? = null

    // Compose が購読する状態はスナップショットステートにする
    private var night by mutableStateOf(true)
    private var flickMode by mutableStateOf(true)
    private var mode by mutableStateOf(MODE_ROMAJI)
    private var symbolPage by mutableStateOf(0) // 0=記号 / 1=絵文字
    private var caps by mutableStateOf(false)
    private var currentDisplay by mutableStateOf("")
    private var candidates by mutableStateOf<List<String>>(emptyList())
    private var candidateReadings by mutableStateOf<List<String>>(emptyList())
    private var highlight by mutableStateOf(-1)

    private var userDictionary: UserDictionary = UserDictionary()

    // パスワード欄 / 学習抑制フラグのある入力先では true:
    // 英数モード固定＋学習記録を行わない (onStartInput ごとに判定し直す)
    private var secureSession = false
    private val romajiBuf = StringBuilder()
    private val kanaBuf = StringBuilder()
    private var currentReading = ""

    // かなトグル打ちの追跡
    private var lastKanaRow = -1
    private var lastKanaTime: Long = 0

    // 句読点循環キー (、。？！) の循環順と現在位置。
    // 他のキー操作ではリセットしない (タップ回数だけで進む)。
    private var punctCyclePos = 0

    // 削除キー長押しリピート
    // (inner class ではなく通常のネストクラスにしているのは、この手動
    //  ビルドの d8 が inner class のクラスファイルで NPE を起こすため)
    private val repeatHandler = Handler(Looper.getMainLooper())
    private val repeater: Runnable = DeleteRepeater(this)

    private class DeleteRepeater(private val service: YoruKanaService) : Runnable {
        override fun run() {
            service.doDelete()
            service.repeatHandler.postDelayed(this, 50)
        }
    }

    // ----- 1.4 プレビュー表示状態 (オーバーレイ) -----
    // フリックプレビュー: 十字配置の中心座標はプレビュー枠 (152dp) 内の
    // ローカル座標で保持する。枠の位置はルート左上からの px オフセット。
    private class FlickUiState(
        val chars: List<String>,
        val leftPx: Int,
        val topPx: Int,
        val cxPx: Float,
        val cyPx: Float,
        val dir: Int,
    )

    private class KeyUiState(
        val text: String,
        val leftPx: Int,
        val topPx: Int,
    )

    private var flickUi by mutableStateOf<FlickUiState?>(null)
    private var keyUi by mutableStateOf<KeyUiState?>(null)

    // ルート (入力ビュー全体) の幅 px。プレビュー位置のクランプに使う
    private var rootWidthPx = 0

    // 候補バーのスクロール状態と候補チップの左端座標 (ハイライト追従用)。
    // View 時代の HorizontalScrollView と同じく、バーが隠れても保持する。
    private val candScrollState = ScrollState(0)
    private val candItemLeft: MutableMap<Int, Int> = HashMap()

    // ----- 1.5 学習ストア (SharedPreferences 永続化) -----
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
                    if (key.startsWith(FREQ_PREFIX) && value is Int) {
                        freq[key.substring(FREQ_PREFIX.length)] = value
                    }
                }
            } catch (e: Exception) {
                freq.clear()
            }
        }

        override fun getFrequency(reading: String, candidate: String): Int {
            return freq[key(reading, candidate)] ?: 0
        }

        override fun recordSelection(reading: String, candidate: String) {
            val k = key(reading, candidate)
            val v = getFrequency(reading, candidate) + 1
            freq[k] = v
            try {
                prefs.edit().putInt(FREQ_PREFIX + k, v).apply()
            } catch (e: Exception) {
                // 保存に失敗してもメモリ上の頻度は保ち、入力は止めない
            }
        }

        override fun clear() {
            freq.clear()
            val ed = prefs.edit()
            for (k in prefs.all.keys) {
                if (k.startsWith(FREQ_PREFIX)) ed.remove(k)
            }
            ed.apply()
        }

        companion object {
            private fun key(reading: String, candidate: String): String {
                return "$reading|$candidate"
            }
        }
    }

    // ===== 2. ライフサイクル =====
    // 起動は段階ごとに失敗を閉じ込める: セーフティ記録 → 設定 → 学習 →
    // 辞書/変換エンジン の順に初期化し、どこかで例外が出ても「入力できる
    // 最小限の状態」で起動を完了させる。起動クラッシュが連続した場合は
    // SafetyNet がモードを返し、危険な機能から順に無効化する。
    override fun onCreate() {
        super.onCreate()
        // 最初に例外ハンドラと起動モードの判定を行う (これより後の
        // 初期化で死んでも、次回起動時に検知できるようにするため)
        SafetyNet.install(this)
        safetyMode = try {
            SafetyNet.beginLaunch(this)
        } catch (e: Exception) {
            SafetyMode.NORMAL
        }
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        // 設定: ファイルが壊れていても既定値で起動を続ける
        val p = try {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            null
        }
        prefs = p

        // 学習ストア: セーフモード以上では永続化をやめ、メモリ実装にする
        // (設定ファイル起因のクラッシュを断ち切るため)
        val persistent = if (safetyMode == SafetyMode.NORMAL && p != null) {
            try {
                PrefsLearningStore(p)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        prefsLearning = persistent
        val store: LearningStore = persistent ?: MemoryLearningStore()
        learning = store

        // 変換エンジン: 最小モードでは構築自体をしない (候補なしの直接
        // 入力になる)。構築に失敗したら一度だけ作り直し、それでも
        // 駄目なら変換なしで起動する。
        engine = if (safetyMode == SafetyMode.MINIMAL) {
            null
        } else {
            try {
                ConversionEngine(Dictionary(), store)
            } catch (e: Exception) {
                try {
                    ConversionEngine(Dictionary(), store)
                } catch (e2: Exception) {
                    null
                }
            }
        }
    }

    private fun loadPrefs() {
        val p = prefs ?: return
        night = "day" != p.getString(KEY_THEME, "night")
        // セーフモード以上ではバイブレーションも止める (復旧優先)
        vibrateOn = p.getBoolean(KEY_VIBRATE, true) && safetyMode == SafetyMode.NORMAL
        flickMode = "toggle" != p.getString(KEY_KANA_STYLE, "flick")
        if (night) {
            colRoot = AndroidColor.rgb(0x0B, 0x12, 0x26)
            colBarBg = AndroidColor.rgb(0x0B, 0x12, 0x26)
            colKey = AndroidColor.rgb(0x1C, 0x2A, 0x4A)
            colSpecial = AndroidColor.rgb(0x27, 0x35, 0x5C)
            colText = AndroidColor.rgb(0xEA, 0xF0, 0xFF)
            colAccent = AndroidColor.rgb(0x3D, 0x5A, 0xFE)
        } else {
            colRoot = AndroidColor.rgb(0xE8, 0xEB, 0xF0)
            colBarBg = AndroidColor.rgb(0xE8, 0xEB, 0xF0)
            colKey = AndroidColor.rgb(0xFF, 0xFF, 0xFF)
            colSpecial = AndroidColor.rgb(0xD5, 0xDB, 0xE8)
            colText = AndroidColor.rgb(0x18, 0x20, 0x38)
            colAccent = AndroidColor.rgb(0x9D, 0xB8, 0xF0)
        }
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val p = prefs ?: return
        loadPrefs()
        // composing の状態はセッションをまたいで保持しない方針:
        // 前セッションの composing が残っていれば、先に確定 (finish) してから
        // 内部状態を消す (restarting=true の場合も同じ)。
        val prevIc = currentInputConnection
        prevIc?.finishComposingText()
        clearComposingState()
        caps = false
        symbolPage = 0
        // 学習頻度を設定画面側の変更 (リセット等) に追随させる
        prefsLearning?.reload()
        // ユーザー辞書は入力開始時に読み込む (設定画面での変更を反映するため)。
        // セーフモード以上では読み込まない。壊れたデータでも例外にせず、
        // 空の辞書として扱う。
        userDictionary = if (safetyMode == SafetyMode.NORMAL) {
            try {
                UserDictionary.decode(p.getString(KEY_USER_DICT, ""))
            } catch (e: Exception) {
                UserDictionary()
            }
        } else {
            UserDictionary()
        }
        engine?.userDictionary = userDictionary

        // 初期モード: subtype の extra value (mode=romaji / mode=kana) を最優先し、
        // 取得できない場合は「既定の入力方式」設定を使う
        var subtypeMode: String? = null
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            val subtype = imm?.currentInputMethodSubtype
            if (subtype != null) subtypeMode = subtype.getExtraValueOf("mode")
        } catch (e: Exception) {
            subtypeMode = null // 取得できなければ設定の既定モードにフォールバック
        }
        if ("kana" == subtypeMode) {
            mode = MODE_KANA
        } else if ("romaji" == subtypeMode) {
            mode = MODE_ROMAJI
        } else {
            val def = p.getString(KEY_DEFAULT_MODE, "romaji")
            mode = if ("kana" == def) MODE_KANA else if ("en" == def) MODE_EN else MODE_ROMAJI
        }

        // パスワード欄と学習抑制指定の入力先はセキュアセッションとする:
        // 英数モードに固定し (モード切替でも日本語モードへ移らない)、
        // 学習記録も行わない。判定は onStartInput のたびに行う。
        secureSession = isPasswordField(attribute) ||
            (attribute.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        if (secureSession) mode = MODE_EN
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // テーマ・入力方式などの設定変更はスナップショットステート経由で
        // 自動的に再コンポジションされる (View 時代の buildKeyboard 相当)
        loadPrefs()
        // 表示のたびに階層全体へのオーナー付与をやり直す (アタッチ時の
        // リスナーと二重化し、親階層の差し替えに追随する)
        val v = inputViewRef
        if (v != null) {
            tagViewTreeOwners(v)
            v.post { tagViewTreeOwners(v) }
        }
    }

    // フルスクリーン抽出モード (横向き・一部アプリで入力ビューが画面全体に
    // 広がるモード) は常に無効化し、キーボードは通常の高さで表示する。
    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }

    override fun onCreateInputView(): View {
        loadPrefs()
        val composeView = ComposeView(this)
        inputViewRef = composeView
        tagViewTreeOwners(composeView)
        composeView.setContent { KeyboardUi() }
        // アタッチされるたび (初回表示・再表示) に階層全体へ付け直す。
        // フレームワークが入力ビューを IME ウィンドウへ組み込む際に
        // 親階層が差し替わるため、作った直後だけでは足りない。
        composeView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                tagViewTreeOwners(v)
            }

            override fun onViewDetachedFromWindow(v: View) {
                // 何もしない (次回アタッチ時に付け直す)
            }
        })
        // 入力ビューを作れた = 起動完了。セーフティの起動失敗カウントを
        // ここでリセットする (作れる前に死んだ場合だけ次回に数える)
        SafetyNet.markStartupCompleted(this)
        return composeView
    }

    // 入力ビューからウィンドウのルート (decorView) までの全階層に
    // ViewTree オーナーを付ける。実機 (AQUOS wish2) で、Compose が
    // キーボード本体ではなく IME ウィンドウの親レイアウト
    // (android:id/parentPanel) からオーナーを探しに行き、見つけられず
    // IllegalStateException で即落ちする事象を確認した (セーフティの
    // クラッシュ記録で特定)。本体だけに付けると端末側の探し方によって
    // 届かないため、階層のどこから探しても見つかるようにする。
    private fun tagViewTreeOwners(view: View) {
        var current: View? = view
        while (current != null) {
            current.setViewTreeLifecycleOwner(this)
            current.setViewTreeViewModelStoreOwner(this)
            current.setViewTreeSavedStateRegistryOwner(this)
            current = current.parent as? View
        }
        try {
            val decor = window?.window?.decorView
            if (decor != null) {
                decor.setViewTreeLifecycleOwner(this)
                decor.setViewTreeViewModelStoreOwner(this)
                decor.setViewTreeSavedStateRegistryOwner(this)
            }
        } catch (e: Exception) {
            // decorView が取れない環境では階層側の付与だけで続行する
        }
    }

    // InputConnection へ DPAD 左右のキーイベントを送る
    // composing 中は先に確定 (flush) してから送る (確定前の文字列にカーソルが
    // 吸われて意図しない位置に動くのを防ぐ)
    private fun sendCursorKey(left: Boolean) {
        val ic = currentInputConnection ?: return
        if (currentDisplay.isNotEmpty()) flushComposing()
        val code = if (left) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ===== 3. キーボード UI (Compose) =====
    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density + 0.5f).toInt()
    }

    @Composable
    private fun KeyboardUi() {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(cRoot)
                .onGloballyPositioned { rootWidthPx = it.size.width },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 候補も未確定文字も無いときは候補バーごと出さない
                // (View 時代の GONE と同じく、キーボードがその分低くなる)
                if (candidates.isNotEmpty() || currentDisplay.isNotEmpty()) {
                    CandidateBar()
                }
                when (mode) {
                    MODE_ROMAJI -> RomajiKeys()
                    MODE_KANA -> KanaKeys()
                    MODE_EN -> EnKeys()
                    MODE_SYMBOL -> SymbolKeys()
                }
            }
            // プレビューはレイアウトに参加しないオーバーレイ (最前面)。
            // サイズ固定で絶対配置するため、表示してもキーボードは広がらない。
            FlickPreviewOverlay()
            KeyPreviewOverlay()
        }
    }

    // ----- 3.1 候補バー -----
    @Composable
    private fun CandidateBar() {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .background(cBarBg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(candScrollState),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                candidates.forEachIndexed { i, cand ->
                    val idx = i
                    Box(
                        modifier = Modifier
                            .onGloballyPositioned {
                                candItemLeft[idx] = it.positionInParent().x.toInt()
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { buzz(); commitCandidate(idx) }
                            .background(
                                if (idx == highlight) cAccent else Color.Transparent,
                                RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(cand, fontSize = 16.sp, color = cText)
                    }
                }
            }
            BarCursorButton(true)
            BarCursorButton(false)
        }
        // ハイライト中の候補がバーの外にはみ出していたら、見える位置まで
        // スクロールする (変換キーを押しても選択中の候補が見えないと
        // 変換している実感が無く、確定できないため)
        LaunchedEffect(highlight, candidates) {
            if (highlight >= 0 && highlight < candidates.size) {
                val left = candItemLeft[highlight]
                if (left != null) candScrollState.scrollTo(max(0, left - dp(8)))
            }
        }
    }

    // 候補バー右端のカーソルボタン
    @Composable
    private fun BarCursorButton(left: Boolean) {
        Box(
            modifier = Modifier
                .width(34.dp)
                .height(42.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { buzz(); sendCursorKey(left) },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (left) "◀" else "▶", fontSize = 13.sp, color = cText)
        }
    }

    // ----- 3.2 キーの見た目 -----
    // キー同士の隙間はタッチ領域の内側 (背景のインセット) で描く。
    // タッチ判定は外側の Box 全面に付くため、隙間はデッドゾーンにならない
    // (View 時代の InsetDrawable 方式と同じ)。
    @Composable
    private fun KeyVisual(label: String, textSizeSp: Int, special: Boolean) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1.dp, vertical = 2.dp)
                .background(
                    if (special) cSpecial else cKey,
                    RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, fontSize = textSizeSp.sp, color = cText)
        }
    }

    // クリック (指が離れた瞬間) で動作するキー。View 時代の
    // setOnClickListener と同じタイミング。
    @Composable
    private fun RowScope.ClickKey(
        label: String, weight: Float, special: Boolean, textSizeSp: Int,
        heightDp: Int = 48, onClick: () -> Unit,
    ) {
        Box(
            modifier = Modifier
                .weight(weight)
                .height(heightDp.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { buzz(); onClick() },
        ) {
            KeyVisual(label, textSizeSp, special)
        }
    }

    // 文字キー。押した瞬間 (DOWN) に入力する。速く打ったときに指の滑りで
    // クリックがキャンセルされ、キーを取りこぼすのを防ぐため
    // (一般的な IME は押した瞬間に入力する)。
    // longChar があるキー (QWERTY 最上段) は、短く押したら文字、長押し
    // (450ms) で longChar (数字) を入力し、離したときの二重入力はしない。
    @Composable
    private fun RowScope.LetterKey(ch: String, longChar: String?) {
        var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .onGloballyPositioned { coords = it }
                .pointerInput(ch, longChar) {
                    awaitPointerEventScope {
                        while (true) {
                            val first = awaitPointerEvent()
                            val down = first.changes.firstOrNull { it.changedToDown() }
                                ?: continue
                            down.consume()
                            var longFired = false
                            val longRunnable: Runnable? = if (longChar != null) {
                                Runnable {
                                    longFired = true
                                    buzz()
                                    flushComposing()
                                    commitDirect(longChar)
                                    // プレビューの表示も数字に切り替える
                                    coords?.let { showKeyPreview(it, longChar) }
                                }
                            } else {
                                null
                            }
                            try {
                                coords?.let { showKeyPreview(it, ch) }
                                if (longRunnable != null) {
                                    repeatHandler.postDelayed(longRunnable, 450)
                                } else {
                                    buzz()
                                    onLetter(ch)
                                }
                                waitRelease(down.id)
                                if (longRunnable != null && !longFired) {
                                    buzz()
                                    onLetter(ch)
                                }
                            } finally {
                                // 離した/キャンセルされたらプレビュー消去と
                                // 長押し予約の解除を必ず行う
                                hideKeyPreview()
                                if (longRunnable != null) {
                                    repeatHandler.removeCallbacks(longRunnable)
                                }
                            }
                        }
                    }
                },
        ) {
            KeyVisual(ch, 17, false)
        }
    }

    // 削除キー。押した瞬間に1文字消し、長押し (400ms〜) で 50ms 間隔の
    // リピートに入る。離す/キャンセルで必ず止める。
    @Composable
    private fun RowScope.DeleteKey(weight: Float, heightDp: Int) {
        Box(
            modifier = Modifier
                .weight(weight)
                .height(heightDp.dp)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val first = awaitPointerEvent()
                            val down = first.changes.firstOrNull { it.changedToDown() }
                                ?: continue
                            down.consume()
                            buzz()
                            doDelete()
                            repeatHandler.postDelayed(repeater, 400)
                            try {
                                waitRelease(down.id)
                            } finally {
                                repeatHandler.removeCallbacks(repeater)
                            }
                        }
                    }
                },
        ) {
            KeyVisual("⌫", 17, true)
        }
    }

    // かなキー (Gboard 方式配置: 高さ58dp / 22sp)。
    // フリック/トグルのどちらの方式でも同じ配置・同じ付け方にする。
    @Composable
    private fun RowScope.KanaKey(rowIdx: Int) {
        var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        val flick = flickMode
        Box(
            modifier = Modifier
                .weight(1f)
                .height(KANA_KEY_HEIGHT_DP.dp)
                .onGloballyPositioned { coords = it }
                .pointerInput(rowIdx, flick) {
                    awaitPointerEventScope {
                        while (true) {
                            val first = awaitPointerEvent()
                            val down = first.changes.firstOrNull { it.changedToDown() }
                                ?: continue
                            down.consume()
                            buzz()
                            if (!flick) {
                                // トグル打ち: フリック方式と同じく押した瞬間に入力。
                                // キャンセル時は後始末が要らないので、そのまま
                                // ハンドラを抜ける (例外は伝播させる)。
                                onKanaKey(rowIdx)
                                waitRelease(down.id)
                                continue
                            }
                            // フリック: DOWN 位置からの変位で方向を解決する。
                            // 速いフリックでは MOVE が閾値を超える前に指が離れる
                            // ことがあるため、離れた位置の座標で方向を決め直して
                            // から確定する (View 時代の ACTION_UP 処理と同じ)。
                            val downPos = down.position
                            var dir = FlickResolver.DIR_CENTER
                            coords?.let { showFlickPreview(rowIdx, it) }
                            try {
                                var done = false
                                while (!done) {
                                    val ev = awaitPointerEvent()
                                    val change = ev.changes.firstOrNull { it.id == down.id }
                                        ?: continue
                                    change.consume()
                                    dir = FlickResolver.resolveDirection(
                                        change.position.x - downPos.x,
                                        change.position.y - downPos.y,
                                        dp(20).toFloat(),
                                    )
                                    if (change.pressed) {
                                        setFlickDirection(dir)
                                    } else {
                                        hideFlickPreview()
                                        if (dir == FlickResolver.DIR_CENTER) {
                                            // タップ扱い: 従来のトグル循環を併用する
                                            onKanaKey(rowIdx)
                                        } else {
                                            appendKanaFlick(rowIdx, dir)
                                        }
                                        done = true
                                    }
                                }
                            } finally {
                                // キャンセル時は確定せず、プレビュー消去だけを必ず行う
                                hideFlickPreview()
                            }
                        }
                    }
                },
        ) {
            KeyVisual(KanaUtils.TOGGLE_KEY_LABELS[rowIdx], 22, false)
        }
    }

    // 追跡中のポインタが離れるまで待つ (他のポインタのイベントは読み捨てる)
    private suspend fun AwaitPointerEventScope.waitRelease(id: PointerId) {
        var released = false
        while (!released) {
            val ev = awaitPointerEvent()
            val change = ev.changes.firstOrNull { it.id == id } ?: continue
            change.consume()
            if (!change.pressed) released = true
        }
    }

    // モードキーのラベルは「現在のモード」ではなく「タップで切り替わる先の
    // モード」を表す (押すと何になるかが一目で分かるようにするため)。
    // 切替順は (mode + 1) % 4: ローマ字→かな→英数→記号→ローマ字。
    private fun modeLabel(): String {
        return when (mode) {
            MODE_ROMAJI -> "あ"   // 次は かな入力
            MODE_KANA -> "ABC"    // 次は 英数入力
            MODE_EN -> "記号"      // 次は 記号入力
            else -> "あ"             // 記号の次は ローマ字入力
        }
    }

    @Composable
    private fun RowScope.ModeKey(weight: Float, heightDp: Int) {
        ClickKey(modeLabel(), weight, true, 14, heightDp) { switchMode((mode + 1) % 4) }
    }

    @Composable
    private fun RowScope.SpaceKey(weight: Float, heightDp: Int) {
        // ラベルは実際の動作と一致させる (英数=スペース、記号=空白、その他=変換)
        val label = if (mode == MODE_EN) "スペース" else if (mode == MODE_SYMBOL) "空白" else "変換"
        ClickKey(label, weight, true, 14, heightDp) { onSpace() }
    }

    @Composable
    private fun RowScope.EnterKey(weight: Float) {
        ClickKey("改行", weight, true, 14) { onEnter() }
    }

    @Composable
    private fun RowScope.CursorKey(left: Boolean) {
        ClickKey(if (left) "←" else "→", 0.8f, true, 15) { sendCursorKey(left) }
    }

    // ラベル・幅・高さを指定できるカーソルキー (かな配置の左右列用)
    @Composable
    private fun RowScope.CursorKey(
        left: Boolean, label: String, weight: Float, heightDp: Int,
    ) {
        ClickKey(label, weight, true, 15, heightDp) { sendCursorKey(left) }
    }

    // ----- 3.3 ローマ字 QWERTY -----
    @Composable
    private fun RomajiKeys() {
        val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        for (r in rows.indices) {
            Row(modifier = Modifier.fillMaxWidth()) {
                if (r == 2) {
                    ClickKey("、", 0.8f, false, 16) { commitPunct("、") }
                }
                val chars = rows[r].toCharArray()
                for (i in chars.indices) {
                    // 最上段は長押しで数字 1〜0 を入力する
                    LetterKey(chars[i].toString(), if (r == 0) "1234567890"[i].toString() else null)
                }
                if (r == 2) {
                    ClickKey("。", 0.8f, false, 16) { commitPunct("。") }
                    DeleteKey(1.2f, 48)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            ModeKey(1.1f, 48)
            CursorKey(true)
            SpaceKey(2.6f, 48)
            CursorKey(false)
            EnterKey(1.1f)
        }
    }

    // ----- 3.4 かな 12キー (フリック / トグル切替) -----
    // 全4行とも weight [0.75 / 1 / 1 / 1 / 0.75] で統一し、5列の境界を
    // 全行で一致させる (ユーザーの指定 2026-11-18)。両端のユーティ
    // リティ列は細く、中央3列のかなキーが主役の標準的な配分。
    // 以前は行4だけ合計が異なり「わ」の列位置がずれていた。
    // 高さ58dp / かなキー 22sp は共通。全4行:
    // 行1: 記 あ か さ ⌫ / 行2: ◀ た な は ▶ /
    // 行3: 😊 ま や ら ゛゜小 / 行4: モード 、。？！ わ 変換 ⏎
    // ゛゜小 はかなキーのすぐ右 (行3右列) に置き、打った直後に届きやすくする。
    @Composable
    private fun KanaKeys() {
        // 行1: [記] あ か さ [⌫]
        Row(modifier = Modifier.fillMaxWidth()) {
            ClickKey("記", 0.75f, true, 15, KANA_KEY_HEIGHT_DP) {
                symbolPage = 0
                switchMode(MODE_SYMBOL)
            }
            KanaKey(0)
            KanaKey(1)
            KanaKey(2)
            DeleteKey(0.75f, KANA_KEY_HEIGHT_DP)
        }

        // 行2: [◀] た な は [▶]
        Row(modifier = Modifier.fillMaxWidth()) {
            CursorKey(true, "◀", 0.75f, KANA_KEY_HEIGHT_DP)
            KanaKey(3)
            KanaKey(4)
            KanaKey(5)
            CursorKey(false, "▶", 0.75f, KANA_KEY_HEIGHT_DP)
        }

        // 行3: [😊] ま や ら [゛゜小] (濁点キーはかなキーのすぐ右に置く)
        Row(modifier = Modifier.fillMaxWidth()) {
            ClickKey("😊", 0.75f, true, 17, KANA_KEY_HEIGHT_DP) {
                symbolPage = 1
                switchMode(MODE_SYMBOL)
            }
            KanaKey(6)
            KanaKey(7)
            KanaKey(8)
            ClickKey("゛゜小", 0.75f, true, 13, KANA_KEY_HEIGHT_DP) { onDakutenKey() }
        }

        // 行4: [モード] [、。？！] わ [変換] [⏎] (列境界を行1〜3と一致させる)
        Row(modifier = Modifier.fillMaxWidth()) {
            ModeKey(0.75f, KANA_KEY_HEIGHT_DP)
            ClickKey("、。？！", 1f, true, 14, KANA_KEY_HEIGHT_DP) { onPunctCycleKey() }
            KanaKey(9) // わ
            SpaceKey(1f, KANA_KEY_HEIGHT_DP)
            ClickKey("⏎", 0.75f, true, 20, KANA_KEY_HEIGHT_DP) { onEnter() }
        }
    }

    // ----- 3.5 英数 QWERTY -----
    @Composable
    private fun EnKeys() {
        Row(modifier = Modifier.fillMaxWidth()) {
            for (c in "1234567890".toCharArray()) LetterKey(c.toString(), null)
        }
        val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        for (r in rows.indices) {
            Row(modifier = Modifier.fillMaxWidth()) {
                if (r == 2) {
                    ClickKey(if (caps) "⇪" else "⇧", 1.2f, true, 16) { caps = !caps }
                }
                for (c in rows[r].toCharArray()) {
                    LetterKey((if (caps) c.uppercaseChar() else c).toString(), null)
                }
                if (r == 2) DeleteKey(1.2f, 48)
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            ModeKey(1.1f, 48)
            ClickKey(",", 0.9f, false, 17) { commitDirect(",") }
            SpaceKey(2.8f, 48)
            ClickKey(".", 0.9f, false, 17) { commitDirect(".") }
            EnterKey(1.1f)
        }
    }

    // ----- 3.6 記号グリッド -----
    @Composable
    private fun SymbolKeys() {
        // 記号 / 絵文字 の切替タブ (選択中タブが special 色になる)
        Row(modifier = Modifier.fillMaxWidth()) {
            ClickKey("記号", 1f, symbolPage == 0, 14) { symbolPage = 0 }
            ClickKey("絵文字", 1f, symbolPage == 1, 14) { symbolPage = 1 }
        }

        val emoji = symbolPage == 1
        val items = if (emoji) EMOJIS else SYMBOLS
        val cols = if (emoji) 8 else 10
        var i = 0
        while (i < items.size) {
            val end = minOf(i + cols, items.size)
            Row(modifier = Modifier.fillMaxWidth()) {
                for (j in i until end) {
                    val s = items[j]
                    ClickKey(s, 1f, false, if (emoji) 17 else 15) { commitPunct(s) }
                }
                // 最終行が列数に満たない場合は透明スペーサーで埋め、
                // キーの幅・高さが他行と揃うようにする
                for (j in end until i + cols) {
                    Box(modifier = Modifier.weight(1f).height(48.dp))
                }
            }
            i += cols
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            ModeKey(1.1f, 48)
            SpaceKey(2.8f, 48)
            DeleteKey(1.2f, 48)
            EnterKey(1.1f)
        }
    }

    // ----- 3.7 プレビュー (オーバーレイ描画) -----
    // フリックプレビュー (十字配置)。View 時代の FlickPreviewView の
    // Canvas 描画を Compose Canvas で再現する。
    @Composable
    private fun FlickPreviewOverlay() {
        val ui = flickUi ?: return
        val textMeasurer = rememberTextMeasurer()
        Box(
            modifier = Modifier
                .offset { IntOffset(ui.leftPx, ui.topPx) }
                .size(FLICK_PREVIEW_SIZE_DP.dp),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val unit = dp(38).toFloat()
                val pad = unit * 0.62f
                val cx = ui.cxPx
                val cy = ui.cyPx
                drawRoundRect(
                    color = cSpecial,
                    topLeft = Offset(cx - unit - pad, cy - unit - pad),
                    size = Size((unit + pad) * 2, (unit + pad) * 2),
                    cornerRadius = CornerRadius(unit * 0.3f, unit * 0.3f),
                )
                // DIR_CENTER, LEFT, UP, RIGHT, DOWN の順のオフセット
                val offs = arrayOf(
                    floatArrayOf(0f, 0f), floatArrayOf(-unit, 0f), floatArrayOf(0f, -unit),
                    floatArrayOf(unit, 0f), floatArrayOf(0f, unit),
                )
                val style = TextStyle(
                    color = cText,
                    fontSize = (unit * 0.52f).toSp(),
                )
                for (d in 0 until 5) {
                    val ch = ui.chars.getOrNull(d) ?: ""
                    if (ch.isEmpty()) continue // 未割り当て方向 (や行の左右等) は描かない
                    val x = cx + offs[d][0]
                    val y = cy + offs[d][1]
                    // ハイライト円は、文字が割り当てられた方向にだけ描く
                    if (d == ui.dir) {
                        drawCircle(color = cAccent, radius = unit * 0.55f, center = Offset(x, y))
                    }
                    val layout = textMeasurer.measure(ch, style)
                    drawText(
                        layout,
                        topLeft = Offset(
                            x - layout.size.width / 2f,
                            y - layout.size.height / 2f,
                        ),
                    )
                }
            }
        }
    }

    // キープレビュー (文字キー用): 押したキーの文字をキーの上に大きく出す
    @Composable
    private fun KeyPreviewOverlay() {
        val ui = keyUi ?: return
        Box(
            modifier = Modifier
                .offset { IntOffset(ui.leftPx, ui.topPx) }
                .size(KEY_PREVIEW_SIZE_DP.dp)
                .background(cSpecial, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(ui.text, fontSize = 26.sp, color = cText)
        }
    }

    // ===== 4. 入力処理 =====
    private fun buzz() {
        if (!vibrateOn) return
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            v?.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // バイブレーションが使えない環境では無音で継続する
        }
    }

    private fun onLetter(ch: String) {
        if (mode == MODE_EN) {
            commitDirect(ch)
            return
        }
        if (mode == MODE_ROMAJI) {
            if (ch.length == 1 && ch[0] in '0'..'9') {
                commitDirect(ch)
                return
            }
            romajiBuf.append(ch)
            highlight = -1
            updateComposing()
        }
    }

    private fun onKanaKey(rowIdx: Int) {
        val now = System.currentTimeMillis()
        var cycled = false
        // トグル循環: 同じキーを1秒以内に再タップし、末尾文字がその行に
        // 属していれば、新規追加ではなく末尾文字を段の次の文字に進める
        if (rowIdx == lastKanaRow && now - lastKanaTime < 1000 && kanaBuf.isNotEmpty()) {
            val last = kanaBuf[kanaBuf.length - 1]
            if (KanaUtils.rowIndexOf(last) == rowIdx) {
                kanaBuf.setCharAt(kanaBuf.length - 1, KanaUtils.nextInRow(rowIdx, last))
                cycled = true
            }
        }
        if (!cycled) {
            kanaBuf.append(KanaUtils.TOGGLE_ROWS[rowIdx][0])
        }
        lastKanaRow = rowIdx
        lastKanaTime = now
        highlight = -1
        updateComposing()
    }

    // 句読点循環キー: タップごとに 、→。→？→！ を順にコミットする
    private fun onPunctCycleKey() {
        commitPunct(PUNCT_CYCLE[punctCyclePos])
        punctCyclePos = (punctCyclePos + 1) % PUNCT_CYCLE.size
    }

    private fun onDakutenKey() {
        if (kanaBuf.isEmpty()) return
        val last = kanaBuf[kanaBuf.length - 1]
        val next = KanaUtils.toggleDakuten(last)
        if (next != last) {
            kanaBuf.setCharAt(kanaBuf.length - 1, next)
            lastKanaRow = -1
            highlight = -1
            updateComposing()
        }
    }

    // ----- 4.1 フリック入力 -----
    // フリックで選んだ文字をそのまま composing に追加する (トグル循環はしない)
    private fun appendKanaFlick(rowIdx: Int, dir: Int) {
        val c = FlickResolver.charAt(rowIdx, dir)
        if (c == '\u0000') return
        kanaBuf.append(c)
        lastKanaRow = -1
        highlight = -1
        updateComposing()
    }

    private fun showFlickPreview(rowIdx: Int, coords: LayoutCoordinates) {
        // 最小モードではプレビュー (オーバーレイ描画) を出さない
        if (safetyMode == SafetyMode.MINIMAL) return
        val size = dp(FLICK_PREVIEW_SIZE_DP)
        val pos = coords.positionInRoot()
        val keyCx = (pos.x + coords.size.width / 2f).toInt()
        val keyCy = (pos.y + coords.size.height / 2f).toInt()
        // プレビュー (固定サイズ) の中心がキー中心に重なるよう位置決めする。
        // 画面端のキーではみ出す場合は、左端は 0 以上かつルート幅の内側に、
        // 上端は 0 以上にクランプする。
        var left = keyCx - size / 2
        var top = keyCy - size / 2
        val rootW = rootWidthPx
        if (left < 0) left = 0
        if (rootW > 0 && left + size > rootW) left = max(0, rootW - size)
        if (top < 0) top = 0
        val chars = ArrayList<String>(5)
        for (d in 0 until 5) {
            chars.add(
                if (FlickResolver.isAssigned(rowIdx, d)) {
                    FlickResolver.charAt(rowIdx, d).toString()
                } else {
                    ""
                },
            )
        }
        // 描画座標はプレビュー枠内のローカル座標で渡す
        // (十字の中心 = 枠内でのキー中心の相対位置)。
        flickUi = FlickUiState(
            chars = chars,
            leftPx = left,
            topPx = top,
            cxPx = (keyCx - left).toFloat(),
            cyPx = (keyCy - top).toFloat(),
            dir = FlickResolver.DIR_CENTER,
        )
    }

    private fun setFlickDirection(dir: Int) {
        val ui = flickUi ?: return
        if (ui.dir != dir) {
            flickUi = FlickUiState(ui.chars, ui.leftPx, ui.topPx, ui.cxPx, ui.cyPx, dir)
        }
    }

    private fun hideFlickPreview() {
        flickUi = null
    }

    // 文字キーのキープレビューをキーの真上に表示する (画面端では内側に寄せ、
    // 上に置けない場合はキーの下に出す)
    private fun showKeyPreview(coords: LayoutCoordinates, text: String) {
        // 最小モードではプレビュー (オーバーレイ描画) を出さない
        if (safetyMode == SafetyMode.MINIMAL) return
        val size = dp(KEY_PREVIEW_SIZE_DP)
        val pos = coords.positionInRoot()
        val keyCx = (pos.x + coords.size.width / 2f).toInt()
        var left = keyCx - size / 2
        if (left < 0) left = 0
        val rootW = rootWidthPx
        if (rootW > 0 && left + size > rootW) left = max(0, rootW - size)
        var top = pos.y.toInt() - size - dp(6)
        if (top < 0) top = pos.y.toInt() + coords.size.height + dp(6)
        keyUi = KeyUiState(text, left, top)
    }

    private fun hideKeyPreview() {
        keyUi = null
    }

    // ----- 4.2 共通キー処理 (スペース / Enter / 削除 / モード切替) -----
    private fun onSpace() {
        if (mode == MODE_EN || mode == MODE_SYMBOL) {
            commitDirect(" ")
            return
        }
        if (currentDisplay.isEmpty()) {
            // composing が無い日本語モードのスペースは全角スペースを入れる
            // (英数/記号モードの半角スペースとは別扱い)
            commitDirect("　")
            return
        }
        if (candidates.isNotEmpty()) {
            highlight = (highlight + 1) % candidates.size
            applyComposingText()
        }
    }

    private fun onEnter() {
        val ic = currentInputConnection ?: return
        if (highlight >= 0 && highlight < candidates.size) {
            commitCandidate(highlight)
            return
        }
        if (currentDisplay.isNotEmpty()) {
            // ハイライト無しの Enter は composing をそのまま確定する
            val text = if (mode == MODE_ROMAJI) {
                RomajiConverter.convertFully(romajiBuf.toString())
            } else {
                kanaBuf.toString()
            }
            ic.commitText(text, 1)
            clearComposingState()
            return
        }
        val ei = currentInputEditorInfo
        val action = if (ei != null) {
            ei.imeOptions and EditorInfo.IME_MASK_ACTION
        } else {
            EditorInfo.IME_ACTION_NONE
        }
        if (ei != null && action != EditorInfo.IME_ACTION_NONE &&
            (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
        ) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun doDelete() {
        val ic = currentInputConnection ?: return
        // 変換中 (候補ハイライト中) の削除は、文字を消す前に変換だけを取り消す
        if (highlight >= 0) {
            highlight = -1
            applyComposingText()
            return
        }
        if (mode == MODE_ROMAJI && romajiBuf.isNotEmpty()) {
            romajiBuf.deleteCharAt(romajiBuf.length - 1)
            highlight = -1
            updateComposing()
        } else if (mode == MODE_KANA && kanaBuf.isNotEmpty()) {
            kanaBuf.deleteCharAt(kanaBuf.length - 1)
            lastKanaRow = -1
            highlight = -1
            updateComposing()
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    private fun switchMode(newMode: Int) {
        // セキュアセッション (パスワード欄等) では英数モードから移らない
        if (secureSession && newMode != MODE_EN) return
        flushComposing()
        mode = newMode
    }

    // ===== 5. composing / 候補 =====
    private fun clearComposingState() {
        romajiBuf.setLength(0)
        kanaBuf.setLength(0)
        currentReading = ""
        currentDisplay = ""
        candidates = emptyList()
        candidateReadings = emptyList()
        highlight = -1
        lastKanaRow = -1
    }

    // composing を確定してから直接コミットする (句読点・記号用)
    private fun commitPunct(s: String) {
        flushComposing()
        commitDirect(s)
    }

    private fun flushComposing() {
        // 変換中の候補があれば、その候補で確定する (画面に見えている文字列が
        // そのまま確定するのが自然なため)
        if (highlight >= 0 && highlight < candidates.size) {
            commitCandidate(highlight)
            return
        }
        if (currentDisplay.isEmpty()) return
        val ic = currentInputConnection ?: return
        val text = if (mode == MODE_ROMAJI) {
            RomajiConverter.convertFully(romajiBuf.toString())
        } else {
            kanaBuf.toString()
        }
        ic.commitText(text, 1)
        clearComposingState()
    }

    private fun commitDirect(s: String) {
        val ic = currentInputConnection
        ic?.commitText(s, 1)
    }

    private fun updateComposing() {
        val ic = currentInputConnection ?: return
        if (mode == MODE_ROMAJI) {
            val r = RomajiConverter.convert(romajiBuf.toString())
            currentDisplay = r.kana + r.pending
            // 表示は pending の "n" のままだが、候補検索用の読みでは末尾の
            // n を ん として扱う (例: "kan" の表示で かん の候補を出すため)。
            // n 以外の pending は読みに含めない。
            currentReading = r.kana + (if (r.pending == "n") "ん" else "")
        } else if (mode == MODE_KANA) {
            currentDisplay = kanaBuf.toString()
            currentReading = currentDisplay
        } else {
            return
        }
        if (currentDisplay.isEmpty()) {
            try {
                ic.finishComposingText()
            } catch (e: Exception) {
                // 接続が切れた等の例外でキー処理全体を落とさない
            }
            candidates = emptyList()
            candidateReadings = emptyList()
        } else {
            refreshCandidates()
            applyComposingText()
        }
    }

    // composing の表示を更新する。変換中 (候補をハイライト中) は候補の文字列を
    // 未確定表示にする。本文側でも変換結果が見えるため、候補バーだけを見る
    // 必要がなくなる。ハイライトが無ければ読み (かな) を表示する。
    private fun applyComposingText() {
        val ic = currentInputConnection ?: return
        val text = if (highlight >= 0 && highlight < candidates.size) {
            candidates[highlight]
        } else {
            currentDisplay
        }
        try {
            if (text.isEmpty()) ic.finishComposingText() else ic.setComposingText(text, 1)
        } catch (e: Exception) {
            // 入力先との接続が切れた等の例外でキー処理全体を落とさない
        }
    }

    private fun refreshCandidates() {
        val newCandidates = ArrayList<String>()
        val newReadings = ArrayList<String>()
        if (currentReading.isNotEmpty()) {
            val eng = engine
            if (eng != null) {
                try {
                    fillCandidates(eng, newCandidates, newReadings)
                } catch (e: Exception) {
                    // 変換で例外が出たら候補なしにする (読みの表示と確定は
                    // 候補なしでも動作するため、入力は継続できる)
                    newCandidates.clear()
                    newReadings.clear()
                }
            }
        }
        candidates = newCandidates
        candidateReadings = newReadings
    }

    private fun fillCandidates(
        eng: ConversionEngine,
        newCandidates: ArrayList<String>,
        newReadings: ArrayList<String>,
    ) {
        val raw = if (mode == MODE_ROMAJI) romajiBuf.toString() else null
        for (c in eng.candidates(currentReading, raw)) {
            newCandidates.add(c)
            newReadings.add(currentReading)
        }
        // 予測候補を後ろに足す (重複排除)
        for (e in eng.predictEntries(currentReading, 8)) {
            if (!newCandidates.contains(e[1])) {
                newCandidates.add(e[1])
                newReadings.add(e[0])
            }
        }
        // 候補は最大24件に絞る。candidateReadings は候補と同順・同数で
        // 対応付けるため、末尾から一緒に切る
        while (newCandidates.size > 24) {
            newCandidates.removeAt(newCandidates.size - 1)
            newReadings.removeAt(newReadings.size - 1)
        }
    }

    private fun commitCandidate(idx: Int) {
        if (idx < 0 || idx >= candidates.size) return
        val ic = currentInputConnection ?: return
        val text = candidates[idx]
        val reading = if (idx < candidateReadings.size) candidateReadings[idx] else currentReading
        // ひらがな原文・ローマ字原文そのままの確定は学習しない。
        // セキュアセッション (パスワード欄・学習抑制指定) では一切記録しない。
        if (!secureSession && text != reading &&
            !(mode == MODE_ROMAJI && text == romajiBuf.toString())
        ) {
            learning?.recordSelection(reading, text)
        }
        ic.commitText(text, 1)
        clearComposingState()
    }

    // ----- 5.1 終了処理 -----
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // ビューが閉じたら削除リピートとフリックプレビューを残さない
        repeatHandler.removeCallbacks(repeater)
        hideFlickPreview()
        hideKeyPreview()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        repeatHandler.removeCallbacks(repeater)
        hideFlickPreview()
        hideKeyPreview()
        // 入力先に composing が残らないよう、先に確定してから内部状態を消す
        val ic = currentInputConnection
        ic?.finishComposingText()
        clearComposingState()
    }

    override fun onDestroy() {
        // Handler のコールバックを確実に解除してから破棄する
        repeatHandler.removeCallbacks(repeater)
        hideFlickPreview()
        hideKeyPreview()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    // ===== 6. 定数・データ =====
    companion object {
        // ----- 6.1 定数・設定 -----
        const val PREFS = "yorukana_prefs"
        const val KEY_THEME = "theme"         // "night" / "day"
        const val KEY_VIBRATE = "vibrate"     // boolean
        const val KEY_DEFAULT_MODE = "default_mode" // "romaji"/"kana"/"en"
        const val KEY_KANA_STYLE = "kana_style"     // "flick" / "toggle"
        const val KEY_USER_DICT = "user_dict"       // UserDictionary.encode() 形式
        const val FREQ_PREFIX = "freq:"

        const val MODE_ROMAJI = 0
        const val MODE_KANA = 1
        const val MODE_EN = 2
        const val MODE_SYMBOL = 3

        // フリックプレビュー (オーバーレイ) の一辺のサイズ。レイアウトに
        // 参加させると表示した瞬間にキーボード全体が広がるため、固定サイズ
        // のオーバーレイとして絶対配置する。
        private const val FLICK_PREVIEW_SIZE_DP = 152

        // キープレビュー (文字キー) の一辺のサイズ
        private const val KEY_PREVIEW_SIZE_DP = 52

        // かなキーボードのキー高さ (全4行共通)。行ごとに高さが違うと
        // 不揃いで打ちにくく見えるため、KanaKeys 内のキーは必ずこの定数を
        // 使うこと (行4のキーも含め全行 58dp で統一する)。
        private const val KANA_KEY_HEIGHT_DP = 58

        // 句読点循環キー (、。？！) の循環順。
        private val PUNCT_CYCLE: Array<String> = arrayOf("、", "。", "？", "！")

        // ----- 6.2 記号・絵文字データ -----
        // 記号グリッド: 全72件 (10列 × 8行、最終行は2件)。SymbolKeys の
        // ループで全件分のキーを作るため、配列に載せた記号はすべて表示される。
        private val SYMBOLS: Array<String> = arrayOf(
            "、", "。", "！", "？", "・", "：", "；", "（", "）", "「",
            "」", "『", "』", "［", "］", "｛", "｝", "＠", "＃", "￥",
            "％", "＆", "＊", "＋", "－", "＝", "＜", "＞", "＿", "〜",
            "…", "＾", "｜", "／", "＼", "＂", "＇", "〈", "〉", "《",
            "》", "☆", "★", "○", "●", "◎", "◇", "◆", "□", "■",
            "△", "▲", "▽", "▼", "※", "♪", "♥", "→", "←", "↑",
            "↓", "°", "１", "２", "３", "４", "５", "６", "７", "８",
            "９", "０",
        )

        // よく使う絵文字 約60個 (タップで直接コミット)
        private val EMOJIS: Array<String> = arrayOf(
            "😀", "😁", "😂", "🤣", "😊", "😍", "😘", "😎",
            "🤔", "😴", "😭", "😱", "😡", "😇", "🥺", "👍",
            "👎", "🙏", "👏", "💪", "🔥", "✨", "⭐", "❤️",
            "💔", "💯", "🎉", "🎂", "🍀", "🌸", "🌙", "☀️",
            "☔", "❄️", "⚡", "🌈", "🍙", "🍜", "🍣", "☕",
            "🍺", "🚗", "🚃", "✈️", "📱", "💻", "📚", "✏️",
            "⚽", "🎮", "🎵", "🎧", "🐱", "🐶", "🐰", "🎁",
            "⏰", "💤", "📷", "🏫",
        )

        // ----- 6.3 パスワード欄判定 -----
        // 入力先がパスワード系フィールドかどうか (クラス別に variation を見る)
        private fun isPasswordField(attribute: EditorInfo): Boolean {
            val type = attribute.inputType
            val cls = type and InputType.TYPE_MASK_CLASS
            val variation = type and InputType.TYPE_MASK_VARIATION
            if (cls == InputType.TYPE_CLASS_TEXT) {
                return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            }
            if (cls == InputType.TYPE_CLASS_NUMBER) {
                return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
            return false
        }
    }
}
