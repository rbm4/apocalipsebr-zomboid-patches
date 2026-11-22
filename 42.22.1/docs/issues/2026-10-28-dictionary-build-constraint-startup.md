# Dictionary startup blocked by database build constraint

Created2026-10-28. Status: **schema correction implemented/tested; not applied to live database**.
See the [subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md) and
[cross-build character investigation](../reports/2026-10-28-cross-build-character-reconstruction.md).

The supplied42.22 log fails WorldDictionary.init at PROVISION_NAMESPACE with
SQLState23514, world_dictionary_namespace_game_build_check. PostgreSQL rejects
the namespace insert before publication and before player login/reconstruction.
The subsequent WorldDictionary backups and server termination follow that SQL
error; this log does not establish corruption of WorldDictionary.bin.

Backend V6 created separate checks requiring42.21.0 and world249. The target
connector now inserts42.22.0/251. New backend
[V34 migration](../../../../apocalipsebr-manager/manager/src/main/resources/db/game-runtime-migration/V34__Support_42_22_World_Dictionary_Namespaces.sql)
replaces those checks with one paired check admitting42.21.0/249 or42.22.0/251.
Old namespace rows/history, format1 checks, triggers, foreign keys and engine
privileges are unchanged. The original V6 file is unchanged. See the
[backend runtime guide](../../../../apocalipsebr-manager/manager/docs/game-runtime-datasource.md)
for rollout and isolated regression coverage.

Deploy the backend containing V34 and let its gameRuntimeFlyway apply the new
migration to the game-runtime datasource before retrying server startup.
Recompiling the Java patch or restarting only the game server cannot apply DDL.
No live database migration, deployment or server restart was executed here.

This fixes the reported CHECK violation. A reused older namespace can next hit
the connector's separate metadata guard. Old source-view loading and cross-build
character recovery remain separate work. Do not relabel historical namespace
rows or delete dictionary files to bypass this error.

Validation: WorldDictionaryBuildMigrationTest passed against isolated PostgreSQL17.6
(1 test,0 failures/errors/skips): actual V6 rejection reproduced, V34 upgrade and
re-run validated, old namespace/history preserved, supported pairs accepted and
invalid pairs/format rejected. Test cluster stopped; live datasource untouched.
