package com.artemis.ondevice;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The wake word, which the user may change in Settings. The keyword spotter takes a phrase as
 * pinyin tokens (initial + tonal final, "n ǐ h ǎo ā t è @你好阿特"); {@link #keywordLine} turns
 * Chinese into that, from the bundled hanzi table (assets/pinyin.txt, scripts/make-pinyin-table.py).
 */
final class WakeWord {

    static final String DEFAULT = "你好阿特";
    static final int MIN_CHARS = 3, MAX_CHARS = 6;
    private static final String[] INITIALS = {"zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g",
            "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w"};
    /** Other assistants' wake words: they would wake too, and two assistants would answer. */
    private static final String[] TAKEN = {"小艺小艺", "小爱同学", "小度小度", "天猫精灵", "小布小布", "你好小布",
            "小欧小欧", "你好小欧", "小v小v", "你好小荣", "小荣小荣", "豆包豆包"};

    private static Map<String, String> pinyin;
    private static Set<String> tokens;

    private WakeWord() {}

    /**
     * The spotter's keyword line for the phrase. Throws IllegalArgumentException with a message
     * for the user when the phrase cannot be a wake word.
     */
    static synchronized String keywordLine(Context ctx, String phrase) {
        String p = phrase == null ? "" : phrase.trim().replaceAll("\\s+", "");
        if (p.length() < MIN_CHARS || p.length() > MAX_CHARS) {
            throw new IllegalArgumentException(ctx.getString(R.string.wake_word_length, MIN_CHARS, MAX_CHARS));
        }
        for (String taken : TAKEN) {
            if (p.toLowerCase(java.util.Locale.ROOT).equals(taken)) {
                throw new IllegalArgumentException(ctx.getString(R.string.wake_word_taken));
            }
        }
        try {
            load(ctx.getAssets());
        } catch (IOException e) {
            throw new IllegalArgumentException(ctx.getString(R.string.wake_word_no_models));
        }
        String[] syllables = new String[p.length()];
        for (int i = 0; i < p.length(); i++) {
            String ch = String.valueOf(p.charAt(i));
            syllables[i] = pinyin.get(ch);
            if (syllables[i] == null) throw new IllegalArgumentException(ctx.getString(R.string.wake_word_bad_char, ch));
            for (String token : split(syllables[i])) {
                if (!tokens.contains(token)) throw new IllegalArgumentException(ctx.getString(R.string.wake_word_bad_char, ch));
            }
        }
        String dictionary = tokensOf(syllables);
        String spoken = tokensOf(sandhi(syllables));
        // Both pronunciations, "/"-separated: 你好 is written nǐ hǎo but said ní hǎo.
        boolean usable = !spoken.equals(dictionary);
        for (String t : spoken.split(" ")) usable &= tokens.contains(t);
        return dictionary + " @" + p + (usable ? "/" + spoken + " @" + p : "");
    }

    private static String tokensOf(String[] syllables) {
        StringBuilder sb = new StringBuilder();
        for (String s : syllables) {
            for (String token : split(s)) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(token);
            }
        }
        return sb.toString();
    }

    private static final String THIRD = "ǎěǐǒǔǚ", SECOND = "áéíóúǘ";

    /** Third-tone sandhi: a third tone before another third tone is said as a second tone. */
    static String[] sandhi(String[] syllables) {
        String[] out = syllables.clone();
        for (int i = 0; i + 1 < out.length; i++) {
            if (third(syllables[i]) && third(syllables[i + 1])) {
                StringBuilder sb = new StringBuilder(out[i]);
                for (int k = 0; k < sb.length(); k++) {
                    int at = THIRD.indexOf(sb.charAt(k));
                    if (at >= 0) sb.setCharAt(k, SECOND.charAt(at));
                }
                out[i] = sb.toString();
            }
        }
        return out;
    }

    private static boolean third(String syllable) {
        for (int k = 0; k < syllable.length(); k++) if (THIRD.indexOf(syllable.charAt(k)) >= 0) return true;
        return false;
    }

    /** "hǎo" -> ["h", "ǎo"]; a syllable without an initial ("ā") stays whole. */
    static String[] split(String syllable) {
        for (String initial : INITIALS) {
            if (syllable.startsWith(initial) && syllable.length() > initial.length()) {
                return new String[]{initial, syllable.substring(initial.length())};
            }
        }
        return new String[]{syllable};
    }

    private static void load(AssetManager am) throws IOException {
        if (pinyin != null) return;
        Map<String, String> map = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(am.open("pinyin.txt"), StandardCharsets.UTF_8))) {
            for (String l; (l = r.readLine()) != null; ) {
                int tab = l.indexOf('\t');
                if (tab > 0 && !l.startsWith("#")) map.put(l.substring(0, tab), l.substring(tab + 1));
            }
        }
        Set<String> set = new HashSet<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(am.open("voice/kws/tokens.txt"), StandardCharsets.UTF_8))) {
            for (String l; (l = r.readLine()) != null; ) {
                int space = l.indexOf(' ');
                if (space > 0) set.add(l.substring(0, space));
            }
        }
        pinyin = map;
        tokens = set;
    }
}
