package cn.redture.campus.rag.service;

import cn.redture.campus.config.RagProperties;
import cn.redture.campus.pojo.dto.RagAskRequestDTO;
import cn.redture.campus.pojo.vo.RagAnswerResponseVO;
import cn.redture.campus.pojo.vo.RagSourceVO;
import cn.redture.campus.rag.repository.KnowledgeRepository;
import cn.redture.campus.rag.retrieval.Reranker;
import cn.redture.campus.rag.retrieval.RetrievedChunk;
import cn.redture.campus.rag.retrieval.RetrievalQueryPlan;
import cn.redture.campus.rag.retrieval.AcademicScope;
import cn.redture.campus.rag.retrieval.RankFusion;
import cn.redture.campus.rag.retrieval.CurriculumRoute;
import cn.redture.campus.rag.retrieval.EvidencePolicy;
import cn.redture.campus.rag.retrieval.CourseEvidence;
import cn.redture.campus.rag.retrieval.CitationPolicy;
import cn.redture.campus.rag.retrieval.CourseSchedule;
import cn.redture.campus.util.SecurityContextHolderUtil;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class RagQueryOrchestrator {

    private static final String PROMPT_TEMPLATE = "campus-rag-v2";

    private final RagProperties properties;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final QwenEmbeddingService embeddingService;
    private final KnowledgeRepository repository;
    private final Reranker reranker;
    private final RetrievalQueryPlanner queryPlanner;

    public RagQueryOrchestrator(RagProperties properties,
                                ObjectProvider<ChatModel> chatModelProvider,
                                QwenEmbeddingService embeddingService,
                                KnowledgeRepository repository,
                                Reranker reranker,
                                RetrievalQueryPlanner queryPlanner) {
        this.properties = properties;
        this.chatModelProvider = chatModelProvider;
        this.embeddingService = embeddingService;
        this.repository = repository;
        this.reranker = reranker;
        this.queryPlanner = queryPlanner;
    }

    public RagAnswerResponseVO ask(RagAskRequestDTO request) {
        if (!StringUtils.hasText(request.getQuestion())) {
            throw new IllegalArgumentException("Question cannot be empty");
        }
        AcademicScope scope = AcademicScope.resolve(request.getQuestion(), request.getTargetGrade(), request.getTargetCohort());
        Long userId = SecurityContextHolderUtil.getUserId();
        var catalog = repository.activeCurricula();
        String effectiveMajor = CurriculumRoute.effectiveMajor(request.getQuestion(), request.getTargetMajor(), catalog);
        CurriculumRoute route = CurriculumRoute.resolve(request.getQuestion(), scope, catalog);
        Long sessionId = repository.ensureSession(userId, request.getSessionId());

        boolean scheduleRequested = CourseSchedule.requested(request.getQuestion());
        if (route.unavailable() || (scheduleRequested && effectiveMajor != null
                && CurriculumRoute.forMajor(effectiveMajor, scope, catalog).unavailable())) {
            return directAnswer(request, sessionId, "根据现有资料无法回答。", List.of(), "evidence-refusal-v1");
        }
        if (scope.cohort() != null && scope.grade() == null && request.getQuestion().matches("(?s).*(培养|课程|修什么课|修哪些课).*")) {
            return directAnswer(request, sessionId, "你指的是" + scope.cohort() + "毕业，还是同年份入学的“级”？当前培养方案按入学年份管理，请明确入学年份和专业，不能将“届”直接当作“级”。",
                    List.of(), "academic-scope-clarification-v1");
        }
        if (scheduleRequested) {
            if (effectiveMajor == null || scope.grade() == null) return directAnswer(request, sessionId,
                    "请明确专业和入学年份（例如2024级），以确定要查询的大三课程清单。", List.of(), "academic-scope-clarification-v1");
            var majorRoute = CurriculumRoute.forMajor(effectiveMajor, scope, catalog);
            var documents = catalog.stream().filter(d -> majorRoute.docIds().contains(d.getDocId()))
                    .filter(d -> scope.grade() != null && scope.grade().equals(d.getTargetGrade()))
                    .filter(d -> route.docIds() == null || route.docIds().contains(d.getDocId())).toList();
            if (documents.isEmpty()) return directAnswer(request, sessionId, "根据现有资料无法回答。", List.of(), "evidence-refusal-v1");
            if (documents.size() > 1) return directAnswer(request, sessionId, "该专业和入学年份存在多份培养方案，请补充文档版本以确定课程清单。",
                    List.of(), "academic-scope-clarification-v1");
            var schedule = CourseSchedule.answer(request.getQuestion(), scope.grade(), repository.curriculumCourseChunks(documents.getFirst().getDocId()));
            return directAnswer(request, sessionId, schedule.answer(), schedule.sources(), "course-schedule-v1");
        }

        RetrievalQueryPlan plan = properties.getRetrieval().isQueryPlanningEnabled() && !route.unavailable()
                ? queryPlanner.plan(request.getQuestion())
                : RetrievalQueryPlan.fallback(request.getQuestion());
        String rewrittenQuery = plan.rerankQuery();
        KnowledgeRepository.QueryRecord queryRecord = repository.insertQuery(sessionId, request.getQuestion(), rewrittenQuery);

        List<List<RetrievedChunk>> vectorRoutes = new ArrayList<>();
        List<List<RetrievedChunk>> keywordRoutes = new ArrayList<>();
        for (String query : plan.effectiveQueries(properties.getRetrieval().getMaxExpandedQueries())) {
            if (route.unavailable()) break;
            if (!preservesScope(query, scope) || !preservesMajor(query, effectiveMajor, catalog)) continue;
            float[] queryEmbedding = embeddingService.embedQuery(query);
            vectorRoutes.add(repository.searchByVector(queryEmbedding, effectiveMajor,
                    scope.grade(), scope.cohort(), properties.getRetrieval().getVectorTopK(), route.docIds()));
        }
        for (String query : plan.keywordQueries(properties.getRetrieval().getMaxExpandedQueries())) {
            if (route.unavailable()) break;
            if (!preservesScope(query, scope) || !preservesMajor(query, effectiveMajor, catalog)) continue;
            keywordRoutes.add(repository.searchByKeyword(query, effectiveMajor,
                    scope.grade(), scope.cohort(), properties.getRetrieval().getBm25TopK(), route.docIds()));
        }
        List<RetrievedChunk> fused = RankFusion.fuse(vectorRoutes, keywordRoutes, properties.getRetrieval().getRrfK());
        List<RetrievedChunk> reranked = reranker.rerank(request.getQuestion(), fused, Math.max(1, fused.size())).stream()
                .filter(chunk -> EvidencePolicy.supports(request.getQuestion(), chunk, properties.getRetrieval().getMinimumEvidenceCoverage()))
                .filter(chunk -> chunk.getRerankScore() != null && Double.isFinite(chunk.getRerankScore())
                        && chunk.getRerankScore() >= properties.getRetrieval().getRerankThreshold())
                .limit(properties.getRetrieval().getFinalTopK())
                .toList();

        repository.insertRetrievalTrace(queryRecord.queryId(), fused, reranked, properties.getRetrieval().getFinalTopK(),
                0.3, 0.7, properties.getRetrieval().getRerankThreshold());

        GenerationResult generation = generateAnswer(request.getQuestion(), reranked);
        String answer = generation.answer();
        double confidence = reranked.isEmpty() || !generation.publishSources() ? 0 : Math.min(0.99, reranked.getFirst().getEvidenceScore());
        repository.insertGenerationTrace(queryRecord.queryId(), generation.model(), generation.template(), answer);
        repository.completeQuery(queryRecord.queryId(), confidence);

        return RagAnswerResponseVO.builder()
                .sessionId(sessionId)
                .queryId(queryRecord.queryId())
                .traceId(queryRecord.traceId())
                .originalQuery(request.getQuestion())
                .rewrittenQuery(rewrittenQuery)
                .answer(answer)
                .sources(toSources(generation.publishSources() ? reranked : List.of()))
                .build();
    }

    private record GenerationResult(String answer, String model, String template, boolean publishSources) { }

    private RagAnswerResponseVO directAnswer(RagAskRequestDTO request, Long sessionId, String answer,
                                             List<RetrievedChunk> sources, String template) {
        var query = repository.insertQuery(sessionId, request.getQuestion(), request.getQuestion());
        repository.insertRetrievalTrace(query.queryId(), sources, sources, sources.size(), 0, 0, 0);
        repository.insertGenerationTrace(query.queryId(), sources.isEmpty() ? "none" : "structured-course-schedule", template, answer);
        repository.completeQuery(query.queryId(), 0.0);
        return RagAnswerResponseVO.builder().sessionId(sessionId).queryId(query.queryId()).traceId(query.traceId())
                .originalQuery(request.getQuestion()).rewrittenQuery(request.getQuestion()).answer(answer).sources(toSources(sources)).build();
    }

    private GenerationResult generateAnswer(String originalQuestion, List<RetrievedChunk> chunks) {
        if (chunks.isEmpty()) {
            return new GenerationResult("根据现有资料无法回答。", "none", "evidence-refusal-v1", false);
        }
        var courseAnswer = CourseEvidence.answer(originalQuestion, chunks);
        if (courseAnswer.isPresent()) return new GenerationResult(courseAnswer.get(), "structured-course-evidence", "course-evidence-v2", true);
        StringBuilder references = new StringBuilder();
        StringBuilder allowedCitations = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            allowedCitations.append("[").append(i + 1).append("] ");
            references.append("[").append(i + 1).append("] 来源：")
                    .append(chunk.getTitle()).append("，chunk_id=")
                    .append(chunk.getChunkId()).append("\n")
                    .append(chunk.getContent()).append("\n\n");
        }
        String prompt = """
                你是面向大学生的校园生活与政策问答助手。请严格依据参考资料回答用户问题。
                如果参考资料没有相关信息，请回答“根据现有资料无法回答”，不要自行编造。
                回答要清晰、可执行，并在关键结论后标注对应来源。可使用的引用编号仅有：%s。
                不得引用其他编号，课程数值必须对应同一课程行，不能串读相邻行。

                参考资料：
                %s

                用户问题：%s
                """.formatted(allowedCitations, references, originalQuestion);
        String answer = chatModel().chat(prompt);
        String template = PROMPT_TEMPLATE;
        if (!CitationPolicy.valid(answer, chunks.size())) {
            answer = chatModel().chat(prompt + "\n上一版回答的引用缺失或越界，请依据上述资料重新给出回答。仅可引用："
                    + allowedCitations + "\n上一版：\n" + answer);
            template = "campus-rag-v2-citation-repair";
        }
        if (!CitationPolicy.valid(answer, chunks.size())) {
            return new GenerationResult("回答引用校验失败，请重试。", properties.getQwen().getChatModel(), "citation-rejected-v1", false);
        }
        return new GenerationResult(answer, properties.getQwen().getChatModel(), template, !CitationPolicy.isRefusal(answer));
    }

    private ChatModel chatModel() {
        return chatModelProvider.getObject();
    }

    private boolean preservesScope(String query, AcademicScope scope) {
        try {
            return AcademicScope.resolve(query, scope.grade(), scope.cohort()).equals(scope);
        } catch (IllegalArgumentException invalidExpansion) {
            return false;
        }
    }

    private boolean preservesMajor(String query, String major, List<cn.redture.campus.rag.retrieval.CurriculumDocument> catalog) {
        try {
            return java.util.Objects.equals(major, CurriculumRoute.effectiveMajor(query, major, catalog));
        } catch (IllegalArgumentException conflictingExpansion) {
            return false;
        }
    }

    private List<RagSourceVO> toSources(List<RetrievedChunk> chunks) {
        List<RagSourceVO> sources = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            sources.add(RagSourceVO.builder()
                    .chunkId(chunk.getChunkId())
                    .docId(chunk.getDocId())
                    .parentId(chunk.getParentId())
                    .title(chunk.getTitle())
                    .content(chunk.getContent())
                    .score(chunk.getRerankScore() == null ? 0 : chunk.getRerankScore())
                    .metadata(chunk.getMetadata())
                    .build());
        }
        return sources;
    }
}
