import jp.yorukana.ime.ConversionEngine;
import jp.yorukana.ime.Dictionary;
import jp.yorukana.ime.FlickResolver;
import jp.yorukana.ime.KanaUtils;
import jp.yorukana.ime.MemoryLearningStore;
import jp.yorukana.ime.RomajiConverter;
import jp.yorukana.ime.UserDictionary;

import java.util.List;

// よるかな IME 純ロジックの単体テスト (Android非依存クラスのみ)
// 実行: tests/run-tests.sh  (本体は kotlinc でコンパイルし、本ハーネスを
// javac + java で実行。件数を数えて全パスを確認する)

// ===== 1. テスト本体 =====
public class TestMain {
    private static int passed = 0;

    private static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAILED: " + name);
        passed++;
        System.out.println("ok: " + name);
    }

    private static void eq(String actual, String expected, String name) {
        check(expected.equals(actual),
            name + " (expected=" + expected + ", actual=" + actual + ")");
    }

    public static void main(String[] args) {
        testRomaji();
        testKanaUtils();
        testDictionaryAndEngine();
        testFlickResolver();
        testUserDictionary();
        System.out.println("ALL TESTS PASSED: " + passed + " checks");
    }

    // ----- 1.1 RomajiConverter -----
    private static void testRomaji() {
        RomajiConverter.Result r;

        r = RomajiConverter.convert("ohayou");
        eq(r.kana, "おはよう", "romaji ohayou");
        eq(r.pending, "", "romaji ohayou pending");

        r = RomajiConverter.convert("gakkou");
        eq(r.kana, "がっこう", "romaji sokuon gakkou");

        r = RomajiConverter.convert("kani");
        eq(r.kana, "かに", "romaji kani (n before vowel)");
        eq(r.pending, "", "romaji kani pending");

        r = RomajiConverter.convert("kan");
        eq(r.kana, "か", "romaji kan kana");
        eq(r.pending, "n", "romaji kan pending n");

        r = RomajiConverter.convert("kann");
        eq(r.kana, "かん", "romaji kann -> kan");
        eq(r.pending, "", "romaji kann pending");

        r = RomajiConverter.convert("kan'i");
        eq(r.kana, "かんい", "romaji kan'i");

        r = RomajiConverter.convert("nna");
        eq(r.kana, "んな", "romaji nna");

        r = RomajiConverter.convert("kyou");
        eq(r.kana, "きょう", "romaji youon kyou");

        r = RomajiConverter.convert("shashin");
        eq(r.kana, "しゃし", "romaji shashin kana (final n pending)");
        eq(r.pending, "n", "romaji shashin pending n");
        eq(RomajiConverter.convertFully("shashin"), "しゃしん", "romaji shashin fully");

        r = RomajiConverter.convert("matcha");
        eq(r.kana, "まっちゃ", "romaji matcha (tch sokuon)");

        // "tch" の途中 "tc" は pending になる (t が素通し表示にならない)
        r = RomajiConverter.convert("matc");
        eq(r.kana, "ま", "romaji matc kana");
        eq(r.pending, "tc", "romaji matc pending tc");

        r = RomajiConverter.convert("fa");
        eq(r.kana, "ふぁ", "romaji foreign fa");
        r = RomajiConverter.convert("ti");
        eq(r.kana, "てぃ", "romaji foreign ti (SPEC)");
        r = RomajiConverter.convert("di");
        eq(r.kana, "でぃ", "romaji foreign di (SPEC)");
        r = RomajiConverter.convert("wi");
        eq(r.kana, "うぃ", "romaji foreign wi");

        r = RomajiConverter.convert("xtu");
        eq(r.kana, "っ", "romaji xtu");
        r = RomajiConverter.convert("ltu");
        eq(r.kana, "っ", "romaji ltu");
        r = RomajiConverter.convert("axtu");
        eq(r.kana, "あっ", "romaji axtu");
        // 4文字キー xtsu / ltsu (最長一致の上限を 4 にした v1.1.1 の回帰テスト)
        r = RomajiConverter.convert("xtsu");
        eq(r.kana, "っ", "romaji xtsu");
        eq(r.pending, "", "romaji xtsu pending");
        r = RomajiConverter.convert("ltsu");
        eq(r.kana, "っ", "romaji ltsu");
        eq(RomajiConverter.convertFully("axtsu"), "あっ", "romaji axtsu fully");
        eq(RomajiConverter.convertFully("konnichiha"), "こんにちは",
            "romaji konnichiha unaffected by 4-char match");

        r = RomajiConverter.convert("k");
        eq(r.kana, "", "romaji k kana empty");
        eq(r.pending, "k", "romaji k pending");
        r = RomajiConverter.convert("ky");
        eq(r.pending, "ky", "romaji ky pending");
        r = RomajiConverter.convert("kak");
        eq(r.kana, "か", "romaji kak kana");
        eq(r.pending, "k", "romaji kak pending");
        r = RomajiConverter.convert("sh");
        eq(r.pending, "sh", "romaji sh pending");

        eq(RomajiConverter.convertFully("kan"), "かん", "convertFully kan flushes n");
        eq(RomajiConverter.convertFully("sensei"), "せんせい", "convertFully sensei");
        eq(RomajiConverter.convertFully("shinbun"), "しんぶん", "convertFully shinbun");
        eq(RomajiConverter.convertFully("zutto"), "ずっと", "convertFully zutto");
        eq(RomajiConverter.convertFully("kippu"), "きっぷ", "convertFully kippu");
        eq(RomajiConverter.convertFully("wo"), "を", "romaji wo");
        eq(RomajiConverter.convertFully("nn"), "ん", "romaji nn alone");
        eq(RomajiConverter.convertFully("jikan"), "じかん", "convertFully jikan");
        eq(RomajiConverter.convertFully("ryokou"), "りょこう", "convertFully ryokou");
        eq(RomajiConverter.convertFully("denwa"), "でんわ", "convertFully denwa (n before w)");
        eq(RomajiConverter.convertFully("genki"), "げんき", "convertFully genki");
        eq(RomajiConverter.convertFully("hon"), "ほん", "convertFully hon");
        eq(RomajiConverter.convertFully("xa"), "ぁ", "romaji xa small");
        eq(RomajiConverter.convertFully("ko-hi-"), "こーひー", "romaji hyphen is choon");
    }

    // ----- 1.2 KanaUtils -----
    private static void testKanaUtils() {
        eq(KanaUtils.toKatakana("がっこう"), "ガッコウ", "toKatakana gakkou");
        eq(KanaUtils.toKatakana("ぁっゔヶ"), "ァッヴヶ", "toKatakana small/vu/ke");
        eq(KanaUtils.toHiragana("ガッコウ"), "がっこう", "toHiragana gakkou");
        eq(KanaUtils.toHiragana("ヴ"), "ゔ", "toHiragana vu");
        eq(KanaUtils.toKatakana("あー"), "アー", "toKatakana keeps choon");

        check(KanaUtils.toggleDakuten('か') == 'が', "toggle ka->ga");
        check(KanaUtils.toggleDakuten('が') == 'か', "toggle ga->ka");
        check(KanaUtils.toggleDakuten('は') == 'ば', "toggle ha->ba");
        check(KanaUtils.toggleDakuten('ば') == 'ぱ', "toggle ba->pa");
        check(KanaUtils.toggleDakuten('ぱ') == 'は', "toggle pa->ha");
        check(KanaUtils.toggleDakuten('つ') == 'づ', "toggle tsu->zu");
        check(KanaUtils.toggleDakuten('づ') == 'っ', "toggle zu->small tsu");
        check(KanaUtils.toggleDakuten('っ') == 'つ', "toggle small tsu->tsu");
        check(KanaUtils.toggleDakuten('あ') == 'ぁ', "toggle a->small a");
        check(KanaUtils.toggleDakuten('ぁ') == 'あ', "toggle small a->a");
        check(KanaUtils.toggleDakuten('う') == 'ゔ', "toggle u->vu");
        check(KanaUtils.toggleDakuten('ん') == 'ん', "toggle n unchanged");
        check(KanaUtils.canToggle('か') && !KanaUtils.canToggle('ん'), "canToggle");

        check(KanaUtils.TOGGLE_ROWS.length == 10, "toggle rows 10");
        eq(KanaUtils.TOGGLE_ROWS[8], "らりるれろ", "toggle row ra");
        eq(KanaUtils.TOGGLE_ROWS[9], "わをんー〜", "toggle row wa");
        check(KanaUtils.rowIndexOf('き') == 1, "rowIndexOf ki");
        check(KanaUtils.rowIndexOf('ん') == 9, "rowIndexOf n");
        check(KanaUtils.rowIndexOf('x') == -1, "rowIndexOf none");

        // ユーザー辞書のよみ検証 (v1.1.1)
        check(KanaUtils.isValidReading("よるかな"), "validReading hiragana");
        check(KanaUtils.isValidReading("こーひー"), "validReading choon ok");
        check(KanaUtils.isValidReading("ひとびと"), "validReading plain ok");
        check(KanaUtils.isValidReading("とうきょう"), "validReading youon ok");
        check(!KanaUtils.isValidReading(""), "validReading empty ng");
        check(!KanaUtils.isValidReading(null), "validReading null ng");
        check(!KanaUtils.isValidReading("yoru"), "validReading alphabet ng");
        check(!KanaUtils.isValidReading("夜"), "validReading kanji ng");
        check(!KanaUtils.isValidReading("よる1"), "validReading digit ng");
        check(!KanaUtils.isValidReading("ヨルカナ"), "validReading katakana ng");

        check(KanaUtils.nextInRow(0, 'あ') == 'い', "nextInRow a->i");
        check(KanaUtils.nextInRow(0, 'お') == 'あ', "nextInRow o wraps to a");
        check(KanaUtils.nextInRow(7, 'よ') == 'や', "nextInRow yo wraps to ya");
        check(KanaUtils.nextInRow(9, '〜') == 'わ', "nextInRow wa row wraps");
    }

    // ----- 1.3 Dictionary / ConversionEngine -----
    private static void testDictionaryAndEngine() {
        Dictionary dict = new Dictionary();
        check(dict.size() >= 600, "dictionary size >= 600 (actual=" + dict.size() + ")");
        check(dict.lookup("がっこう").contains("学校"), "dict gakkou -> 学校");
        check(dict.lookup("せんせい").contains("先生"), "dict sensei -> 先生");
        check(dict.lookup("かいた").contains("書いた"), "dict godan ta-form kaita");
        check(dict.lookup("かきます").contains("書きます"), "dict godan masu-form");
        check(dict.lookup("たべた").contains("食べた"), "dict ichidan ta-form");
        check(dict.lookup("たべます").contains("食べます"), "dict ichidan masu-form");
        check(dict.lookup("たかかった").contains("高かった"), "dict i-adj past");
        check(dict.lookup("しずかな").contains("静かな"), "dict na-adj rentai");
        check(dict.lookup("いった").contains("行った"), "dict iku exception itta");
        // 減る は五段動詞 (一段に誤分類されていた v1.2.1 修正の回帰テスト)
        check(dict.lookup("へった").contains("減った"), "dict heru godan ta-form");
        check(dict.lookup("へらない").contains("減らない"), "dict heru godan nai-form");
        check(dict.lookup("へれる").contains("減れる"), "dict heru godan potential");
        check(!dict.lookup("へた").contains("減た"), "dict heru no wrong ichidan ta-form");
        check(!dict.lookup("へない").contains("減ない"), "dict heru no wrong ichidan nai-form");
        // 気持ちいい は「良い」の不規則活用 (よくない/よかった) で生成する
        // (I_ADJ 生成の きもちいくない 等の誤形が出ないことの回帰テスト)
        check(dict.lookup("きもちいい").contains("気持ちいい"), "dict kimochiii");
        check(dict.lookup("きもちよくない").contains("気持ちよくない"),
            "dict kimochiyokunai");
        check(dict.lookup("きもちよかった").contains("気持ちよかった"),
            "dict kimochiyokatta");
        check(!dict.lookup("きもちいくない").contains("気持ちいくない"),
            "dict no wrong kimochiikunai");
        check(dict.lookup("き").contains("木"), "dict single-kanji fallback ki");
        check(dict.lookup("そら").contains("空"), "dict sora");
        check(dict.has("がっこう") && !dict.has("ああああ"), "dict has");

        MemoryLearningStore learning = new MemoryLearningStore();
        ConversionEngine engine = new ConversionEngine(dict, learning);

        List<String> cands = engine.candidates("がっこう", "gakkou");
        check(cands.get(0).equals("学校"), "engine first candidate is kanji");
        check(cands.contains("ガッコウ"), "engine has katakana candidate");
        check(cands.contains("がっこう"), "engine has hiragana candidate");
        check(cands.contains("gakkou"), "engine has romaji candidate");
        check(cands.indexOf("学校") < cands.indexOf("ガッコウ")
            && cands.indexOf("ガッコウ") < cands.indexOf("がっこう")
            && cands.indexOf("がっこう") < cands.indexOf("gakkou"),
            "engine candidate order dict>katakana>hiragana>romaji");

        // 重複排除
        List<String> dup = engine.candidates("あめ", null);
        check(dup.stream().distinct().count() == dup.size(), "engine dedup");

        // 末尾助詞の簡易分離
        List<String> split = engine.candidates("がっこうは", null);
        check(split.get(0).equals("学校は"), "engine particle split gakkou+ha");
        List<String> split2 = engine.candidates("ねこが", null);
        check(split2.get(0).equals("猫が"), "engine particle split neko+ga");

        // 予測候補 (前方一致)
        List<String> pred = engine.predict("がっ", 8);
        check(pred.contains("学校"), "engine predict gakkou");
        check(engine.predict("", 8).isEmpty(), "engine predict empty prefix");
        List<String[]> entries = engine.predictEntries("がっ", 8);
        check(!entries.isEmpty() && entries.stream()
            .anyMatch(e -> e[0].equals("がっこう") && e[1].equals("学校")),
            "engine predictEntries pair");

        // 前方一致 (二分探索版): prefix より長く prefix で始まる読みだけが返る
        List<String> withPrefix = dict.readingsWithPrefix("がっ");
        check(withPrefix.contains("がっこう"), "dict prefix contains gakkou");
        check(withPrefix.stream().allMatch(k -> k.startsWith("がっ") && k.length() > 2),
            "dict prefix all match and longer");
        check(!dict.readingsWithPrefix("がっこう").contains("がっこう"),
            "dict prefix excludes exact key");
        check(dict.readingsWithPrefix("").isEmpty(), "dict prefix empty");

        // 学習: 頻度が候補順に反映される (はし は初期順 橋,箸,端。箸を学習させる)
        List<String> before = engine.candidates("はし", null);
        check(before.indexOf("橋") >= 0 && before.indexOf("橋") < before.indexOf("箸"),
            "engine hashi initial order");
        learning.recordSelection("はし", "箸");
        learning.recordSelection("はし", "箸");
        List<String> after = engine.candidates("はし", null);
        check(after.indexOf("箸") < after.indexOf("橋"), "learning promotes selected candidate");
        check(learning.getFrequency("はし", "箸") == 2, "learning frequency count");
        learning.clear();
        check(learning.getFrequency("はし", "箸") == 0, "learning clear");
        List<String> restored = engine.candidates("はし", null);
        check(restored.indexOf("橋") < restored.indexOf("箸"), "learning clear restores order");
    }

    // ----- 1.4 FlickResolver -----
    private static void testFlickResolver() {
        // 方向判定 (しきい値 24px)
        check(FlickResolver.resolveDirection(0, 0, 24) == FlickResolver.DIR_CENTER,
            "flick dir zero is center");
        // しきい値 0 でも変位ゼロは中央 (左と誤判定しない) — v1.2.1 回帰テスト
        check(FlickResolver.resolveDirection(0, 0, 0) == FlickResolver.DIR_CENTER,
            "flick dir zero with zero threshold is center");
        check(FlickResolver.resolveDirection(10, 5, 24) == FlickResolver.DIR_CENTER,
            "flick dir below threshold is center");
        check(FlickResolver.resolveDirection(30, 0, 24) == FlickResolver.DIR_RIGHT,
            "flick dir right");
        check(FlickResolver.resolveDirection(-30, 0, 24) == FlickResolver.DIR_LEFT,
            "flick dir left");
        check(FlickResolver.resolveDirection(0, 30, 24) == FlickResolver.DIR_DOWN,
            "flick dir down (dy positive)");
        check(FlickResolver.resolveDirection(0, -30, 24) == FlickResolver.DIR_UP,
            "flick dir up (dy negative)");
        check(FlickResolver.resolveDirection(30, 20, 24) == FlickResolver.DIR_RIGHT,
            "flick dir diagonal dominant x");
        check(FlickResolver.resolveDirection(20, 30, 24) == FlickResolver.DIR_DOWN,
            "flick dir diagonal dominant y");
        check(FlickResolver.resolveDirection(-30, -40, 24) == FlickResolver.DIR_UP,
            "flick dir diagonal up-left is up");

        // 標準行 (あ・か・さ・た・な・は・ま・ら) は 中央/左/上/右/下 = 段の1〜5文字目
        String[] standardRows = {
            "あいうえお", "かきくけこ", "さしすせそ", "たちつてと",
            "なにぬねの", "はひふへほ", "まみむめも", null, "らりるれろ",
        };
        int[] dirs = {FlickResolver.DIR_CENTER, FlickResolver.DIR_LEFT,
            FlickResolver.DIR_UP, FlickResolver.DIR_RIGHT, FlickResolver.DIR_DOWN};
        for (int row = 0; row < standardRows.length; row++) {
            if (standardRows[row] == null) continue;
            for (int d = 0; d < 5; d++) {
                check(FlickResolver.charAt(row, dirs[d]) == standardRows[row].charAt(d),
                    "flick char row=" + row + " dir=" + d);
                check(FlickResolver.isAssigned(row, dirs[d]),
                    "flick assigned row=" + row + " dir=" + d);
            }
        }

        // や行 (row=7): 中央=や 上=ゆ 下=よ、左右は未割り当て→中央にフォールバック
        check(FlickResolver.charAt(7, FlickResolver.DIR_CENTER) == 'や', "flick ya center");
        check(FlickResolver.charAt(7, FlickResolver.DIR_UP) == 'ゆ', "flick ya up is yu");
        check(FlickResolver.charAt(7, FlickResolver.DIR_DOWN) == 'よ', "flick ya down is yo");
        check(FlickResolver.charAt(7, FlickResolver.DIR_LEFT) == 'や',
            "flick ya left falls back to center");
        check(FlickResolver.charAt(7, FlickResolver.DIR_RIGHT) == 'や',
            "flick ya right falls back to center");
        check(!FlickResolver.isAssigned(7, FlickResolver.DIR_LEFT)
            && !FlickResolver.isAssigned(7, FlickResolver.DIR_RIGHT),
            "flick ya left/right unassigned");
        check(FlickResolver.isAssigned(7, FlickResolver.DIR_UP)
            && FlickResolver.isAssigned(7, FlickResolver.DIR_DOWN),
            "flick ya up/down assigned");

        // わ行 (row=9): 中央=わ 左=を 上=ん 右=ー 下=〜
        check(FlickResolver.charAt(9, FlickResolver.DIR_CENTER) == 'わ', "flick wa center");
        check(FlickResolver.charAt(9, FlickResolver.DIR_LEFT) == 'を', "flick wa left is wo");
        check(FlickResolver.charAt(9, FlickResolver.DIR_UP) == 'ん', "flick wa up is n");
        check(FlickResolver.charAt(9, FlickResolver.DIR_RIGHT) == 'ー', "flick wa right is choon");
        check(FlickResolver.charAt(9, FlickResolver.DIR_DOWN) == '〜', "flick wa down is nami");

        // 範囲外
        check(FlickResolver.charAt(-1, FlickResolver.DIR_CENTER) == '\0', "flick row -1 is nul");
        check(FlickResolver.charAt(10, FlickResolver.DIR_CENTER) == '\0', "flick row 10 is nul");
        check(!FlickResolver.isAssigned(10, FlickResolver.DIR_CENTER),
            "flick row 10 unassigned");
        check(FlickResolver.ROW_COUNT == 10, "flick row count 10");
    }

    // ----- 1.5 UserDictionary -----
    private static void testUserDictionary() {
        UserDictionary ud = new UserDictionary();
        check(ud.size() == 0 && !ud.has("ねこ"), "userdict empty");
        ud.add("ねこ", "猫又");
        ud.add("ねこ", "ネコチャン");
        ud.add("よるかな", "夜奏");
        ud.add("ねこ", "猫又"); // 重複は無視
        check(ud.size() == 3, "userdict size 3 (dup ignored)");
        check(ud.lookup("ねこ").equals(List.of("猫又", "ネコチャン")),
            "userdict lookup keeps order");
        check(ud.lookup("いぬ").isEmpty(), "userdict lookup missing is empty");
        ud.add("", "単語");
        ud.add("よみ", "");
        check(ud.size() == 3, "userdict ignores empty reading/word");

        // encode / decode 往復
        UserDictionary restored = UserDictionary.decode(ud.encode());
        check(restored.size() == 3, "userdict encode/decode size");
        check(restored.lookup("よるかな").equals(List.of("夜奏")),
            "userdict encode/decode content");
        check(UserDictionary.decode("").size() == 0, "userdict decode empty");
        check(UserDictionary.decode(null).size() == 0, "userdict decode null");

        // 削除
        check(ud.remove("ねこ", "猫又"), "userdict remove returns true");
        check(ud.lookup("ねこ").equals(List.of("ネコチャン")), "userdict after remove");
        check(!ud.remove("ねこ", "猫又"), "userdict remove missing returns false");
        check(ud.remove("ねこ", "ネコチャン") && !ud.has("ねこ"),
            "userdict reading removed when last word deleted");

        // 変換候補での優先順位: ユーザー辞書 > 学習 > 内蔵辞書
        Dictionary dict = new Dictionary();
        MemoryLearningStore learning = new MemoryLearningStore();
        UserDictionary user = new UserDictionary();
        user.add("はし", "マイ箸");
        user.add("よるかな", "夜奏");
        ConversionEngine engine = new ConversionEngine(dict, learning, user);

        // 学習で「箸」を強く学習させても、ユーザー辞書が先頭
        for (int i = 0; i < 5; i++) learning.recordSelection("はし", "箸");
        List<String> cands = engine.candidates("はし", null);
        check(cands.get(0).equals("マイ箸"), "userdict beats learning");
        check(cands.indexOf("マイ箸") < cands.indexOf("箸"), "userdict before learned word");

        // 内蔵辞書に無い読みでもユーザー辞書だけで候補が出る
        List<String> only = engine.candidates("よるかな", null);
        check(only.get(0).equals("夜奏"), "userdict-only reading candidate");

        // ユーザー単語が内蔵候補と重なる場合は重複しない (あめ→雨 は内蔵にもある)
        user.add("あめ", "雨");
        List<String> ame = engine.candidates("あめ", null);
        check(ame.get(0).equals("雨") && ame.stream().filter("雨"::equals).count() == 1,
            "userdict dedup with builtin");

        // setter での差し替え (IME が入力開始時に読み込む経路)
        ConversionEngine engine2 = new ConversionEngine(dict, learning);
        check(engine2.candidates("よるかな", null).get(0).equals("ヨルカナ"),
            "engine without userdict falls back to katakana");
        engine2.setUserDictionary(user);
        check(engine2.candidates("よるかな", null).get(0).equals("夜奏"),
            "engine setUserDictionary takes effect");
    }
}
