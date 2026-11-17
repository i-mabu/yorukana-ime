import jp.yorukana.ime.ConversionEngine;
import jp.yorukana.ime.Dictionary;
import jp.yorukana.ime.KanaUtils;
import jp.yorukana.ime.MemoryLearningStore;
import jp.yorukana.ime.RomajiConverter;

import java.util.List;

// よるかな IME 純ロジックの単体テスト (Android非依存クラスのみ)
// 実行: tests/run-tests.sh  (javac + java、件数を数えて全パスを確認する)
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
        System.out.println("ALL TESTS PASSED: " + passed + " checks");
    }

    // ----- RomajiConverter -----
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

    // ----- KanaUtils -----
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
        check(KanaUtils.nextInRow(0, 'あ') == 'い', "nextInRow a->i");
        check(KanaUtils.nextInRow(0, 'お') == 'あ', "nextInRow o wraps to a");
        check(KanaUtils.nextInRow(7, 'よ') == 'や', "nextInRow yo wraps to ya");
        check(KanaUtils.nextInRow(9, '〜') == 'わ', "nextInRow wa row wraps");
    }

    // ----- Dictionary / ConversionEngine -----
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
}
