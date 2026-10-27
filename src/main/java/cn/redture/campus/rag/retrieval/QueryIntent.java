package cn.redture.campus.rag.retrieval;

import java.util.List;

/**
 * 一次语义理解的结果；原问题、检索表达和业务条件分别保存，不再相互拼接。
 */
public record QueryIntent(
    String searchQuery, String domain, Task task, List<AcademicTarget> academicTargets, List<String> courseNames,
    Integer studyYear, Integer semester, String courseNature, boolean countCourses, boolean fallback,
    List<String> alternatives, String hypotheticalText) {

    public enum Task {SEARCH, COURSE_LIST, COURSE_FACT, CURRICULUM_COMPARE}

    /**
     * 条件来源用于追踪当前原文与已确认上下文，推测年份不能冒充明确入学年份。
     */
    public record AcademicTarget(String major, Integer enrollmentYear, String graduationCohort,
                                 String majorSource, String enrollmentSource) {
    }

    public boolean academic() {
        return task != Task.SEARCH;
    }

    public static QueryIntent search(String question) {
        return new QueryIntent(question, "general", Task.SEARCH, List.of(), List.of(), null, null, "ALL", false, true, List.of(), null);
    }
}
