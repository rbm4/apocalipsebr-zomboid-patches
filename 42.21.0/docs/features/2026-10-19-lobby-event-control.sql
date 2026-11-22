-- Contract v1. BACKEND-OWNED MIGRATION: review/apply through backend migrations.
-- The game NEVER creates/alters schema. No credentials or role names belong here.
CREATE SCHEMA IF NOT EXISTS player_rel;
CREATE TABLE player_rel.lobby_event_control (
    server_key text NOT NULL CHECK (server_key ~ '^[a-zA-Z0-9_-]{1,80}$'),
    world text NOT NULL CHECK (length(btrim(world)) BETWEEN 1 AND 128),
    desired_event_id uuid,
    desired_revision bigint NOT NULL DEFAULT 0 CHECK (desired_revision BETWEEN 0 AND 9007199254740991),
    desired_name text,
    desired_starts_at timestamptz,
    desired_state text NOT NULL DEFAULT 'idle' CHECK (desired_state IN ('idle','scheduled','cancelled')),
    reported_event_id uuid,
    reported_revision bigint CHECK (reported_revision BETWEEN 1 AND 9007199254740991),
    reported_state text CHECK (reported_state = 'release_requested'),
    reported_at timestamptz,
    engine_version text NOT NULL,
    engine_heartbeat_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (server_key,world),
    CHECK (
        (desired_state='idle' AND desired_event_id IS NULL AND desired_name IS NULL AND desired_starts_at IS NULL)
        OR (desired_state IN ('scheduled','cancelled') AND desired_revision > 0
            AND desired_event_id IS NOT NULL AND desired_name IS NOT NULL
            AND length(btrim(desired_name)) BETWEEN 1 AND 160 AND desired_name !~ '[[:cntrl:]]'
            AND desired_starts_at IS NOT NULL AND isfinite(desired_starts_at)
            AND desired_starts_at >= '1970-01-01 00:00:00.001+00'::timestamptz
            AND desired_starts_at < '10000-01-01 00:00:00+00'::timestamptz)
    ),
    CHECK ((reported_event_id IS NULL AND reported_revision IS NULL AND reported_state IS NULL AND reported_at IS NULL)
        OR (reported_event_id IS NOT NULL AND reported_revision IS NOT NULL
            AND reported_state IS NOT NULL AND reported_at IS NOT NULL))
);

CREATE FUNCTION player_rel.guard_lobby_event_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.server_key IS DISTINCT FROM OLD.server_key OR NEW.world IS DISTINCT FROM OLD.world THEN
        RAISE EXCEPTION 'Event scope is immutable';
    END IF;
    IF NEW.desired_revision < OLD.desired_revision OR
       (ROW(NEW.desired_event_id,NEW.desired_name,NEW.desired_starts_at,NEW.desired_state)
        IS DISTINCT FROM ROW(OLD.desired_event_id,OLD.desired_name,OLD.desired_starts_at,OLD.desired_state)
        AND NEW.desired_revision <= OLD.desired_revision) THEN
        RAISE EXCEPTION 'Schedule changes require a strictly increasing desired_revision';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER guard_lobby_event_revision BEFORE UPDATE ON player_rel.lobby_event_control
    FOR EACH ROW EXECUTE FUNCTION player_rel.guard_lobby_event_revision();

-- Backend publishes using optimistic concurrency (bind all values):
-- UPDATE player_rel.lobby_event_control
-- SET desired_event_id=:uuid, desired_revision=desired_revision+1,
--     desired_name=:name, desired_starts_at=:instant, desired_state='scheduled'
-- WHERE server_key=:server AND world=:world AND desired_revision=:expected_revision
-- RETURNING desired_revision;
-- Zero returned rows = conflict/missing scope; reread, never overwrite blindly.
-- Cancel similarly with desired_state='cancelled', desired_revision+1.
-- Do NOT delete/recreate control rows or clear engine-owned receipt columns.
