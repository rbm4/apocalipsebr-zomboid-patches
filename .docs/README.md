# ApocalipseBR agent documentation

Active documentation for the root Maven Byte Buddy agent. Version-directory
documentation and sources remain historical references.

## Current direction

As of 2026-11-21, new feature ports target the original game JAR with the agent.
Patched runtime coexistence is no longer the migration target. Legacy engine
optimizations are not present merely because the agent loads successfully.
Each session selects one bounded feature; no migration queue is authorized here.

## Index

- [Server XML hotfixes](features/hotfixes.md)
- [Server Lua APIs, solar power and bounded service workers](features/server-lua.md)

- [Agent architecture and original-JAR development](architecture/README.md)
- [Feature implementation catalog](features/README.md)
- [PostgreSQL and NPC runtime migration](features/postgresql.md)
- [PostgreSQL dictionary setup](features/postgresql-dictionary.md)
- [PostgreSQL player authority and historical payload decoding](features/postgresql-players.md)
- [Dictionary prerequisites and PostgreSQL player reconstruction](reports/2026-11-21-player-reconstruction-context.md)
- [Bot engine vanilla dependencies before porting](reports/2026-11-21-npc-dependency-map.md)
- [NPC actors, gameplay adapters and persistence](features/npc-engine.md)
- [Migration workflow and status](migrations/README.md)
- [Per-feature migration template](migrations/feature-template.md)
- [Validation and runtime checks](validation/README.md)
- [Opt-in real-server integration tests](validation/server-integration.md)
- [Reports and historical evidence](reports/README.md)

For each implemented feature, add its guide under `features/`, link it here or
from the feature catalog, and update its migration status with validation evidence.
