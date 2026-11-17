package jp.yorukana.ime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

// ===== 1. 設定画面 =====
// IME有効化ガイド、テーマ (夜/昼)、バイブ、既定入力方式、かな入力方式
// (フリック/トグル)、ユーザー辞書、学習リセット。
// 設定は SharedPreferences で Service と共有する。
class SettingsActivity : Activity() {

    private var prefs: SharedPreferences? = null

    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun sectionTitle(root: LinearLayout, text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 17f
        tv.setPadding(0, dp(20), 0, dp(6))
        root.addView(tv)
        return tv
    }

    private fun bodyText(root: LinearLayout, text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 14f
        root.addView(tv)
        return tv
    }

    // ----- ユーザー辞書の永続化・一覧 -----
    private fun saveUserDict(dict: UserDictionary) {
        prefs?.edit()?.putString(YoruKanaService.KEY_USER_DICT, dict.encode())?.apply()
    }

    private fun buildDictList(container: LinearLayout, dict: UserDictionary) {
        container.removeAllViews()
        if (dict.size() == 0) {
            val empty = TextView(this)
            empty.text = "（まだ登録がありません）"
            empty.textSize = 14f
            container.addView(empty)
            return
        }
        for (e in dict.entries()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val tv = TextView(this)
            tv.text = e[0] + " → " + e[1]
            tv.textSize = 15f
            row.addView(
                tv, LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                ),
            )
            val del = Button(this)
            del.text = "削除"
            val reading = e[0]
            val word = e[1]
            del.setOnClickListener {
                dict.remove(reading, word)
                saveUserDict(dict)
                buildDictList(container, dict)
                Toast.makeText(this, "削除しました", Toast.LENGTH_SHORT).show()
            }
            row.addView(del)
            container.addView(row)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = getSharedPreferences(YoruKanaService.PREFS, Context.MODE_PRIVATE)
        prefs = p

        val scroll = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(20), dp(20), dp(20))
        scroll.addView(root)

        val title = TextView(this)
        title.text = "よるかな IME 設定"
        title.textSize = 22f
        root.addView(title)

        // ----- 有効化ガイド -----
        sectionTitle(root, "有効化の手順")
        bodyText(
            root, "1. 下のボタンから「入力方法の設定」を開き、「よるかな IME」をオンにします。\n" +
                "2. 文字入力画面でキーボード切替 (地球儀キー等) から「よるかな IME」を選びます。",
        )

        val openSettings = Button(this)
        openSettings.text = "入力方法の設定を開く"
        openSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        root.addView(openSettings)

        val pickIme = Button(this)
        pickIme.text = "キーボードを選択する"
        pickIme.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        }
        root.addView(pickIme)

        // ----- テーマ -----
        sectionTitle(root, "テーマ")
        val themeGroup = RadioGroup(this)
        val nightBtn = RadioButton(this)
        nightBtn.id = 1
        nightBtn.text = "夜テーマ (既定)"
        val dayBtn = RadioButton(this)
        dayBtn.id = 2
        dayBtn.text = "昼テーマ"
        themeGroup.addView(nightBtn)
        themeGroup.addView(dayBtn)
        val night = "day" != p.getString(YoruKanaService.KEY_THEME, "night")
        (if (night) nightBtn else dayBtn).isChecked = true
        themeGroup.setOnCheckedChangeListener { _, id ->
            p.edit().putString(
                YoruKanaService.KEY_THEME,
                if (id == nightBtn.id) "night" else "day",
            ).apply()
        }
        root.addView(themeGroup)

        // ----- バイブ -----
        sectionTitle(root, "キー操作")
        val vibrateBox = CheckBox(this)
        vibrateBox.text = "キータップ時にバイブレーション"
        vibrateBox.isChecked = p.getBoolean(YoruKanaService.KEY_VIBRATE, true)
        vibrateBox.setOnCheckedChangeListener { _, checked ->
            p.edit().putBoolean(YoruKanaService.KEY_VIBRATE, checked).apply()
        }
        root.addView(vibrateBox)

        // ----- 既定の入力方式 -----
        sectionTitle(root, "既定の入力方式")
        val modeGroup = RadioGroup(this)
        val romajiBtn = RadioButton(this)
        romajiBtn.id = 11
        romajiBtn.text = "ローマ字入力 (QWERTY)"
        val kanaBtn = RadioButton(this)
        kanaBtn.id = 12
        kanaBtn.text = "かな入力 (12キー)"
        val enBtn = RadioButton(this)
        enBtn.id = 13
        enBtn.text = "英数入力 (QWERTY)"
        modeGroup.addView(romajiBtn)
        modeGroup.addView(kanaBtn)
        modeGroup.addView(enBtn)
        val def = p.getString(YoruKanaService.KEY_DEFAULT_MODE, "romaji")
        (if ("kana" == def) kanaBtn else if ("en" == def) enBtn else romajiBtn).isChecked = true
        modeGroup.setOnCheckedChangeListener { _, id ->
            val v = if (id == kanaBtn.id) "kana" else if (id == enBtn.id) "en" else "romaji"
            p.edit().putString(YoruKanaService.KEY_DEFAULT_MODE, v).apply()
        }
        root.addView(modeGroup)

        // ----- かな入力方式 -----
        sectionTitle(root, "かな入力方式")
        val kanaGroup = RadioGroup(this)
        val flickBtn = RadioButton(this)
        flickBtn.id = 21
        flickBtn.text = "フリック入力 (既定)"
        val toggleBtn = RadioButton(this)
        toggleBtn.id = 22
        toggleBtn.text = "トグル打ち"
        kanaGroup.addView(flickBtn)
        kanaGroup.addView(toggleBtn)
        val flick = "toggle" != p.getString(YoruKanaService.KEY_KANA_STYLE, "flick")
        (if (flick) flickBtn else toggleBtn).isChecked = true
        kanaGroup.setOnCheckedChangeListener { _, id ->
            p.edit().putString(
                YoruKanaService.KEY_KANA_STYLE,
                if (id == toggleBtn.id) "toggle" else "flick",
            ).apply()
        }
        root.addView(kanaGroup)

        // ----- ユーザー辞書 -----
        sectionTitle(root, "ユーザー辞書")
        bodyText(root, "登録した単語は変換候補の先頭に出ます。よみはひらがなで入力してください（カタカナは自動でひらがなに直します）。")
        val userDict = UserDictionary.decode(
            p.getString(YoruKanaService.KEY_USER_DICT, ""),
        )
        val readingInput = EditText(this)
        readingInput.hint = "よみ (例: よるかな)"
        root.addView(readingInput)
        val wordInput = EditText(this)
        wordInput.hint = "単語 (例: 夜奏)"
        root.addView(wordInput)
        val dictList = LinearLayout(this)
        dictList.orientation = LinearLayout.VERTICAL
        val addWord = Button(this)
        addWord.text = "単語を追加"
        addWord.setOnClickListener {
            val reading = KanaUtils.toHiragana(readingInput.text.toString().trim())
            val word = wordInput.text.toString().trim()
            if (reading.isEmpty() || word.isEmpty()) {
                Toast.makeText(
                    this, "よみと単語の両方を入力してください",
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }
            if (!KanaUtils.isValidReading(reading)) {
                Toast.makeText(
                    this,
                    "よみはひらがなで入力してください（英字・漢字・数字は使えません）",
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }
            userDict.add(reading, word)
            saveUserDict(userDict)
            readingInput.setText("")
            wordInput.setText("")
            buildDictList(dictList, userDict)
            Toast.makeText(this, "登録しました", Toast.LENGTH_SHORT).show()
        }
        root.addView(addWord)
        root.addView(dictList)
        buildDictList(dictList, userDict)

        // ----- 学習リセット -----
        sectionTitle(root, "学習データ")
        bodyText(root, "確定した候補の頻度を記録し、次回から優先表示します。データは端末内にのみ保存されます。")
        val reset = Button(this)
        reset.text = "学習データをリセット"
        reset.setOnClickListener {
            val ed = p.edit()
            for (k in p.all.keys) {
                if (k.startsWith(YoruKanaService.FREQ_PREFIX)) ed.remove(k)
            }
            ed.apply()
            Toast.makeText(this, "学習データをリセットしました", Toast.LENGTH_SHORT).show()
        }
        root.addView(reset)

        // ----- プライバシー -----
        sectionTitle(root, "プライバシー")
        bodyText(root, "よるかな IME はネットワーク権限を持たず、入力内容を外部に送信することは一切ありません。")

        setContentView(scroll)
    }
}
