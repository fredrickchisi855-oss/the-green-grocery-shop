-- 0002: device credential lifecycle and owner-authorized pairing codes.
-- Store only SHA-256 hashes of device tokens and one-time pairing codes.
-- Never store owner passwords, cashier PINs, or raw device credentials.
ALTER TABLE devices ADD COLUMN token_hash TEXT;
ALTER TABLE devices ADD COLUMN token_created_at TEXT;
ALTER TABLE devices ADD COLUMN token_last_used_at TEXT;

CREATE TABLE IF NOT EXISTS device_pairing_codes (
  code_hash TEXT PRIMARY KEY,
  shop_id TEXT NOT NULL REFERENCES shops(id),
  created_by TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at TEXT NOT NULL,
  consumed_at TEXT,
  consumed_by_device TEXT REFERENCES devices(id)
);

CREATE INDEX IF NOT EXISTS idx_pairing_codes_shop_expiry
  ON device_pairing_codes(shop_id, expires_at);
