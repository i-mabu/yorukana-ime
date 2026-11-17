package jp.yorukana.ime

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

// ===== 1. よるかな IME 本体 (InputMethodService) =====
// 入力方式4種: ローマ字QWERTY / かな12キー (フリック・トグル) / 英数QWERTY /
// 記号・絵文字グリッド。ユーザー辞書対応。
// composing は setComposingText、確定は commitText を使う。ネットワーク権限なし。
class YoruKanaService : InputMethodService() {

    // テーマ色
    private var colRoot = 0
    private var colKey = 0
    private var colSpecial = 0
    private var colText = 0
    private var colAccent = 0
    private var colBarBg = 0

    // ----- 1.2 状態 -----
    private var dictionary: Dictionary? = null
    private var learning: PrefsLearningStore? = null
    private var engine: ConversionEngine? = null
    private var prefs: SharedPreferences? = null
    private var vibrateOn = true
    private var night = true
    private var flickMode = true
    private var userDictionary: UserDictionary = UserDictionary()

    private var mode = MODE_ROMAJI
    private var symbolPage = 0 // 0=記号 / 1=絵文字
    // パスワード欄 / 学習抑制フラグのある入力先では true:
    // 英数モード固定＋学習記録を行わない (onStartInput ごとに判定し直す)
    private var secureSession = false
    private val romajiBuf = StringBuilder()
    private val kanaBuf = StringBuilder()
    private var currentReading = ""
    private var currentDisplay = ""
    private var candidates: MutableList<String> = ArrayList()
    private var candidateReadings: MutableList<String> = ArrayList()
    private var highlight = -1
    private var caps = false

    // かなトグル打ちの追跡
    private var lastKanaRow = -1
    private var lastKanaTime: Long = 0

    // 句読点循環キー (、。？！) の循環順と現在位置。
    // 他のキー操作ではリセットしない (タップ回数だけで進む)。
    private var punctCyclePos = 0

    // 削除キー長押しリピート
    // (static 内部クラスにしているのは、この手動ビルドの d8 が
    //  非 static 内部クラスのクラスファイルで NPE を起こすため)
    private val repeatHandler = Handler(Looper.getMainLooper())
    private val repeater: Runnable = DeleteRepeater(this)

    private class DeleteRepeater(private val service: YoruKanaService) : Runnable {
        override fun run() {
            service.doDelete()
            service.repeatHandler.postDelayed(this, 50)
        }
    }

    // かなキーのフリック操作を受け持つタッチリスナ (static 内部クラス: d8 対策)
    private class KanaFlickTouchListener(
        private val service: YoruKanaService,
        private val rowIdx: Int,
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var dir = FlickResolver.DIR_CENTER
        private var active = false
        private var pointerId = -1 // 追跡中のポインタ (マルチタッチ対策)

        // 指が離れたときの確定処理 (ACTION_UP / 対象ポインタの POINTER_UP 共通)
        private fun finishGesture() {
            active = false
            pointerId = -1
            service.hideFlickPreview()
            if (dir == FlickResolver.DIR_CENTER) {
                // タップ扱い: 従来のトグル循環を併用する
                service.onKanaKey(rowIdx)
            } else {
                service.appendKanaFlick(rowIdx, dir)
            }
        }

        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    service.buzz()
                    downX = ev.rawX
                    downY = ev.rawY
                    dir = FlickResolver.DIR_CENTER
                    active = true
                    pointerId = ev.getPointerId(0)
                    service.showFlickPreview(rowIdx, v)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!active) return true
                    dir = FlickResolver.resolveDirection(
                        ev.rawX - downX, ev.rawY - downY, service.dp(24).toFloat(),
                    )
                    service.setFlickDirection(dir)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!active) return true
                    finishGesture()
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // 追跡中のポインタが離れた場合だけ ACTION_UP 相当として確定する
                    if (active && ev.getPointerId(ev.actionIndex) == pointerId) {
                        finishGesture()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // キャンセル時は確定せず、プレビュー消去だけを必ず行う
                    active = false
                    pointerId = -1
                    service.hideFlickPreview()
                    return true
                }
                else -> return true
            }
        }
    }

    // フリックプレビュー (十字配置) を描くオーバーレイ View (static 内部クラス: d8 対策)
    // PopupWindow は IME で不安定なため、入力ビュー内のオーバーレイで描く。
    private class FlickPreviewView(ctx: Context) : View(ctx) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var chars: Array<String> = emptyArray()
        private var dir = FlickResolver.DIR_CENTER
        private var cx = 0f
        private var cy = 0f
        private var unit = 0f

        init {
            textPaint.textAlign = Paint.Align.CENTER
        }

        fun showPreview(
            chars: Array<String>, cx: Float, cy: Float, unit: Float,
            bg: Int, hi: Int, text: Int,
        ) {
            this.chars = chars
            this.cx = cx
            this.cy = cy
            this.unit = unit
            this.dir = FlickResolver.DIR_CENTER
            bgPaint.color = bg
            hiPaint.color = hi
            textPaint.color = text
            textPaint.textSize = unit * 0.52f
            invalidate()
        }

        fun setDirection(dir: Int) {
            this.dir = dir
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (chars.size < 5 || unit <= 0) return
            val pad = unit * 0.62f
            canvas.drawRoundRect(
                cx - unit - pad, cy - unit - pad,
                cx + unit + pad, cy + unit + pad, unit * 0.3f, unit * 0.3f, bgPaint,
            )
            // DIR_CENTER, LEFT, UP, RIGHT, DOWN の順のオフセット
            val offs = arrayOf(
                floatArrayOf(0f, 0f), floatArrayOf(-unit, 0f), floatArrayOf(0f, -unit),
                floatArrayOf(unit, 0f), floatArrayOf(0f, unit),
            )
            for (d in 0 until 5) {
                val assigned = chars[d].isNotEmpty()
                if (!assigned) continue // 未割り当て方向 (や行の左右等) は何も描かない
                val x = cx + offs[d][0]
                val y = cy + offs[d][1]
                // ハイライト円は、文字が割り当てられた方向にだけ描く
                if (d == dir) canvas.drawCircle(x, y, unit * 0.55f, hiPaint)
                val baseline = y - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(chars[d], x, baseline, textPaint)
            }
        }
    }

    // ----- 1.3 ビュー -----
    private var rootFrame: FrameLayout? = null
    private var rootView: LinearLayout? = null
    private var barBox: LinearLayout? = null
    private var candidateRow: LinearLayout? = null
    private var keyboardBox: LinearLayout? = null
    private var flickPreview: FlickPreviewView? = null

    // ----- 1.4 学習ストア (SharedPreferences 永続化) -----
    class PrefsLearningStore(private val prefs: SharedPreferences) : LearningStore {
        private val freq: MutableMap<String, Int> = HashMap()

        init {
            reload()
        }

        // SharedPreferences から頻度を読み直す。設定画面で学習リセットされた
        // 場合など、外部から prefs が書き換わった内容を反映するために使う
        fun reload() {
            freq.clear()
            for ((key, value) in prefs.all) {
                if (key.startsWith(FREQ_PREFIX) && value is Int) {
                    freq[key.substring(FREQ_PREFIX.length)] = value
                }
            }
        }

        override fun getFrequency(reading: String, candidate: String): Int {
            return freq[key(reading, candidate)] ?: 0
        }

        override fun recordSelection(reading: String, candidate: String) {
            val k = key(reading, candidate)
            val v = getFrequency(reading, candidate) + 1
            freq[k] = v
            prefs.edit().putInt(FREQ_PREFIX + k, v).apply()
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
    override fun onCreate() {
        super.onCreate()
        val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val dict = Dictionary()
        dictionary = dict
        val store = PrefsLearningStore(p)
        learning = store
        engine = ConversionEngine(dict, store)
    }

    private fun loadPrefs() {
        val p = prefs ?: return
        night = "day" != p.getString(KEY_THEME, "night")
        vibrateOn = p.getBoolean(KEY_VIBRATE, true)
        flickMode = "toggle" != p.getString(KEY_KANA_STYLE, "flick")
        if (night) {
            colRoot = Color.rgb(0x0B, 0x12, 0x26)
            colBarBg = Color.rgb(0x0B, 0x12, 0x26)
            colKey = Color.rgb(0x1C, 0x2A, 0x4A)
            colSpecial = Color.rgb(0x27, 0x35, 0x5C)
            colText = Color.rgb(0xEA, 0xF0, 0xFF)
            colAccent = Color.rgb(0x3D, 0x5A, 0xFE)
        } else {
            colRoot = Color.rgb(0xE8, 0xEB, 0xF0)
            colBarBg = Color.rgb(0xE8, 0xEB, 0xF0)
            colKey = Color.rgb(0xFF, 0xFF, 0xFF)
            colSpecial = Color.rgb(0xD5, 0xDB, 0xE8)
            colText = Color.rgb(0x18, 0x20, 0x38)
            colAccent = Color.rgb(0x9D, 0xB8, 0xF0)
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
        learning?.reload()
        // ユーザー辞書は入力開始時に読み込む (設定画面での変更を反映するため)
        userDictionary = UserDictionary.decode(p.getString(KEY_USER_DICT, ""))
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
        loadPrefs()
        val rv = rootView
        if (rv != null) {
            rv.setBackgroundColor(colRoot)
            barBox?.setBackgroundColor(colBarBg)
            buildKeyboard()
            renderCandidates()
        }
    }

    // フルスクリーン抽出モード (横向き・一部アプリで入力ビューが画面全体に
    // 広がるモード) は常に無効化し、キーボードは通常の高さで表示する。
    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }

    override fun onCreateInputView(): View {
        loadPrefs()
        val frame = FrameLayout(this)
        rootFrame = frame
        val root = LinearLayout(this)
        rootView = root
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(colRoot)
        // 高さは内容に合わせる。LayoutParams 未指定 (FrameLayout 既定の
        // MATCH_PARENT) だと入力ビューがウィンドウ全域に広がる一因になる。
        frame.addView(
            root, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        // 候補バー (右端にカーソル ◀ ▶ を固定配置)。
        // 候補も未確定文字も無いときは renderCandidates() で GONE にして隠す。
        val bar = LinearLayout(this)
        barBox = bar
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(colBarBg)
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        val candRow = LinearLayout(this)
        candidateRow = candRow
        candRow.orientation = LinearLayout.HORIZONTAL
        candRow.gravity = Gravity.CENTER_VERTICAL
        scroll.addView(candRow)
        bar.addView(scroll, LinearLayout.LayoutParams(0, dp(42), 1f))
        bar.addView(makeBarCursorButton(true))
        bar.addView(makeBarCursorButton(false))
        root.addView(bar)

        // キーボード領域
        val kb = LinearLayout(this)
        keyboardBox = kb
        kb.orientation = LinearLayout.VERTICAL
        root.addView(kb)

        // フリックプレビュー用オーバーレイ (最前面、通常は非表示)。
        // 固定サイズ (190dp 四方) で追加し、表示時にキー中心へ位置決めする。
        // MATCH_PARENT だと VISIBLE 化した瞬間に FrameLayout がウィンドウ
        // 全域に広がり、キーボードが全画面化してしまう。
        val preview = FlickPreviewView(this)
        flickPreview = preview
        preview.visibility = View.GONE
        val previewSize = dp(FLICK_PREVIEW_SIZE_DP)
        frame.addView(preview, FrameLayout.LayoutParams(previewSize, previewSize))

        buildKeyboard()
        renderCandidates()
        return frame
    }

    // 候補バー右端のカーソルボタン
    private fun makeBarCursorButton(left: Boolean): TextView {
        val tv = TextView(this)
        tv.text = if (left) "◀" else "▶"
        tv.textSize = 13f
        tv.gravity = Gravity.CENTER
        tv.setTextColor(colText)
        tv.layoutParams = LinearLayout.LayoutParams(dp(34), dp(42))
        tv.setOnClickListener { buzz(); sendCursorKey(left) }
        return tv
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

    // ===== 3. キーボード構築 (コード生成UI) =====
    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun keyBg(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(6).toFloat()
        return g
    }

    private fun makeKey(label: String, weight: Float, special: Boolean, textSize: Int): TextView {
        return makeKey(label, weight, special, textSize, 48)
    }

    private fun makeKey(
        label: String, weight: Float, special: Boolean,
        textSize: Int, heightDp: Int,
    ): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.textSize = textSize.toFloat()
        tv.gravity = Gravity.CENTER
        tv.setTextColor(colText)
        tv.background = keyBg(if (special) colSpecial else colKey)
        val lp = LinearLayout.LayoutParams(0, dp(heightDp), weight)
        // 左右マージンは 1dp に詰め、キーの実効幅を広げる (打ち間違い対策)。
        // 上下は 2dp のままにして行間は維持する。
        lp.setMargins(dp(1), dp(2), dp(1), dp(2))
        tv.layoutParams = lp
        return tv
    }

    private fun makeRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        keyboardBox?.addView(row)
        return row
    }

    private fun buildKeyboard() {
        val kb = keyboardBox ?: return
        kb.removeAllViews()
        when (mode) {
            MODE_ROMAJI -> buildRomaji()
            MODE_KANA -> buildKana()
            MODE_EN -> buildEn()
            MODE_SYMBOL -> buildSymbol()
        }
    }

    // モードキーのラベルは「現在のモード」ではなく「タップで切り替わる先の
    // モード」を表す (押すと何になるかが一目で分かるようにするため)。
    // 切替順は addModeKey の (mode + 1) % 4: ローマ字→かな→英数→記号→ローマ字。
    private fun modeLabel(): String {
        return when (mode) {
            MODE_ROMAJI -> "あ"   // 次は かな入力
            MODE_KANA -> "ABC"    // 次は 英数入力
            MODE_EN -> "記号"      // 次は 記号入力
            else -> "あ"             // 記号の次は ローマ字入力
        }
    }

    private fun letterKey(row: LinearLayout, ch: String): TextView {
        val k = makeKey(ch, 1f, false, 17)
        k.setOnClickListener { buzz(); onLetter(ch) }
        row.addView(k)
        return k
    }

    private fun addDeleteKey(row: LinearLayout, weight: Float) {
        addDeleteKey(row, weight, 48)
    }

    private fun addDeleteKey(row: LinearLayout, weight: Float, heightDp: Int) {
        val del = makeKey("⌫", weight, true, 17, heightDp)
        del.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    buzz()
                    doDelete()
                    repeatHandler.postDelayed(repeater, 400)
                    true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_CANCEL -> {
                    // 指が離れた/キャンセルされたらリピートを必ず止める
                    repeatHandler.removeCallbacks(repeater)
                    true
                }
                else -> true
            }
        }
        row.addView(del)
    }

    private fun addModeKey(row: LinearLayout, weight: Float) {
        addModeKey(row, weight, 48)
    }

    private fun addModeKey(row: LinearLayout, weight: Float, heightDp: Int) {
        val k = makeKey(modeLabel(), weight, true, 14, heightDp)
        k.setOnClickListener { buzz(); switchMode((mode + 1) % 4) }
        row.addView(k)
    }

    private fun addSpaceKey(row: LinearLayout, weight: Float) {
        addSpaceKey(row, weight, 48)
    }

    private fun addSpaceKey(row: LinearLayout, weight: Float, heightDp: Int) {
        // ラベルは実際の動作と一致させる (英数=スペース、記号=空白、その他=変換)
        val label = if (mode == MODE_EN) "スペース" else if (mode == MODE_SYMBOL) "空白" else "変換"
        val k = makeKey(label, weight, true, 14, heightDp)
        k.setOnClickListener { buzz(); onSpace() }
        row.addView(k)
    }

    private fun addEnterKey(row: LinearLayout, weight: Float) {
        val k = makeKey("改行", weight, true, 14)
        k.setOnClickListener { buzz(); onEnter() }
        row.addView(k)
    }

    private fun addCursorKey(row: LinearLayout, left: Boolean) {
        val k = makeKey(if (left) "←" else "→", 0.8f, true, 15)
        k.setOnClickListener { buzz(); sendCursorKey(left) }
        row.addView(k)
    }

    // ラベル・幅・高さを指定できるカーソルキー (かな配置の左右列用)
    private fun addCursorKey(
        row: LinearLayout, left: Boolean, label: String,
        weight: Float, heightDp: Int,
    ) {
        val k = makeKey(label, weight, true, 15, heightDp)
        k.setOnClickListener { buzz(); sendCursorKey(left) }
        row.addView(k)
    }

    // ----- 3.1 ローマ字 QWERTY -----
    private fun buildRomaji() {
        val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        for (r in rows.indices) {
            val row = makeRow()
            if (r == 2) {
                val punct = makeKey("、", 0.8f, false, 16)
                punct.setOnClickListener { buzz(); commitPunct("、") }
                row.addView(punct)
            }
            val chars = rows[r].toCharArray()
            for (i in chars.indices) {
                val k = letterKey(row, chars[i].toString())
                if (r == 0) {
                    // 最上段は長押しで数字 1〜0 を入力する
                    val digit = "1234567890"[i].toString()
                    k.setOnLongClickListener {
                        buzz()
                        flushComposing()
                        commitDirect(digit)
                        true
                    }
                }
            }
            if (r == 2) {
                val punct = makeKey("。", 0.8f, false, 16)
                punct.setOnClickListener { buzz(); commitPunct("。") }
                row.addView(punct)
                addDeleteKey(row, 1.2f)
            }
        }
        val bottom = makeRow()
        addModeKey(bottom, 1.1f)
        addCursorKey(bottom, true)
        addSpaceKey(bottom, 2.6f)
        addCursorKey(bottom, false)
        addEnterKey(bottom, 1.1f)
    }

    // ----- 3.2 かな 12キー (フリック / トグル切替、Gboard 方式の5列配置) -----
    // 中央3列がかなキー (weight 1 / 高さ58dp / 22sp)、左右に細い
    // ユーティリティ列 (weight 0.75) を置く。全4行:
    // 行1: 記 あ か さ ⌫ / 行2: ◀ た な は ▶ /
    // 行3: 😊 ま や ら 変換 / 行4: モード ゛゜小 わ 、。？！ ⏎ (左右は weight 1.1)
    private fun buildKana() {
        // 行1: [記] あ か さ [⌫]
        val row1 = makeRow()
        val symKey = makeKey("記", 0.75f, true, 15, KANA_KEY_HEIGHT_DP)
        symKey.setOnClickListener { buzz(); symbolPage = 0; switchMode(MODE_SYMBOL) }
        row1.addView(symKey)
        addKanaKey(row1, 0)
        addKanaKey(row1, 1)
        addKanaKey(row1, 2)
        addDeleteKey(row1, 0.75f, KANA_KEY_HEIGHT_DP)

        // 行2: [◀] た な は [▶]
        val row2 = makeRow()
        addCursorKey(row2, true, "◀", 0.75f, KANA_KEY_HEIGHT_DP)
        addKanaKey(row2, 3)
        addKanaKey(row2, 4)
        addKanaKey(row2, 5)
        addCursorKey(row2, false, "▶", 0.75f, KANA_KEY_HEIGHT_DP)

        // 行3: [😊] ま や ら [変換]
        val row3 = makeRow()
        val emojiKey = makeKey("😊", 0.75f, true, 17, KANA_KEY_HEIGHT_DP)
        emojiKey.setOnClickListener { buzz(); symbolPage = 1; switchMode(MODE_SYMBOL) }
        row3.addView(emojiKey)
        addKanaKey(row3, 6)
        addKanaKey(row3, 7)
        addKanaKey(row3, 8)
        addSpaceKey(row3, 0.75f, KANA_KEY_HEIGHT_DP)

        // 行4: [モード] ゛゜小 わ [、。？！] [⏎] (左右キーは少し幅広)
        val row4 = makeRow()
        addModeKey(row4, 1.1f, KANA_KEY_HEIGHT_DP)
        val dakuten = makeKey("゛゜小", 1f, true, 15, KANA_KEY_HEIGHT_DP)
        dakuten.setOnClickListener { buzz(); onDakutenKey() }
        row4.addView(dakuten)
        addKanaKey(row4, 9) // わ
        val punctCycle = makeKey("、。？！", 1f, true, 13, KANA_KEY_HEIGHT_DP)
        punctCycle.setOnClickListener { buzz(); onPunctCycleKey() }
        row4.addView(punctCycle)
        val enter = makeKey("⏎", 1.1f, true, 20, KANA_KEY_HEIGHT_DP)
        enter.setOnClickListener { buzz(); onEnter() }
        row4.addView(enter)
    }

    // かなキー (Gboard 方式配置: 高さ58dp / 22sp)。
    // フリック/トグルのどちらの方式でも同じ配置・同じ付け方にする。
    private fun addKanaKey(row: LinearLayout, rowIdx: Int) {
        val k = makeKey(KanaUtils.TOGGLE_KEY_LABELS[rowIdx], 1f, false, 22, KANA_KEY_HEIGHT_DP)
        if (flickMode) {
            k.setOnTouchListener(KanaFlickTouchListener(this, rowIdx))
        } else {
            k.setOnClickListener { buzz(); onKanaKey(rowIdx) }
        }
        row.addView(k)
    }

    // ----- 3.3 英数 QWERTY -----
    private fun buildEn() {
        val nums = makeRow()
        for (c in "1234567890".toCharArray()) letterKey(nums, c.toString())
        val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        for (r in rows.indices) {
            val row = makeRow()
            if (r == 2) {
                val shift = makeKey(if (caps) "⇪" else "⇧", 1.2f, true, 16)
                shift.setOnClickListener { buzz(); caps = !caps; buildKeyboard() }
                row.addView(shift)
            }
            for (c in rows[r].toCharArray()) {
                letterKey(row, (if (caps) c.uppercaseChar() else c).toString())
            }
            if (r == 2) addDeleteKey(row, 1.2f)
        }
        val bottom = makeRow()
        addModeKey(bottom, 1.1f)
        val comma = makeKey(",", 0.9f, false, 17)
        comma.setOnClickListener { buzz(); commitDirect(",") }
        bottom.addView(comma)
        addSpaceKey(bottom, 2.8f)
        val period = makeKey(".", 0.9f, false, 17)
        period.setOnClickListener { buzz(); commitDirect(".") }
        bottom.addView(period)
        addEnterKey(bottom, 1.1f)
    }

    // ----- 3.4 記号グリッド -----
    private fun buildSymbol() {
        // 記号 / 絵文字 の切替タブ
        val tabs = makeRow()
        val symTab = makeKey("記号", 1f, symbolPage == 0, 14)
        symTab.setOnClickListener { buzz(); symbolPage = 0; buildKeyboard() }
        tabs.addView(symTab)
        val emojiTab = makeKey("絵文字", 1f, symbolPage == 1, 14)
        emojiTab.setOnClickListener { buzz(); symbolPage = 1; buildKeyboard() }
        tabs.addView(emojiTab)

        val emoji = symbolPage == 1
        val items = if (emoji) EMOJIS else SYMBOLS
        val cols = if (emoji) 8 else 10
        var i = 0
        while (i < items.size) {
            val row = makeRow()
            val end = minOf(i + cols, items.size)
            for (j in i until end) {
                val s = items[j]
                val k = makeKey(s, 1f, false, if (emoji) 17 else 15)
                k.setOnClickListener { buzz(); commitPunct(s) }
                row.addView(k)
            }
            // 最終行が列数に満たない場合は透明スペーサーで埋め、
            // キーの幅・高さが他行と揃うようにする
            for (j in end until i + cols) {
                val spacer = View(this)
                val lp = LinearLayout.LayoutParams(0, dp(48), 1f)
                lp.setMargins(dp(1), dp(2), dp(1), dp(2))
                spacer.layoutParams = lp
                row.addView(spacer)
            }
            i += cols
        }
        val bottom = makeRow()
        addModeKey(bottom, 1.1f)
        addSpaceKey(bottom, 2.8f)
        addDeleteKey(bottom, 1.2f)
        addEnterKey(bottom, 1.1f)
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

    private fun showFlickPreview(rowIdx: Int, key: View) {
        val preview = flickPreview ?: return
        val frame = rootFrame ?: return
        val size = dp(FLICK_PREVIEW_SIZE_DP)
        val off = offsetInRoot(key)
        val keyCx = off[0] + key.width / 2
        val keyCy = off[1] + key.height / 2
        // プレビュー (固定サイズ) の中心がキー中心に重なるよう位置決めする。
        // 画面端のキーではみ出す場合は、左端は 0 以上かつルート幅の内側に、
        // 上端は 0 以上にクランプする。
        var left = keyCx - size / 2
        var top = keyCy - size / 2
        val rootW = frame.width
        if (left < 0) left = 0
        if (rootW > 0 && left + size > rootW) left = max(0, rootW - size)
        if (top < 0) top = 0
        val lp = preview.layoutParams as FrameLayout.LayoutParams
        lp.width = size
        lp.height = size
        lp.leftMargin = left
        lp.topMargin = top
        preview.layoutParams = lp
        val chars = Array(5) { "" }
        for (d in 0 until 5) {
            chars[d] = if (FlickResolver.isAssigned(rowIdx, d)) {
                FlickResolver.charAt(rowIdx, d).toString()
            } else {
                ""
            }
        }
        // 描画座標はプレビュー View 内のローカル座標で渡す
        // (十字の中心 = ビュー内でのキー中心の相対位置)。
        preview.showPreview(
            chars, (keyCx - left).toFloat(), (keyCy - top).toFloat(), dp(46).toFloat(),
            colSpecial, colAccent, colText,
        )
        preview.visibility = View.VISIBLE
    }

    private fun setFlickDirection(dir: Int) {
        flickPreview?.setDirection(dir)
    }

    private fun hideFlickPreview() {
        flickPreview?.visibility = View.GONE
    }

    // ルート FrameLayout 基準のビュー左上座標を求める
    private fun offsetInRoot(v: View): IntArray {
        var x = 0
        var y = 0
        var cur: View? = v
        while (cur != null && cur !== rootFrame) {
            x += cur.left
            y += cur.top
            val parent = cur.parent
            cur = parent as? View
        }
        return intArrayOf(x, y)
    }

    private fun onSpace() {
        if (mode == MODE_EN || mode == MODE_SYMBOL) {
            commitDirect(" ")
            return
        }
        if (currentDisplay.isEmpty()) {
            commitDirect("　")
            return
        }
        if (candidates.isNotEmpty()) {
            highlight = (highlight + 1) % candidates.size
            renderCandidates()
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
            renderCandidates()
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
        buildKeyboard()
        renderCandidates()
    }

    // ===== 5. composing / 候補 =====
    private fun clearComposingState() {
        romajiBuf.setLength(0)
        kanaBuf.setLength(0)
        currentReading = ""
        currentDisplay = ""
        candidates = ArrayList()
        candidateReadings = ArrayList()
        highlight = -1
        lastKanaRow = -1
    }

    // composing を確定してから直接コミットする (句読点・記号用)
    private fun commitPunct(s: String) {
        flushComposing()
        commitDirect(s)
    }

    private fun flushComposing() {
        if (currentDisplay.isEmpty()) return
        val ic = currentInputConnection ?: return
        val text = if (mode == MODE_ROMAJI) {
            RomajiConverter.convertFully(romajiBuf.toString())
        } else {
            kanaBuf.toString()
        }
        ic.commitText(text, 1)
        clearComposingState()
        renderCandidates()
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
            currentReading = r.kana + (if (r.pending == "n") "ん" else "")
        } else if (mode == MODE_KANA) {
            currentDisplay = kanaBuf.toString()
            currentReading = currentDisplay
        } else {
            return
        }
        if (currentDisplay.isEmpty()) {
            ic.finishComposingText()
            candidates = ArrayList()
            candidateReadings = ArrayList()
        } else {
            ic.setComposingText(currentDisplay, 1)
            refreshCandidates()
        }
        renderCandidates()
    }

    private fun refreshCandidates() {
        candidates = ArrayList()
        candidateReadings = ArrayList()
        if (currentReading.isEmpty()) return
        val eng = engine ?: return
        val raw = if (mode == MODE_ROMAJI) romajiBuf.toString() else null
        for (c in eng.candidates(currentReading, raw)) {
            candidates.add(c)
            candidateReadings.add(currentReading)
        }
        // 予測候補を後ろに足す (重複排除)
        for (e in eng.predictEntries(currentReading, 8)) {
            if (!candidates.contains(e[1])) {
                candidates.add(e[1])
                candidateReadings.add(e[0])
            }
        }
        while (candidates.size > 24) {
            candidates.removeAt(candidates.size - 1)
            candidateReadings.removeAt(candidateReadings.size - 1)
        }
    }

    private fun renderCandidates() {
        val candRow = candidateRow ?: return
        // 候補が無く未確定文字も無いときは候補バーごと隠し、キーボードを
        // その分 (42dp) 低くする。バー右端のカーソル ◀ ▶ も一緒に消える。
        // 候補か未確定文字が現れたら再表示する (状態変化はすべて
        // updateComposing / 本メソッド経由でここに集まる)。
        val bar = barBox
        if (bar != null) {
            val showBar = candidates.isNotEmpty() || currentDisplay.isNotEmpty()
            bar.visibility = if (showBar) View.VISIBLE else View.GONE
        }
        candRow.removeAllViews()
        for (i in candidates.indices) {
            val idx = i
            val tv = TextView(this)
            tv.text = candidates[i]
            tv.textSize = 16f
            tv.gravity = Gravity.CENTER
            tv.setTextColor(colText)
            tv.setPadding(dp(12), dp(4), dp(12), dp(4))
            if (i == highlight) tv.background = keyBg(colAccent)
            tv.setOnClickListener { buzz(); commitCandidate(idx) }
            candRow.addView(tv)
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
        renderCandidates()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // ビューが閉じたら削除リピートとフリックプレビューを残さない
        repeatHandler.removeCallbacks(repeater)
        hideFlickPreview()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        repeatHandler.removeCallbacks(repeater)
        hideFlickPreview()
        // 入力先に composing が残らないよう、先に確定してから内部状態を消す
        val ic = currentInputConnection
        ic?.finishComposingText()
        clearComposingState()
    }

    override fun onDestroy() {
        // Handler のコールバックを確実に解除してから破棄する
        repeatHandler.removeCallbacks(repeater)
        hideFlickPreview()
        super.onDestroy()
    }

    companion object {
        // ----- 1.1 定数・設定 -----
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

        // フリックプレビュー (オーバーレイ) の一辺のサイズ。MATCH_PARENT にすると
        // VISIBLE 化した瞬間にキーボード全体が全画面化するため固定サイズにする。
        private const val FLICK_PREVIEW_SIZE_DP = 190

        // かなキーボードのキー高さ (全4行共通)。行ごとに高さが違うと
        // 不揃いで打ちにくく見えるため、buildKana 内のキーは必ずこの定数を
        // 使うこと (行4のキーも含め全行 58dp で統一する)。
        private const val KANA_KEY_HEIGHT_DP = 58

        // 句読点循環キー (、。？！) の循環順。
        private val PUNCT_CYCLE: Array<String> = arrayOf("、", "。", "？", "！")

        // 記号グリッド: 全72件 (10列 × 8行、最終行は2件)。buildSymbol の
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
