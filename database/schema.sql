PRAGMA foreign_keys = ON;

-- Local-first database for THE GREEN GROCERY SHOP.
-- Built for a mixed grocery shop AND tuckshop: stock is counted in base units,
-- while a product may be purchased in cases and sold as singles.
-- Money is stored as integer minor units (cents) and always paired with currency.

CREATE TABLE IF NOT EXISTS app_users (
  id TEXT PRIMARY KEY,
  username TEXT NOT NULL UNIQUE COLLATE NOCASE,
  display_name TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('OWNER','CASHIER')),
  pin_hash TEXT,
  password_hash TEXT,
  is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0,1)),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK ((role = 'OWNER' AND password_hash IS NOT NULL) OR
         (role = 'CASHIER' AND pin_hash IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS products (
  id TEXT PRIMARY KEY,
  barcode TEXT UNIQUE,
  name TEXT NOT NULL,
  category TEXT NOT NULL DEFAULT 'General',
  unit TEXT NOT NULL DEFAULT 'pc', -- base stock unit: piece, bottle, kg, litre, etc.
  cost_minor INTEGER NOT NULL DEFAULT 0 CHECK (cost_minor >= 0),
  cost_currency TEXT NOT NULL DEFAULT 'USD' CHECK (cost_currency IN ('USD','ZiG')),
  sell_minor INTEGER NOT NULL CHECK (sell_minor >= 0),
  sell_currency TEXT NOT NULL DEFAULT 'USD' CHECK (sell_currency IN ('USD','ZiG')),
  quantity REAL NOT NULL DEFAULT 0 CHECK (quantity >= 0), -- always in base units
  low_stock_level REAL NOT NULL DEFAULT 0 CHECK (low_stock_level >= 0),
  is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0,1)),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Alternative sale/purchase units. Example: one box = 50 biscuits.
-- conversion_factor is the number of base units represented by one selected unit.
CREATE TABLE IF NOT EXISTS product_units (
  id TEXT PRIMARY KEY,
  product_id TEXT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
  unit_name TEXT NOT NULL, -- single, packet, box, crate, bottle, kg, etc.
  conversion_factor REAL NOT NULL CHECK (conversion_factor > 0),
  barcode TEXT UNIQUE,
  sell_minor INTEGER CHECK (sell_minor IS NULL OR sell_minor >= 0),
  sell_currency TEXT CHECK (sell_currency IS NULL OR sell_currency IN ('USD','ZiG')),
  is_default INTEGER NOT NULL DEFAULT 0 CHECK (is_default IN (0,1)),
  UNIQUE(product_id, unit_name)
);

CREATE TABLE IF NOT EXISTS stock_batches (
  id TEXT PRIMARY KEY,
  product_id TEXT NOT NULL REFERENCES products(id),
  batch_number TEXT,
  quantity_remaining REAL NOT NULL CHECK (quantity_remaining >= 0),
  expiry_date TEXT, -- ISO YYYY-MM-DD; nullable for non-perishables
  received_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  notes TEXT
);

CREATE TABLE IF NOT EXISTS exchange_rates (
  id TEXT PRIMARY KEY,
  base_currency TEXT NOT NULL CHECK (base_currency IN ('USD','ZiG')),
  quote_currency TEXT NOT NULL CHECK (quote_currency IN ('USD','ZiG')),
  rate REAL NOT NULL CHECK (rate > 0),
  effective_at TEXT NOT NULL,
  created_by TEXT REFERENCES app_users(id)
);

CREATE TABLE IF NOT EXISTS customers (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  phone TEXT,
  notes TEXT,
  credit_limit_minor INTEGER CHECK (credit_limit_minor IS NULL OR credit_limit_minor >= 0),
  credit_limit_currency TEXT CHECK (credit_limit_currency IS NULL OR credit_limit_currency IN ('USD','ZiG')),
  is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0,1)),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sales (
  id TEXT PRIMARY KEY,
  receipt_number TEXT NOT NULL UNIQUE,
  cashier_id TEXT NOT NULL REFERENCES app_users(id),
  customer_id TEXT REFERENCES customers(id),
  total_minor INTEGER NOT NULL CHECK (total_minor >= 0),
  currency TEXT NOT NULL CHECK (currency IN ('USD','ZiG')),
  payment_method TEXT NOT NULL,
  exchange_rate REAL,
  status TEXT NOT NULL DEFAULT 'COMPLETED' CHECK (status IN ('COMPLETED','VOIDED','REFUNDED')),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  voided_at TEXT,
  voided_by TEXT REFERENCES app_users(id)
);

CREATE TABLE IF NOT EXISTS sale_items (
  id TEXT PRIMARY KEY,
  sale_id TEXT NOT NULL REFERENCES sales(id),
  product_id TEXT NOT NULL REFERENCES products(id),
  product_name_snapshot TEXT NOT NULL,
  unit_name_snapshot TEXT NOT NULL DEFAULT 'pc',
  conversion_factor_snapshot REAL NOT NULL DEFAULT 1 CHECK (conversion_factor_snapshot > 0),
  quantity REAL NOT NULL CHECK (quantity > 0), -- quantity in selected selling unit
  base_quantity REAL NOT NULL CHECK (base_quantity > 0), -- deducted from stock
  unit_price_minor INTEGER NOT NULL CHECK (unit_price_minor >= 0),
  unit_cost_minor INTEGER NOT NULL CHECK (unit_cost_minor >= 0),
  currency TEXT NOT NULL CHECK (currency IN ('USD','ZiG')),
  line_total_minor INTEGER NOT NULL CHECK (line_total_minor >= 0)
);

CREATE TABLE IF NOT EXISTS sale_payments (
  id TEXT PRIMARY KEY,
  sale_id TEXT NOT NULL REFERENCES sales(id) ON DELETE CASCADE,
  payment_method TEXT NOT NULL, -- cash, EcoCash, bank transfer, etc.
  amount_minor INTEGER NOT NULL CHECK (amount_minor >= 0),
  currency TEXT NOT NULL CHECK (currency IN ('USD','ZiG')),
  reference TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Customer debt book: DEBIT increases what the customer owes; CREDIT reduces it.
-- Keep balances separate for USD and ZiG; never silently combine currencies.
CREATE TABLE IF NOT EXISTS customer_account_entries (
  id TEXT PRIMARY KEY,
  customer_id TEXT NOT NULL REFERENCES customers(id),
  sale_id TEXT REFERENCES sales(id),
  entry_type TEXT NOT NULL CHECK (entry_type IN ('CREDIT_SALE','REPAYMENT','ADJUSTMENT')),
  direction TEXT NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
  amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
  currency TEXT NOT NULL CHECK (currency IN ('USD','ZiG')),
  payment_method TEXT,
  reference TEXT,
  notes TEXT,
  recorded_by TEXT REFERENCES app_users(id),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS suppliers (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  phone TEXT,
  address TEXT,
  notes TEXT,
  is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0,1))
);

CREATE TABLE IF NOT EXISTS purchases (
  id TEXT PRIMARY KEY,
  supplier_id TEXT REFERENCES suppliers(id),
  reference_number TEXT,
  total_minor INTEGER NOT NULL CHECK (total_minor >= 0),
  currency TEXT NOT NULL CHECK (currency IN ('USD','ZiG')),
  status TEXT NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('DRAFT','RECEIVED','CANCELLED')),
  recorded_by TEXT REFERENCES app_users(id),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS purchase_items (
  id TEXT PRIMARY KEY,
  purchase_id TEXT NOT NULL REFERENCES purchases(id) ON DELETE CASCADE,
  product_id TEXT NOT NULL REFERENCES products(id),
  quantity REAL NOT NULL CHECK (quantity > 0), -- selected purchase unit
  base_quantity REAL NOT NULL CHECK (base_quantity > 0),
  unit_name_snapshot TEXT NOT NULL DEFAULT 'pc',
  conversion_factor_snapshot REAL NOT NULL DEFAULT 1 CHECK (conversion_factor_snapshot > 0),
  unit_cost_minor INTEGER NOT NULL CHECK (unit_cost_minor >= 0),
  currency TEXT NOT NULL CHECK (currency IN ('USD','ZiG'))
);

CREATE TABLE IF NOT EXISTS stock_movements (
  id TEXT PRIMARY KEY,
  product_id TEXT NOT NULL REFERENCES products(id),
  movement_type TEXT NOT NULL CHECK (movement_type IN ('OPENING','PURCHASE','SALE','SPOILAGE','ADJUSTMENT','SALE_REVERSAL','RETURN')),
  quantity_change REAL NOT NULL,
  reason TEXT NOT NULL,
  reference_id TEXT,
  performed_by TEXT REFERENCES app_users(id),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS expenses (
  id TEXT PRIMARY KEY,
  description TEXT NOT NULL,
  amount_minor INTEGER NOT NULL CHECK (amount_minor >= 0),
  currency TEXT NOT NULL CHECK (currency IN ('USD','ZiG')),
  category TEXT NOT NULL DEFAULT 'General',
  recorded_by TEXT REFERENCES app_users(id),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS audit_log (
  id TEXT PRIMARY KEY,
  actor_id TEXT REFERENCES app_users(id),
  action TEXT NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id TEXT,
  details_json TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sync_queue (
  id TEXT PRIMARY KEY,
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  operation TEXT NOT NULL CHECK (operation IN ('INSERT','UPDATE','DELETE')),
  payload_json TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  synced_at TEXT,
  attempts INTEGER NOT NULL DEFAULT 0,
  last_error TEXT
);

CREATE INDEX IF NOT EXISTS idx_products_name ON products(name);
CREATE INDEX IF NOT EXISTS idx_sales_created_at ON sales(created_at);
CREATE INDEX IF NOT EXISTS idx_stock_movements_product_time ON stock_movements(product_id, created_at);
CREATE INDEX IF NOT EXISTS idx_sync_queue_pending ON sync_queue(synced_at, created_at);
CREATE INDEX IF NOT EXISTS idx_product_units_product ON product_units(product_id);
CREATE INDEX IF NOT EXISTS idx_stock_batches_expiry ON stock_batches(expiry_date);
CREATE INDEX IF NOT EXISTS idx_customer_entries_customer_time ON customer_account_entries(customer_id, created_at);
CREATE INDEX IF NOT EXISTS idx_purchases_created_at ON purchases(created_at);
