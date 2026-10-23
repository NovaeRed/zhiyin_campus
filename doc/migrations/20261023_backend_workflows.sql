BEGIN;
ALTER TABLE rag_sessions ADD COLUMN IF NOT EXISTS title varchar(120);
ALTER TABLE rag_sessions ADD COLUMN IF NOT EXISTS archived boolean NOT NULL DEFAULT false;
ALTER TABLE rag_sessions ADD COLUMN IF NOT EXISTS target_scope jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE rag_queries ADD COLUMN IF NOT EXISTS error_code varchar(64);
ALTER TABLE rag_queries ADD COLUMN IF NOT EXISTS failure_stage varchar(64);
ALTER TABLE student_profiles ADD COLUMN IF NOT EXISTS target_cohort varchar(16);

CREATE TABLE IF NOT EXISTS app_rag_requests (
 id uuid PRIMARY KEY, user_id bigint NOT NULL REFERENCES users(id), request_key varchar(128) NOT NULL,
 request_hash varchar(64) NOT NULL, session_id bigint REFERENCES rag_sessions(id), query_id bigint REFERENCES rag_queries(id),
 state varchar(20) NOT NULL CHECK(state IN ('running','completed','failed','cancelled')),
 response jsonb, error_code varchar(64), instance_id uuid NOT NULL,
 lease_until timestamptz NOT NULL DEFAULT now()+interval '2 minutes',
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(user_id,request_key)
);
CREATE INDEX IF NOT EXISTS app_rag_requests_lease ON app_rag_requests(state,lease_until);
CREATE TABLE IF NOT EXISTS app_ingestion_jobs (
 id uuid PRIMARY KEY, user_id bigint NOT NULL REFERENCES users(id), source_path text NOT NULL,
 original_name varchar(255) NOT NULL, request jsonb NOT NULL,
 state varchar(20) NOT NULL CHECK(state IN ('queued','processing','completed','failed','cancelled')),
 phase varchar(40) NOT NULL DEFAULT 'queued', progress integer NOT NULL DEFAULT 0 CHECK(progress BETWEEN 0 AND 100),
 attempt integer NOT NULL DEFAULT 0, result jsonb, error_code varchar(64), rebuild_of bigint REFERENCES knowledge_docs(id),
 instance_id uuid, lease_until timestamptz, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS app_ingestion_jobs_queue ON app_ingestion_jobs(state,created_at);
CREATE TABLE IF NOT EXISTS app_audit_events (
 id bigserial PRIMARY KEY, actor_id bigint REFERENCES users(id), action varchar(64) NOT NULL,
 object_type varchar(40) NOT NULL, object_id varchar(64), detail jsonb NOT NULL DEFAULT '{}'::jsonb,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS app_rag_feedback (
 id bigserial PRIMARY KEY, query_id bigint NOT NULL REFERENCES rag_queries(id), user_id bigint NOT NULL REFERENCES users(id),
 rating integer NOT NULL CHECK(rating IN (-1,1)), comment varchar(2000) NOT NULL DEFAULT '',
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE(query_id,user_id)
);
COMMIT;
