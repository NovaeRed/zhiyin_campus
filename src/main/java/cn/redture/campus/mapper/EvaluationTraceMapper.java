package cn.redture.campus.mapper;

import cn.redture.campus.rag.evaluation.RecordedRetrieval;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EvaluationTraceMapper {
    @Select(
        """
            SELECT q.id AS "queryId", q.trace_id AS "traceId", q.original_query AS question,
                   q.rewritten_query AS "rewrittenQuery", t.top_k AS "recordedTopK",
                   t.rerank_threshold AS threshold, t.initial_retrieval::text AS "fusedJson",
                   t.reranked_result::text AS "selectedJson"
            FROM rag_queries q
            JOIN rag_sessions s ON s.id = q.session_id
            JOIN retrieval_traces t ON t.query_id = q.id
            WHERE q.id = #{queryId} AND s.user_id = #{userId} AND s.target_scope->>'deletedAt' IS NULL AND q.workflow_state = 'completed'
            """)
    RecordedRetrieval findOwnedTrace(@Param("queryId") Long queryId, @Param("userId") Long userId);
}
