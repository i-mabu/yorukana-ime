import jp.yorukana.ime.ConversionEngine
import jp.yorukana.ime.Dictionary
import jp.yorukana.ime.EnFlickResolver
import jp.yorukana.ime.FlickResolver
import jp.yorukana.ime.KanaUtils
import jp.yorukana.ime.MemoryLearningStore
import jp.yorukana.ime.RomajiConverter
import jp.yorukana.ime.SafetyGuard
import jp.yorukana.ime.SafetyMode
import jp.yorukana.ime.SymbolData
import jp.yorukana.ime.UserDictionary

// よるかな IME 純ロジックの単体テスト (Android 非依存クラスのみ)。
// 実行: tests/run-tests.sh (純ロジック + 本ハーネスを kotlinc でコンパイルし
// JVM で実行する。件数を数えて全パスを確認する)

private var passed = 0

private fun check(cond: Boolean, name: String) {
    if (!cond) throw AssertionError("FAILED: $name")
    passed++
    println("ok: $name")
}

private fun eq(actual: String?, expected: String, name: String) {
    check(expected == actual, "$name (expected=$expected, actual=$actual)")
}

fun main() {
    testRomaji()
    testKanaUtils()
    testDictionaryAndEngine()
    testFlickResolver()
    testEnFlickResolver()
    testUserDictionary()
    testSafetyGuard()
    testSymbolData()
    println("ALL TESTS PASSED: $passed checks")
}

// ----- RomajiConverter -----
private fun testRomaji() {
    var r = RomajiConverter.convert("ohayou")
    eq(r.kana, "おはよう", "romaji ohayou")
    eq(r.pending, "", "romaji ohayou pending")

    r = RomajiConverter.convert("gakkou")
    eq(r.kana, "がっこう", "romaji sokuon gakkou")

    r = RomajiConverter.convert("kani")
    eq(r.kana, "かに", "romaji kani (n before vowel)")
    eq(r.pending, "", "romaji kani pending")

    r = RomajiConverter.convert("kan")
    eq(r.kana, "か", "romaji kan kana")
    eq(r.pending, "n", "romaji kan pending n")

    r = RomajiConverter.convert("kann")
    eq(r.kana, "かん", "romaji kann -> kan")
    eq(r.pending, "", "romaji kann pending")

    r = RomajiConverter.convert("kan'i")
    eq(r.kana, "かんい", "romaji kan'i")

    r = RomajiConverter.convert("nna")
    eq(r.kana, "んな", "romaji nna")

    r = RomajiConverter.convert("kyou")
    eq(r.kana, "きょう", "romaji youon kyou")

    r = RomajiConverter.convert("shashin")
    eq(r.kana, "しゃし", "romaji shashin kana (final n pending)")
    eq(r.pending, "n", "romaji shashin pending n")
    eq(RomajiConverter.convertFully("shashin"), "しゃしん", "romaji shashin fully")

    r = RomajiConverter.convert("matcha")
    eq(r.kana, "まっちゃ", "romaji matcha (tch sokuon)")

    // "tch" の途中 "tc" は pending になる (t が素通し表示にならない)
    r = RomajiConverter.convert("matc")
    eq(r.kana, "ま", "romaji matc kana")
    eq(r.pending, "tc", "romaji matc pending tc")

    r = RomajiConverter.convert("fa")
    eq(r.kana, "ふぁ", "romaji foreign fa")
    r = RomajiConverter.convert("ti")
    eq(r.kana, "てぃ", "romaji foreign ti (SPEC)")
    r = RomajiConverter.convert("di")
    eq(r.kana, "でぃ", "romaji foreign di (SPEC)")
    r = RomajiConverter.convert("wi")
    eq(r.kana, "うぃ", "romaji foreign wi")

    r = RomajiConverter.convert("xtu")
    eq(r.kana, "っ", "romaji xtu")
    r = RomajiConverter.convert("ltu")
    eq(r.kana, "っ", "romaji ltu")
    r = RomajiConverter.convert("axtu")
    eq(r.kana, "あっ", "romaji axtu")
    // 4文字キー xtsu / ltsu (最長一致の上限を 4 にした回帰テスト)
    r = RomajiConverter.convert("xtsu")
    eq(r.kana, "っ", "romaji xtsu")
    eq(r.pending, "", "romaji xtsu pending")
    r = RomajiConverter.convert("ltsu")
    eq(r.kana, "っ", "romaji ltsu")
    eq(RomajiConverter.convertFully("axtsu"), "あっ", "romaji axtsu fully")
    eq(
        RomajiConverter.convertFully("konnichiha"), "こんにちは",
        "romaji konnichiha unaffected by 4-char match",
    )

    r = RomajiConverter.convert("k")
    eq(r.kana, "", "romaji k kana empty")
    eq(r.pending, "k", "romaji k pending")
    r = RomajiConverter.convert("ky")
    eq(r.pending, "ky", "romaji ky pending")
    r = RomajiConverter.convert("kak")
    eq(r.kana, "か", "romaji kak kana")
    eq(r.pending, "k", "romaji kak pending")
    r = RomajiConverter.convert("sh")
    eq(r.pending, "sh", "romaji sh pending")

    eq(RomajiConverter.convertFully("kan"), "かん", "convertFully kan flushes n")
    eq(RomajiConverter.convertFully("sensei"), "せんせい", "convertFully sensei")
    eq(RomajiConverter.convertFully("shinbun"), "しんぶん", "convertFully shinbun")
    eq(RomajiConverter.convertFully("zutto"), "ずっと", "convertFully zutto")
    eq(RomajiConverter.convertFully("kippu"), "きっぷ", "convertFully kippu")
    eq(RomajiConverter.convertFully("wo"), "を", "romaji wo")
    eq(RomajiConverter.convertFully("nn"), "ん", "romaji nn alone")
    eq(RomajiConverter.convertFully("jikan"), "じかん", "convertFully jikan")
    eq(RomajiConverter.convertFully("ryokou"), "りょこう", "convertFully ryokou")
    eq(RomajiConverter.convertFully("denwa"), "でんわ", "convertFully denwa (n before w)")
    eq(RomajiConverter.convertFully("genki"), "げんき", "convertFully genki")
    eq(RomajiConverter.convertFully("hon"), "ほん", "convertFully hon")
    eq(RomajiConverter.convertFully("xa"), "ぁ", "romaji xa small")
    eq(RomajiConverter.convertFully("ko-hi-"), "こーひー", "romaji hyphen is choon")
}

// ----- KanaUtils -----
private fun testKanaUtils() {
    eq(KanaUtils.toKatakana("がっこう"), "ガッコウ", "toKatakana gakkou")
    eq(KanaUtils.toKatakana("ぁっゔヶ"), "ァッヴヶ", "toKatakana small/vu/ke")
    eq(KanaUtils.toHiragana("ガッコウ"), "がっこう", "toHiragana gakkou")
    eq(KanaUtils.toHiragana("ヴ"), "ゔ", "toHiragana vu")
    eq(KanaUtils.toKatakana("あー"), "アー", "toKatakana keeps choon")

    check(KanaUtils.toggleDakuten('か') == 'が', "toggle ka->ga")
    check(KanaUtils.toggleDakuten('が') == 'か', "toggle ga->ka")
    check(KanaUtils.toggleDakuten('は') == 'ば', "toggle ha->ba")
    check(KanaUtils.toggleDakuten('ば') == 'ぱ', "toggle ba->pa")
    check(KanaUtils.toggleDakuten('ぱ') == 'は', "toggle pa->ha")
    check(KanaUtils.toggleDakuten('つ') == 'づ', "toggle tsu->zu")
    check(KanaUtils.toggleDakuten('づ') == 'っ', "toggle zu->small tsu")
    check(KanaUtils.toggleDakuten('っ') == 'つ', "toggle small tsu->tsu")
    check(KanaUtils.toggleDakuten('あ') == 'ぁ', "toggle a->small a")
    check(KanaUtils.toggleDakuten('ぁ') == 'あ', "toggle small a->a")
    check(KanaUtils.toggleDakuten('う') == 'ゔ', "toggle u->vu")
    check(KanaUtils.toggleDakuten('ん') == 'ん', "toggle n unchanged")
    check(KanaUtils.canToggle('か') && !KanaUtils.canToggle('ん'), "canToggle")

    check(KanaUtils.TOGGLE_ROWS.size == 10, "toggle rows 10")
    eq(KanaUtils.TOGGLE_ROWS[8], "らりるれろ", "toggle row ra")
    eq(KanaUtils.TOGGLE_ROWS[9], "わをんー〜", "toggle row wa")
    check(KanaUtils.rowIndexOf('き') == 1, "rowIndexOf ki")
    check(KanaUtils.rowIndexOf('ん') == 9, "rowIndexOf n")
    check(KanaUtils.rowIndexOf('x') == -1, "rowIndexOf none")

    // ユーザー辞書のよみ検証
    check(KanaUtils.isValidReading("よるかな"), "validReading hiragana")
    check(KanaUtils.isValidReading("こーひー"), "validReading choon ok")
    check(KanaUtils.isValidReading("ひとびと"), "validReading plain ok")
    check(KanaUtils.isValidReading("とうきょう"), "validReading youon ok")
    check(!KanaUtils.isValidReading(""), "validReading empty ng")
    check(!KanaUtils.isValidReading(null), "validReading null ng")
    check(!KanaUtils.isValidReading("yoru"), "validReading alphabet ng")
    check(!KanaUtils.isValidReading("夜"), "validReading kanji ng")
    check(!KanaUtils.isValidReading("よる1"), "validReading digit ng")
    check(!KanaUtils.isValidReading("ヨルカナ"), "validReading katakana ng")

    check(KanaUtils.nextInRow(0, 'あ') == 'い', "nextInRow a->i")
    check(KanaUtils.nextInRow(0, 'お') == 'あ', "nextInRow o wraps to a")
    check(KanaUtils.nextInRow(7, 'よ') == 'や', "nextInRow yo wraps to ya")
    check(KanaUtils.nextInRow(9, '〜') == 'わ', "nextInRow wa row wraps")
}

// ----- Dictionary / ConversionEngine -----
private fun testDictionaryAndEngine() {
    val dict = Dictionary()
    check(dict.size() == 3410, "dictionary size == 3410 (actual=${dict.size()})")
    check(dict.lookup("がっこう").contains("学校"), "dict gakkou -> 学校")
    check(dict.lookup("せんせい").contains("先生"), "dict sensei -> 先生")
    check(dict.lookup("かいた").contains("書いた"), "dict godan ta-form kaita")
    check(dict.lookup("かきます").contains("書きます"), "dict godan masu-form")
    check(dict.lookup("たべた").contains("食べた"), "dict ichidan ta-form")
    check(dict.lookup("たべます").contains("食べます"), "dict ichidan masu-form")
    check(dict.lookup("たかかった").contains("高かった"), "dict i-adj past")
    check(dict.lookup("しずかな").contains("静かな"), "dict na-adj rentai")
    check(dict.lookup("いった").contains("行った"), "dict iku exception itta")
    // 減る は五段動詞 (一段に誤分類されていた修正の回帰テスト)
    check(dict.lookup("へった").contains("減った"), "dict heru godan ta-form")
    check(dict.lookup("へらない").contains("減らない"), "dict heru godan nai-form")
    check(dict.lookup("へれる").contains("減れる"), "dict heru godan potential")
    check(!dict.lookup("へた").contains("減た"), "dict heru no wrong ichidan ta-form")
    check(!dict.lookup("へない").contains("減ない"), "dict heru no wrong ichidan nai-form")
    // 気持ちいい は「良い」の不規則活用 (よくない/よかった) で生成する
    // (I_ADJ 生成の きもちいくない 等の誤形が出ないことの回帰テスト)
    check(dict.lookup("きもちいい").contains("気持ちいい"), "dict kimochiii")
    check(dict.lookup("きもちよくない").contains("気持ちよくない"), "dict kimochiyokunai")
    check(dict.lookup("きもちよかった").contains("気持ちよかった"), "dict kimochiyokatta")
    check(!dict.lookup("きもちいくない").contains("気持ちいくない"), "dict no wrong kimochiikunai")
    check(dict.lookup("き").contains("木"), "dict single-kanji fallback ki")
    check(dict.lookup("そら").contains("空"), "dict sora")
    check(dict.has("がっこう") && !dict.has("ああああ"), "dict has")

    val learning = MemoryLearningStore()
    val engine = ConversionEngine(dict, learning)

    val cands = engine.candidates("がっこう", "gakkou")
    check(cands[0] == "学校", "engine first candidate is kanji")
    check(cands.contains("ガッコウ"), "engine has katakana candidate")
    check(cands.contains("がっこう"), "engine has hiragana candidate")
    check(cands.contains("gakkou"), "engine has romaji candidate")
    check(
        cands.indexOf("学校") < cands.indexOf("ガッコウ") &&
            cands.indexOf("ガッコウ") < cands.indexOf("がっこう") &&
            cands.indexOf("がっこう") < cands.indexOf("gakkou"),
        "engine candidate order dict>katakana>hiragana>romaji",
    )

    // 重複排除
    val dup = engine.candidates("あめ", null)
    check(dup.distinct().size == dup.size, "engine dedup")

    // 末尾助詞の簡易分離
    val split = engine.candidates("がっこうは", null)
    check(split[0] == "学校は", "engine particle split gakkou+ha")
    val split2 = engine.candidates("ねこが", null)
    check(split2[0] == "猫が", "engine particle split neko+ga")

    // 予測候補 (前方一致)
    val pred = engine.predict("がっ", 8)
    check(pred.contains("学校"), "engine predict gakkou")
    check(engine.predict("", 8).isEmpty(), "engine predict empty prefix")
    val entries = engine.predictEntries("がっ", 8)
    check(
        entries.isNotEmpty() && entries.any { e -> e[0] == "がっこう" && e[1] == "学校" },
        "engine predictEntries pair",
    )

    // 長い入力でも変換が効く (文節分割変換: 助詞の位置で区切って変換)
    check(
        engine.candidates("きょうはあつい", null).contains("今日は暑い"),
        "engine segmented convert kyouhaatsui",
    )
    check(
        engine.candidates("がっこうにいく", null).contains("学校に行く"),
        "engine segmented convert gakkouniiku",
    )
    check(
        engine.candidates("わたしはがくせい", null).contains("私は学生"),
        "engine segmented convert watashihagakusei",
    )
    check(
        engine.candidates("べんきょうする", null).contains("勉強する"),
        "engine segmented convert suru verb",
    )
    check(
        engine.candidates("にほんごをべんきょうする", null).contains("日本語を勉強する"),
        "engine segmented convert long sentence",
    )
    check(
        engine.candidates("ありがとうございます", null).contains("有難うございます"),
        "engine greeting phrase",
    )

    // 長い入力でも予測が止まらない (末尾セグメントへの予測)
    check(
        engine.predictEntries("わたしはがくせ", 8)
            .any { e -> e[0] == "わたしはがくせい" && e[1] == "私は学生" },
        "engine segmented predict watashiha",
    )
    check(
        engine.predictEntries("きょうはあつ", 8).any { e -> e[1] == "今日は暑い" },
        "engine segmented predict kyouhaatsu",
    )
    check(
        engine.predictEntries("がっこうにい", 8).any { e -> e[1] == "学校に行く" },
        "engine segmented predict gakkouni-i",
    )
    check(engine.predict("きょうはあつ", 8).contains("今日は暑い"), "engine predict long input")

    // 区切れない入力では誤った予測を出さない (てんき は分割対象外)
    check(engine.predictEntries("てんき", 8).isEmpty(), "engine no garbage prediction tenki")

    // 前方一致 (二分探索版): prefix より長く prefix で始まる読みだけが返る
    val withPrefix = dict.readingsWithPrefix("がっ")
    check(withPrefix.contains("がっこう"), "dict prefix contains gakkou")
    check(
        withPrefix.all { k -> k.startsWith("がっ") && k.length > 2 },
        "dict prefix all match and longer",
    )
    check(!dict.readingsWithPrefix("がっこう").contains("がっこう"), "dict prefix excludes exact key")
    check(dict.readingsWithPrefix("").isEmpty(), "dict prefix empty")

    // 学習: 頻度が候補順に反映される (はし は初期順 橋,箸,端。箸を学習させる)
    val before = engine.candidates("はし", null)
    check(
        before.indexOf("橋") >= 0 && before.indexOf("橋") < before.indexOf("箸"),
        "engine hashi initial order",
    )
    learning.recordSelection("はし", "箸")
    learning.recordSelection("はし", "箸")
    val after = engine.candidates("はし", null)
    check(after.indexOf("箸") < after.indexOf("橋"), "learning promotes selected candidate")
    check(learning.getFrequency("はし", "箸") == 2, "learning frequency count")
    learning.clear()
    check(learning.getFrequency("はし", "箸") == 0, "learning clear")
    val restored = engine.candidates("はし", null)
    check(restored.indexOf("橋") < restored.indexOf("箸"), "learning clear restores order")
}

// ----- FlickResolver -----
private fun testFlickResolver() {
    // 方向判定 (しきい値 24px)
    check(FlickResolver.resolveDirection(0f, 0f, 24f) == FlickResolver.DIR_CENTER, "flick dir zero is center")
    // しきい値 0 でも変位ゼロは中央 (左と誤判定しない)
    check(FlickResolver.resolveDirection(0f, 0f, 0f) == FlickResolver.DIR_CENTER, "flick dir zero with zero threshold is center")
    check(FlickResolver.resolveDirection(10f, 5f, 24f) == FlickResolver.DIR_CENTER, "flick dir below threshold is center")
    check(FlickResolver.resolveDirection(30f, 0f, 24f) == FlickResolver.DIR_RIGHT, "flick dir right")
    check(FlickResolver.resolveDirection(-30f, 0f, 24f) == FlickResolver.DIR_LEFT, "flick dir left")
    check(FlickResolver.resolveDirection(0f, 30f, 24f) == FlickResolver.DIR_DOWN, "flick dir down (dy positive)")
    check(FlickResolver.resolveDirection(0f, -30f, 24f) == FlickResolver.DIR_UP, "flick dir up (dy negative)")
    check(FlickResolver.resolveDirection(30f, 20f, 24f) == FlickResolver.DIR_RIGHT, "flick dir diagonal dominant x")
    check(FlickResolver.resolveDirection(20f, 30f, 24f) == FlickResolver.DIR_DOWN, "flick dir diagonal dominant y")
    check(FlickResolver.resolveDirection(-30f, -40f, 24f) == FlickResolver.DIR_UP, "flick dir diagonal up-left is up")

    // 標準行 (あ・か・さ・た・な・は・ま・ら) は 中央/左/上/右/下 = 段の1〜5文字目
    val standardRows = arrayOf(
        "あいうえお", "かきくけこ", "さしすせそ", "たちつてと",
        "なにぬねの", "はひふへほ", "まみむめも", null, "らりるれろ",
    )
    val dirs = intArrayOf(
        FlickResolver.DIR_CENTER, FlickResolver.DIR_LEFT,
        FlickResolver.DIR_UP, FlickResolver.DIR_RIGHT, FlickResolver.DIR_DOWN,
    )
    for (row in standardRows.indices) {
        val chars = standardRows[row] ?: continue
        for (d in 0 until 5) {
            check(FlickResolver.charAt(row, dirs[d]) == chars[d], "flick char row=$row dir=$d")
            check(FlickResolver.isAssigned(row, dirs[d]), "flick assigned row=$row dir=$d")
        }
    }

    // や行 (row=7): 中央=や 上=ゆ 下=よ、左右は未割り当て→中央にフォールバック
    check(FlickResolver.charAt(7, FlickResolver.DIR_CENTER) == 'や', "flick ya center")
    check(FlickResolver.charAt(7, FlickResolver.DIR_UP) == 'ゆ', "flick ya up is yu")
    check(FlickResolver.charAt(7, FlickResolver.DIR_DOWN) == 'よ', "flick ya down is yo")
    check(FlickResolver.charAt(7, FlickResolver.DIR_LEFT) == 'や', "flick ya left falls back to center")
    check(FlickResolver.charAt(7, FlickResolver.DIR_RIGHT) == 'や', "flick ya right falls back to center")
    check(
        !FlickResolver.isAssigned(7, FlickResolver.DIR_LEFT) &&
            !FlickResolver.isAssigned(7, FlickResolver.DIR_RIGHT),
        "flick ya left/right unassigned",
    )
    check(
        FlickResolver.isAssigned(7, FlickResolver.DIR_UP) &&
            FlickResolver.isAssigned(7, FlickResolver.DIR_DOWN),
        "flick ya up/down assigned",
    )

    // わ行 (row=9): 中央=わ 左=を 上=ん 右=ー 下=〜
    check(FlickResolver.charAt(9, FlickResolver.DIR_CENTER) == 'わ', "flick wa center")
    check(FlickResolver.charAt(9, FlickResolver.DIR_LEFT) == 'を', "flick wa left is wo")
    check(FlickResolver.charAt(9, FlickResolver.DIR_UP) == 'ん', "flick wa up is n")
    check(FlickResolver.charAt(9, FlickResolver.DIR_RIGHT) == 'ー', "flick wa right is choon")
    check(FlickResolver.charAt(9, FlickResolver.DIR_DOWN) == '〜', "flick wa down is nami")

    // 範囲外
    check(FlickResolver.charAt(-1, FlickResolver.DIR_CENTER) == '\u0000', "flick row -1 is nul")
    check(FlickResolver.charAt(10, FlickResolver.DIR_CENTER) == '\u0000', "flick row 10 is nul")
    check(!FlickResolver.isAssigned(10, FlickResolver.DIR_CENTER), "flick row 10 unassigned")
    check(FlickResolver.ROW_COUNT == 10, "flick row count 10")
}

// ----- EnFlickResolver -----
private fun testEnFlickResolver() {
    check(EnFlickResolver.GROUP_COUNT == 11, "enflick group count 11")

    // ABC (group=1): 中央=a 左=b 上=c、右・下は未割り当て (中央へフォールバック)
    check(EnFlickResolver.charAt(1, FlickResolver.DIR_CENTER) == 'a', "enflick abc center")
    check(EnFlickResolver.charAt(1, FlickResolver.DIR_LEFT) == 'b', "enflick abc left")
    check(EnFlickResolver.charAt(1, FlickResolver.DIR_UP) == 'c', "enflick abc up")
    check(!EnFlickResolver.isAssigned(1, FlickResolver.DIR_RIGHT), "enflick abc right unassigned")
    check(EnFlickResolver.charAt(1, FlickResolver.DIR_RIGHT) == 'a', "enflick abc right falls back to center")

    // PQRS / WXYZ は4文字 (右まで割り当て)
    check(EnFlickResolver.charAt(6, FlickResolver.DIR_RIGHT) == 's', "enflick pqrs right")
    check(EnFlickResolver.charAt(8, FlickResolver.DIR_RIGHT) == 'z', "enflick wxyz right")
    check(EnFlickResolver.charAt(5, FlickResolver.DIR_UP) == 'o', "enflick mno up")

    // 数字・記号グループ (group=0) と句読点グループ (group=10)
    check(EnFlickResolver.charAt(0, FlickResolver.DIR_CENTER) == '0', "enflick num center")
    check(EnFlickResolver.charAt(0, FlickResolver.DIR_LEFT) == '/', "enflick num left")
    check(EnFlickResolver.charAt(0, FlickResolver.DIR_UP) == ':', "enflick num up")
    check(EnFlickResolver.charAt(10, FlickResolver.DIR_CENTER) == ',', "enflick punct center")
    check(EnFlickResolver.charAt(10, FlickResolver.DIR_DOWN) == '.', "enflick punct down")

    // タップ循環の並び (割り当て済みを方向順に)
    val cyc = EnFlickResolver.cycleChars(1)
    check(cyc.size == 3 && cyc[0] == 'a' && cyc[1] == 'b' && cyc[2] == 'c', "enflick cycle abc")
    val cyc6 = EnFlickResolver.cycleChars(6)
    check(cyc6.size == 4 && cyc6[3] == 's', "enflick cycle pqrs")
    check(EnFlickResolver.cycleChars(0).size == 5, "enflick cycle num 5")
    check(EnFlickResolver.cycleChars(99).isEmpty(), "enflick cycle out of range empty")

    // ラベル
    check(EnFlickResolver.label(1) == "ABC", "enflick label abc")
    check(EnFlickResolver.label(0) == "0/:_", "enflick label num")
    check(EnFlickResolver.label(99) == "", "enflick label out of range")

    // 範囲外
    check(EnFlickResolver.charAt(11, FlickResolver.DIR_CENTER) == '\u0000', "enflick group 11 is nul")
    check(!EnFlickResolver.isAssigned(-1, FlickResolver.DIR_CENTER), "enflick group -1 unassigned")
}

// ----- UserDictionary -----
private fun testUserDictionary() {
    val ud = UserDictionary()
    check(ud.size() == 0 && !ud.has("ねこ"), "userdict empty")
    ud.add("ねこ", "猫又")
    ud.add("ねこ", "ネコチャン")
    ud.add("よるかな", "夜奏")
    ud.add("ねこ", "猫又") // 重複は無視
    check(ud.size() == 3, "userdict size 3 (dup ignored)")
    check(ud.lookup("ねこ") == listOf("猫又", "ネコチャン"), "userdict lookup keeps order")
    check(ud.lookup("いぬ").isEmpty(), "userdict lookup missing is empty")
    ud.add("", "単語")
    ud.add("よみ", "")
    check(ud.size() == 3, "userdict ignores empty reading/word")

    // encode / decode 往復
    val restored = UserDictionary.decode(ud.encode())
    check(restored.size() == 3, "userdict encode/decode size")
    check(restored.lookup("よるかな") == listOf("夜奏"), "userdict encode/decode content")
    check(UserDictionary.decode("").size() == 0, "userdict decode empty")
    check(UserDictionary.decode(null).size() == 0, "userdict decode null")

    // 削除
    check(ud.remove("ねこ", "猫又"), "userdict remove returns true")
    check(ud.lookup("ねこ") == listOf("ネコチャン"), "userdict after remove")
    check(!ud.remove("ねこ", "猫又"), "userdict remove missing returns false")
    check(
        ud.remove("ねこ", "ネコチャン") && !ud.has("ねこ"),
        "userdict reading removed when last word deleted",
    )

    // 変換候補での優先順位: ユーザー辞書 > 学習 > 内蔵辞書
    val dict = Dictionary()
    val learning = MemoryLearningStore()
    val user = UserDictionary()
    user.add("はし", "マイ箸")
    user.add("よるかな", "夜奏")
    val engine = ConversionEngine(dict, learning, user)

    // 学習で「箸」を強く学習させても、ユーザー辞書が先頭
    repeat(5) { learning.recordSelection("はし", "箸") }
    val cands = engine.candidates("はし", null)
    check(cands[0] == "マイ箸", "userdict beats learning")
    check(cands.indexOf("マイ箸") < cands.indexOf("箸"), "userdict before learned word")

    // 内蔵辞書に無い読みでもユーザー辞書だけで候補が出る
    val only = engine.candidates("よるかな", null)
    check(only[0] == "夜奏", "userdict-only reading candidate")

    // ユーザー単語が内蔵候補と重なる場合は重複しない (あめ→雨 は内蔵にもある)
    user.add("あめ", "雨")
    val ame = engine.candidates("あめ", null)
    check(ame[0] == "雨" && ame.count { it == "雨" } == 1, "userdict dedup with builtin")

    // プロパティでの差し替え (IME が入力開始時に読み込む経路)
    val engine2 = ConversionEngine(dict, learning)
    check(
        engine2.candidates("よるかな", null)[0] == "ヨルカナ",
        "engine without userdict falls back to katakana",
    )
    engine2.userDictionary = user
    check(
        engine2.candidates("よるかな", null)[0] == "夜奏",
        "engine setUserDictionary takes effect",
    )

    // ユーザー辞書の語も予測候補に出る (前方一致)
    user.add("よるかなすぺしゃる", "夜奏スペシャル")
    check(engine.predict("よるかな", 8).contains("夜奏スペシャル"), "engine predict includes userdict")
    check(
        user.readingsWithPrefix("よるかな").contains("よるかなすぺしゃる"),
        "userdict readingsWithPrefix",
    )
    check(
        !user.readingsWithPrefix("よるかな").contains("よるかな"),
        "userdict readingsWithPrefix excludes exact",
    )
}

// ----- SafetyGuard -----
private fun testSafetyGuard() {
    // モード判定: 0-1回は通常、2-3回はセーフ、4回以上は最小
    check(SafetyGuard.modeFor(0) == SafetyMode.NORMAL, "safety mode 0 = NORMAL")
    check(SafetyGuard.modeFor(1) == SafetyMode.NORMAL, "safety mode 1 = NORMAL")
    check(SafetyGuard.modeFor(2) == SafetyMode.SAFE, "safety mode 2 = SAFE")
    check(SafetyGuard.modeFor(3) == SafetyMode.SAFE, "safety mode 3 = SAFE")
    check(SafetyGuard.modeFor(4) == SafetyMode.MINIMAL, "safety mode 4 = MINIMAL")
    check(SafetyGuard.modeFor(10) == SafetyMode.MINIMAL, "safety mode 10 = MINIMAL")

    // 起動開始時の失敗数: 前回が未完了なら +1、完了していれば据え置き
    check(SafetyGuard.failuresAtLaunch(0, false) == 0, "safety failures fresh launch")
    check(SafetyGuard.failuresAtLaunch(0, true) == 1, "safety failures after pending")
    check(SafetyGuard.failuresAtLaunch(2, true) == 3, "safety failures accumulate")
    check(SafetyGuard.failuresAtLaunch(3, false) == 3, "safety failures kept when completed")

    // クラッシュ記録の整形と分解 (往復)
    val record = SafetyGuard.formatCrashRecord(
        1700000000000L, "java.lang.IllegalStateException", "boom",
        "  at a.b(C.kt:1)",
    )
    val parsed = SafetyGuard.parseCrashRecord(record)
    check(parsed != null, "safety record parse non-null")
    eq(parsed!![0], "1700000000000", "safety record time")
    eq(parsed[1], "java.lang.IllegalStateException", "safety record class")
    eq(parsed[2], "boom", "safety record message")

    // メッセージが null でも記録でき、空文字として分解される
    val parsedNull = SafetyGuard.parseCrashRecord(
        SafetyGuard.formatCrashRecord(1L, "java.lang.Error", null, ""),
    )
    check(parsedNull != null && parsedNull[2].isEmpty(), "safety record null message")

    // 長いメッセージは 200 文字に切り詰められる
    val longMsg = "x".repeat(500)
    val parsedLong = SafetyGuard.parseCrashRecord(
        SafetyGuard.formatCrashRecord(1L, "E", longMsg, ""),
    )
    check(parsedLong != null && parsedLong[2].length == 200, "safety record message truncated to 200")

    // 壊れた記録・空記録は null
    check(SafetyGuard.parseCrashRecord(null) == null, "safety record null input")
    check(SafetyGuard.parseCrashRecord("") == null, "safety record empty input")
    check(SafetyGuard.parseCrashRecord("only-one-line") == null, "safety record broken input")
}

// ----- SymbolData (記号パネルのレイアウト整合性) -----
private fun testSymbolData() {
    check(SymbolData.SYMBOL_CATEGORY_NAMES.size == 6, "symbol categories 6")
    check(
        SymbolData.SYMBOL_CATEGORY_ITEMS.size == SymbolData.SYMBOL_CATEGORY_NAMES.size,
        "symbol items match categories",
    )
    check(
        SymbolData.SYMBOL_CATEGORY_COLS.size == SymbolData.SYMBOL_CATEGORY_NAMES.size,
        "symbol cols match categories",
    )
    check(
        SymbolData.SYMBOL_CATEGORY_ITEMS.all { it.isNotEmpty() },
        "symbol categories all non-empty",
    )
    check(
        SymbolData.SYMBOL_CATEGORY_COLS.all { it > 0 },
        "symbol cols all positive",
    )
    check(SymbolData.SYMBOL_PAGE_COMMON == 0, "symbol page common 0")
    check(SymbolData.SYMBOL_PAGE_NUMBER == 3, "symbol page number 3")
    check(SymbolData.SYMBOL_PAGE_KAOMOJI == 4, "symbol page kaomoji 4")
    check(SymbolData.SYMBOL_PAGE_EMOJI == 5, "symbol page emoji 5")
    check(
        SymbolData.SYMBOL_CATEGORY_ITEMS[SymbolData.SYMBOL_PAGE_KAOMOJI] === SymbolData.KAOMOJIS,
        "symbol kaomoji page points at kaomojis",
    )
    check(
        SymbolData.SYMBOL_CATEGORY_ITEMS[SymbolData.SYMBOL_PAGE_EMOJI] === SymbolData.EMOJIS,
        "symbol emoji page points at emojis",
    )
    check(SymbolData.SYMBOL_CATEGORY_COLS[SymbolData.SYMBOL_PAGE_KAOMOJI] == 3, "symbol kaomoji 3 cols")
    check(SymbolData.SYMBOL_CATEGORY_COLS[SymbolData.SYMBOL_PAGE_EMOJI] == 8, "symbol emoji 8 cols")
}
