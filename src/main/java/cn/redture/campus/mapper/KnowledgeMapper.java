package cn.redture.campus.mapper;

import cn.redture.campus.rag.repository.DocChunkInsert;
import cn.redture.campus.rag.repository.KnowledgeDocumentInsert;
import cn.redture.campus.rag.repository.RagQueryInsert;
import cn.redture.campus.rag.retrieval.RetrievedChunk;
import cn.redture.campus.rag.retrieval.CurriculumDocument;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface KnowledgeMapper {

    @Select("""
            SELECT c.id AS "chunkId", c.doc_id AS "docId", c.parent_id AS "parentId", d.title,
              COALESCE(p.content,c.content) AS content,c.content AS "matchedContent",c.target_grade AS "targetGrade",
              c.metadata::text AS metadata
            FROM doc_chunks c JOIN knowledge_docs d ON d.id=c.doc_id
            LEFT JOIN doc_chunks p ON p.id=c.parent_id AND p.doc_id=c.doc_id AND p.chunk_level='parent'
            WHERE c.doc_id=#{docId} AND d.status='active' AND c.doc_status='active' AND c.chunk_level='child'
              AND c.metadata->>'chunk_type'='table'
            ORDER BY c.id
            """)
    List<RetrievedChunk> curriculumCourseChunks(@Param("docId") Long docId);

    @Select("""
            SELECT d.id AS "docId", d.title, MIN(c.target_grade) AS "targetGrade", MIN(c.target_major) AS "targetMajor"
            FROM knowledge_docs d JOIN doc_chunks c ON c.doc_id = d.id
            WHERE d.status = 'active' AND c.doc_status = 'active' AND c.chunk_level = 'child'
              AND c.target_grade IS NOT NULL
            GROUP BY d.id HAVING COUNT(DISTINCT c.target_grade) = 1 AND COUNT(DISTINCT c.target_major) <= 1
            ORDER BY d.id
            """)
    List<CurriculumDocument> activeCurricula();

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
            <script>
            SELECT c.id AS "chunkId", c.doc_id AS "docId", c.parent_id AS "parentId", d.title,
                   COALESCE(p.content, c.content) AS content, c.content AS "matchedContent", c.target_grade AS "targetGrade",
                   COALESCE(p.metadata, c.metadata)::text AS metadata,
                   1 - (c.embedding &lt;=> #{embeddingVector}::vector) AS "vectorScore"
            FROM doc_chunks c
            JOIN knowledge_docs d ON d.id = c.doc_id
            LEFT JOIN doc_chunks p ON p.id = c.parent_id
            WHERE c.doc_status = 'active'
              AND d.status = 'active'
              AND c.chunk_level = 'child'
              AND c.embedding IS NOT NULL
              AND c.content !~ '^[[:space:]]*[[]PDF_PAGE[[:space:]]+[0-9]+[]][[:space:]]*$'
              <if test="excludedDocIds != null and excludedDocIds.size() > 0">
                AND d.id NOT IN <foreach collection="excludedDocIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </if>
              <if test="docIds != null">
                <choose>
                  <when test="docIds.size() > 0">
                    AND d.id IN <foreach collection="docIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                  </when>
                  <otherwise>AND 1 = 0</otherwise>
                </choose>
              </if>
              AND (CAST(#{targetMajor,jdbcType=VARCHAR} AS varchar) IS NULL
                   OR c.target_major IS NULL OR c.target_major = #{targetMajor,jdbcType=VARCHAR})
              AND (CAST(#{targetGrade,jdbcType=INTEGER} AS integer) IS NULL
                   OR c.target_grade IS NULL OR c.target_grade = #{targetGrade,jdbcType=INTEGER})
              AND (CAST(#{targetCohort,jdbcType=VARCHAR} AS varchar) IS NULL
                   OR c.metadata->>'target_cohort' IS NULL
                   OR c.metadata->>'target_cohort' = #{targetCohort,jdbcType=VARCHAR})
            ORDER BY c.embedding &lt;=> #{embeddingVector}::vector, c.id
            LIMIT #{limit}
            </script>
            """)
    List<RetrievedChunk> searchByVector(@Param("embeddingVector") String embeddingVector,
                                        @Param("targetMajor") String targetMajor,
                                        @Param("targetGrade") Integer targetGrade,
                                        @Param("targetCohort") String targetCohort,
                                        @Param("limit") int limit, @Param("docIds") List<Long> docIds,
                                        @Param("excludedDocIds") List<Long> excludedDocIds);

    @Select("""
            <script>
            WITH scored AS (
            SELECT c.id AS "chunkId", c.doc_id AS "docId", c.parent_id AS "parentId", d.title,
                   COALESCE(p.content, c.content) AS content, c.content AS "matchedContent", c.target_grade AS "targetGrade",
                   COALESCE(p.metadata, c.metadata)::text AS metadata,
                   (
                     ts_rank_cd(to_tsvector('simple', c.content), plainto_tsquery('simple', #{query}))
                     <if test="keywords != null and keywords.size() > 0">
                       +
                       <foreach collection="keywords" item="keyword" separator="+">
                         (CASE WHEN c.content ILIKE '%' || #{keyword} || '%'
                               THEN length(#{keyword}) ELSE 0.0 END
                          + CASE WHEN d.title ILIKE '%' || #{keyword} || '%'
                                 THEN length(#{keyword}) * 0.5 ELSE 0.0 END)
                       </foreach>
                     </if>
                   ) AS "bm25Score"
            FROM doc_chunks c
            JOIN knowledge_docs d ON d.id = c.doc_id
            LEFT JOIN doc_chunks p ON p.id = c.parent_id
            WHERE c.doc_status = 'active'
              AND d.status = 'active'
              AND c.chunk_level = 'child'
              AND c.content !~ '^[[:space:]]*[[]PDF_PAGE[[:space:]]+[0-9]+[]][[:space:]]*$'
              <if test="excludedDocIds != null and excludedDocIds.size() > 0">
                AND d.id NOT IN <foreach collection="excludedDocIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </if>
              <if test="docIds != null">
                <choose>
                  <when test="docIds.size() > 0">
                    AND d.id IN <foreach collection="docIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                  </when>
                  <otherwise>AND 1 = 0</otherwise>
                </choose>
              </if>
              AND (CAST(#{targetMajor,jdbcType=VARCHAR} AS varchar) IS NULL
                   OR c.target_major IS NULL OR c.target_major = #{targetMajor,jdbcType=VARCHAR})
              AND (CAST(#{targetGrade,jdbcType=INTEGER} AS integer) IS NULL
                   OR c.target_grade IS NULL OR c.target_grade = #{targetGrade,jdbcType=INTEGER})
              AND (CAST(#{targetCohort,jdbcType=VARCHAR} AS varchar) IS NULL
                   OR c.metadata->>'target_cohort' IS NULL
                   OR c.metadata->>'target_cohort' = #{targetCohort,jdbcType=VARCHAR})
              AND (to_tsvector('simple', c.content) @@ plainto_tsquery('simple', #{query})
                   <if test="keywords != null and keywords.size() > 0">
                     OR
                     <foreach collection="keywords" item="keyword" separator=" OR ">
                       c.content ILIKE '%' || #{keyword} || '%'
                     </foreach>
                   </if>)
            ), ranked AS (
                SELECT scored.*, ROW_NUMBER() OVER (
                    PARTITION BY "docId", COALESCE("parentId", "chunkId")
                    ORDER BY "bm25Score" DESC, "chunkId") AS context_rank
                FROM scored
            )
            SELECT * FROM ranked WHERE context_rank = 1
            ORDER BY "bm25Score" DESC, "chunkId"
            LIMIT #{limit}
            </script>
            """)
    List<RetrievedChunk> searchByKeyword(@Param("query") String query,
                                         @Param("keywords") List<String> keywords,
                                         @Param("targetMajor") String targetMajor,
                                         @Param("targetGrade") Integer targetGrade,
                                         @Param("targetCohort") String targetCohort,
                                         @Param("limit") int limit, @Param("docIds") List<Long> docIds,
                                         @Param("excludedDocIds") List<Long> excludedDocIds);

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
