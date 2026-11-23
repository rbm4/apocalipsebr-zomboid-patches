CREATE TABLE apocbr_world.chunk_object_mutations (
    dataset text NOT NULL,
    run_id uuid NOT NULL,
    object_id uuid NOT NULL,
    revision bigint NOT NULL,
    x integer NOT NULL,
    y integer NOT NULL,
    z integer NOT NULL,
    object_index integer NOT NULL,
    special_object boolean NOT NULL,
    payload bytea,
    PRIMARY KEY (dataset, run_id, object_id),
    CHECK (revision > 0),
    CHECK (object_index >= 0),
    CHECK (payload IS NULL OR octet_length(payload) BETWEEN 1 AND 65536)
)
