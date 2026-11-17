package jp.yorukana.ime

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ===== 1. 設定画面 (Jetpack Compose) =====
// IME有効化ガイド、テーマ (夜/昼)、バイブ、既定入力方式、かな入力方式
// (フリック/トグル)、ユーザー辞書、学習リセット。
// 設定は SharedPreferences で Service と共有する。
// テーマ設定はこの画面の配色 (Material3 の夜/昼スキーム) にも即時反映する。
class SettingsActivity : ComponentActivity() {

    private var prefs: SharedPreferences? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = getSharedPreferences(YoruKanaService.PREFS, Context.MODE_PRIVATE)
        prefs = p
        setContent {
            SettingsScreen(p)
        }
    }

    // ----- 1.1 ユーザー辞書の永続化 -----
    private fun saveUserDict(dict: UserDictionary) {
        prefs?.edit()?.putString(YoruKanaService.KEY_USER_DICT, dict.encode())?.apply()
    }

    @Composable
    private fun SettingsScreen(p: SharedPreferences) {
        val context = LocalContext.current
        // テーマは設定値と連動させる (切り替えると即座に画面の配色が変わる)
        var night by remember { mutableStateOf("day" != p.getString(YoruKanaService.KEY_THEME, "night")) }
        val scheme = if (night) {
            darkColorScheme(
                primary = Color(0xFF8FA8FF),
                background = Color(0xFF0B1226),
                surface = Color(0xFF1C2A4A),
                onBackground = Color(0xFFEAF0FF),
                onSurface = Color(0xFFEAF0FF),
            )
        } else {
            lightColorScheme(
                primary = Color(0xFF3D5AFE),
                background = Color(0xFFE8EBF0),
                surface = Color(0xFFFFFFFF),
                onBackground = Color(0xFF182038),
                onSurface = Color(0xFF182038),
            )
        }
        MaterialTheme(colorScheme = scheme) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                ) {
                    Text("よるかな IME 設定", fontSize = 22.sp)

                    // ----- 1.2 有効化ガイド -----
                    SectionTitle("有効化の手順")
                    BodyText(
                        "1. 下のボタンから「入力方法の設定」を開き、「よるかな IME」をオンにします。\n" +
                            "2. 文字入力画面でキーボード切替 (地球儀キー等) から「よるかな IME」を選びます。",
                    )
                    Button(
                        onClick = {
                            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("入力方法の設定を開く") }
                    Button(
                        onClick = {
                            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                            imm?.showInputMethodPicker()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("キーボードを選択する") }

                    // ----- 1.3 テーマ -----
                    SectionTitle("テーマ")
                    var theme by remember {
                        mutableStateOf(if (night) "night" else "day")
                    }
                    RadioRow("夜テーマ (既定)", theme == "night") {
                        theme = "night"
                        night = true
                        p.edit().putString(YoruKanaService.KEY_THEME, "night").apply()
                    }
                    RadioRow("昼テーマ", theme == "day") {
                        theme = "day"
                        night = false
                        p.edit().putString(YoruKanaService.KEY_THEME, "day").apply()
                    }

                    // ----- 1.4 バイブ -----
                    SectionTitle("キー操作")
                    var vibrate by remember {
                        mutableStateOf(p.getBoolean(YoruKanaService.KEY_VIBRATE, true))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                vibrate = !vibrate
                                p.edit().putBoolean(YoruKanaService.KEY_VIBRATE, vibrate).apply()
                            },
                    ) {
                        Checkbox(checked = vibrate, onCheckedChange = { checked ->
                            vibrate = checked
                            p.edit().putBoolean(YoruKanaService.KEY_VIBRATE, checked).apply()
                        })
                        Text("キータップ時にバイブレーション", fontSize = 14.sp)
                    }

                    // ----- 1.5 既定の入力方式 -----
                    SectionTitle("既定の入力方式")
                    var defMode by remember {
                        mutableStateOf(p.getString(YoruKanaService.KEY_DEFAULT_MODE, "romaji") ?: "romaji")
                    }
                    RadioRow("ローマ字入力 (QWERTY)", defMode == "romaji") {
                        defMode = "romaji"
                        p.edit().putString(YoruKanaService.KEY_DEFAULT_MODE, "romaji").apply()
                    }
                    RadioRow("かな入力 (12キー)", defMode == "kana") {
                        defMode = "kana"
                        p.edit().putString(YoruKanaService.KEY_DEFAULT_MODE, "kana").apply()
                    }
                    RadioRow("英数入力 (QWERTY)", defMode == "en") {
                        defMode = "en"
                        p.edit().putString(YoruKanaService.KEY_DEFAULT_MODE, "en").apply()
                    }

                    // ----- 1.6 かな入力方式 -----
                    SectionTitle("かな入力方式")
                    var kanaStyle by remember {
                        mutableStateOf(p.getString(YoruKanaService.KEY_KANA_STYLE, "flick") ?: "flick")
                    }
                    RadioRow("フリック入力 (既定)", kanaStyle == "flick") {
                        kanaStyle = "flick"
                        p.edit().putString(YoruKanaService.KEY_KANA_STYLE, "flick").apply()
                    }
                    RadioRow("トグル打ち", kanaStyle == "toggle") {
                        kanaStyle = "toggle"
                        p.edit().putString(YoruKanaService.KEY_KANA_STYLE, "toggle").apply()
                    }

                    // ----- 1.7 ユーザー辞書 -----
                    SectionTitle("ユーザー辞書")
                    BodyText("登録した単語は変換候補の先頭に出ます。よみはひらがなで入力してください（カタカナは自動でひらがなに直します）。")
                    val userDict = remember {
                        UserDictionary.decode(p.getString(YoruKanaService.KEY_USER_DICT, ""))
                    }
                    var entries by remember { mutableStateOf(userDict.entries()) }
                    var reading by remember { mutableStateOf("") }
                    var word by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = reading,
                        onValueChange = { reading = it },
                        label = { Text("よみ (例: よるかな)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = word,
                        onValueChange = { word = it },
                        label = { Text("単語 (例: 夜奏)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            val r = KanaUtils.toHiragana(reading.trim())
                            val w = word.trim()
                            if (r.isEmpty() || w.isEmpty()) {
                                Toast.makeText(
                                    context, "よみと単語の両方を入力してください",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@Button
                            }
                            if (!KanaUtils.isValidReading(r)) {
                                Toast.makeText(
                                    context,
                                    "よみはひらがなで入力してください（英字・漢字・数字は使えません）",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@Button
                            }
                            userDict.add(r, w)
                            saveUserDict(userDict)
                            reading = ""
                            word = ""
                            entries = userDict.entries()
                            Toast.makeText(context, "登録しました", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("単語を追加") }
                    if (entries.isEmpty()) {
                        BodyText("（まだ登録がありません）")
                    } else {
                        for (e in entries) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    e[0] + " → " + e[1],
                                    fontSize = 15.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    userDict.remove(e[0], e[1])
                                    saveUserDict(userDict)
                                    entries = userDict.entries()
                                    Toast.makeText(context, "削除しました", Toast.LENGTH_SHORT).show()
                                }) { Text("削除") }
                            }
                        }
                    }

                    // ----- 1.8 学習リセット -----
                    SectionTitle("学習データ")
                    BodyText("確定した候補の頻度を記録し、次回から優先表示します。データは端末内にのみ保存されます。")
                    Button(
                        onClick = {
                            val ed = p.edit()
                            for (k in p.all.keys) {
                                if (k.startsWith(YoruKanaService.FREQ_PREFIX)) ed.remove(k)
                            }
                            ed.apply()
                            Toast.makeText(context, "学習データをリセットしました", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("学習データをリセット") }

                    // ----- 1.9 プライバシー -----
                    SectionTitle("プライバシー")
                    BodyText("よるかな IME はネットワーク権限を持たず、入力内容を外部に送信することは一切ありません。")
                }
            }
        }
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(
            text,
            fontSize = 17.sp,
            modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
        )
    }

    @Composable
    private fun BodyText(text: String) {
        Text(text, fontSize = 14.sp)
    }

    @Composable
    private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect),
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Text(label, fontSize = 14.sp)
        }
    }
}
