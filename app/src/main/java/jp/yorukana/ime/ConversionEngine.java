package jp.yorukana.ime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

// ===== 1. 変換エンジン (純ロジック / Android非依存) =====
// 候補生成順: ①辞書候補 (学習頻度でソート) ②カタカナ ③ひらがな原文 ④ローマ字原文。
// 末尾助詞の簡易分離変換と、前方一致の予測候補も提供する。
public final class ConversionEngine {

    // 末尾助詞として分離を試みる文字
    private static final String PARTICLES = "はがをにへとのかもねよ";

    private final Dictionary dictionary;
    private final LearningStore learning;

    public ConversionEngine(Dictionary dictionary, LearningStore learning) {
        this.dictionary = dictionary;
        this.learning = learning;
    }

    public Dictionary dictionary() { return dictionary; }

    // 学習頻度が高い順に安定ソート
    private List<String> sortByFreq(String reading, List<String> cands) {
        List<String> out = new ArrayList<>(cands);
        out.sort(Comparator.comparingInt(
            (String c) -> learning != null ? learning.getFrequency(reading, c) : 0).reversed());
        return out;
    }

    // ----- 1.1 候補生成 -----
    // reading: ひらがな読み / romajiRaw: ローマ字原文 (かな入力時は null でよい)
    public List<String> candidates(String reading, String romajiRaw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (reading == null || reading.isEmpty()) return new ArrayList<>(out);

        List<String> dict = dictionary.lookup(reading);
        if (!dict.isEmpty()) {
            out.addAll(sortByFreq(reading, dict));
        } else {
            // 末尾助詞の簡易分離: 語幹が辞書にあれば 語幹候補+助詞 を提示
            String split = splitParticleCandidate(reading);
            if (split != null) {
                String stem = reading.substring(0, reading.length() - 1);
                String particle = reading.substring(reading.length() - 1);
                List<String> stemCands = sortByFreq(reading,
                    withSuffix(dictionary.lookup(stem), particle));
                out.addAll(stemCands);
            }
        }
        out.add(KanaUtils.toKatakana(reading)); // ②カタカナ
        out.add(reading);                       // ③ひらがな原文
        if (romajiRaw != null && !romajiRaw.isEmpty()) {
            out.add(romajiRaw);                 // ④ローマ字原文
        }
        return new ArrayList<>(out);
    }

    private static List<String> withSuffix(List<String> base, String suffix) {
        List<String> out = new ArrayList<>(base.size());
        for (String b : base) out.add(b + suffix);
        return out;
    }

    // 分離変換が可能なら語幹を返す (不可なら null)
    private String splitParticleCandidate(String reading) {
        if (reading.length() < 3) return null; // 語幹2文字以上を要求
        char last = reading.charAt(reading.length() - 1);
        if (PARTICLES.indexOf(last) < 0) return null;
        String stem = reading.substring(0, reading.length() - 1);
        return dictionary.has(stem) ? stem : null;
    }

    // ----- 1.2 予測候補 (前方一致) -----
    // prefix で始まる読みの代表候補を返す。頻度→読みの短さ の順。
    public List<String> predict(String prefix, int limit) {
        List<String> out = new ArrayList<>();
        if (prefix == null || prefix.isEmpty() || limit <= 0) return out;
        List<String> readings = dictionary.readingsWithPrefix(prefix);
        readings.sort((a, b) -> {
            int fa = bestFreq(a), fb = bestFreq(b);
            if (fa != fb) return Integer.compare(fb, fa);
            return Integer.compare(a.length(), b.length());
        });
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String r : readings) {
            List<String> cands = dictionary.lookup(r);
            if (cands.isEmpty()) continue;
            String best = sortByFreq(r, cands).get(0);
            if (seen.add(best)) out.add(best);
            if (out.size() >= limit) break;
        }
        return out;
    }

    // 予測候補を (読み, 候補) ペアで返す (学習記録に正しい読みを使うため)
    public List<String[]> predictEntries(String prefix, int limit) {
        List<String[]> out = new ArrayList<>();
        if (prefix == null || prefix.isEmpty() || limit <= 0) return out;
        List<String> readings = dictionary.readingsWithPrefix(prefix);
        readings.sort((a, b) -> {
            int fa = bestFreq(a), fb = bestFreq(b);
            if (fa != fb) return Integer.compare(fb, fa);
            return Integer.compare(a.length(), b.length());
        });
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String r : readings) {
            List<String> cands = dictionary.lookup(r);
            if (cands.isEmpty()) continue;
            String best = sortByFreq(r, cands).get(0);
            if (seen.add(best)) out.add(new String[]{r, best});
            if (out.size() >= limit) break;
        }
        return out;
    }

    private int bestFreq(String reading) {
        if (learning == null) return 0;
        int best = 0;
        for (String c : dictionary.lookup(reading)) {
            best = Math.max(best, learning.getFrequency(reading, c));
        }
        return best;
    }
}
