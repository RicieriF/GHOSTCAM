PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS devices (
  device_id TEXT PRIMARY KEY,
  device_name TEXT,
  model TEXT,
  android_version TEXT,
  app_version TEXT,
  created_at TEXT NOT NULL,
  last_seen_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS licenses (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  license_key TEXT NOT NULL UNIQUE,
  plan TEXT NOT NULL CHECK(plan IN ('DAY','THREE_DAY','WEEK')),
  status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','EXPIRED','SUSPENDED','REVOKED')),
  device_id TEXT,
  customer_name TEXT,
  customer_contact TEXT,
  created_at TEXT NOT NULL,
  activated_at TEXT,
  expires_at TEXT,
  last_check TEXT,
  notes TEXT,
  source TEXT NOT NULL DEFAULT 'ADMIN',
  square_order_id TEXT,
  FOREIGN KEY(device_id) REFERENCES devices(device_id)
);

CREATE INDEX IF NOT EXISTS idx_licenses_device ON licenses(device_id);
CREATE INDEX IF NOT EXISTS idx_licenses_status ON licenses(status);
CREATE INDEX IF NOT EXISTS idx_licenses_order ON licenses(square_order_id);

CREATE TABLE IF NOT EXISTS checkouts (
  checkout_id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  plan TEXT NOT NULL CHECK(plan IN ('DAY','THREE_DAY','WEEK')),
  amount_cents INTEGER NOT NULL,
  square_order_id TEXT UNIQUE,
  square_payment_link_id TEXT,
  status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','PAID','CANCELLED','FAILED')),
  license_key TEXT,
  created_at TEXT NOT NULL,
  paid_at TEXT,
  FOREIGN KEY(device_id) REFERENCES devices(device_id)
);

CREATE INDEX IF NOT EXISTS idx_checkouts_order ON checkouts(square_order_id);

CREATE TABLE IF NOT EXISTS compatibility_reports (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id TEXT NOT NULL,
  target_package TEXT NOT NULL,
  target_version_name TEXT,
  target_version_code TEXT,
  ghostcam_version TEXT,
  android_version TEXT,
  device_model TEXT,
  summary TEXT NOT NULL,
  created_at TEXT NOT NULL,
  FOREIGN KEY(device_id) REFERENCES devices(device_id)
);

CREATE INDEX IF NOT EXISTS idx_reports_target ON compatibility_reports(target_package, target_version_code);

CREATE TABLE IF NOT EXISTS compatibility_rules (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  target_package TEXT NOT NULL,
  min_version_code INTEGER,
  max_version_code INTEGER,
  status TEXT NOT NULL CHECK(status IN ('OK','WARNING','BROKEN')),
  message TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS releases (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  version_name TEXT NOT NULL UNIQUE,
  version_code INTEGER,
  minimum_supported INTEGER NOT NULL DEFAULT 0,
  mandatory INTEGER NOT NULL DEFAULT 0,
  download_url TEXT,
  notes TEXT,
  created_at TEXT NOT NULL
);
