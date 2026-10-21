package com.God_Symbol;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class TransferRepository {
  private final JdbcTemplate jdbc;

  public TransferRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public boolean insert(Transfer transfer) {
    try {
      jdbc.update("INSERT INTO transfers (transfer_key, owner_id, file_name, stored_name, size_bytes, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
          transfer.key(), transfer.owner(), transfer.name(), transfer.storedName(), transfer.size(), Timestamp.from(transfer.expires()), Timestamp.from(transfer.created()));
      return true;
    } catch (org.springframework.dao.DuplicateKeyException ex) { return false; }
  }

  public Optional<Transfer> findActive(String key, Instant now) {
    List<Transfer> rows = jdbc.query("SELECT transfer_key, owner_id, file_name, stored_name, size_bytes, expires_at, created_at FROM transfers WHERE transfer_key = ? AND expires_at > ?",
        (rs, n) -> new Transfer(rs.getString("transfer_key"), rs.getString("owner_id"), rs.getString("file_name"), rs.getString("stored_name"), rs.getLong("size_bytes"), rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("created_at").toInstant()), key, Timestamp.from(now));
    return rows.stream().findFirst();
  }

  public List<Transfer> findExpired(Instant now) {
    return jdbc.query("SELECT transfer_key, owner_id, file_name, stored_name, size_bytes, expires_at, created_at FROM transfers WHERE expires_at <= ?",
        (rs, n) -> new Transfer(rs.getString("transfer_key"), rs.getString("owner_id"), rs.getString("file_name"), rs.getString("stored_name"), rs.getLong("size_bytes"), rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("created_at").toInstant()), Timestamp.from(now));
  }

  public List<Transfer> findActiveByOwner(String owner, Instant now) {
    return jdbc.query("SELECT transfer_key, owner_id, file_name, stored_name, size_bytes, expires_at, created_at FROM transfers WHERE owner_id = ? AND expires_at > ? ORDER BY created_at DESC",
        (rs, n) -> new Transfer(rs.getString("transfer_key"), rs.getString("owner_id"), rs.getString("file_name"), rs.getString("stored_name"), rs.getLong("size_bytes"), rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("created_at").toInstant()), owner, Timestamp.from(now));
  }

  public boolean delete(String key, String owner) {
    return jdbc.update("DELETE FROM transfers WHERE transfer_key = ? AND owner_id = ?", key, owner) == 1;
  }

  public boolean deleteExpired(String key, Instant now) {
    return jdbc.update("DELETE FROM transfers WHERE transfer_key = ? AND expires_at <= ?", key, Timestamp.from(now)) == 1;
  }

  public record Transfer(String key, String owner, String name, String storedName, long size, Instant expires, Instant created) {}
}
