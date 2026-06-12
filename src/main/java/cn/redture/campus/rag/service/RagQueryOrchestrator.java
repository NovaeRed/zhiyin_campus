package cn.redture.campus.rag.service;

import cn.redture.campus.config.RagProperties;
import cn.redture.campus.pojo.dto.RagAskRequestDTO;
import cn.redture.campus.pojo.vo.RagAnswerResponseVO;
import cn.redture.campus.pojo.vo.RagSourceVO;
import cn.redture.campus.rag.repository.KnowledgeRepository;
import cn.redture.campus.rag.retrieval.Reranker;
import cn.redture.campus.rag.retrieval.RetrievedChunk;
import cn.redture.campus.util.SecurityContextHolderUtil;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RagQueryOrchestrator {

    private static final String PROMPT_TEMPLATE = "campus-rag-v1";

    private final RagProperties properties;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final QwenEmbeddingService embeddingService;
    private final KnowledgeRepository repository;
    private final Reranker reranker;

    public RagQueryOrchestrator(RagProperties properties,
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

    public RagAnswerResponseVO ask(RagAskRequestDTO request) {
        if (!StringUtils.hasText(request.getQuestion())) {
            throw new IllegalArgumentException("Question cannot be empty");
        }
        Long userId = SecurityContextHolderUtil.getUserId();
        Long sessionId = repository.ensureSession(userId, request.getSessionId());

        String rewrittenQuery = rewriteQuery(request.getQuestion());
        KnowledgeRepository.QueryRecord queryRecord = repository.insertQuery(sessionId, request.getQuestion(), rewrittenQuery);

        float[] queryEmbedding = embeddingService.embedQuery(rewrittenQuery);
        List<RetrievedChunk> vectorHits = repository.searchByVector(queryEmbedding, request.getTargetMajor(),
                request.getTargetGrade(), properties.getRetrieval().getVectorTopK());
        List<RetrievedChunk> keywordHits = repository.searchByKeyword(rewrittenQuery, request.getTargetMajor(),
                request.getTargetGrade(), properties.getRetrieval().getBm25TopK());
        List<RetrievedChunk> fused = reciprocalRankFusion(vectorHits, keywordHits, properties.getRetrieval().getRrfK());
        List<RetrievedChunk> reranked = reranker.rerank(rewrittenQuery, fused, properties.getRetrieval().getFinalTopK()).stream()
                .filter(chunk -> chunk.getRerankScore() == null
                        || chunk.getRerankScore() >= properties.getRetrieval().getRerankThreshold())
                .toList();

        repository.insertRetrievalTrace(queryRecord.queryId(), fused, reranked, properties.getRetrieval().getFinalTopK(),
                0.3, 0.7, properties.getRetrieval().getRerankThreshold());

        String answer = generateAnswer(request.getQuestion(), reranked);
        double confidence = reranked.isEmpty() ? 0 : Math.min(0.99, reranked.getFirst().getRerankScore());
        repository.insertGenerationTrace(queryRecord.queryId(), properties.getQwen().getChatModel(), PROMPT_TEMPLATE, answer);
        repository.completeQuery(queryRecord.queryId(), confidence);

        return RagAnswerResponseVO.builder()
                .sessionId(sessionId)
                .queryId(queryRecord.queryId())
                .traceId(queryRecord.traceId())
                .originalQuery(request.getQuestion())
                .rewrittenQuery(rewrittenQuery)
                .answer(answer)
                .sources(toSources(reranked))
                .build();
    }

    private String rewriteQuery(String question) {
        String prompt = """
                你是校园知识库检索 Query 改写器。请把用户问题改写成适合检索的独立、完整、简洁的问题。
                只输出改写后的问题，不要解释。

                用户问题：%s
                """.formatted(question);
        try {
            String rewritten = chatModel().chat(prompt);
            return StringUtils.hasText(rewritten) ? rewritten.trim() : question;
        } catch (RuntimeException e) {
            return question;
        }
    }

    private String generateAnswer(String originalQuestion, List<RetrievedChunk> chunks) {
        if (chunks.isEmpty()) {
            return "根据现有资料无法回答。";
        }
        StringBuilder references = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            references.append("[").append(i + 1).append("] 来源：")
                    .append(chunk.getTitle()).append("，chunk_id=")
                    .append(chunk.getChunkId()).append("\n")
                    .append(chunk.getContent()).append("\n\n");
        }
        String prompt = """
                你是面向大学生的校园生活与政策问答助手。请严格依据参考资料回答用户问题。
                如果参考资料没有相关信息，请回答“根据现有资料无法回答”，不要自行编造。
                回答要清晰、可执行，并在关键结论后用 [1]、[2] 这样的编号标注来源。

                参考资料：
                %s

                用户问题：%s
                """.formatted(references, originalQuestion);
        return chatModel().chat(prompt);
    }

    private ChatModel chatModel() {
        return chatModelProvider.getObject();
    }

    private List<RetrievedChunk> reciprocalRankFusion(List<RetrievedChunk> vectorHits,
                                                       List<RetrievedChunk> keywordHits,
                                                       int rrfK) {
        Map<Long, RetrievedChunk> byId = new LinkedHashMap<>();
        addRankScores(byId, vectorHits, rrfK, true);
        addRankScores(byId, keywordHits, rrfK, false);
        return byId.values().stream()
                .sorted(Comparator.comparing(RetrievedChunk::getFusedScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private void addRankScores(Map<Long, RetrievedChunk> byId, List<RetrievedChunk> hits, int rrfK, boolean vector) {
        for (int i = 0; i < hits.size(); i++) {
            RetrievedChunk hit = hits.get(i);
            RetrievedChunk merged = byId.computeIfAbsent(hit.getChunkId(), ignored -> copy(hit));
            double rankScore = 1.0 / (rrfK + i + 1);
            merged.setFusedScore((merged.getFusedScore() == null ? 0 : merged.getFusedScore()) + rankScore);
            if (vector) {
                merged.setVectorScore(hit.getVectorScore());
            } else {
                merged.setBm25Score(hit.getBm25Score());
            }
        }
    }

    private RetrievedChunk copy(RetrievedChunk chunk) {
        return RetrievedChunk.builder()
                .chunkId(chunk.getChunkId())
                .docId(chunk.getDocId())
                .parentId(chunk.getParentId())
                .title(chunk.getTitle())
                .content(chunk.getContent())
                .metadata(chunk.getMetadata())
                .vectorScore(chunk.getVectorScore())
                .bm25Score(chunk.getBm25Score())
                .fusedScore(0.0)
                .build();
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
