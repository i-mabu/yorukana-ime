package jp.yorukana.ime;

import java.util.HashMap;
import java.util.Map;

// ===== 1. かなユーティリティ (純ロジック / Android非依存) =====
// ひらがな⇔カタカナ変換、濁点/半濁点/小書きトグル、12キー段循環表。
public final class KanaUtils {

    // ----- 1.1 ひらがな ⇔ カタカナ -----
    // ぁ(0x3041)〜ゖ(0x3096) と ァ(0x30A1)〜ヶ(0x30F6) は 0x60 差で対応する。
    public static String toKatakana(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'ぁ' && c <= 'ゖ') c = (char) (c + 0x60);
            sb.append(c);
        }
        return sb.toString();
    }

    public static String toHiragana(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'ァ' && c <= 'ヶ') c = (char) (c - 0x60);
            sb.append(c);
        }
        return sb.toString();
    }

    // ----- 1.2 濁点・半濁点・小書きトグル -----
    // 1文字を次の形へ循環させる。対象外の文字はそのまま返す。
    // 例: か→が→か / は→ば→ぱ→は / つ→づ→っ→つ / あ→ぁ→あ / う→ゔ→ぅ→う
    private static final Map<Character, Character> TOGGLE = new HashMap<>();

    private static void cycle(char... chars) {
        for (int i = 0; i < chars.length; i++) {
            TOGGLE.put(chars[i], chars[(i + 1) % chars.length]);
        }
    }

    static {
        cycle('か', 'が'); cycle('き', 'ぎ'); cycle('く', 'ぐ'); cycle('け', 'げ'); cycle('こ', 'ご');
        cycle('さ', 'ざ'); cycle('し', 'じ'); cycle('す', 'ず'); cycle('せ', 'ぜ'); cycle('そ', 'ぞ');
        cycle('た', 'だ'); cycle('ち', 'ぢ'); cycle('て', 'で'); cycle('と', 'ど');
        cycle('は', 'ば', 'ぱ'); cycle('ひ', 'び', 'ぴ'); cycle('ふ', 'ぶ', 'ぷ');
        cycle('へ', 'べ', 'ぺ'); cycle('ほ', 'ぼ', 'ぽ');
        cycle('つ', 'づ', 'っ');
        cycle('う', 'ゔ', 'ぅ'); // う→ゔ→ぅ→う
        cycle('あ', 'ぁ'); cycle('い', 'ぃ');
        cycle('え', 'ぇ'); cycle('お', 'ぉ');
        cycle('や', 'ゃ'); cycle('ゆ', 'ゅ'); cycle('よ', 'ょ');
        cycle('わ', 'ゎ');
    }

    public static char toggleDakuten(char c) {
        Character next = TOGGLE.get(c);
        return next != null ? next : c;
    }

    public static boolean canToggle(char c) {
        return TOGGLE.containsKey(c);
    }

    // ----- 1.3 12キー (トグル打ち) の段循環表 -----
    // 行の先頭文字がキー代表。タップ回数でこの順に循環する。
    public static final String[] TOGGLE_ROWS = {
        "あいうえお", // あ行
        "かきくけこ", // か行
        "さしすせそ", // さ行
        "たちつてと", // た行
        "なにぬねの", // な行
        "はひふへほ", // は行
        "まみむめも", // ま行
        "やゆよ",     // や行
        "らりるれろ", // ら行
        "わをんー〜", // わ行
    };

    public static final String[] TOGGLE_KEY_LABELS = {
        "あ", "か", "さ", "た", "な", "は", "ま", "や", "ら", "わ",
    };

    // 文字が属する行のインデックス (属さなければ -1)
    public static int rowIndexOf(char c) {
        for (int r = 0; r < TOGGLE_ROWS.length; r++) {
            if (TOGGLE_ROWS[r].indexOf(c) >= 0) return r;
        }
        return -1;
    }

    // 行内での次の文字 (末尾なら先頭へ)
    public static char nextInRow(int row, char current) {
        String r = TOGGLE_ROWS[row];
        int idx = r.indexOf(current);
        if (idx < 0) return r.charAt(0);
        return r.charAt((idx + 1) % r.length());
    }
}
