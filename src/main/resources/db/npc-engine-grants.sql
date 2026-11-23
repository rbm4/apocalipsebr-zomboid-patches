-- Administrator bootstrap after backend-owned schemas/tables exist. Never executed automatically by the agent.
-- Default privileges belong to the actual migration creator; adjust FOR ROLE if that creator differs.
GRANT USAGE, CREATE ON SCHEMA public, player_rel, npc_engine TO pz_server_01;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public, player_rel, npc_engine TO pz_server_01;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public, player_rel, npc_engine TO pz_server_01;
ALTER DEFAULT PRIVILEGES FOR ROLE apocalipsebr IN SCHEMA public, player_rel, npc_engine
    GRANT ALL PRIVILEGES ON TABLES TO pz_server_01;
ALTER DEFAULT PRIVILEGES FOR ROLE apocalipsebr IN SCHEMA public, player_rel, npc_engine
    GRANT ALL PRIVILEGES ON SEQUENCES TO pz_server_01;
