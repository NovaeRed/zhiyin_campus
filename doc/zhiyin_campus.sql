-- ==========================================
-- 0. 基础扩展与全局设置
-- ==========================================
CREATE
EXTENSION IF NOT EXISTS vector;
CREATE
EXTENSION IF NOT EXISTS "uuid-ossp";

-- 向量检索查询优化参数（建议在应用连接池初始化时设置）
-- SET hnsw.ef_search = 100;
-- SET hnsw.iterative_scan = strict_order;

-- ==========================================
-- 1. 用户与学籍画像（敏捷认证版）
-- ==========================================
CREATE TABLE users
(
    id            BIGSERIAL PRIMARY KEY,
    student_no    VARCHAR(32) UNIQUE NOT NULL, -- 学号（业务主键，如 2025010001）
    username      VARCHAR(50)        NOT NULL,
    password_hash VARCHAR(255)       NOT NULL, -- BCrypt 加密（CAS 接入后可降级为可选）
    role          VARCHAR(20) DEFAULT 'student' CHECK (role IN ('student', 'admin', 'super_admin', 'editor')),
    is_active     BOOLEAN     DEFAULT true,
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ DEFAULT NOW(),
    updated_at    TIMESTAMPTZ DEFAULT NOW(),
    deleted_at    TIMESTAMPTZ
);
CREATE INDEX idx_users_student_active ON users (student_no, is_active) WHERE deleted_at IS NULL;

CREATE TABLE student_profiles
(
    user_id            BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    student_no         VARCHAR(32) UNIQUE NOT NULL,
    major              VARCHAR(100)       NOT NULL,
    grade              INT                NOT NULL, -- 入学年份
    curriculum_version VARCHAR(32),
    credits_completed  DECIMAL(4, 1) DEFAULT 0.0,
    gpa                DECIMAL(3, 2),
    interest_tags      JSONB         DEFAULT '[]',
    synced_at          TIMESTAMPTZ   DEFAULT NOW()
);
CREATE INDEX idx_profiles_major_grade ON student_profiles (major, grade);

-- ==========================================
-- 2. 知识库与文档切割（严格三要素模型）
-- ==========================================
CREATE TABLE knowledge_docs
(
    id             BIGSERIAL PRIMARY KEY,
    title          VARCHAR(255) NOT NULL,
    file_path      VARCHAR(500) NOT NULL,           -- MinIO/OSS 相对路径
    file_type      VARCHAR(10) CHECK (file_type IN ('pdf', 'docx', 'xlsx', 'md', 'html', 'txt')),
    version        VARCHAR(32) DEFAULT 'v1',
    status         VARCHAR(20) DEFAULT 'pending' CHECK (status IN ('pending', 'processing', 'active', 'deprecated', 'failed')),
    metadata       JSONB       DEFAULT '{}'::jsonb, -- {department, effective_date, policy_level}
    chunk_strategy JSONB       DEFAULT '{}'::jsonb, -- 见下方注释规范
    created_by     BIGINT REFERENCES users (id),
    created_at     TIMESTAMPTZ DEFAULT NOW(),
    updated_at     TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_docs_status_updated ON knowledge_docs (status, updated_at DESC);

COMMENT
ON COLUMN knowledge_docs.chunk_strategy IS '
切割策略配置(JSONB)，示例:
- 固定+重叠: {"type":"fixed","params":{"chunk_size":500,"overlap":80}}
- 递归语义:  {"type":"recursive","params":{"chunk_size":512,"overlap":64,"separators":["\n\n","\n","。","；"]}}
- 父子切割:  {"type":"parent_child","params":{"parent_size":1200,"child_size":400,"overlap":64}}
- 表格专项:  {"type":"table_aware","params":{"preserve_structure":true}}
';

CREATE TABLE doc_chunks
(
    id           BIGSERIAL PRIMARY KEY,
    doc_id       BIGINT      NOT NULL REFERENCES knowledge_docs (id) ON DELETE CASCADE,

    -- 父子切割核心字段
    parent_id    BIGINT      REFERENCES doc_chunks (id) ON DELETE SET NULL,
    chunk_level  VARCHAR(10) NOT NULL CHECK (chunk_level IN ('parent', 'child')),

    -- 核心要素1：原文（LLM 阅读用，始终保留在 PG）
    content      TEXT        NOT NULL,

    -- 核心要素2：向量（MVP 期存 pgvector，后期迁移至 Milvus 可安全 DROP）
    embedding    vector(1024),                 -- BGE-m3 维度，可按模型调整

    -- 核心要素3：元数据（过滤与溯源用）
    metadata     JSONB       DEFAULT '{}'::jsonb,
    /* 标准化结构:
       {
         "file_name": "2024版计算机培养方案.pdf",
         "page_num": 12,
         "section_path": "第三章 毕业要求 > 3.2 学分认定",
         "chunk_type": "text|table|code",
         "token_count": 480,
         "effective_date": "2024-09-01"
       }
    */

    -- 反范式过滤字段（替代 JSONB 全表扫描，加速 RAG 前置过滤）
    target_major VARCHAR(100),                 -- 适用专业（NULL=全校）
    target_grade INT,                          -- 适用年级（NULL=全年级）
    doc_status   VARCHAR(20) DEFAULT 'active', -- 逻辑状态控制

    created_at   TIMESTAMPTZ DEFAULT NOW()
);

-- 索引设计（生产级优化）
-- 1. HNSW 向量索引（仅对 child 块生效，节省 50%+ 索引空间）
CREATE INDEX idx_chunks_embedding_hnsw
    ON doc_chunks USING hnsw (embedding vector_cosine_ops)
    WITH (m = 24, ef_construction = 128)
    WHERE chunk_level = 'child';

-- 2. RAG 高频过滤组合索引（覆盖 WHERE 条件）
CREATE INDEX idx_chunks_filter_fast
    ON doc_chunks (doc_status, target_major, target_grade, chunk_level) WHERE doc_status = 'active' AND chunk_level = 'child';

-- 3. 父子关联查询索引（生成答案时反查 Parent 上下文）
CREATE INDEX idx_chunks_parent_lookup ON doc_chunks (parent_id) WHERE chunk_level = 'child';

-- 4. 文档内顺序索引
CREATE INDEX idx_chunks_doc_order ON doc_chunks (doc_id, chunk_level, id);

-- ==========================================
-- 3. 策略路由缓存（避免重复决策）
-- ==========================================
CREATE TABLE chunk_routing_cache
(
    id                BIGSERIAL PRIMARY KEY,
    doc_type          VARCHAR(50) NOT NULL, -- 如: pdf_policy, xlsx_scholarship
    feature_hash      VARCHAR(64) NOT NULL, -- L2 启发式指标哈希
    resolved_strategy VARCHAR(30) NOT NULL,
    config_snapshot   JSONB,                -- 决策时使用的完整参数
    eval_metrics      JSONB,                -- {ndcg_5: 0.82, hit_rate: 0.89}
    created_at        TIMESTAMPTZ DEFAULT NOW(),
    updated_at        TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (doc_type, feature_hash)
);

-- ==========================================
-- 4. RAG 会话与工作流追踪
-- ==========================================
CREATE TABLE rag_sessions
(
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_sessions_user_time ON rag_sessions (user_id, created_at DESC);

CREATE TABLE rag_queries
(
    id               BIGSERIAL PRIMARY KEY,
    session_id       BIGINT             NOT NULL REFERENCES rag_sessions (id) ON DELETE CASCADE,
    trace_id         VARCHAR(64) UNIQUE NOT NULL DEFAULT gen_random_uuid(),
    original_query   TEXT               NOT NULL,
    rewritten_query  TEXT,
    intent_category  VARCHAR(50),
    workflow_state   VARCHAR(30)                 DEFAULT 'pending',
    final_confidence DECIMAL(3, 2),
    retry_count      INT                         DEFAULT 0,
    created_at       TIMESTAMPTZ                 DEFAULT NOW(),
    completed_at     TIMESTAMPTZ
);
CREATE INDEX idx_queries_session_time ON rag_queries (session_id, created_at DESC);
CREATE INDEX idx_queries_workflow ON rag_queries (workflow_state, created_at DESC) WHERE workflow_state != 'completed';

-- 检索过程快照
CREATE TABLE retrieval_traces
(
    query_id           BIGINT PRIMARY KEY REFERENCES rag_queries (id) ON DELETE CASCADE,
    initial_retrieval  JSONB,    -- [{chunk_id, bm25, vector, fused}]
    reranked_result    JSONB,    -- [{chunk_id, rerank_score, rank}]
    selected_chunk_ids BIGINT[], -- 最终送入 LLM 的 chunk ID
    top_k              INT,
    bm25_weight        DECIMAL(3, 2),
    vector_weight      DECIMAL(3, 2),
    rerank_threshold   DECIMAL(3, 2),
    created_at         TIMESTAMPTZ DEFAULT NOW()
);

-- 生成过程与评测指标
CREATE TABLE generation_traces
(
    query_id           BIGINT PRIMARY KEY REFERENCES rag_queries (id) ON DELETE CASCADE,
    llm_model          VARCHAR(50),
    prompt_template    VARCHAR(100),
    generated_answer   TEXT,
    faithfulness_score DECIMAL(3, 2), -- LLM-Judge 忠实度
    relevance_score    DECIMAL(3, 2), -- LLM-Judge 相关性
    token_usage        JSONB,         -- {prompt_tokens, completion_tokens, total}
    created_at         TIMESTAMPTZ DEFAULT NOW()
);

-- ==========================================
-- 5. 反馈、评测与自调节
-- ==========================================
CREATE TABLE user_feedback
(
    id            BIGSERIAL PRIMARY KEY,
    query_id      BIGINT NOT NULL REFERENCES rag_queries (id) ON DELETE CASCADE,
    user_id       BIGINT REFERENCES users (id) ON DELETE SET NULL,
    rating        INT CHECK (rating IN (1, 0, -1)), -- 1:赞成 0:中立 -1:反对
    feedback_tags JSONB       DEFAULT '[]',
    comment       TEXT,
    created_at    TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_feedback_query ON user_feedback (query_id);

CREATE TABLE ground_truth_qa
(
    id                 BIGSERIAL PRIMARY KEY,
    query              TEXT NOT NULL,
    expected_answer    TEXT,
    relevant_chunk_ids BIGINT[] NOT NULL,
    difficulty         VARCHAR(20) CHECK (difficulty IN ('easy', 'medium', 'hard')),
    query_type         VARCHAR(50),
    is_active          BOOLEAN     DEFAULT true,
    created_at         TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE eval_runs
(
    id           BIGSERIAL PRIMARY KEY,
    run_type     VARCHAR(30) CHECK (run_type IN ('offline', 'online')),
    metrics      JSONB NOT NULL, -- {hit_rate_k5, mrr, ndcg_5, context_precision, avg_faithfulness}
    sample_size  INT,
    duration_ms  INT,
    status       VARCHAR(20) DEFAULT 'running',
    started_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
);
CREATE INDEX idx_eval_runs_time ON eval_runs (run_type, status, completed_at DESC);

-- 动态参数中心
CREATE TABLE system_configs
(
    id           BIGSERIAL PRIMARY KEY,
    config_key   VARCHAR(100) UNIQUE NOT NULL,
    config_value JSONB               NOT NULL,
    description  TEXT,
    updated_by   BIGINT REFERENCES users (id),
    updated_at   TIMESTAMPTZ DEFAULT NOW(),
    version      INT         DEFAULT 1
);

-- 参数变更审计（支持回滚）
CREATE TABLE config_tuning_history
(
    id             BIGSERIAL PRIMARY KEY,
    config_key     VARCHAR(100) NOT NULL,
    old_value      JSONB,
    new_value      JSONB,
    trigger_reason VARCHAR(100),
    operator       BIGINT REFERENCES users (id),
    created_at     TIMESTAMPTZ DEFAULT NOW()
);

-- ==========================================
-- 6. 注释与初始配置种子数据
-- ==========================================
-- 初始化核心可调参数（自调节引擎默认值）
INSERT INTO system_configs (config_key, config_value, description)
VALUES ('top_k_default', '{
  "value": 10
}', '初始检索数量'),
       ('rerank_threshold', '{
         "value": 0.65
       }', 'Rerank 最低放行分数'),
       ('alpha_beta_ratio', '{
         "alpha": 0.7,
         "beta": 0.3
       }', '向量/BM25 融合权重'),
       ('chunk_routing_rules', '{
         "pdf": "parent_child",
         "xlsx": "table_aware",
         "md": "recursive",
         "default": "recursive"
       }', '文件类型→策略自动路由规则'),
       ('eval_cool_down_hours', '{
         "value": 24
       }', '自调节冷却期(小时)');

-- 分析统计信息（生产入库后执行）
-- ANALYZE users, student_profiles, knowledge_docs, doc_chunks, rag_sessions, rag_queries;