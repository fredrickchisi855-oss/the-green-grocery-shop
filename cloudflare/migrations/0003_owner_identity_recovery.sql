-- One-time cloud-authorized owner enrollment. Additive only; no shop records are removed.
CREATE TABLE IF NOT EXISTS owner_accounts (
  shop_id TEXT PRIMARY KEY REFERENCES shops(id),
  username TEXT NOT NULL COLLATE NOCASE,
  display_name TEXT NOT NULL,
  password_salt TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  password_iterations INTEGER NOT NULL,
  enrollment_challenge_hash TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_owner_accounts_username ON owner_accounts(shop_id, username);
CREATE TABLE IF NOT EXISTS owner_enrollment_challenges (
  challenge_hash TEXT PRIMARY KEY,
  shop_id TEXT NOT NULL REFERENCES shops(id),
  device_id TEXT NOT NULL REFERENCES devices(id),
  expires_at TEXT NOT NULL,
  consumed_at TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_owner_challenges_shop_expiry ON owner_enrollment_challenges(shop_id, expires_at);
