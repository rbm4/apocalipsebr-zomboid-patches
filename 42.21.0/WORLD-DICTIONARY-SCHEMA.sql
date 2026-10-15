-- Proposed backend migration contract, audited against PZ 42.21.0.
-- Reference DDL only: this file is NOT a numbered/applied backend migration.
-- See WORLD-DICTIONARY-SCHEMA.md for allocation, publication and loader rules.
-- UUIDs are supplied by the application; no extension or UUID generator required.
CREATE SCHEMA world_dict;

CREATE TABLE world_dict.namespace (
    id uuid PRIMARY KEY,
    name text COLLATE "C" NOT NULL UNIQUE CHECK (name <> ''),
    game_build text NOT NULL CHECK (game_build = '42.21.0'),
    world_version integer NOT NULL CHECK (world_version = 249),
    dictionary_format_version integer NOT NULL CHECK (dictionary_format_version = 1),
    current_revision bigint NOT NULL DEFAULT 0 CHECK (current_revision >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE world_dict.revision (
    namespace_id uuid NOT NULL REFERENCES world_dict.namespace(id),
    revision bigint NOT NULL CHECK (revision >= 0),
    reason text NOT NULL CHECK (reason <> ''),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (namespace_id, revision)
);

ALTER TABLE world_dict.namespace ADD CONSTRAINT namespace_current_revision_fk
    FOREIGN KEY (id, current_revision) REFERENCES world_dict.revision(namespace_id, revision)
    DEFERRABLE INITIALLY DEFERRED;

-- One info allocator for BOTH items and non-item entities.
-- Each named string/script register has an independent allocator.
-- Sprite next_id is preserved metadata; vanilla has no active allocator here.
CREATE TABLE world_dict.registry (
    namespace_id uuid NOT NULL REFERENCES world_dict.namespace(id),
    family text COLLATE "C" NOT NULL CHECK (family IN ('info','object','sprite','string','script')),
    register_name text COLLATE "C" NOT NULL DEFAULT '',
    next_id integer NOT NULL DEFAULT 0,
    string_max_length integer,
    created_revision bigint NOT NULL,
    PRIMARY KEY (namespace_id, family, register_name),
    FOREIGN KEY (namespace_id, created_revision) REFERENCES world_dict.revision(namespace_id, revision),
    CHECK ((family IN ('string','script') AND register_name <> '')
        OR (family IN ('info','object','sprite') AND register_name = '')),
    CHECK ((family IN ('info','string','script') AND next_id BETWEEN 0 AND 32767)
        OR (family = 'object' AND next_id BETWEEN 0 AND 127)
        OR (family = 'sprite' AND next_id >= 0)),
    CHECK ((family = 'string' AND string_max_length IS NOT NULL AND string_max_length > 0)
        OR (family <> 'string' AND string_max_length IS NULL))
);

-- Append-only identity catalog. Native IDs below are dictionary IDs, NOT instance IDs.
CREATE TABLE world_dict.entry (
    namespace_id uuid NOT NULL,
    family text COLLATE "C" NOT NULL,
    register_name text COLLATE "C" NOT NULL,
    native_id integer NOT NULL,
    kind text COLLATE "C" NOT NULL CHECK (kind IN ('item','entity','object','sprite','string','script')),
    stable_key text COLLATE "C" NOT NULL,
    module_name text COLLATE "C",
    local_name text COLLATE "C",
    created_revision bigint NOT NULL,
    PRIMARY KEY (namespace_id, family, register_name, native_id),
    UNIQUE (namespace_id, family, register_name, stable_key),
    UNIQUE (namespace_id, family, register_name, native_id, kind),
    FOREIGN KEY (namespace_id, family, register_name)
        REFERENCES world_dict.registry(namespace_id, family, register_name),
    FOREIGN KEY (namespace_id, created_revision) REFERENCES world_dict.revision(namespace_id, revision),
    CHECK ((family = 'info' AND kind IN ('item','entity'))
        OR (family <> 'info' AND family = kind)),
    CHECK ((family IN ('info','string','script') AND native_id BETWEEN 0 AND 32766)
        OR (family = 'object' AND native_id BETWEEN 0 AND 126)
        OR (family = 'sprite' AND native_id >= 0)),
    CHECK ((family = 'info' AND module_name IS NOT NULL AND module_name <> ''
            AND local_name IS NOT NULL AND local_name <> '' AND stable_key = module_name || '.' || local_name)
        OR (family <> 'info' AND module_name IS NULL AND local_name IS NULL))
);

-- Immutable per-startup view: shared identities plus THIS server's resolved definitions.
CREATE TABLE world_dict.runtime_view (
    namespace_id uuid NOT NULL,
    id uuid NOT NULL,
    identity_revision bigint NOT NULL,
    server_key text COLLATE "C" NOT NULL CHECK (server_key <> ''),
    save_key text COLLATE "C" NOT NULL CHECK (save_key <> ''),
    status text NOT NULL DEFAULT 'staging' CHECK (status IN ('staging','ready')),
    mod_manifest_sha256 text CHECK (mod_manifest_sha256 ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at timestamptz,
    PRIMARY KEY (namespace_id, id),
    FOREIGN KEY (namespace_id, identity_revision) REFERENCES world_dict.revision(namespace_id, revision),
    CHECK ((status = 'staging' AND published_at IS NULL) OR (status = 'ready' AND published_at IS NOT NULL))
);
CREATE INDEX runtime_view_lookup ON world_dict.runtime_view(namespace_id, server_key, save_key, published_at DESC)
    WHERE status = 'ready';

-- Freeze allocator header values alongside a published view, including empty registers.
CREATE TABLE world_dict.registry_state (
    namespace_id uuid NOT NULL,
    view_id uuid NOT NULL,
    family text COLLATE "C" NOT NULL,
    register_name text COLLATE "C" NOT NULL,
    next_id integer NOT NULL,
    PRIMARY KEY (namespace_id, view_id, family, register_name),
    FOREIGN KEY (namespace_id, view_id) REFERENCES world_dict.runtime_view(namespace_id, id),
    FOREIGN KEY (namespace_id, family, register_name)
        REFERENCES world_dict.registry(namespace_id, family, register_name),
    CHECK ((family IN ('info','string','script') AND next_id BETWEEN 0 AND 32767)
        OR (family = 'object' AND next_id BETWEEN 0 AND 127)
        OR (family = 'sprite' AND next_id >= 0))
);

-- Full reconciled view state for info/string/script entries, including absent definitions.
-- Object and sprite identities have no native loaded/mod/version state.
CREATE TABLE world_dict.entry_state (
    namespace_id uuid NOT NULL,
    view_id uuid NOT NULL,
    family text COLLATE "C" NOT NULL,
    register_name text COLLATE "C" NOT NULL,
    native_id integer NOT NULL,
    kind text COLLATE "C" NOT NULL CHECK (kind IN ('item','entity','string','script')),
    is_loaded boolean NOT NULL,
    mod_id text COLLATE "C",
    exists_as_vanilla boolean,
    is_modded boolean,
    obsolete boolean,
    removed boolean,
    overrides_present boolean,
    script_version bigint,
    PRIMARY KEY (namespace_id, view_id, family, register_name, native_id),
    UNIQUE (namespace_id, view_id, family, register_name, native_id, kind),
    FOREIGN KEY (namespace_id, view_id) REFERENCES world_dict.runtime_view(namespace_id, id),
    FOREIGN KEY (namespace_id, family, register_name, native_id, kind)
        REFERENCES world_dict.entry(namespace_id, family, register_name, native_id, kind),
    CHECK ((kind IN ('item','entity') AND mod_id IS NOT NULL AND mod_id <> ''
            AND exists_as_vanilla IS NOT NULL AND is_modded IS NOT NULL
            AND obsolete IS NOT NULL AND removed IS NOT NULL AND overrides_present IS NOT NULL
            AND is_modded = (mod_id <> 'pz-vanilla'))
        OR (kind IN ('string','script') AND mod_id IS NULL AND exists_as_vanilla IS NULL
            AND is_modded IS NULL AND obsolete IS NULL AND removed IS NULL AND overrides_present IS NULL)),
    CHECK ((kind = 'script' AND script_version IS NOT NULL) OR (kind <> 'script' AND script_version IS NULL))
);
CREATE INDEX entry_state_reverse_lookup ON world_dict.entry_state(namespace_id, family, register_name, native_id);

CREATE TABLE world_dict.mod_override (
    namespace_id uuid NOT NULL,
    view_id uuid NOT NULL,
    family text COLLATE "C" NOT NULL CHECK (family = 'info'),
    register_name text COLLATE "C" NOT NULL CHECK (register_name = ''),
    native_id integer NOT NULL,
    kind text COLLATE "C" NOT NULL CHECK (kind IN ('item','entity')),
    position smallint NOT NULL CHECK (position BETWEEN 0 AND 126),
    mod_id text COLLATE "C" NOT NULL CHECK (mod_id <> ''),
    PRIMARY KEY (namespace_id, view_id, family, register_name, native_id, position),
    UNIQUE (namespace_id, view_id, family, register_name, native_id, mod_id),
    FOREIGN KEY (namespace_id, view_id, family, register_name, native_id, kind)
        REFERENCES world_dict.entry_state(namespace_id, view_id, family, register_name, native_id, kind)
);

-- Audit only. Native lookup/load never replays this journal as its source of truth.
CREATE TABLE world_dict.journal (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    namespace_id uuid NOT NULL,
    revision bigint NOT NULL,
    view_id uuid,
    event_type text NOT NULL CHECK (event_type <> ''),
    detail jsonb NOT NULL CHECK (jsonb_typeof(detail) = 'object'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (namespace_id, revision) REFERENCES world_dict.revision(namespace_id, revision),
    FOREIGN KEY (namespace_id, view_id) REFERENCES world_dict.runtime_view(namespace_id, id)
);
CREATE INDEX journal_revision_lookup ON world_dict.journal(namespace_id, revision, id);

-- Provision each namespace in ONE transaction, with application-supplied :namespace_id:
-- INSERT namespace (...,current_revision) VALUES (...,0);
-- INSERT revision VALUES (:namespace_id,0,'initial',CURRENT_TIMESTAMP);
-- INSERT registry (...,family,register_name,next_id,string_max_length,created_revision):
--   ('info','',0,NULL,0), ('object','',0,NULL,0), ('sprite','',0,NULL,0),
--   ('string','Generic',0,64,0),
--   ('script','SpriteConfigs',0,NULL,0),
--   ('script','SpriteOverlayConfigs',0,NULL,0),
--   ('script','ContextMenuConfigs',0,NULL,0).
-- Do NOT insert game definitions, assign native IDs or invent script hashes in migration.
