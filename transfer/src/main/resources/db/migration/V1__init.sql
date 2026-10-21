CREATE TABLE IF NOT EXISTS transfers (
  transfer_key VARCHAR(6) NOT NULL PRIMARY KEY,
  owner_id VARCHAR(100) NOT NULL,
  file_name TEXT NOT NULL,
  stored_name VARCHAR(255) NOT NULL,
  size_bytes BIGINT NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  CONSTRAINT chk_transfers_size_bytes CHECK (size_bytes >= 0)
);

CREATE INDEX idx_transfers_expires_at ON transfers (expires_at);
CREATE INDEX idx_transfers_owner_id ON transfers (owner_id);
