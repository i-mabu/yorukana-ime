package jp.yorukana.ime;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// ===== 1. よるかな IME 本体 (InputMethodService) =====
// 入力方式4種: ローマ字QWERTY / かな12キートグル / 英数QWERTY / 記号グリッド。
// composing は setComposingText、確定は commitText を使う。ネットワーク権限なし。
public class YoruKanaService extends InputMethodService {

    // ----- 1.1 定数・設定 -----
    static final String PREFS = "yorukana_prefs";
    static final String KEY_THEME = "theme";         // "night" / "day"
    static final String KEY_VIBRATE = "vibrate";     // boolean
    static final String KEY_DEFAULT_MODE = "default_mode"; // "romaji"/"kana"/"en"
    static final String FREQ_PREFIX = "freq:";

    static final int MODE_ROMAJI = 0;
    static final int MODE_KANA = 1;
    static final int MODE_EN = 2;
    static final int MODE_SYMBOL = 3;

    // テーマ色
    private int colRoot, colKey, colSpecial, colText, colAccent, colBarBg;

    // ----- 1.2 状態 -----
    private Dictionary dictionary;
    private PrefsLearningStore learning;
    private ConversionEngine engine;
    private SharedPreferences prefs;
    private boolean vibrateOn = true;
    private boolean night = true;

    private int mode = MODE_ROMAJI;
    private final StringBuilder romajiBuf = new StringBuilder();
    private final StringBuilder kanaBuf = new StringBuilder();
    private String currentReading = "";
    private String currentDisplay = "";
    private List<String> candidates = new ArrayList<>();
    private List<String> candidateReadings = new ArrayList<>();
    private int highlight = -1;
    private boolean caps = false;

    // かなトグル打ちの追跡
    private int lastKanaRow = -1;
    private long lastKanaTime = 0;

    // 削除キー長押しリピート
    // (static 内部クラスにしているのは、この手動ビルドの d8 が
    //  非 static 内部クラスのクラスファイルで NPE を起こすため)
    private final Handler repeatHandler = new Handler(Looper.getMainLooper());
    private final Runnable repeater = new DeleteRepeater(this);

    private static final class DeleteRepeater implements Runnable {
        private final YoruKanaService service;
        DeleteRepeater(YoruKanaService service) { this.service = service; }
        @Override public void run() {
            service.doDelete();
            service.repeatHandler.postDelayed(this, 50);
        }
    }

    // ----- 1.3 ビュー -----
    private LinearLayout rootView;
    private LinearLayout candidateRow;
    private LinearLayout keyboardBox;

    // ----- 1.4 学習ストア (SharedPreferences 永続化) -----
    static final class PrefsLearningStore implements LearningStore {
        private final SharedPreferences prefs;
        private final Map<String, Integer> freq = new HashMap<>();

        PrefsLearningStore(SharedPreferences prefs) {
            this.prefs = prefs;
            for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
                if (e.getKey().startsWith(FREQ_PREFIX) && e.getValue() instanceof Integer) {
                    freq.put(e.getKey().substring(FREQ_PREFIX.length()), (Integer) e.getValue());
                }
            }
        }

        private static String key(String reading, String candidate) {
            return reading + "|" + candidate;
        }

        @Override public int getFrequency(String reading, String candidate) {
            Integer v = freq.get(key(reading, candidate));
            return v != null ? v : 0;
        }

        @Override public void recordSelection(String reading, String candidate) {
            String k = key(reading, candidate);
            int v = getFrequency(reading, candidate) + 1;
            freq.put(k, v);
            prefs.edit().putInt(FREQ_PREFIX + k, v).apply();
        }

        @Override public void clear() {
            freq.clear();
            SharedPreferences.Editor ed = prefs.edit();
            for (String k : prefs.getAll().keySet()) {
                if (k.startsWith(FREQ_PREFIX)) ed.remove(k);
            }
            ed.apply();
        }
    }

    // ===== 2. ライフサイクル =====
    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        dictionary = new Dictionary();
        learning = new PrefsLearningStore(prefs);
        engine = new ConversionEngine(dictionary, learning);
    }

    private void loadPrefs() {
        night = !"day".equals(prefs.getString(KEY_THEME, "night"));
        vibrateOn = prefs.getBoolean(KEY_VIBRATE, true);
        if (night) {
            colRoot = Color.rgb(0x0B, 0x12, 0x26);
            colBarBg = Color.rgb(0x0B, 0x12, 0x26);
            colKey = Color.rgb(0x1C, 0x2A, 0x4A);
            colSpecial = Color.rgb(0x27, 0x35, 0x5C);
            colText = Color.rgb(0xEA, 0xF0, 0xFF);
            colAccent = Color.rgb(0x3D, 0x5A, 0xFE);
        } else {
            colRoot = Color.rgb(0xE8, 0xEB, 0xF0);
            colBarBg = Color.rgb(0xE8, 0xEB, 0xF0);
            colKey = Color.rgb(0xFF, 0xFF, 0xFF);
            colSpecial = Color.rgb(0xD5, 0xDB, 0xE8);
            colText = Color.rgb(0x18, 0x20, 0x38);
            colAccent = Color.rgb(0x9D, 0xB8, 0xF0);
        }
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        loadPrefs();
        clearComposingState();
        String def = prefs.getString(KEY_DEFAULT_MODE, "romaji");
        mode = "kana".equals(def) ? MODE_KANA : "en".equals(def) ? MODE_EN : MODE_ROMAJI;
        // パスワード欄では英数モードを既定にする
        int variation = attribute.inputType & InputType.TYPE_MASK_VARIATION;
        if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
            mode = MODE_EN;
        }
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        loadPrefs();
        if (rootView != null) {
            rootView.setBackgroundColor(colRoot);
            buildKeyboard();
            renderCandidates();
        }
    }

    @Override
    public View onCreateInputView() {
        loadPrefs();
        rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setBackgroundColor(colRoot);

        // 候補バー
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setBackgroundColor(colBarBg);
        candidateRow = new LinearLayout(this);
        candidateRow.setOrientation(LinearLayout.HORIZONTAL);
        candidateRow.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(candidateRow);
        rootView.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        // キーボード領域
        keyboardBox = new LinearLayout(this);
        keyboardBox.setOrientation(LinearLayout.VERTICAL);
        rootView.addView(keyboardBox);
        buildKeyboard();
        renderCandidates();
        return rootView;
    }

    // ===== 3. キーボード構築 (コード生成UI) =====
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable keyBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(6));
        return g;
    }

    private TextView makeKey(String label, float weight, boolean special, int textSize) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(textSize);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(colText);
        tv.setBackground(keyBg(special ? colSpecial : colKey));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, dp(48), weight);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        tv.setLayoutParams(lp);
        return tv;
    }

    private LinearLayout makeRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        keyboardBox.addView(row);
        return row;
    }

    private void buildKeyboard() {
        if (keyboardBox == null) return;
        keyboardBox.removeAllViews();
        switch (mode) {
            case MODE_ROMAJI: buildRomaji(); break;
            case MODE_KANA: buildKana(); break;
            case MODE_EN: buildEn(); break;
            case MODE_SYMBOL: buildSymbol(); break;
        }
    }

    private String modeLabel() {
        switch (mode) {
            case MODE_ROMAJI: return "あ";
            case MODE_KANA: return "かな";
            case MODE_EN: return "A";
            default: return "記号";
        }
    }

    private TextView letterKey(LinearLayout row, final String ch) {
        TextView k = makeKey(ch, 1f, false, 17);
        k.setOnClickListener(v -> { buzz(); onLetter(ch); });
        row.addView(k);
        return k;
    }

    private void addDeleteKey(LinearLayout row, float weight) {
        TextView del = makeKey("⌫", weight, true, 17);
        del.setOnTouchListener((v, ev) -> {
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    buzz();
                    doDelete();
                    repeatHandler.postDelayed(repeater, 400);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    repeatHandler.removeCallbacks(repeater);
                    return true;
                default:
                    return true;
            }
        });
        row.addView(del);
    }

    private void addModeKey(LinearLayout row, float weight) {
        TextView k = makeKey(modeLabel(), weight, true, 14);
        k.setOnClickListener(v -> { buzz(); switchMode((mode + 1) % 4); });
        row.addView(k);
    }

    private void addSpaceKey(LinearLayout row, float weight) {
        TextView k = makeKey(mode == MODE_EN ? "スペース" : "変換", weight, true, 14);
        k.setOnClickListener(v -> { buzz(); onSpace(); });
        row.addView(k);
    }

    private void addEnterKey(LinearLayout row, float weight) {
        TextView k = makeKey("改行", weight, true, 14);
        k.setOnClickListener(v -> { buzz(); onEnter(); });
        row.addView(k);
    }

    private void addCursorKey(LinearLayout row, final boolean left) {
        TextView k = makeKey(left ? "←" : "→", 0.8f, true, 15);
        k.setOnClickListener(v -> {
            buzz();
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
                    left ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,
                    left ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT));
            }
        });
        row.addView(k);
    }

    // ----- 3.1 ローマ字 QWERTY -----
    private void buildRomaji() {
        String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = makeRow();
            if (r == 2) {
                TextView punct = makeKey("、", 0.8f, false, 16);
                punct.setOnClickListener(v -> { buzz(); commitPunct("、"); });
                row.addView(punct);
            }
            for (char c : rows[r].toCharArray()) letterKey(row, String.valueOf(c));
            if (r == 2) {
                TextView punct = makeKey("。", 0.8f, false, 16);
                punct.setOnClickListener(v -> { buzz(); commitPunct("。"); });
                row.addView(punct);
                addDeleteKey(row, 1.2f);
            }
        }
        LinearLayout bottom = makeRow();
        addModeKey(bottom, 1.1f);
        addCursorKey(bottom, true);
        addSpaceKey(bottom, 2.6f);
        addCursorKey(bottom, false);
        addEnterKey(bottom, 1.1f);
    }

    // ----- 3.2 かな 12キー -----
    private void buildKana() {
        for (int r = 0; r < 2; r++) {
            LinearLayout row = makeRow();
            for (int i = 0; i < 5; i++) {
                final int rowIdx = r * 5 + i;
                TextView k = makeKey(KanaUtils.TOGGLE_KEY_LABELS[rowIdx], 1f, false, 19);
                k.setOnClickListener(v -> { buzz(); onKanaKey(rowIdx); });
                row.addView(k);
            }
        }
        LinearLayout func = makeRow();
        TextView dakuten = makeKey("゛゜小", 1.2f, true, 14);
        dakuten.setOnClickListener(v -> { buzz(); onDakutenKey(); });
        func.addView(dakuten);
        TextView ten = makeKey("、", 1f, false, 17);
        ten.setOnClickListener(v -> { buzz(); commitPunct("、"); });
        func.addView(ten);
        TextView maru = makeKey("。", 1f, false, 17);
        maru.setOnClickListener(v -> { buzz(); commitPunct("。"); });
        func.addView(maru);
        TextView hatena = makeKey("？", 1f, false, 17);
        hatena.setOnClickListener(v -> { buzz(); commitPunct("？"); });
        func.addView(hatena);
        TextView bikkuri = makeKey("！", 1f, false, 17);
        bikkuri.setOnClickListener(v -> { buzz(); commitPunct("！"); });
        func.addView(bikkuri);
        addDeleteKey(func, 1.2f);

        LinearLayout bottom = makeRow();
        addModeKey(bottom, 1.1f);
        addCursorKey(bottom, true);
        addSpaceKey(bottom, 2.6f);
        addCursorKey(bottom, false);
        addEnterKey(bottom, 1.1f);
    }

    // ----- 3.3 英数 QWERTY -----
    private void buildEn() {
        LinearLayout nums = makeRow();
        for (char c : "1234567890".toCharArray()) letterKey(nums, String.valueOf(c));
        String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = makeRow();
            if (r == 2) {
                TextView shift = makeKey(caps ? "⇪" : "⇧", 1.2f, true, 16);
                shift.setOnClickListener(v -> { buzz(); caps = !caps; buildKeyboard(); });
                row.addView(shift);
            }
            for (char c : rows[r].toCharArray()) {
                letterKey(row, caps ? String.valueOf(Character.toUpperCase(c)) : String.valueOf(c));
            }
            if (r == 2) addDeleteKey(row, 1.2f);
        }
        LinearLayout bottom = makeRow();
        addModeKey(bottom, 1.1f);
        TextView comma = makeKey(",", 0.9f, false, 17);
        comma.setOnClickListener(v -> { buzz(); commitDirect(","); });
        bottom.addView(comma);
        addSpaceKey(bottom, 2.8f);
        TextView period = makeKey(".", 0.9f, false, 17);
        period.setOnClickListener(v -> { buzz(); commitDirect("."); });
        bottom.addView(period);
        addEnterKey(bottom, 1.1f);
    }

    // ----- 3.4 記号グリッド -----
    private static final String[] SYMBOLS = {
        "、", "。", "！", "？", "・", "：", "；", "（", "）", "「",
        "」", "『", "』", "［", "］", "｛", "｝", "＠", "＃", "￥",
        "％", "＆", "＊", "＋", "－", "＝", "＜", "＞", "＿", "〜",
        "…", "＾", "｜", "／", "＼", "＂", "＇", "〈", "〉", "《",
        "》", "☆", "★", "○", "●", "◎", "◇", "◆", "□", "■",
        "△", "▲", "▽", "▼", "※", "♪", "♥", "→", "←", "↑",
        "↓", "°", "１", "２", "３", "４", "５", "６", "７", "８",
    };

    private void buildSymbol() {
        int cols = 10;
        for (int i = 0; i < SYMBOLS.length; i += cols) {
            LinearLayout row = makeRow();
            for (int j = i; j < Math.min(i + cols, SYMBOLS.length); j++) {
                final String s = SYMBOLS[j];
                TextView k = makeKey(s, 1f, false, 15);
                k.setOnClickListener(v -> { buzz(); commitPunct(s); });
                row.addView(k);
            }
        }
        LinearLayout bottom = makeRow();
        addModeKey(bottom, 1.1f);
        addSpaceKey(bottom, 2.8f);
        addDeleteKey(bottom, 1.2f);
        addEnterKey(bottom, 1.1f);
    }

    // ===== 4. 入力処理 =====
    private void buzz() {
        if (!vibrateOn) return;
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            v.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    private void onLetter(String ch) {
        if (mode == MODE_EN) {
            commitDirect(ch);
            return;
        }
        if (mode == MODE_ROMAJI) {
            if (ch.length() == 1 && ch.charAt(0) >= '0' && ch.charAt(0) <= '9') {
                commitDirect(ch);
                return;
            }
            romajiBuf.append(ch);
            highlight = -1;
            updateComposing();
        }
    }

    private void onKanaKey(int rowIdx) {
        long now = System.currentTimeMillis();
        boolean cycled = false;
        if (rowIdx == lastKanaRow && now - lastKanaTime < 1000 && kanaBuf.length() > 0) {
            char last = kanaBuf.charAt(kanaBuf.length() - 1);
            if (KanaUtils.rowIndexOf(last) == rowIdx) {
                kanaBuf.setCharAt(kanaBuf.length() - 1, KanaUtils.nextInRow(rowIdx, last));
                cycled = true;
            }
        }
        if (!cycled) {
            kanaBuf.append(KanaUtils.TOGGLE_ROWS[rowIdx].charAt(0));
        }
        lastKanaRow = rowIdx;
        lastKanaTime = now;
        highlight = -1;
        updateComposing();
    }

    private void onDakutenKey() {
        if (kanaBuf.length() == 0) return;
        char last = kanaBuf.charAt(kanaBuf.length() - 1);
        char next = KanaUtils.toggleDakuten(last);
        if (next != last) {
            kanaBuf.setCharAt(kanaBuf.length() - 1, next);
            lastKanaRow = -1;
            highlight = -1;
            updateComposing();
        }
    }

    private void onSpace() {
        if (mode == MODE_EN || mode == MODE_SYMBOL) {
            commitDirect(" ");
            return;
        }
        if (currentDisplay.isEmpty()) {
            commitDirect("　");
            return;
        }
        if (!candidates.isEmpty()) {
            highlight = (highlight + 1) % candidates.size();
            renderCandidates();
        }
    }

    private void onEnter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (highlight >= 0 && highlight < candidates.size()) {
            commitCandidate(highlight);
            return;
        }
        if (!currentDisplay.isEmpty()) {
            // ハイライト無しの Enter は composing をそのまま確定する
            String text = (mode == MODE_ROMAJI)
                ? RomajiConverter.convertFully(romajiBuf.toString())
                : kanaBuf.toString();
            ic.commitText(text, 1);
            clearComposingState();
            renderCandidates();
            return;
        }
        EditorInfo ei = getCurrentInputEditorInfo();
        int action = ei != null ? (ei.imeOptions & EditorInfo.IME_MASK_ACTION) : EditorInfo.IME_ACTION_NONE;
        if (ei != null && action != EditorInfo.IME_ACTION_NONE
                && (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) {
            ic.performEditorAction(action);
        } else {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        }
    }

    private void doDelete() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (mode == MODE_ROMAJI && romajiBuf.length() > 0) {
            romajiBuf.deleteCharAt(romajiBuf.length() - 1);
            highlight = -1;
            updateComposing();
        } else if (mode == MODE_KANA && kanaBuf.length() > 0) {
            kanaBuf.deleteCharAt(kanaBuf.length() - 1);
            lastKanaRow = -1;
            highlight = -1;
            updateComposing();
        } else {
            ic.deleteSurroundingText(1, 0);
        }
    }

    private void switchMode(int newMode) {
        flushComposing();
        mode = newMode;
        buildKeyboard();
        renderCandidates();
    }

    // ===== 5. composing / 候補 =====
    private void clearComposingState() {
        romajiBuf.setLength(0);
        kanaBuf.setLength(0);
        currentReading = "";
        currentDisplay = "";
        candidates = new ArrayList<>();
        candidateReadings = new ArrayList<>();
        highlight = -1;
        lastKanaRow = -1;
    }

    // composing を確定してから直接コミットする (句読点・記号用)
    private void commitPunct(String s) {
        flushComposing();
        commitDirect(s);
    }

    private void flushComposing() {
        if (currentDisplay.isEmpty()) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String text = (mode == MODE_ROMAJI)
            ? RomajiConverter.convertFully(romajiBuf.toString())
            : kanaBuf.toString();
        ic.commitText(text, 1);
        clearComposingState();
        renderCandidates();
    }

    private void commitDirect(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(s, 1);
    }

    private void updateComposing() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (mode == MODE_ROMAJI) {
            RomajiConverter.Result r = RomajiConverter.convert(romajiBuf.toString());
            currentDisplay = r.kana + r.pending;
            currentReading = r.kana + (r.pending.equals("n") ? "ん" : "");
        } else if (mode == MODE_KANA) {
            currentDisplay = kanaBuf.toString();
            currentReading = currentDisplay;
        } else {
            return;
        }
        if (currentDisplay.isEmpty()) {
            ic.finishComposingText();
            candidates = new ArrayList<>();
            candidateReadings = new ArrayList<>();
        } else {
            ic.setComposingText(currentDisplay, 1);
            refreshCandidates();
        }
        renderCandidates();
    }

    private void refreshCandidates() {
        candidates = new ArrayList<>();
        candidateReadings = new ArrayList<>();
        if (currentReading.isEmpty()) return;
        String raw = (mode == MODE_ROMAJI) ? romajiBuf.toString() : null;
        for (String c : engine.candidates(currentReading, raw)) {
            candidates.add(c);
            candidateReadings.add(currentReading);
        }
        // 予測候補を後ろに足す (重複排除)
        for (String[] e : engine.predictEntries(currentReading, 8)) {
            if (!candidates.contains(e[1])) {
                candidates.add(e[1]);
                candidateReadings.add(e[0]);
            }
        }
        while (candidates.size() > 24) {
            candidates.remove(candidates.size() - 1);
            candidateReadings.remove(candidateReadings.size() - 1);
        }
    }

    private void renderCandidates() {
        if (candidateRow == null) return;
        candidateRow.removeAllViews();
        for (int i = 0; i < candidates.size(); i++) {
            final int idx = i;
            TextView tv = new TextView(this);
            tv.setText(candidates.get(i));
            tv.setTextSize(16);
            tv.setGravity(Gravity.CENTER);
            tv.setTextColor(colText);
            tv.setPadding(dp(12), dp(4), dp(12), dp(4));
            if (i == highlight) tv.setBackground(keyBg(colAccent));
            tv.setOnClickListener(v -> { buzz(); commitCandidate(idx); });
            candidateRow.addView(tv);
        }
    }

    private void commitCandidate(int idx) {
        if (idx < 0 || idx >= candidates.size()) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String text = candidates.get(idx);
        String reading = idx < candidateReadings.size() ? candidateReadings.get(idx) : currentReading;
        // ひらがな原文・ローマ字原文そのままの確定は学習しない
        if (!text.equals(reading) && !(mode == MODE_ROMAJI && text.equals(romajiBuf.toString()))) {
            learning.recordSelection(reading, text);
        }
        ic.commitText(text, 1);
        clearComposingState();
        renderCandidates();
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        repeatHandler.removeCallbacks(repeater);
        clearComposingState();
    }
}
