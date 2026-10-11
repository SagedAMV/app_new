-- Apply manually to the dedicated provisioning D1 database after deployment is authorized.
-- Only SHA-256 token hashes and public request bindings are stored here. Never insert R2 secrets.
CREATE TABLE IF NOT EXISTS provisioning_invites (
    token_hash TEXT NOT NULL PRIMARY KEY CHECK(length(token_hash) = 64 AND token_hash NOT GLOB '*[^0-9a-f]*'),
    expires_at INTEGER NOT NULL CHECK(typeof(expires_at) = 'integer' AND expires_at > 0),
    enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0, 1)),
    claim_hash TEXT CHECK(claim_hash IS NULL OR (length(claim_hash) = 64 AND claim_hash NOT GLOB '*[^0-9a-f]*')),
    claim_expires_at INTEGER CHECK(claim_expires_at IS NULL OR (typeof(claim_expires_at) = 'integer' AND claim_expires_at > 0)),
    label TEXT NOT NULL DEFAULT '' CHECK(length(label) <= 64),
    CHECK((claim_hash IS NULL AND claim_expires_at IS NULL) OR
          (claim_hash IS NOT NULL AND claim_expires_at IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS provisioning_invites_expiry ON provisioning_invites(expires_at);
CREATE INDEX IF NOT EXISTS provisioning_claims_expiry ON provisioning_invites(claim_expires_at);
