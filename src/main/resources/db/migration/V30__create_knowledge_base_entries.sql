-- Knowledge base read by the voice assistant during a call (menu, allergens, house
-- rules, access...). Unlike restaurants.attributes (fixed keys), entries are free
-- text retrieved by semantic similarity: each one stores an embedding of its text.
--
-- Embedding model: voyage-4-lite, 1024 dimensions, cosine distance. Changing the
-- model means re-embedding every row; changing the dimension means a new migration.
-- Requires PostgreSQL with pgvector (see docker/postgres/Dockerfile).

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_base_entries (
    id            UUID          NOT NULL DEFAULT gen_random_uuid(),
    restaurant_id UUID          NOT NULL,
    title         VARCHAR(200)  NOT NULL,
    content       TEXT          NOT NULL,
    -- manual: typed by the restaurateur; import: extracted from a document;
    -- unanswered: written in reply to a question the assistant could not answer.
    source        VARCHAR(16)   NOT NULL DEFAULT 'manual',
    -- Written by the application right after the row, in the same transaction.
    -- Not mapped by JPA: the vector type is only handled in native queries.
    embedding     vector(1024),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_kb_entries_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,
    CONSTRAINT chk_kb_entries_source CHECK (source IN ('manual', 'import', 'unanswered'))
);

CREATE INDEX idx_kb_entries_restaurant ON knowledge_base_entries (restaurant_id);

-- Approximate nearest neighbour over cosine distance (the <=> operator).
CREATE INDEX idx_kb_entries_embedding ON knowledge_base_entries
    USING hnsw (embedding vector_cosine_ops);

-- Questions callers asked that the assistant could not answer. The restaurateur
-- answers them in one sentence; the answer becomes a knowledge base entry.
CREATE TABLE unanswered_questions (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    restaurant_id   UUID         NOT NULL,
    question        TEXT         NOT NULL,
    -- Normalised form, so the same question asked twice is counted, not duplicated.
    question_key    VARCHAR(200) NOT NULL,
    asked_count     INTEGER      NOT NULL DEFAULT 1,
    last_asked_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status          VARCHAR(16)  NOT NULL DEFAULT 'open',
    answer_entry_id UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT uq_unanswered_restaurant_key UNIQUE (restaurant_id, question_key),
    CONSTRAINT fk_unanswered_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,
    CONSTRAINT fk_unanswered_entry FOREIGN KEY (answer_entry_id) REFERENCES knowledge_base_entries (id) ON DELETE SET NULL,
    CONSTRAINT chk_unanswered_status CHECK (status IN ('open', 'answered', 'ignored'))
);

CREATE INDEX idx_unanswered_restaurant_status ON unanswered_questions (restaurant_id, status);
