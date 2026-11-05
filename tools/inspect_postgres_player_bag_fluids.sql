-- Read-only inspection of current-character bag and fluid state (42.21.0).
-- Example: psql <connection options> -v world=servertest -v username=Alice -v slot=0 -f tools/inspect_postgres_player_bag_fluids.sql
-- Credentials should come from your normal psql environment/.pgpass.
-- Includes retained snapshots of this character life; live heads are updated
-- in place, so these rows are NOT a guaranteed history of every reconciliation.
\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;

WITH RECURSIVE selected AS (
    SELECT p.active_character_id AS character_id, h.snapshot_id AS head_id,
           h.revision AS head_revision
    FROM player_rel.player_slot p
    JOIN player_rel.character_head h ON h.character_id = p.active_character_id
    WHERE p.world = :'world' AND p.player_index = :'slot'::integer
      AND (p.username = :'username' OR p.split_username = :'username'
           OR (p.identity_mode = 'username' AND p.identity_key = :'username'))
), bags AS (
    SELECT s.id AS snapshot_id, s.id = selected.head_id AS is_current,
           selected.head_revision, s.capture_revision, s.capture_reason,
           s.captured_at, s.committed_at, s.status,
           i.id AS item_id, i.native_id, i.full_type, b.container_id,
           c.id AS fluid_component_id, c.coverage, c.codec_version
    FROM selected
    JOIN player_rel.snapshot s ON s.character_id = selected.character_id
    JOIN player_rel.bag_state b ON b.snapshot_id = s.id
    JOIN player_rel.item i ON i.snapshot_id = b.snapshot_id AND i.id = b.item_id
    LEFT JOIN player_rel.component c
      ON c.snapshot_id = s.id AND c.owner_object_id = i.id
     AND c.kind = 'entity.FluidContainer'
), fluid_nodes AS (
    SELECT v.*, '$'::text AS path, ARRAY[v.id] AS visited
    FROM bags
    JOIN player_rel.value_node v
      ON v.snapshot_id = bags.snapshot_id AND v.component_id = bags.fluid_component_id
     AND v.parent_id IS NULL
    UNION ALL
    SELECT child.*,
           parent.path || CASE WHEN child.key_kind = 'string'
                               THEN '.' || child.key_text
                               ELSE '[' || child.position || ']' END,
           parent.visited || child.id
    FROM fluid_nodes parent
    JOIN player_rel.value_node child
      ON child.snapshot_id = parent.snapshot_id
     AND child.component_id = parent.component_id AND child.parent_id = parent.id
    WHERE NOT child.id = ANY(parent.visited) AND cardinality(parent.visited) < 65
)
SELECT bags.*,
       (SELECT string_agg(e.kind || ':' || e.slot, ', ' ORDER BY e.kind, e.position)
        FROM player_rel.equipment e
        WHERE e.snapshot_id = bags.snapshot_id AND e.item_id = bags.item_id) AS equipment_slots,
       (SELECT jsonb_object_agg(n.path,
                   CASE n.value_kind
                       WHEN 'string' THEN to_jsonb(n.text_value)
                       WHEN 'number' THEN to_jsonb(n.number_value)
                       WHEN 'integer' THEN to_jsonb(n.integer_value)
                       WHEN 'boolean' THEN to_jsonb(n.boolean_value)
                       WHEN 'null' THEN 'null'::jsonb
                   END ORDER BY n.path)
        FROM fluid_nodes n
        WHERE n.snapshot_id = bags.snapshot_id AND n.component_id = bags.fluid_component_id
          AND n.value_kind IN ('string', 'number', 'integer', 'boolean', 'null')) AS fluid_fields
FROM bags
ORDER BY is_current DESC, captured_at DESC, full_type, item_id;

COMMIT;
