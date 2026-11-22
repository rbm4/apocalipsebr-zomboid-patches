-- Read-only exercise regularity / stiffness inspection for 42.21.0.
-- psql <connection options> -v world=servertest -v username=Alice -v slot=0 -f tools/inspect_postgres_player_fitness.sql
-- Use normal psql credentials/.pgpass. Run after exercise, after logout, and after login.
-- Live snapshots update in place: retained snapshots are not a full history.
\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;

-- Shows whether the head exists, when it committed, and whether it contains fitness rows.
WITH selected AS (
    SELECT p.active_character_id AS character_id, h.snapshot_id, h.revision
    FROM player_rel.player_slot p
    JOIN player_rel.character_head h ON h.character_id = p.active_character_id
    WHERE p.world = :'world' AND p.player_index = :'slot'::integer
      AND (p.username = :'username' OR p.split_username = :'username'
           OR (p.identity_mode = 'username' AND p.identity_key = :'username'))
)
SELECT s.id AS snapshot_id, s.id = selected.snapshot_id AS is_current,
       selected.revision AS head_revision, s.source_dataset_id, s.capture_revision, s.capture_reason,
       s.captured_at, s.committed_at, s.status,
       (SELECT count(*) FROM player_rel.fitness_value f WHERE f.snapshot_id = s.id) AS fitness_rows,
       (SELECT count(*) FROM player_rel.fitness_value f WHERE f.snapshot_id = s.id
            AND f.map_name = 'regularityMap') AS regularity_rows,
       (SELECT count(*) FROM player_rel.fitness_body_part f WHERE f.snapshot_id = s.id) AS active_stiffness_parts
FROM selected JOIN player_rel.snapshot s ON s.character_id = selected.character_id
ORDER BY is_current DESC, s.captured_at DESC;

-- Current authoritative values. regularityMap is the exercise progress bar;
-- stiffnessIncMap/stiffnessTimerMap describe delayed soreness, exeTimer its exercise time.
SELECT h.revision, s.captured_at, s.committed_at,
       f.map_name, f.key, f.real_value, f.integer_value
FROM player_rel.player_slot p
JOIN player_rel.character_head h ON h.character_id = p.active_character_id
JOIN player_rel.snapshot s ON s.id = h.snapshot_id
JOIN player_rel.fitness_value f ON f.snapshot_id = h.snapshot_id AND f.character_id = p.active_character_id
WHERE p.world = :'world' AND p.player_index = :'slot'::integer
  AND (p.username = :'username' OR p.split_username = :'username'
       OR (p.identity_mode = 'username' AND p.identity_key = :'username'))
ORDER BY f.map_name, f.key;

-- Current soreness is separate from exercise regularity.
SELECT b.body_part_key, b.stiffness
FROM player_rel.player_slot p
JOIN player_rel.character_head h ON h.character_id = p.active_character_id
JOIN player_rel.body_part b ON b.snapshot_id = h.snapshot_id AND b.character_id = p.active_character_id
WHERE p.world = :'world' AND p.player_index = :'slot'::integer
  AND (p.username = :'username' OR p.split_username = :'username'
       OR (p.identity_mode = 'username' AND p.identity_key = :'username'))
ORDER BY b.body_part_key;

COMMIT;
