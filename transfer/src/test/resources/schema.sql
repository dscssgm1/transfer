CREATE TABLE transfers (
  transfer_key VARCHAR(6) PRIMARY KEY,
  owner_id VARCHAR(100) NOT NULL,
  file_name VARCHAR(1000) NOT NULL,
  stored_name VARCHAR(255) NOT NULL,
  size_bytes BIGINT NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL
);
