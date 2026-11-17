package jp.yorukana.ime;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

// ===== 1. 設定画面 =====
// IME有効化ガイド、テーマ (夜/昼)、バイブ、既定入力方式、学習リセット。
// 設定は SharedPreferences で Service と共有する。
public class SettingsActivity extends Activity {

    private SharedPreferences prefs;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView sectionTitle(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(17);
        tv.setPadding(0, dp(20), 0, dp(6));
        root.addView(tv);
        return tv;
    }

    private TextView bodyText(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        root.addView(tv);
        return tv;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(YoruKanaService.PREFS, Context.MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("よるかな IME 設定");
        title.setTextSize(22);
        root.addView(title);

        // ----- 有効化ガイド -----
        sectionTitle(root, "有効化の手順");
        bodyText(root, "1. 下のボタンから「入力方法の設定」を開き、「よるかな IME」をオンにします。\n"
            + "2. 文字入力画面でキーボード切替 (地球儀キー等) から「よるかな IME」を選びます。");

        Button openSettings = new Button(this);
        openSettings.setText("入力方法の設定を開く");
        openSettings.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        root.addView(openSettings);

        Button pickIme = new Button(this);
        pickIme.setText("キーボードを選択する");
        pickIme.setOnClickListener(v -> {
            InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });
        root.addView(pickIme);

        // ----- テーマ -----
        sectionTitle(root, "テーマ");
        RadioGroup themeGroup = new RadioGroup(this);
        RadioButton nightBtn = new RadioButton(this);
        nightBtn.setId(1);
        nightBtn.setText("夜テーマ (既定)");
        RadioButton dayBtn = new RadioButton(this);
        dayBtn.setId(2);
        dayBtn.setText("昼テーマ");
        themeGroup.addView(nightBtn);
        themeGroup.addView(dayBtn);
        boolean night = !"day".equals(prefs.getString(YoruKanaService.KEY_THEME, "night"));
        (night ? nightBtn : dayBtn).setChecked(true);
        themeGroup.setOnCheckedChangeListener((g, id) ->
            prefs.edit().putString(YoruKanaService.KEY_THEME,
                id == nightBtn.getId() ? "night" : "day").apply());
        root.addView(themeGroup);

        // ----- バイブ -----
        sectionTitle(root, "キー操作");
        CheckBox vibrateBox = new CheckBox(this);
        vibrateBox.setText("キータップ時にバイブレーション");
        vibrateBox.setChecked(prefs.getBoolean(YoruKanaService.KEY_VIBRATE, true));
        vibrateBox.setOnCheckedChangeListener((b, checked) ->
            prefs.edit().putBoolean(YoruKanaService.KEY_VIBRATE, checked).apply());
        root.addView(vibrateBox);

        // ----- 既定の入力方式 -----
        sectionTitle(root, "既定の入力方式");
        RadioGroup modeGroup = new RadioGroup(this);
        RadioButton romajiBtn = new RadioButton(this);
        romajiBtn.setId(11);
        romajiBtn.setText("ローマ字入力 (QWERTY)");
        RadioButton kanaBtn = new RadioButton(this);
        kanaBtn.setId(12);
        kanaBtn.setText("かな入力 (12キー)");
        RadioButton enBtn = new RadioButton(this);
        enBtn.setId(13);
        enBtn.setText("英数入力 (QWERTY)");
        modeGroup.addView(romajiBtn);
        modeGroup.addView(kanaBtn);
        modeGroup.addView(enBtn);
        String def = prefs.getString(YoruKanaService.KEY_DEFAULT_MODE, "romaji");
        ("kana".equals(def) ? kanaBtn : "en".equals(def) ? enBtn : romajiBtn).setChecked(true);
        modeGroup.setOnCheckedChangeListener((g, id) -> {
            String v = id == kanaBtn.getId() ? "kana" : id == enBtn.getId() ? "en" : "romaji";
            prefs.edit().putString(YoruKanaService.KEY_DEFAULT_MODE, v).apply();
        });
        root.addView(modeGroup);

        // ----- 学習リセット -----
        sectionTitle(root, "学習データ");
        bodyText(root, "確定した候補の頻度を記録し、次回から優先表示します。データは端末内にのみ保存されます。");
        Button reset = new Button(this);
        reset.setText("学習データをリセット");
        reset.setOnClickListener(v -> {
            SharedPreferences.Editor ed = prefs.edit();
            for (String k : prefs.getAll().keySet()) {
                if (k.startsWith(YoruKanaService.FREQ_PREFIX)) ed.remove(k);
            }
            ed.apply();
            Toast.makeText(this, "学習データをリセットしました", Toast.LENGTH_SHORT).show();
        });
        root.addView(reset);

        // ----- プライバシー -----
        sectionTitle(root, "プライバシー");
        bodyText(root, "よるかな IME はネットワーク権限を持たず、入力内容を外部に送信することは一切ありません。");

        setContentView(scroll);
    }
}
