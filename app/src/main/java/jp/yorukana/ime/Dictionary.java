package jp.yorukana.ime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ===== 1. 内蔵辞書 (純ロジック / Android非依存) =====
// DictionaryData の明示エントリに加え、動詞・形容詞の活用形を生成して登録する。
// 読み → 候補リスト のマップとして保持する。
public final class Dictionary {

    private final Map<String, List<String>> map = new LinkedHashMap<>();
    private final Map<String, List<String>> singleKanji = new HashMap<>();

    public Dictionary() {
        build();
    }

    // ----- 1.1 構築 -----
    private void add(String reading, String candidate) {
        if (reading == null || reading.isEmpty() || candidate == null || candidate.isEmpty()) return;
        List<String> list = map.computeIfAbsent(reading, k -> new ArrayList<>());
        if (!list.contains(candidate)) list.add(candidate);
    }

    private void build() {
        for (String e : DictionaryData.ENTRIES) {
            int eq = e.indexOf('=');
            if (eq < 0) continue;
            String reading = e.substring(0, eq);
            for (String cand : e.substring(eq + 1).split(",")) add(reading, cand);
        }
        for (String v : DictionaryData.GODAN) {
            int bar = v.indexOf('|');
            genGodan(v.substring(0, bar), v.substring(bar + 1));
        }
        for (String v : DictionaryData.ICHIDAN) {
            int bar = v.indexOf('|');
            genIchidan(v.substring(0, bar), v.substring(bar + 1));
        }
        for (String a : DictionaryData.I_ADJ) {
            int bar = a.indexOf('|');
            genIAdj(a.substring(0, bar), a.substring(bar + 1));
        }
        for (String a : DictionaryData.NA_ADJ) {
            int bar = a.indexOf('|');
            genNaAdj(a.substring(0, bar), a.substring(bar + 1));
        }
        for (String e : DictionaryData.SINGLE_KANJI) {
            int eq = e.indexOf('=');
            if (eq < 0) continue;
            List<String> list = new ArrayList<>();
            Collections.addAll(list, e.substring(eq + 1).split(","));
            singleKanji.put(e.substring(0, eq), list);
        }
    }

    // 五段動詞の活用行: 語尾 → {い段, あ段, え段, お段, た形語尾, て形語尾}
    private static final Map<Character, String[]> GODAN_ROWS = new HashMap<>();
    static {
        GODAN_ROWS.put('く', new String[]{"き", "か", "け", "こ", "いた", "いて"});
        GODAN_ROWS.put('ぐ', new String[]{"ぎ", "が", "げ", "ご", "いだ", "いで"});
        GODAN_ROWS.put('す', new String[]{"し", "さ", "せ", "そ", "した", "して"});
        GODAN_ROWS.put('つ', new String[]{"ち", "た", "て", "と", "った", "って"});
        GODAN_ROWS.put('る', new String[]{"り", "ら", "れ", "ろ", "った", "って"});
        GODAN_ROWS.put('う', new String[]{"い", "わ", "え", "お", "った", "って"});
        GODAN_ROWS.put('ぬ', new String[]{"に", "な", "ね", "の", "んだ", "んで"});
        GODAN_ROWS.put('ぶ', new String[]{"び", "ば", "べ", "ぼ", "んだ", "んで"});
        GODAN_ROWS.put('む', new String[]{"み", "ま", "め", "も", "んだ", "んで"});
    }

    private void genGodan(String reading, String kanji) {
        if (reading.length() < 2 || kanji.length() < 2) return;
        char last = reading.charAt(reading.length() - 1);
        String[] row = GODAN_ROWS.get(last);
        if (row == null) return;
        String rs = reading.substring(0, reading.length() - 1); // 読みの語幹
        String ks = kanji.substring(0, kanji.length() - 1);     // 漢字形の語幹
        add(reading, kanji);                                    // 辞書形
        add(rs + row[0] + "ます", ks + row[0] + "ます");        // ます形
        add(rs + row[0] + "ました", ks + row[0] + "ました");    // ました
        add(rs + row[0] + "たい", ks + row[0] + "たい");        // たい
        add(rs + row[0] + "た", ks + row[0] + "た");            // 連用 (た の前段)
        String taR, teR, taK, teK;
        if (reading.equals("いく")) { // 行く は例外 (いった/いって)
            taR = "いった"; teR = "いって"; taK = "行った"; teK = "行って";
        } else {
            taR = rs + row[4]; teR = rs + row[5]; taK = ks + row[4]; teK = ks + row[5];
        }
        add(taR, taK);                                          // た形
        add(teR, teK);                                          // て形
        add(rs + row[1] + "ない", ks + row[1] + "ない");        // ない形
        add(rs + row[2] + "る", ks + row[2] + "る");            // 可能形
        add(rs + row[3] + "う", ks + row[3] + "う");            // 意向形
    }

    private void genIchidan(String reading, String kanji) {
        if (reading.length() < 2 || !reading.endsWith("る")) return;
        String rs = reading.substring(0, reading.length() - 1);
        String ks = kanji.substring(0, kanji.length() - 1);
        add(reading, kanji);                                    // 辞書形
        add(rs + "ます", ks + "ます");
        add(rs + "ました", ks + "ました");
        add(rs + "た", ks + "た");                              // た形
        add(rs + "て", ks + "て");                              // て形
        add(rs + "ない", ks + "ない");                          // ない形
        add(rs + "たい", ks + "たい");                          // たい
        add(rs + "られる", ks + "られる");                      // 可能/受身
        add(rs + "よう", ks + "よう");                          // 意向形
    }

    private void genIAdj(String reading, String kanji) {
        if (reading.length() < 2 || !reading.endsWith("い")) return;
        String rs = reading.substring(0, reading.length() - 1);
        String ks = kanji.substring(0, kanji.length() - 1);
        add(reading, kanji);                                    // 終止形
        add(rs + "くない", ks + "くない");                      // 否定
        add(rs + "かった", ks + "かった");                      // 過去
        add(rs + "くて", ks + "くて");                          // て形
        add(rs + "く", ks + "く");                              // 連用
        add(rs + "さ", ks + "さ");                              // 名詞化 (高さ等)
    }

    private void genNaAdj(String reading, String kanji) {
        add(reading, kanji);                                    // 語幹
        add(reading + "な", kanji + "な");                      // 連体
        add(reading + "に", kanji + "に");                      // 連用
        add(reading + "だ", kanji + "だ");                      // 終止
        add(reading + "です", kanji + "です");                  // 丁寧
        add(reading + "だった", kanji + "だった");              // 過去
    }

    // ----- 1.2 検索 -----
    public boolean has(String reading) {
        return map.containsKey(reading);
    }

    // 読みに対する候補 (無ければ空リスト)。単漢字フォールバックも含む。
    public List<String> lookup(String reading) {
        List<String> out = new ArrayList<>();
        List<String> hit = map.get(reading);
        if (hit != null) out.addAll(hit);
        if (out.isEmpty() && reading.length() == 1) {
            List<String> sk = singleKanji.get(reading);
            if (sk != null) out.addAll(sk);
        }
        return out;
    }

    // 前方一致する読みの一覧 (予測変換用)
    public List<String> readingsWithPrefix(String prefix) {
        List<String> out = new ArrayList<>();
        if (prefix == null || prefix.isEmpty()) return out;
        for (String key : map.keySet()) {
            if (key.length() > prefix.length() && key.startsWith(prefix)) out.add(key);
        }
        return out;
    }

    // 登録されている読みの総数 (辞書語数)
    public int size() {
        return map.size();
    }
}
