-- One-time activation keys for the separate Administrator setup website.
CREATE TABLE IF NOT EXISTS owner_setup_keys (
  key_hash TEXT PRIMARY KEY,
  shop_id TEXT NOT NULL REFERENCES shops(id),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at TEXT NOT NULL,
  consumed_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_owner_setup_keys_shop_expiry
  ON owner_setup_keys(shop_id, expires_at);
