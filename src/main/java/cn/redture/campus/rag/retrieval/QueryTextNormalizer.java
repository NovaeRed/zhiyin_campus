package cn.redture.campus.rag.retrieval;

import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class QueryTextNormalizer {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s,，。？?；;：:、.!！()（）\\[\\]【】\"'“”‘’]+");
    private static final Pattern QUESTION_WORDS = Pattern.compile(
            "(请问|请|帮我|一下|有哪些|有什么|是什么|应该|怎样|怎么|如何|最多|多少|是否|可以|能不能|需要|进行|相关|关于|什么|是几|几|是|吗|呢|的|了|以及)");

    private QueryTextNormalizer() {
    }

    public static Set<String> terms(String text) {
        return new LinkedHashSet<>(keywords(text));
    }

    public static List<String> keywords(String text) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        for (String part : phrases(text)) {
            addKeywordCandidates(keywords, part);
        }
        return keywords.stream().limit(256).toList();
    }

    public static List<String> phrases(String text) {
        if (!StringUtils.hasText(text)) return List.of();
        String normalized = SEPARATORS.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ");
        return java.util.Arrays.stream(QUESTION_WORDS.matcher(normalized).replaceAll(" ").trim().split("\\s+"))
                .filter(p -> p.length() >= 2).toList();
    }

    private static void addKeywordCandidates(Set<String> keywords, String text) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        for (String phrase : QUESTION_WORDS.matcher(text).replaceAll(" ").trim().split("\\s+")) {
            if (phrase.length() < 2) {
                continue;
            }
            keywords.add(phrase);
            addChineseNgrams(keywords, phrase);
        }
    }

    private static void addChineseNgrams(Set<String> keywords, String phrase) {
        if (!containsChinese(phrase) || phrase.length() < 4) {
            return;
        }
        int maxLength = Math.min(8, phrase.length());
        for (int length = maxLength; length >= 2; length--) {
            for (int start = 0; start + length <= phrase.length(); start++) {
                keywords.add(phrase.substring(start, start + length));
            }
        }
    }

    private static boolean containsChinese(String text) {
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(text.charAt(i));
            if (script == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }
}
