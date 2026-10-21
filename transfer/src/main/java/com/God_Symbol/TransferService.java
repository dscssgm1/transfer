package com.God_Symbol;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
public class TransferService {
  private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private final TransferRepository repository;
  private final Path root;
  private final long maxSize;
  private final Duration expiry;
  private final byte[] ownerSecret;
  private final boolean secureCookie;
  private final SecureRandom random = new SecureRandom();

  public TransferService(TransferRepository repository, String storage, long maxSize, Duration expiry) throws IOException {
    this(repository, storage, maxSize, expiry, "change-me-in-production", false);
  }

  @Autowired
  public TransferService(TransferRepository repository,
      @Value("${transfer.storage:./data}") String storage,
      @Value("${transfer.max-file-size:2147483647}") long maxSize,
      @Value("${transfer.expiry:24h}") Duration expiry,
      @Value("${transfer.owner-secret:change-me-in-production}") String ownerSecret,
      @Value("${transfer.cookie-secure:false}") boolean secureCookie) {
    this.repository = java.util.Objects.requireNonNull(repository, "repository");
    String configuredStorage = storage == null || storage.isBlank() ? "./data" : storage;
    this.root = Paths.get(configuredStorage).toAbsolutePath().normalize();
    this.maxSize = maxSize > 0 ? Math.min(maxSize, 2147483647L) : 2147483647L;
    this.expiry = expiry == null || expiry.isZero() || expiry.isNegative() ? Duration.ofHours(24) : expiry;
    String configuredSecret = ownerSecret == null || ownerSecret.isBlank() ? "change-me-in-production" : ownerSecret;
    this.ownerSecret = configuredSecret.getBytes(StandardCharsets.UTF_8);
    this.secureCookie = secureCookie;
  }

  @jakarta.annotation.PostConstruct
  void initializeStorage() throws IOException {
    Files.createDirectories(root);
  }

  public Map<String,Object> upload(MultipartFile multipart, String owner, HttpServletResponse response) throws IOException {
    if (multipart == null || multipart.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is empty");
    owner = validOwner(owner, response);
    for (int attempt = 0; attempt < 10; attempt++) {
      String key = randomKey(); String stored = key + ".bin"; Path target = safePath(stored); Path tmp = safePath(key + "." + UUID.randomUUID() + ".tmp");
      long size = 0; boolean moved = false;
      try {
        try (InputStream in = multipart.getInputStream(); OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
          byte[] buffer = new byte[8192]; int read;
          while ((read = in.read(buffer)) != -1) { size += read; if (size > maxSize) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "file exceeds limit"); out.write(buffer, 0, read); }
        }
        try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE); } catch (AtomicMoveNotSupportedException e) { Files.move(tmp, target); }
        moved = true;
        Instant now = Instant.now(), expires = now.plus(expiry);
        TransferRepository.Transfer record = new TransferRepository.Transfer(key, owner, safeName(multipart.getOriginalFilename()), stored, size, expires, now);
        if (repository.insert(record)) return Map.of("key", key, "name", record.name(), "size", size, "expiresAt", expires.toString());
      } finally { Files.deleteIfExists(tmp); if (!moved || !repository.findActive(key, Instant.now()).isPresent()) Files.deleteIfExists(target); }
    }
    throw new ResponseStatusException(HttpStatus.CONFLICT, "could not allocate transfer key");
  }

  public TransferRepository.Transfer get(String key) {
    if (!key.matches("[A-Z2-9]{6}")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return repository.findActive(key, Instant.now()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  public void download(TransferRepository.Transfer transfer, jakarta.servlet.http.HttpServletResponse response) throws IOException {
    Path path = safePath(transfer.storedName());
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "file unavailable");
    response.setContentType("application/octet-stream"); response.setHeader("Content-Disposition", "attachment; filename=\"" + headerName(transfer.name()) + "\""); response.setContentLengthLong(transfer.size());
    try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS); OutputStream out = response.getOutputStream()) { in.transferTo(out); }
  }

  public void delete(String key, String owner) throws IOException {
    TransferRepository.Transfer t = get(key);
    owner = ownerId(owner);
    if (owner == null || !owner.equals(t.owner())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    Files.deleteIfExists(safePath(t.storedName()));
    if (!repository.delete(key, owner)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  }

  public java.util.List<TransferRepository.Transfer> list(String owner) {
    String ownerId = ownerId(owner);
    if (ownerId == null) return java.util.List.of();
    return repository.findActiveByOwner(ownerId, Instant.now());
  }

  public String ownerIdFromCookie(String owner) { return ownerId(owner); }

  public void cleanup() {
    Instant now = Instant.now();
    for (TransferRepository.Transfer t : repository.findExpired(now)) {
      try {
        Files.deleteIfExists(safePath(t.storedName()));
        repository.deleteExpired(t.key(), now);
      } catch (IOException ignored) {
        // Leave metadata in place and retry during the next cleanup run.
      }
    }
    // Failed/interrupted uploads never acquire metadata. They are private temporary files,
    // and can be reclaimed safely once they are older than the transfer lifetime.
    try (var paths = Files.list(root)) {
      paths.filter(path -> path.getFileName().toString().endsWith(".tmp"))
          .filter(path -> {
            try { return Files.getLastModifiedTime(path).toInstant().isBefore(now.minus(expiry)); }
            catch (IOException ignored) { return false; }
          })
          .forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
    } catch (IOException ignored) { }
  }

  private String validOwner(String owner, HttpServletResponse response) {
    if (owner != null) {
      String[] parts = owner.split("\\.", -1);
      if (parts.length == 2 && parts[0].matches("[a-f0-9-]{36}") && MessageDigest.isEqual(sign(parts[0]), parts[1].getBytes(StandardCharsets.UTF_8))) return parts[0];
    }
    String created = UUID.randomUUID().toString(); Cookie c = new Cookie("transfer_owner", created + "." + new String(sign(created), StandardCharsets.UTF_8)); c.setPath("/"); c.setHttpOnly(true); c.setSecure(secureCookie); c.setMaxAge(31536000); response.addCookie(c); return created;
  }
  private String ownerId(String owner) {
    if (owner == null) return null;
    String[] parts = owner.split("\\.", -1);
    if (parts.length == 2 && parts[0].matches("[a-f0-9-]{36}") && MessageDigest.isEqual(sign(parts[0]), parts[1].getBytes(StandardCharsets.UTF_8))) return parts[0];
    return null;
  }
  private byte[] sign(String value) { try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(ownerSecret, "HmacSHA256")); return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8); } catch (Exception e) { throw new IllegalStateException(e); } }
  private Path safePath(String name) { Path p = root.resolve(name).normalize(); if (!p.getParent().equals(root)) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR); return p; }
  private String safeName(String name) { if (name == null || name.isBlank()) return "file"; return name.replaceAll("[\\r\\n\\\"]", "_"); }
  private String headerName(String name) { return safeName(name).replaceAll("[^\\p{Print}]", "_"); }
  private String randomKey() { StringBuilder b = new StringBuilder(6); for (int i=0;i<6;i++) b.append(ALPHABET.charAt(random.nextInt(ALPHABET.length()))); return b.toString(); }
}
