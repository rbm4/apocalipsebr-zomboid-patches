-- Apply through the backend migration/provisioning process before deploying the patch.
-- Receipts must remain available for as long as a server journal/backup can be replayed.
CREATE TABLE public.apocbr_persistence_receipt (
    operation_id uuid PRIMARY KEY,
    committed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
