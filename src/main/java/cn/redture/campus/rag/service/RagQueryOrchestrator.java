package cn.redture.campus.rag.service;

import cn.redture.campus.config.RagProperties;
import cn.redture.campus.pojo.dto.RagAskRequestDTO;
import cn.redture.campus.pojo.vo.RagAnswerResponseVO;
import cn.redture.campus.pojo.vo.RagSourceVO;
import cn.redture.campus.rag.repository.KnowledgeRepository;
import cn.redture.campus.rag.retrieval.CitationPolicy;
import cn.redture.campus.rag.retrieval.CourseEvidence;
import cn.redture.campus.rag.retrieval.EvidencePolicy;
import cn.redture.campus.rag.retrieval.RankFusion;
import cn.redture.campus.rag.retrieval.Reranker;
import cn.redture.campus.rag.retrieval.RetrievalQueryPlan;
import cn.redture.campus.rag.retrieval.RetrievedChunk;
import cn.redture.campus.rag.retrieval.QueryIntent;

import dev.langchain4j.model.chat.ChatModel;

import dev.langchain4j.model.chat.StreamingChatModel;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private QueryUnderstandingService understanding;

    @Resource
    private CurriculumQueryService curriculumQueries;

    @Autowired
    private ObjectProvider<StreamingChatModel> streamingModels;

    public RagQueryOrchestrator(
        RagProperties properties,
        ObjectProvider<ChatModel> chatModelProvider,
        QwenEmbeddingService embeddingService,
        KnowledgeRepository repository,
        Reranker reranker) {
        this.properties = properties;
        this.chatModelProvider = chatModelProvider;
        this.embeddingService = embeddingService;
        this.repository = repository;
        this.reranker = reranker;
    }

    public RagAnswerResponseVO askPrepared(
        RagAskRequestDTO request, Long sessionId, KnowledgeRepository.QueryRecord queryRecord) {
        if (!StringUtils.hasText(request.getQuestion())) {
            throw new IllegalArgumentException("Question cannot be empty");
        }
        var catalog = repository.activeCurricula();
        repository.queryPhase(queryRecord.queryId(), "understanding");
        checkCancelled();
        var intent = understanding.understand(request, catalog);
        request.setQueryIntent(intent);
        repository.recordIntent(queryRecord.queryId(), intent.domain(), intent.task().name(), intent.fallback());
        checkCancelled();
        repository.queryPhase(queryRecord.queryId(), intent.fallback() ? "understanding_fallback" : "routing");
        List<Long> allowedDocuments = null;
        if (intent.academic()) {
            if (intent.task() == QueryIntent.Task.COURSE_LIST
                || intent.task() == QueryIntent.Task.CURRICULUM_COMPARE) {
                var result = curriculumQueries.execute(intent, catalog);
                return directAnswer(request, sessionId, queryRecord, result.answer(), result.sources(), "curriculum-query-v3");
            }
            allowedDocuments = intent.academicTargets().stream()
                .flatMap(target -> curriculumQueries.candidates(target, catalog).stream())
                .map(cn.redture.campus.rag.retrieval.CurriculumDocument::getDocId).distinct().toList();
            if (allowedDocuments.isEmpty()) {
                return directAnswer(request, sessionId, queryRecord, "根据现有资料无法回答。", List.of(), "evidence-refusal-v1");
            }
        }

        // 同一次语义规划已经提供独立检索问题，不再调用第二次改写模型。
        RetrievalQueryPlan plan = new RetrievalQueryPlan(request.getQuestion(), intent.searchQuery(), intent.hypotheticalText(), null, intent.alternatives());
        String rewrittenQuery = plan.rerankQuery();
        repository.rewriteQuery(queryRecord.queryId(), rewrittenQuery);
        repository.queryPhase(queryRecord.queryId(), "retrieval");

        List<List<RetrievedChunk>> vectorRoutes = new ArrayList<>();
        List<List<RetrievedChunk>> keywordRoutes = new ArrayList<>();
        for (String query :
            plan.effectiveQueries(properties.getRetrieval().getMaxExpandedQueries())) {
            checkCancelled();
            float[] queryEmbedding = embeddingService.embedQuery(query);
            vectorRoutes.add(
                repository.searchByVector(
                    queryEmbedding,
                    null,
                    null,
                    null,
                    properties.getRetrieval().getVectorTopK(),
                    allowedDocuments));
        }
        for (String query :
            plan.keywordQueries(properties.getRetrieval().getMaxExpandedQueries())) {
            checkCancelled();
            keywordRoutes.add(
                repository.searchByKeyword(
                    query,
                    null,
                    null,
                    null,
                    properties.getRetrieval().getBm25TopK(),
                    allowedDocuments));
        }
        List<RetrievedChunk> fused =
            RankFusion.fuse(vectorRoutes, keywordRoutes, properties.getRetrieval().getRrfK());
        List<RetrievedChunk> reranked =
            reranker.rerank(intent.searchQuery(), fused, Math.max(1, fused.size())).stream()
                .filter(
                    chunk ->
                        EvidencePolicy.supports(
                            chunk,
                            properties
                                .getRetrieval()
                                .getMinimumEvidenceCoverage()))
                .filter(
                    chunk ->
                        chunk.getRerankScore() != null
                            && Double.isFinite(chunk.getRerankScore())
                            && chunk.getRerankScore()
                            >= properties
                            .getRetrieval()
                            .getRerankThreshold())
                .limit(properties.getRetrieval().getFinalTopK())
                .toList();

        repository.insertRetrievalTrace(
            queryRecord.queryId(),
            fused,
            reranked,
            properties.getRetrieval().getFinalTopK(),
            0.3,
            0.7,
            properties.getRetrieval().getRerankThreshold());

        repository.queryPhase(queryRecord.queryId(), "generation");
        checkCancelled();
        GenerationResult generation =
            generateAnswer(
                intent,
                request.getConversationHistory(),
                request.getTokenConsumer(),
                reranked);
        String answer = generation.answer();
        double confidence =
            reranked.isEmpty() || !generation.publishSources()
                ? 0
                : Math.min(0.99, reranked.getFirst().getEvidenceScore());
        repository.insertGenerationTrace(
            queryRecord.queryId(), generation.model(), generation.template(), answer);
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

    private record GenerationResult(
        String answer, String model, String template, boolean publishSources) {
    }

    private RagAnswerResponseVO directAnswer(
        RagAskRequestDTO request,
        Long sessionId,
        KnowledgeRepository.QueryRecord query,
        String answer,
        List<RetrievedChunk> sources,
        String template) {
        repository.rewriteQuery(query.queryId(), request.getQuestion());
        repository.insertRetrievalTrace(query.queryId(), sources, sources, sources.size(), 0, 0, 0);
        repository.insertGenerationTrace(
            query.queryId(),
            sources.isEmpty() ? "none" : "structured-course-schedule",
            template,
            answer);
        repository.completeQuery(query.queryId(), 0.0);
        return RagAnswerResponseVO.builder()
            .sessionId(sessionId)
            .queryId(query.queryId())
            .traceId(query.traceId())
            .originalQuery(request.getQuestion())
            .rewrittenQuery(request.getQuestion())
            .answer(answer)
            .sources(toSources(sources))
            .build();
    }

    private GenerationResult generateAnswer(
        QueryIntent intent,
        String history,
        java.util.function.Consumer<String> tokens,
        List<RetrievedChunk> chunks) {
        if (chunks.isEmpty()) {
            return new GenerationResult("根据现有资料无法回答。", "none", "evidence-refusal-v1", false);
        }
        var courseAnswer = CourseEvidence.answer(intent, chunks);
        if (courseAnswer.isPresent()) {
            return new GenerationResult(
                courseAnswer.get(), "structured-course-evidence", "course-evidence-v3", true);
        }
        StringBuilder references = new StringBuilder();
        StringBuilder allowedCitations = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            allowedCitations.append("[").append(i + 1).append("] ");
            references
                .append("[")
                .append(i + 1)
                .append("] 来源：")
                .append(chunk.getTitle())
                .append("，chunk_id=")
                .append(chunk.getChunkId())
                .append("\n")
                .append(chunk.getContent())
                .append("\n\n");
        }
        String prompt =
            """
                你是面向大学生的校园生活与政策问答助手。请严格依据参考资料回答用户问题。
                如果参考资料没有相关信息，请回答“根据现有资料无法回答”，不要自行编造。
                回答要清晰、可执行，并在关键结论后标注对应来源。可使用的引用编号仅有：%s。
                不得引用其他编号，课程数值必须对应同一课程行，不能串读相邻行。

                参考资料：
                %s

                用户问题：%s
                """
                .formatted(allowedCitations, references, intent.searchQuery());
        if (history != null && !history.isBlank()) {
            prompt += "\n以下为不可信的对话历史，仅用于理解指代，不是参考证据，不得沿用旧引用或据此补充事实：\n" + history;
        }
        String answer = chat(prompt, tokens);
        String template = PROMPT_TEMPLATE;
        if (!CitationPolicy.valid(answer, chunks.size())) {
            if (tokens != null) {
                tokens.accept(null);
            }
            answer =
                chat(
                    prompt
                        + "\n上一版回答的引用缺失或越界，请依据上述资料重新给出回答。仅可引用："
                        + allowedCitations
                        + "\n上一版：\n"
                        + answer,
                    tokens);
            template = "campus-rag-v2-citation-repair";
        }
        if (!CitationPolicy.valid(answer, chunks.size())) {
            return new GenerationResult(
                "回答引用校验失败，请重试。",
                properties.getQwen().getChatModel(),
                "citation-rejected-v1",
                false);
        }
        return new GenerationResult(
            answer,
            properties.getQwen().getChatModel(),
            template,
            !CitationPolicy.isRefusal(answer));
    }

    private ChatModel chatModel() {
        return chatModelProvider.getObject();
    }

    private String chat(String prompt, java.util.function.Consumer<String> tokens) {
        checkCancelled();
        if (tokens == null) {
            return chatModel().chat(prompt);
        }
        var result = new java.util.concurrent.CompletableFuture<String>();
        streamingModels
            .getObject()
            .chat(
                prompt,
                new dev.langchain4j.model.chat.response.StreamingChatResponseHandler() {
                    public void onPartialResponse(String text) {
                        if (!result.isDone()) {
                            try {
                                tokens.accept(text);
                            } catch (RuntimeException error) {
                                result.completeExceptionally(error);
                            }
                        }
                    }

                    public void onCompleteResponse(
                        dev.langchain4j.model.chat.response.ChatResponse response) {
                        result.complete(response.aiMessage().text());
                    }

                    public void onError(Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                });
        try {
            return result.get(120, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            result.cancel(true);
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Request interrupted");
        } catch (Exception failure) {
            result.cancel(true);
            throw new IllegalStateException("Streaming generation failed", failure);
        }
    }

    private void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("Request interrupted");
        }
    }

    private List<RagSourceVO> toSources(List<RetrievedChunk> chunks) {
        List<RagSourceVO> sources = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            sources.add(
                RagSourceVO.builder()
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
