package cn.redture.campus.rag.service;

import cn.redture.campus.rag.repository.KnowledgeRepository;
import cn.redture.campus.rag.retrieval.CourseSchedule;
import cn.redture.campus.rag.retrieval.CurriculumDocument;
import cn.redture.campus.rag.retrieval.CurriculumMetadata;
import cn.redture.campus.rag.retrieval.QueryIntent;
import cn.redture.campus.rag.retrieval.RetrievedChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 培养方案专用执行器；只消费结构化条件，不再次解释自然语言。
 */
@Service
public class CurriculumQueryService {
    private final KnowledgeRepository repository;

    public CurriculumQueryService(KnowledgeRepository repository) {
        this.repository = repository;
    }

    public record Result(String answer, List<RetrievedChunk> sources) {
    }

    public List<CurriculumDocument> candidates(QueryIntent.AcademicTarget target, List<CurriculumDocument> catalog) {
        return catalog.stream()
            .filter(d -> target.major() == null || target.major().equals(CurriculumMetadata.majorOf(d)))
            .filter(d -> target.enrollmentYear() == null || target.enrollmentYear().equals(d.getTargetGrade()))
            .filter(d -> target.graduationCohort() == null || d.getTargetCohort() == null || target.graduationCohort().equals(d.getTargetCohort()))
            .toList();
    }

    public Result execute(QueryIntent plan, List<CurriculumDocument> catalog) {
        var sources = new ArrayList<RetrievedChunk>();
        var answers = new ArrayList<String>();
        for (var target : plan.academicTargets()) {
            var candidates = candidates(target, catalog);
            if (candidates.isEmpty() && target.major() != null) {
                return new Result("根据现有资料无法回答。", List.of());
            }
            if (target.major() == null) {
                return new Result("请补充要查询的专业，以确定适用的培养方案。", List.of());
            }
            if (target.enrollmentYear() == null) {
                // 只使用原文明示的届别映射，不从毕业年份减去假定学制。
                candidates = candidates.stream().filter(d -> target.graduationCohort() != null
                    && target.graduationCohort().equals(d.getTargetCohort())).toList();
                if (candidates.isEmpty()) {
                    return new Result(target.graduationCohort() == null
                        ? "请补充入学年份，以确定适用的培养方案。"
                        : "已按" + target.graduationCohort() + "毕业理解。现有培养方案目录未明确该届别对应的入学年份，请补充入学年份。", List.of());
                }
            }
            if (candidates.size() != 1) {
                return new Result("该范围存在多份培养方案，请补充入学年份或文档版本。", List.of());
            }
            if (plan.semester() != null && plan.studyYear() == null) {
                return new Result("已确定培养方案，请补充要查询大几的学期课程。", List.of());
            }
            var doc = candidates.getFirst();
            var result = CourseSchedule.answer(doc.getTargetGrade(), plan.studyYear(), plan.semester(),
                plan.courseNature(), plan.countCourses(), repository.curriculumCourseChunks(doc.getDocId()));
            if (result.sources().isEmpty()) {
                return new Result(result.answer(), List.of());
            }
            int offset = sources.size();
            String adjusted = Pattern.compile("\\[(\\d+)\\]").matcher(result.answer())
                .replaceAll(match -> "[" + (Integer.parseInt(match.group(1)) + offset) + "]");
            answers.add("【" + doc.getTitle() + "】\n" + adjusted);
            sources.addAll(result.sources());
        }
        if (answers.isEmpty()) {
            return new Result("根据现有资料无法回答。", List.of());
        }
        return new Result(String.join("\n\n", answers), List.copyOf(sources));
    }
}
