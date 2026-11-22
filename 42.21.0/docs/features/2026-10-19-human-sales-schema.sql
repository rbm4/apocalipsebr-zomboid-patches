-- Apply with the backend migration role before enabling guide trades.
-- Reference contract for manager game-runtime migration V10__Create_Human_Sale.sql.
-- Requires the existing public.apocbr_persistence_receipt migration as well.
BEGIN;
CREATE TABLE IF NOT EXISTS player_rel.apocbr_human_sale (
    sale_id uuid PRIMARY KEY,
    server_id integer NOT NULL CHECK (server_id > 0),
    world text NOT NULL,
    username text NOT NULL,
    steam_id text,
    player_index smallint NOT NULL CHECK (player_index BETWEEN 0 AND 3),
    npc_key text NOT NULL,
    sold_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    item_type text NOT NULL CHECK (item_type = 'Base.Money'),
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 500),
    native_item_ids jsonb NOT NULL CHECK (jsonb_typeof(native_item_ids) = 'array' AND jsonb_array_length(native_item_ids) = quantity),
    status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'credited', 'rejected', 'review')),
    credit_amount numeric(20, 4) CHECK (credit_amount >= 0),
    credit_currency text,
    credited_at timestamptz,
    backend_reference text UNIQUE,
    CHECK (status <> 'credited' OR (credit_amount IS NOT NULL AND credit_currency IS NOT NULL AND credited_at IS NOT NULL AND backend_reference IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS apocbr_human_sale_pending ON player_rel.apocbr_human_sale(recorded_at, sale_id) WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS apocbr_human_sale_account ON player_rel.apocbr_human_sale(server_id, username, sold_at);
COMMENT ON TABLE player_rel.apocbr_human_sale IS 'Server-owned money delivery receipts. Backend owns pricing and credits. Reconcile inventory persistence before production crediting.';
COMMENT ON COLUMN player_rel.apocbr_human_sale.quantity IS 'Count of individual Base.Money item instances, not money bundles, price, or account credit.';
COMMENT ON COLUMN player_rel.apocbr_human_sale.native_item_ids IS 'Auditable source inventory IDs; scoped to world/server, not permanent globally unique identities.';
COMMIT;
