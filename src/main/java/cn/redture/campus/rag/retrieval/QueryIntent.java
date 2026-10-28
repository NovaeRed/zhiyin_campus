package cn.redture.campus.rag.retrieval;

import java.util.List;

/**
 * 保存一次语义理解得到的检索表达、业务任务和结构化条件。
 */
public record QueryIntent(
    String searchQuery, String domain, Task task, List<AcademicTarget> academicTargets, List<String> courseNames,
    Integer studyYear, Integer semester, String courseNature, boolean countCourses, boolean fallback,
    List<String> alternatives, String hypotheticalText) {

    public enum Task {SEARCH, COURSE_LIST, COURSE_FACT, CURRICULUM_COMPARE}

    /**
     * 记录专业、入学年份、毕业届别及其条件来源。
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
