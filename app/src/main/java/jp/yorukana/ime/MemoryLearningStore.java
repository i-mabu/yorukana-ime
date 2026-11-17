package jp.yorukana.ime;

import java.util.HashMap;
import java.util.Map;

// ===== 1. 学習ストア (メモリ実装 / 純ロジック) =====
// テスト用および永続化レイヤの土台。キーは 読み + 区切り + 候補。
public class MemoryLearningStore implements LearningStore {

    static final String SEP = "\u0001";

    protected final Map<String, Integer> freq = new HashMap<>();

    static String key(String reading, String candidate) {
        return reading + SEP + candidate;
    }

    @Override
    public int getFrequency(String reading, String candidate) {
        Integer v = freq.get(key(reading, candidate));
        return v != null ? v : 0;
    }

    @Override
    public void recordSelection(String reading, String candidate) {
        String k = key(reading, candidate);
        freq.put(k, getFrequency(reading, candidate) + 1);
    }

    @Override
    public void clear() {
        freq.clear();
    }

    // 永続化レイヤが復元/保存に使う生マップ
    public Map<String, Integer> rawMap() {
        return freq;
    }
}
