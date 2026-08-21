PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS licenses (
  id TEXT PRIMARY KEY,
  license_key TEXT NOT NULL UNIQUE,
  customer_name TEXT,
  customer_contact TEXT,
  plan TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'ACTIVE',
  created_at TEXT NOT NULL,
  activated_at TEXT,
  expires_at TEXT NOT NULL,
  max_devices INTEGER NOT NULL DEFAULT 1,
  device_id TEXT,
  last_check TEXT,
  notes TEXT
);

CREATE INDEX IF NOT EXISTS idx_licenses_key ON licenses(license_key);
CREATE INDEX IF NOT EXISTS idx_licenses_device ON licenses(device_id);
CREATE INDEX IF NOT EXISTS idx_licenses_status ON licenses(status);

CREATE TABLE IF NOT EXISTS purchases (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  plan TEXT NOT NULL,
  amount_cents INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'PENDING',
  square_order_id TEXT,
  square_payment_link_id TEXT,
  square_payment_id TEXT,
  license_key TEXT,
  created_at TEXT NOT NULL,
  paid_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_purchases_order ON purchases(square_order_id);
CREATE INDEX IF NOT EXISTS idx_purchases_device ON purchases(device_id);

CREATE TABLE IF NOT EXISTS compatibility_reports (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  target_package TEXT NOT NULL,
  note TEXT,
  app_version TEXT,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_reports_package ON compatibility_reports(target_package);
