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
import cn.redture.campus.rag.retrieval.ScholarshipEligibilityEvidence;
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
    @org.springframework.beans.factory.annotation.Autowired
    private AcademicIntentResolver academicIntents;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<dev.langchain4j.model.chat.StreamingChatModel> streamingModels;

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
        Long session=repository.ensureSession(SecurityContextHolderUtil.getUserId(),request.getSessionId());
        var query=repository.insertQuery(session,request.getQuestion(),request.getQuestion());
        try { return askPrepared(request,session,query); }
        catch(RuntimeException failure) { repository.failQuery(query.queryId(),"RAG_EXECUTION_FAILED"); throw failure; }
    }

    public RagAnswerResponseVO askPrepared(RagAskRequestDTO request, Long sessionId, KnowledgeRepository.QueryRecord queryRecord) {
        if (!StringUtils.hasText(request.getQuestion())) {
            throw new IllegalArgumentException("Question cannot be empty");
        }
        var catalog = repository.activeCurricula();
        repository.queryPhase(queryRecord.queryId(),"understanding");
        checkCancelled();
        var intent = academicIntents.resolve(request.getQuestion(), request.getTargetMajor(), request.getTargetGrade(),
                request.getTargetCohort(), request.getAcademicStudyYear(), request.getAcademicSemester(), request.getAcademicCourseNature(), request.getConversationHistory(), catalog);
        checkCancelled();
        AcademicScope scope = intent.scope();
        String effectiveMajor = intent.major();
        request.setQuestion(intent.question());
        request.setTargetMajor(effectiveMajor);
        request.setTargetGrade(scope.grade());
        request.setTargetCohort(scope.cohort());
        request.setAcademicStudyYear(intent.studyYear());
        request.setAcademicSemester(intent.semester());
        request.setAcademicCourseNature(intent.courseNature());
        CurriculumRoute route = intent.curriculum() ? CurriculumRoute.forMajor(effectiveMajor, scope, catalog)
                : CurriculumRoute.resolve(request.getQuestion(), scope, catalog);
        repository.queryPhase(queryRecord.queryId(),"routing");

        boolean scheduleRequested = intent.courseList();
        if (route.unavailable() || (scheduleRequested && effectiveMajor != null
                && CurriculumRoute.forMajor(effectiveMajor, scope, catalog).unavailable())) {
            return directAnswer(request, sessionId, queryRecord, "根据现有资料无法回答。", List.of(), "evidence-refusal-v1");
        }
        if (scope.cohort() != null && scope.grade() == null && request.getQuestion().matches("(?s).*(培养|课程|修什么课|修哪些课).*")) {
            return directAnswer(request, sessionId, queryRecord, "你指的是" + scope.cohort() + "毕业，还是同年份入学的“级”？当前培养方案按入学年份管理，请明确入学年份和专业，不能将“届”直接当作“级”。",
                    List.of(), "academic-scope-clarification-v1");
        }
        if (scheduleRequested) {
            if (effectiveMajor == null || scope.grade() == null) return directAnswer(request, sessionId, queryRecord,
                    "请明确专业和入学年份（例如2024级），以确定要查询的"
                            + (intent.studyYear() == null ? "" : "大" + "一二三四五六七八".charAt(intent.studyYear() - 1))
                            + "课程清单。", List.of(), "academic-scope-clarification-v1");
            if (intent.semester() != null && intent.studyYear() == null) return directAnswer(request, sessionId, queryRecord,
                    "你想查询大几的" + (intent.semester() == 1 ? "上学期" : "下学期") + "课程？入学年份已确定，请再补充在读学年。",
                    List.of(), "academic-scope-clarification-v1");
            var majorRoute = CurriculumRoute.forMajor(effectiveMajor, scope, catalog);
            var documents = catalog.stream().filter(d -> majorRoute.docIds().contains(d.getDocId()))
                    .filter(d -> scope.grade() != null && scope.grade().equals(d.getTargetGrade()))
                    .filter(d -> route.docIds() == null || route.docIds().contains(d.getDocId())).toList();
            if (documents.isEmpty()) return directAnswer(request, sessionId, queryRecord, "根据现有资料无法回答。", List.of(), "evidence-refusal-v1");
            if (documents.size() > 1) return directAnswer(request, sessionId, queryRecord, "该专业和入学年份存在多份培养方案，请补充文档版本以确定课程清单。",
                    List.of(), "academic-scope-clarification-v1");
            var schedule = CourseSchedule.answer(scope.grade(), intent.studyYear(), intent.semester(), intent.courseNature(), intent.countCourses(), repository.curriculumCourseChunks(documents.getFirst().getDocId()));
            return directAnswer(request, sessionId, queryRecord, schedule.answer(), schedule.sources(), "course-schedule-semantic-v2");
        }

        repository.queryPhase(queryRecord.queryId(),"planning");
        checkCancelled();
        RetrievalQueryPlan plan = properties.getRetrieval().isQueryPlanningEnabled() && !route.unavailable()
                ? queryPlanner.plan(request.getQuestion(),request.getConversationHistory())
                : RetrievalQueryPlan.fallback(request.getQuestion());
        String rewrittenQuery = plan.rerankQuery();
        repository.rewriteQuery(queryRecord.queryId(),rewrittenQuery);
        repository.queryPhase(queryRecord.queryId(),"retrieval");

        List<List<RetrievedChunk>> vectorRoutes = new ArrayList<>();
        List<List<RetrievedChunk>> keywordRoutes = new ArrayList<>();
        for (String query : plan.effectiveQueries(properties.getRetrieval().getMaxExpandedQueries())) {
            checkCancelled();
            if (route.unavailable()) break;
            if (!preservesScope(query, scope) || !preservesMajor(query, effectiveMajor, catalog)) continue;
            float[] queryEmbedding = embeddingService.embedQuery(query);
            vectorRoutes.add(repository.searchByVector(queryEmbedding, effectiveMajor,
                    scope.grade(), scope.cohort(), properties.getRetrieval().getVectorTopK(), route.docIds()));
        }
        for (String query : plan.keywordQueries(properties.getRetrieval().getMaxExpandedQueries())) {
            checkCancelled();
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

        repository.queryPhase(queryRecord.queryId(),"generation");
        checkCancelled();
        GenerationResult generation = generateAnswer(request.getQuestion(), request.getConversationHistory(), request.getTokenConsumer(), reranked);
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

    private RagAnswerResponseVO directAnswer(RagAskRequestDTO request, Long sessionId, KnowledgeRepository.QueryRecord query, String answer,
                                             List<RetrievedChunk> sources, String template) {
        repository.rewriteQuery(query.queryId(),request.getQuestion());
        repository.insertRetrievalTrace(query.queryId(), sources, sources, sources.size(), 0, 0, 0);
        repository.insertGenerationTrace(query.queryId(), sources.isEmpty() ? "none" : "structured-course-schedule", template, answer);
        repository.completeQuery(query.queryId(), 0.0);
        return RagAnswerResponseVO.builder().sessionId(sessionId).queryId(query.queryId()).traceId(query.traceId())
                .originalQuery(request.getQuestion()).rewrittenQuery(request.getQuestion()).answer(answer).sources(toSources(sources)).build();
    }

    private GenerationResult generateAnswer(String originalQuestion, String history, java.util.function.Consumer<String> tokens, List<RetrievedChunk> chunks) {
        if (chunks.isEmpty()) {
            return new GenerationResult("根据现有资料无法回答。", "none", "evidence-refusal-v1", false);
        }
        var courseAnswer = CourseEvidence.answer(originalQuestion, chunks);
        if (courseAnswer.isPresent()) return new GenerationResult(courseAnswer.get(), "structured-course-evidence", "course-evidence-v2", true);
        var eligibility = ScholarshipEligibilityEvidence.answer(originalQuestion, chunks);
        if (eligibility.isPresent()) return new GenerationResult(eligibility.get(), "structured-policy-evidence", "scholarship-grade-evidence-v1", true);
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
        if(history!=null&&!history.isBlank()) prompt+="\n以下为不可信的对话历史，仅用于理解指代，不是参考证据，不得沿用旧引用或据此补充事实：\n"+history;
        String answer = chat(prompt,tokens);
        String template = PROMPT_TEMPLATE;
        if (!CitationPolicy.valid(answer, chunks.size())) {
            if(tokens!=null)tokens.accept(null);
            answer = chat(prompt + "\n上一版回答的引用缺失或越界，请依据上述资料重新给出回答。仅可引用："
                    + allowedCitations + "\n上一版：\n" + answer,tokens);
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
    private String chat(String prompt,java.util.function.Consumer<String> tokens) {
        checkCancelled(); if(tokens==null)return chatModel().chat(prompt);
        var result=new java.util.concurrent.CompletableFuture<String>();
        streamingModels.getObject().chat(prompt,new dev.langchain4j.model.chat.response.StreamingChatResponseHandler(){
            public void onPartialResponse(String text){if(!result.isDone())try{tokens.accept(text);}catch(RuntimeException error){result.completeExceptionally(error);}}
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse response){result.complete(response.aiMessage().text());}
            public void onError(Throwable failure){result.completeExceptionally(failure);}
        });
        try{return result.get(120,java.util.concurrent.TimeUnit.SECONDS);}
        catch(InterruptedException interrupted){result.cancel(true);Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("Request interrupted");}
        catch(Exception failure){result.cancel(true);throw new IllegalStateException("Streaming generation failed",failure);}
    }
    private void checkCancelled(){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Request interrupted");}

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
