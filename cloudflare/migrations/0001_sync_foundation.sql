CREATE TABLE IF NOT EXISTS shops (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  shop_id TEXT NOT NULL REFERENCES shops(id),
  label TEXT NOT NULL DEFAULT 'Android device',
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen_at TEXT,
  revoked_at TEXT
);
CREATE TABLE IF NOT EXISTS sync_records (
  shop_id TEXT NOT NULL REFERENCES shops(id),
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  version INTEGER NOT NULL DEFAULT 1,
  deleted INTEGER NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
  updated_by_device TEXT,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (shop_id, entity_type, entity_id)
);
CREATE TABLE IF NOT EXISTS sync_operations (
  operation_id TEXT PRIMARY KEY,
  shop_id TEXT NOT NULL REFERENCES shops(id),
  device_id TEXT NOT NULL REFERENCES devices(id),
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  operation TEXT NOT NULL CHECK (operation IN ('INSERT','UPDATE','DELETE')),
  base_version INTEGER NOT NULL DEFAULT 0,
  resulting_version INTEGER,
  payload_json TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'APPLIED' CHECK (status IN ('APPLIED','CONFLICT')),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sync_records_updated ON sync_records(shop_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_sync_operations_shop ON sync_operations(shop_id, created_at);
