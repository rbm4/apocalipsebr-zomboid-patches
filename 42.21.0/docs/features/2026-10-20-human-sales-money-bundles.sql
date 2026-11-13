-- Standalone equivalent of manager game-runtime migration V12.
-- Run once after 2026-10-19-human-sales-schema.sql; use Flyway instead for managed databases.
BEGIN;
ALTER TABLE player_rel.apocbr_human_sale
    ADD COLUMN bundle_item_ids jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE player_rel.apocbr_human_sale
    DROP CONSTRAINT apocbr_human_sale_quantity_check,
    DROP CONSTRAINT apocbr_human_sale_native_item_ids_check,
    ADD CONSTRAINT apocbr_human_sale_quantity_check CHECK (quantity BETWEEN 1 AND 50000),
    ADD CONSTRAINT apocbr_human_sale_native_item_ids_check CHECK (
        jsonb_typeof(native_item_ids) = 'array'
        AND jsonb_array_length(native_item_ids) BETWEEN 1 AND 500),
    ADD CONSTRAINT apocbr_human_sale_bundle_item_ids_check CHECK (
        jsonb_typeof(bundle_item_ids) = 'array'
        AND native_item_ids @> bundle_item_ids
        AND quantity = jsonb_array_length(native_item_ids) + 99 * jsonb_array_length(bundle_item_ids));
COMMENT ON COLUMN player_rel.apocbr_human_sale.quantity IS 'Base.Money equivalent: each note is 1 and each Base.MoneyBundle is 100. Backend applies credit pricing.';
COMMENT ON COLUMN player_rel.apocbr_human_sale.bundle_item_ids IS 'Subset of native_item_ids identifying Base.MoneyBundle instances; empty for legacy note-only receipts.';
COMMIT;
