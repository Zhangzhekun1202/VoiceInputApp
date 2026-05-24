package com.example.voiceinputapp;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;

final class SimplePinyinDecoder {

    private static final String TAG = "SimplePinyinDecoder";
    private static final String LEXICON_ASSET = "pinyin_lexicon.txt";
    private static final int MAX_CANDIDATES = 120;
    private static final String[] RIME_ICE_CORE_LEXICONS = new String[] {
            "cn_dicts/8105.dict.yaml",
            "cn_dicts/base.dict.yaml"
    };
    private static final String[] RIME_ICE_EXTRA_LEXICONS = new String[] {
            "cn_dicts/ext.dict.yaml",
            "cn_dicts/tencent.dict.yaml",
            "cn_dicts/others.dict.yaml"
    };
    private static final Map<String, List<String>> FULL_PHRASES = new LinkedHashMap<>();
    private static final Map<String, List<String>> SYLLABLES = new LinkedHashMap<>();
    private static final Map<String, List<String>> PRIORITY_CANDIDATES = createPriorityCandidates();
    private static boolean coreLoaded;
    private static boolean fullyLoaded;
    private static boolean loadingStarted;

    private SimplePinyinDecoder() {
    }

    static void preloadAsync(Context context, Runnable onCoreLoaded, Runnable onFullyLoaded) {
        boolean shouldStart;
        synchronized (SimplePinyinDecoder.class) {
            shouldStart = !loadingStarted;
            if (coreLoaded && onCoreLoaded != null) {
                onCoreLoaded.run();
            }
            if (fullyLoaded && onFullyLoaded != null) {
                onFullyLoaded.run();
            }
            if (loadingStarted) {
                return;
            }
            loadingStarted = true;
        }

        if (!shouldStart) {
            return;
        }

        Thread preloadThread = new Thread(() -> {
            loadRimeIceLexicons(context, RIME_ICE_CORE_LEXICONS);
            synchronized (SimplePinyinDecoder.class) {
                coreLoaded = true;
                Log.d(TAG, "Core pinyin lexicon ready: phrases=" + FULL_PHRASES.size()
                        + ", syllables=" + SYLLABLES.size());
            }
            if (onCoreLoaded != null) {
                onCoreLoaded.run();
            }

            loadRimeIceLexicons(context, RIME_ICE_EXTRA_LEXICONS);
            loadFallbackLexicon(context);
            synchronized (SimplePinyinDecoder.class) {
                fullyLoaded = true;
                Log.d(TAG, "Full pinyin lexicon ready: phrases=" + FULL_PHRASES.size()
                        + ", syllables=" + SYLLABLES.size());
            }
            if (onFullyLoaded != null) {
                onFullyLoaded.run();
            }
        }, "pinyin-lexicon-preload");
        preloadThread.setDaemon(true);
        preloadThread.start();
    }

    static synchronized List<String> getCandidates(Context context, String input) {
        if (input == null) {
            return Collections.emptyList();
        }
        String normalized = input.trim().toLowerCase(Locale.US);
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> ordered = new LinkedHashSet<>();
        addAll(ordered, FULL_PHRASES.get(normalized));
        addAll(ordered, SYLLABLES.get(normalized));

        for (Map.Entry<String, List<String>> entry : FULL_PHRASES.entrySet()) {
            if (entry.getKey().startsWith(normalized)) {
                addAll(ordered, entry.getValue());
            }
        }
        for (Map.Entry<String, List<String>> entry : SYLLABLES.entrySet()) {
            if (entry.getKey().startsWith(normalized)) {
                addAll(ordered, entry.getValue());
            }
        }

        List<String> result = new ArrayList<>(ordered);
        prioritize(normalized, result);
        if (result.size() > MAX_CANDIDATES) {
            return new ArrayList<>(result.subList(0, MAX_CANDIDATES));
        }
        return result;
    }

    private static void prioritize(String normalized, List<String> candidates) {
        List<String> priority = PRIORITY_CANDIDATES.get(normalized);
        Map<String, Integer> priorityIndex = new LinkedHashMap<>();
        if (priority != null) {
            for (int index = 0; index < priority.size(); index++) {
                priorityIndex.put(priority.get(index), index);
            }
        }

        candidates.sort(Comparator
                .comparingInt((String value) -> priorityIndex.getOrDefault(value, Integer.MAX_VALUE))
                .thenComparingInt(SimplePinyinDecoder::commonUsageScore)
                .thenComparingInt(String::length));
    }

    private static int commonUsageScore(String value) {
        if (value == null || value.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        String commonChars = "的一是在不了有人我他这中大来上个国到说们为子和你地出道也时年得就那要下以生会自着去之过家学对可她里后小么心多天而能好都然没日于起还发成事只作当想看文无开手十用主行方又如前所本见经头面公同三已老从动两长知民样现分将外但高";
        char first = value.charAt(0);
        int rank = commonChars.indexOf(first);
        return rank >= 0 ? rank : 1000 + value.length();
    }

    private static Map<String, List<String>> createPriorityCandidates() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        putPriority(map, "zhang", "张", "长", "章", "涨", "掌", "丈");
        putPriority(map, "shi", "是", "时", "事", "市", "十", "使", "世", "式", "识");
        putPriority(map, "li", "里", "理", "力", "利", "立", "李", "例", "礼", "历");
        putPriority(map, "de", "的", "得", "地");
        putPriority(map, "zai", "在", "再", "载");
        putPriority(map, "zhe", "这", "着", "者", "折");
        putPriority(map, "na", "那", "哪", "拿", "呢");
        putPriority(map, "wo", "我", "握", "窝");
        putPriority(map, "ni", "你", "呢", "拟", "逆");
        putPriority(map, "ta", "他", "她", "它", "塔");
        putPriority(map, "women", "我们");
        putPriority(map, "nimen", "你们");
        putPriority(map, "tamen", "他们", "她们");
        putPriority(map, "zhong", "中", "种", "重", "钟");
        putPriority(map, "guo", "国", "过", "果", "锅");
        putPriority(map, "ren", "人", "任", "认", "仁");
        putPriority(map, "shenme", "什么");
        putPriority(map, "zenme", "怎么");
        putPriority(map, "keyi", "可以");
        putPriority(map, "yinwei", "因为");
        putPriority(map, "suoyi", "所以");
        putPriority(map, "yijing", "已经");
        putPriority(map, "xianzai", "现在");
        return map;
    }

    private static void putPriority(Map<String, List<String>> map, String key, String... values) {
        List<String> ordered = new ArrayList<>();
        Collections.addAll(ordered, values);
        map.put(key, ordered);
    }

    private static synchronized void parseLine(String rawLine) {
        String line = rawLine == null ? "" : rawLine.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }

        String[] parts = line.split("\\|");
        if (parts.length < 3) {
            return;
        }

        String type = parts[0].trim();
        String key = parts[1].trim().toLowerCase(Locale.US);
        if (key.isEmpty()) {
            return;
        }

        List<String> values = new ArrayList<>();
        for (int index = 2; index < parts.length; index++) {
            String value = parts[index].trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            return;
        }

        if ("P".equals(type)) {
            appendValues(FULL_PHRASES, key, values);
        } else if ("S".equals(type)) {
            appendValues(SYLLABLES, key, values);
        }
    }

    private static void addAll(Set<String> target, List<String> values) {
        if (values == null) {
            return;
        }
        target.addAll(values);
    }

    private static void loadRimeIceLexicons(Context context, String[] assetPaths) {
        for (String assetPath : assetPaths) {
            loadSingleRimeLexicon(context, assetPath);
        }
    }

    private static void loadFallbackLexicon(Context context) {
        try (InputStream inputStream = context.getAssets().open(LEXICON_ASSET);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                parseLine(line);
            }
        } catch (IOException exception) {
            Log.e(TAG, "Failed to load fallback offline pinyin lexicon", exception);
        }
    }

    private static void loadSingleRimeLexicon(Context context, String assetPath) {
        try (InputStream inputStream = context.getAssets().open(assetPath);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            boolean inEntries = false;
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!inEntries) {
                    if ("...".equals(trimmed)) {
                        inEntries = true;
                    }
                    continue;
                }
                parseRimeEntry(trimmed);
            }
            Log.d(TAG, "Loaded Rime lexicon asset: " + assetPath);
        } catch (IOException exception) {
            Log.w(TAG, "Failed to load Rime lexicon asset: " + assetPath, exception);
        }
    }

    private static synchronized void parseRimeEntry(String line) {
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }

        String[] parts = line.split("\\s+");
        if (parts.length < 2) {
            return;
        }

        String word = parts[0].trim();
        if (word.isEmpty()) {
            return;
        }

        StringBuilder keyBuilder = new StringBuilder();
        for (int index = 1; index < parts.length; index++) {
            String token = parts[index].trim().toLowerCase(Locale.US);
            if (token.isEmpty() || isNumericToken(token)) {
                continue;
            }
            for (int i = 0; i < token.length(); i++) {
                char ch = token.charAt(i);
                if (ch >= 'a' && ch <= 'z') {
                    keyBuilder.append(ch);
                }
            }
        }

        String key = keyBuilder.toString();
        if (key.isEmpty()) {
            return;
        }

        if (word.length() == 1) {
            appendValues(SYLLABLES, key, Collections.singletonList(word));
        } else {
            appendValues(FULL_PHRASES, key, Collections.singletonList(word));
        }
    }

    private static boolean isNumericToken(String token) {
        for (int index = 0; index < token.length(); index++) {
            if (!Character.isDigit(token.charAt(index))) {
                return false;
            }
        }
        return !token.isEmpty();
    }

    private static void appendValues(Map<String, List<String>> target, String key, List<String> values) {
        List<String> existing = target.get(key);
        if (existing == null) {
            existing = new ArrayList<>();
            target.put(key, existing);
        }
        for (String value : values) {
            if (!existing.contains(value)) {
                existing.add(value);
            }
        }
    }
}
