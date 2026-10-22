-- Opaque versioned operation specification. Preserve its exact text across readers.
ALTER TABLE player_rel.lobby_event_control
    ADD COLUMN desired_metadata_json text NOT NULL DEFAULT '{}',
    ADD CONSTRAINT lobby_event_metadata_object CHECK (
        length(desired_metadata_json) <= 32768
        AND jsonb_typeof(desired_metadata_json::jsonb) = 'object'
    );

CREATE OR REPLACE FUNCTION player_rel.guard_lobby_event_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.server_key IS DISTINCT FROM OLD.server_key OR NEW.world IS DISTINCT FROM OLD.world THEN
        RAISE EXCEPTION 'Event scope is immutable';
    END IF;
    IF NEW.desired_revision < OLD.desired_revision OR
       (ROW(NEW.desired_event_id,NEW.desired_name,NEW.desired_starts_at,NEW.desired_state,NEW.desired_metadata_json)
        IS DISTINCT FROM ROW(OLD.desired_event_id,OLD.desired_name,OLD.desired_starts_at,OLD.desired_state,OLD.desired_metadata_json)
        AND NEW.desired_revision <= OLD.desired_revision) THEN
        RAISE EXCEPTION 'Schedule changes require a strictly increasing desired_revision';
    END IF;
    RETURN NEW;
END $$;
