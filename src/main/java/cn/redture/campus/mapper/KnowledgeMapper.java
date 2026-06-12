package cn.redture.campus.mapper;

import cn.redture.campus.rag.repository.DocChunkInsert;
import cn.redture.campus.rag.repository.KnowledgeDocumentInsert;
import cn.redture.campus.rag.repository.RagQueryInsert;
import cn.redture.campus.rag.retrieval.RetrievedChunk;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface KnowledgeMapper {

    @Insert("""
            INSERT INTO knowledge_docs(title, file_path, file_type, version, status, metadata, chunk_strategy, created_by)
            VALUES (#{title}, #{filePath}, #{fileType}, #{version}, 'processing',
                    #{metadataJson}::jsonb, #{chunkStrategyJson}::jsonb, #{createdBy,jdbcType=BIGINT})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insertDocument(KnowledgeDocumentInsert record);

    @Insert("""
            INSERT INTO doc_chunks(doc_id, parent_id, chunk_level, content, embedding, metadata,
                                   target_major, target_grade, doc_status)
            VALUES (#{docId}, #{parentId,jdbcType=BIGINT}, #{level}, #{content}, #{embeddingVector,jdbcType=VARCHAR}::vector,
                    #{metadataJson}::jsonb, #{targetMajor,jdbcType=VARCHAR}, #{targetGrade,jdbcType=INTEGER}, 'active')
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insertChunk(DocChunkInsert record);

    @Update("UPDATE knowledge_docs SET status = #{status}, updated_at = NOW() WHERE id = #{docId}")
    int updateDocumentStatus(@Param("docId") Long docId, @Param("status") String status);

    @Update("UPDATE doc_chunks SET doc_status = #{status} WHERE doc_id = #{docId}")
    int updateChunksStatus(@Param("docId") Long docId, @Param("status") String status);

    @Update("UPDATE rag_sessions SET updated_at = NOW() WHERE id = #{sessionId}")
    int touchSession(@Param("sessionId") Long sessionId);

    @Select("INSERT INTO rag_sessions(user_id, created_at, updated_at) VALUES (#{userId}, NOW(), NOW()) RETURNING id")
    Long insertSession(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO rag_queries(session_id, trace_id, original_query, rewritten_query, workflow_state)
            VALUES (#{sessionId}, #{traceId}, #{originalQuery}, #{rewrittenQuery}, 'retrieving')
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insertQuery(RagQueryInsert record);

    @Update("""
            UPDATE rag_queries
            SET workflow_state = 'completed', final_confidence = #{confidence}, completed_at = NOW()
            WHERE id = #{queryId}
            """)
    int completeQuery(@Param("queryId") Long queryId, @Param("confidence") double confidence);

    @Select("""
            SELECT c.id AS "chunkId", c.doc_id AS "docId", c.parent_id AS "parentId", d.title,
                   COALESCE(p.content, c.content) AS content,
                   COALESCE(p.metadata, c.metadata)::text AS metadata,
                   1 - (c.embedding <=> #{embeddingVector}::vector) AS "vectorScore"
            FROM doc_chunks c
            JOIN knowledge_docs d ON d.id = c.doc_id
            LEFT JOIN doc_chunks p ON p.id = c.parent_id
            WHERE c.doc_status = 'active'
              AND c.chunk_level = 'child'
              AND c.embedding IS NOT NULL
              AND (CAST(#{targetMajor,jdbcType=VARCHAR} AS varchar) IS NULL
                   OR c.target_major IS NULL OR c.target_major = #{targetMajor,jdbcType=VARCHAR})
              AND (CAST(#{targetGrade,jdbcType=INTEGER} AS integer) IS NULL
                   OR c.target_grade IS NULL OR c.target_grade = #{targetGrade,jdbcType=INTEGER})
            ORDER BY c.embedding <=> #{embeddingVector}::vector
            LIMIT #{limit}
            """)
    List<RetrievedChunk> searchByVector(@Param("embeddingVector") String embeddingVector,
                                        @Param("targetMajor") String targetMajor,
                                        @Param("targetGrade") Integer targetGrade,
                                        @Param("limit") int limit);

    @Select("""
            <script>
            SELECT c.id AS "chunkId", c.doc_id AS "docId", c.parent_id AS "parentId", d.title,
                   COALESCE(p.content, c.content) AS content,
                   COALESCE(p.metadata, c.metadata)::text AS metadata,
                   (
                     ts_rank_cd(to_tsvector('simple', c.content), plainto_tsquery('simple', #{query}))
                     <if test="keywords != null and keywords.size() > 0">
                       +
                       <foreach collection="keywords" item="keyword" separator="+">
                         CASE WHEN c.content ILIKE '%' || #{keyword} || '%' THEN 1.0 ELSE 0.0 END
                       </foreach>
                     </if>
                   ) AS "bm25Score"
            FROM doc_chunks c
            JOIN knowledge_docs d ON d.id = c.doc_id
            LEFT JOIN doc_chunks p ON p.id = c.parent_id
            WHERE c.doc_status = 'active'
              AND c.chunk_level = 'child'
              AND (CAST(#{targetMajor,jdbcType=VARCHAR} AS varchar) IS NULL
                   OR c.target_major IS NULL OR c.target_major = #{targetMajor,jdbcType=VARCHAR})
              AND (CAST(#{targetGrade,jdbcType=INTEGER} AS integer) IS NULL
                   OR c.target_grade IS NULL OR c.target_grade = #{targetGrade,jdbcType=INTEGER})
              AND (to_tsvector('simple', c.content) @@ plainto_tsquery('simple', #{query})
                   <if test="keywords != null and keywords.size() > 0">
                     OR
                     <foreach collection="keywords" item="keyword" separator=" OR ">
                       c.content ILIKE '%' || #{keyword} || '%'
                     </foreach>
                   </if>)
            ORDER BY "bm25Score" DESC
            LIMIT #{limit}
            </script>
            """)
    List<RetrievedChunk> searchByKeyword(@Param("query") String query,
                                         @Param("keywords") List<String> keywords,
                                         @Param("targetMajor") String targetMajor,
                                         @Param("targetGrade") Integer targetGrade,
                                         @Param("limit") int limit);

    @Insert("""
            INSERT INTO retrieval_traces(query_id, initial_retrieval, reranked_result, selected_chunk_ids,
                                         top_k, bm25_weight, vector_weight, rerank_threshold)
            VALUES (#{queryId}, #{initialJson}::jsonb, #{rerankedJson}::jsonb,
                    CASE WHEN #{selectedChunkIdsCsv} = ''
                         THEN ARRAY[]::bigint[]
                         ELSE string_to_array(#{selectedChunkIdsCsv}, ',')::bigint[]
                    END,
                    #{topK}, #{bm25Weight}, #{vectorWeight}, #{threshold})
            ON CONFLICT (query_id) DO UPDATE SET
              initial_retrieval = EXCLUDED.initial_retrieval,
              reranked_result = EXCLUDED.reranked_result,
              selected_chunk_ids = EXCLUDED.selected_chunk_ids
            """)
    int upsertRetrievalTrace(@Param("queryId") Long queryId,
                             @Param("initialJson") String initialJson,
                             @Param("rerankedJson") String rerankedJson,
                             @Param("selectedChunkIdsCsv") String selectedChunkIdsCsv,
                             @Param("topK") int topK,
                             @Param("bm25Weight") double bm25Weight,
                             @Param("vectorWeight") double vectorWeight,
                             @Param("threshold") double threshold);

    @Insert("""
            INSERT INTO generation_traces(query_id, llm_model, prompt_template, generated_answer)
            VALUES (#{queryId}, #{model}, #{promptTemplate}, #{answer})
            ON CONFLICT (query_id) DO UPDATE SET generated_answer = EXCLUDED.generated_answer
            """)
    int upsertGenerationTrace(@Param("queryId") Long queryId,
                              @Param("model") String model,
                              @Param("promptTemplate") String promptTemplate,
                              @Param("answer") String answer);
}
