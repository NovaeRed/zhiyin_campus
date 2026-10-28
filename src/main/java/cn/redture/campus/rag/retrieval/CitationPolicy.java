package cn.redture.campus.rag.retrieval;

import java.util.regex.Pattern;

public final class CitationPolicy {
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)\\]");

    private CitationPolicy() {
    }

    public static boolean isRefusal(String answer) {
        return answer != null && answer.matches("^\\s*根据现有资料无法回答[。.!！\\s]*$");
    }

    /**
     * 允许标准拒答；普通回答须至少引用一个来源，且所有编号都在当前来源范围内。
     *
     * @param answer      待发布的完整回答
     * @param sourceCount 当前来源列表长度，合法编号从一开始
     * @return 回答是否满足非空及引用编号约束
     */
    public static boolean valid(String answer, int sourceCount) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        if (isRefusal(answer)) {
            return true;
        }
        var citations = CITATION.matcher(answer);
        boolean found = false;
        while (citations.find()) {
            found = true;
            try {
                int number = Integer.parseInt(citations.group(1));
                if (number < 1 || number > sourceCount) {
                    return false;
                }
            } catch (NumberFormatException invalidNumber) {
                return false;
            }
        }
        return found;
    }
}
