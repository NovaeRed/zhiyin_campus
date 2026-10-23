package cn.redture.campus.rag.evaluation;

import cn.redture.campus.mapper.EvaluationTraceMapper;
import cn.redture.campus.rag.retrieval.RetrievedChunk;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EvaluationService {
    private final EvaluationTraceMapper mapper;
    private final ObjectMapper json;
    private final RetrievalEvaluator evaluator;

    public EvaluationService(EvaluationTraceMapper mapper, ObjectMapper json, RetrievalEvaluator evaluator) {
        this.mapper = mapper;
        this.json = json;
        this.evaluator = evaluator;
    }

    public EvaluationReport evaluate(EvaluationRequest request, Long userId) {
        Map<Long, RetrievalSnapshot> traces = new LinkedHashMap<>();
        for (EvaluationRequest.Sample sample : request.samples()) {
            traces.computeIfAbsent(sample.queryId(), id -> snapshot(id, userId));
        }
        return evaluator.evaluate(request, traces);
    }

    public RetrievalSnapshot snapshot(Long queryId, Long userId) {
        if (queryId == null || queryId < 1 || userId == null) throw new IllegalArgumentException("Invalid trace request");
        RecordedRetrieval record = mapper.findOwnedTrace(queryId, userId);
        if (record == null) throw new IllegalArgumentException("No completed retrieval trace available to this user");
        if (record.getRecordedTopK() == null || record.getThreshold() == null) {
            throw new IllegalArgumentException("Historical trace is missing its retrieval configuration");
        }
        return new RetrievalSnapshot(record.getQueryId(), record.getTraceId(), record.getQuestion(), record.getRewrittenQuery(),
                record.getRecordedTopK(), record.getThreshold(), parse(record.getFusedJson()), parse(record.getSelectedJson()));
    }

    private List<RetrievedChunk> parse(String value) {
        try {
            if (value == null) throw new IllegalArgumentException("Retrieval snapshot is missing");
            List<RetrievedChunk> chunks = json.readValue(value, new TypeReference<>() { });
            if (chunks == null || chunks.stream().anyMatch(c -> c == null || c.getChunkId() == null || c.getDocId() == null)) {
                throw new IllegalArgumentException("Retrieval snapshot contains invalid chunks");
            }
            return chunks;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot decode recorded retrieval snapshot", e);
        }
    }
}
