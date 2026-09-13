-- Free-text knowledge base read by the AI during a call (menu, allergens, house
-- rules, access...). Unlike restaurants.attributes (fixed keys), entries are
-- retrieved by semantic similarity: each entry stores an embedding of its content.
--
-- Embedding model is fixed to voyage-4-lite, 1024 dimensions, cosine similarity.
-- Changing the model requires re-embedding every row; changing the dimension
-- requires a new migration. Requires the pgvector/pgvector:pg16 image.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_base_entries (
    id            UUID          NOT NULL DEFAULT gen_random_uuid(),
    restaurant_id UUID          NOT NULL,
    title         VARCHAR(200)  NOT NULL,
    content       TEXT          NOT NULL,
    -- Where the text came from: typed by the restaurateur, imported from a
    -- document, or answered from a question the bot could not handle.
    source        VARCHAR(16)   NOT NULL DEFAULT 'manual',
    embedding     vector(1024)  NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_kb_entries_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,
    CONSTRAINT chk_kb_entries_source CHECK (source IN ('manual', 'import', 'unanswered'))
);

-- Every search is scoped to one restaurant first, then ranked by similarity.
CREATE INDEX idx_kb_entries_restaurant ON knowledge_base_entries (restaurant_id);

-- HNSW approximate nearest-neighbour index over cosine distance (the <=> operator).
CREATE INDEX idx_kb_entries_embedding ON knowledge_base_entries
    USING hnsw (embedding vector_cosine_ops);
