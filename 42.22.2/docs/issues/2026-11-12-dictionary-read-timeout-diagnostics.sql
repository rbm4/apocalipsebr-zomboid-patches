-- Read-only incident capture; run against game_runtime with an observing role.
-- No EXPLAIN ANALYZE: the plan below does not execute the expensive source query.
-- Query text and visibility may be restricted by the observing role.
BEGIN READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '500ms';

SELECT pid, application_name, client_addr, state, wait_event_type, wait_event,
       clock_timestamp() - query_start AS query_age,
       clock_timestamp() - xact_start AS transaction_age,
       pg_blocking_pids(pid) AS blockers, left(query, 1200) AS query
FROM pg_stat_activity
WHERE datname = current_database() AND pid <> pg_backend_pid()
  AND (query ILIKE '%world_dictionary_%' OR state = 'idle in transaction')
ORDER BY query_start;

SELECT relname, n_live_tup, n_dead_tup, last_analyze, last_autoanalyze,
       last_vacuum, last_autovacuum, seq_scan, idx_scan
FROM pg_stat_user_tables
WHERE schemaname = 'player_rel' AND relname LIKE 'world_dictionary_%'
ORDER BY relname;

SELECT tablename, indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'player_rel'
  AND tablename IN ('world_dictionary_entry', 'world_dictionary_entry_state',
                    'world_dictionary_runtime_view')
ORDER BY tablename, indexname;

SELECT v.namespace_id, v.id, v.server_id, v.status, v.identity_revision,
       n.game_build, n.world_version, n.dictionary_format_version
FROM player_rel.world_dictionary_runtime_view v
JOIN player_rel.world_dictionary_namespace n ON n.id = v.namespace_id
WHERE v.namespace_id = '8e1a120b-cf26-42b0-a025-45903a3a90a5'::uuid
  AND v.id = '22ae2ad1-5397-4049-8146-f400ea6d8556'::uuid;

-- Exact column projection/join shape used by READ_SOURCE_ENTRIES.
-- If view metadata is missing, the scalar revision is NULL: resolve that first.
EXPLAIN (COSTS, VERBOSE)
SELECT e.family,e.register_name,e.native_id,e.kind,e.stable_key,e.module_name,
       e.local_name,s.is_loaded,s.mod_id,s.exists_as_vanilla,s.is_modded,
       s.obsolete,s.removed,s.overrides_present,s.script_version
FROM player_rel.world_dictionary_entry e
LEFT JOIN player_rel.world_dictionary_entry_state s
  ON s.namespace_id=e.namespace_id AND s.family=e.family
 AND s.register_name=e.register_name AND s.native_id=e.native_id
 AND s.view_id='22ae2ad1-5397-4049-8146-f400ea6d8556'::uuid
WHERE e.namespace_id='8e1a120b-cf26-42b0-a025-45903a3a90a5'::uuid
  AND e.created_revision <= (
    SELECT identity_revision FROM player_rel.world_dictionary_runtime_view
    WHERE namespace_id='8e1a120b-cf26-42b0-a025-45903a3a90a5'::uuid
      AND id='22ae2ad1-5397-4049-8146-f400ea6d8556'::uuid
  );
-- This scalar-subquery plan is an approximation of JDBC's bound revision.
-- To compare precisely, replace the subquery with the observed identity_revision
-- literal; JDBC generic/custom prepared plans may still differ.
COMMIT;
