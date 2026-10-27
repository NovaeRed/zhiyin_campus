package cn.redture.campus.mapper;

import cn.redture.campus.pojo.vo.PortalViews.*;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface PortalMapper {
    String FILTER =
        " WHERE d.metadata->>'deleted_at' IS NULL AND (#{search} = '' OR strpos(lower(d.title),"
            + " lower(#{search})) > 0) AND (#{status} = '' OR d.status = #{status}) ";
    String DOC_COLUMNS =
        "SELECT d.id, d.title, d.file_type AS \"fileType\", d.version, d.status,"
            + " d.metadata::text AS metadata, d.chunk_strategy::text AS \"chunkStrategy\","
            + " u.username AS \"createdByName\", d.created_at AS \"createdAt\", d.updated_at AS"
            + " \"updatedAt\", (SELECT COUNT(*) FROM doc_chunks c WHERE c.doc_id=d.id AND"
            + " c.chunk_level='parent') AS \"parentChunks\", (SELECT COUNT(*) FROM doc_chunks c"
            + " WHERE c.doc_id=d.id AND c.chunk_level='child') AS \"childChunks\" FROM"
            + " knowledge_docs d LEFT JOIN users u ON u.id=d.created_by ";

    @Select(
        DOC_COLUMNS
            + FILTER
            + "ORDER BY d.created_at DESC, d.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Document> documents(
        @Param("search") String search,
        @Param("status") String status,
        @Param("limit") int limit,
        @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM knowledge_docs d " + FILTER)
    long countDocuments(@Param("search") String search, @Param("status") String status);

    @Select(DOC_COLUMNS + "WHERE d.id=#{id} AND d.metadata->>'deleted_at' IS NULL")
    Document document(@Param("id") Long id);

    @Select(
        "SELECT id, parent_id AS \"parentId\", chunk_level AS \"chunkLevel\", content,"
            + " metadata::text AS metadata, target_major AS \"targetMajor\", target_grade AS"
            + " \"targetGrade\", doc_status AS \"docStatus\" FROM doc_chunks WHERE doc_id=#{id}"
            + " ORDER BY id LIMIT #{limit} OFFSET #{offset}")
    List<Chunk> chunks(
        @Param("id") Long id, @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM doc_chunks WHERE doc_id=#{id}")
    long countChunks(@Param("id") Long id);

    @Select(
        "SELECT status FROM knowledge_docs WHERE id=#{id} AND metadata->>'deleted_at' IS NULL"
            + " FOR UPDATE")
    String lockStatus(@Param("id") Long id);

    @Select(
        "SELECT EXISTS(SELECT 1 FROM app_ingestion_jobs WHERE rebuild_of=#{id} AND state IN"
            + " ('queued','processing'))")
    boolean hasLiveRebuild(@Param("id") Long id);

    @Update(
        "UPDATE knowledge_docs SET"
            + " status='deprecated',metadata=COALESCE(metadata,'{}'::jsonb)||jsonb_build_object('deleted_at',now(),'deleted_by',#{userId}),updated_at=now()"
            + " WHERE id=#{id} AND metadata->>'deleted_at' IS NULL")
    int deleteDocument(@Param("id") Long id, @Param("userId") Long userId);

    @Select(
        "SELECT s.id, COALESCE(s.title,(SELECT left(q.original_query, 80) FROM rag_queries q"
            + " WHERE q.session_id=s.id ORDER BY q.id LIMIT 1), '新对话') AS title, (SELECT"
            + " COUNT(*) FROM rag_queries q WHERE q.session_id=s.id) AS \"queryCount\","
            + " s.updated_at AS \"updatedAt\" FROM rag_sessions s WHERE s.user_id=#{userId} AND"
            + " NOT s.archived AND s.target_scope->>'deletedAt' IS NULL ORDER BY s.updated_at"
            + " DESC, s.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Session> sessions(
        @Param("userId") Long userId, @Param("limit") int limit, @Param("offset") int offset);

    @Select(
        "SELECT COUNT(*) FROM rag_sessions WHERE user_id=#{userId} AND NOT archived AND"
            + " target_scope->>'deletedAt' IS NULL")
    long countSessions(@Param("userId") Long userId);

    @Select(
        "SELECT EXISTS(SELECT 1 FROM rag_sessions WHERE id=#{id} AND user_id=#{userId} AND"
            + " target_scope->>'deletedAt' IS NULL)")
    boolean ownsSession(@Param("id") Long id, @Param("userId") Long userId);

    @Select(
        "SELECT q.id AS \"queryId\", q.original_query AS question, q.workflow_state AS state,"
            + " CASE WHEN q.workflow_state='completed' THEN g.generated_answer END AS answer,"
            + " CASE WHEN q.workflow_state='completed' AND g.llm_model <> 'none' AND"
            + " g.prompt_template <> 'citation-rejected-v1' AND trim(g.generated_answer) !~"
            + " '^根据现有资料无法回答[。.!！[:space:]]*$' THEN t.reranked_result::text ELSE '[]' END AS"
            + " \"sourcesJson\", q.created_at AS \"createdAt\" FROM rag_queries q JOIN"
            + " rag_sessions s ON s.id=q.session_id LEFT JOIN generation_traces g ON"
            + " g.query_id=q.id LEFT JOIN retrieval_traces t ON t.query_id=q.id WHERE"
            + " q.session_id=#{id} AND s.user_id=#{userId} AND s.target_scope->>'deletedAt' IS"
            + " NULL ORDER BY q.id LIMIT #{limit} OFFSET #{offset}")
    List<HistoryQuery> history(
        @Param("id") Long id,
        @Param("userId") Long userId,
        @Param("limit") int limit,
        @Param("offset") int offset);

    @Select(
        "SELECT COUNT(*) FROM rag_queries q JOIN rag_sessions s ON s.id=q.session_id WHERE"
            + " s.id=#{id} AND s.user_id=#{userId} AND s.target_scope->>'deletedAt' IS NULL")
    long countHistory(@Param("id") Long id, @Param("userId") Long userId);
}
