CREATE TABLE apocbr_world.map_chunks (
    dataset text NOT NULL,
    wx integer NOT NULL,
    wy integer NOT NULL,
    payload bytea NOT NULL CHECK (octet_length(payload) >= 17),
    PRIMARY KEY (dataset, wx, wy)
)
