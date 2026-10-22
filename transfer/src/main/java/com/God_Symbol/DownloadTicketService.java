package com.God_Symbol;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 短时下载票据。
 *
 * <p>浏览器通过 {@code <a href>} 下载时无法携带 {@code Authorization} 头，因此前端先调用
 * {@code POST /api/transfers/{key}/download-ticket} 换取一个短时票据，再把票据拼进下载 URL。
 * 票据是 HMAC-SHA256 签名的 {@code 过期秒数.签名}，只绑定文件 Key，不写入数据库，
 * 默认 60 秒后失效。
 */
@Service
public class DownloadTicketService {
  private static final String DOMAIN = "dropkey/download-ticket/v1|";
  private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);
  private static final Duration MAX_TTL = Duration.ofMinutes(5);

  private final byte[] secret;
  private final Duration ttl;
  private final Clock clock;

  @Autowired
  public DownloadTicketService(
      @Value("${transfer.owner-secret:change-me-in-production}") String secret,
      @Value("${transfer.download-ticket-ttl:60s}") Duration ttl) {
    this(secret, ttl, Clock.systemUTC());
  }

  DownloadTicketService(String secret, Duration ttl, Clock clock) {
    String value = secret == null || secret.isBlank() ? "change-me-in-production" : secret;
    this.secret = value.getBytes(StandardCharsets.UTF_8);
    this.ttl = normalizeTtl(ttl);
    this.clock = clock;
  }

  public IssuedTicket issue(String key) {
    long expiresAt = clock.instant().plus(ttl).getEpochSecond();
    String signature = sign(key, expiresAt);
    String ticket = expiresAt + "." + signature;
    String url = "/api/transfers/" + key + "/download?ticket=" + ticket;
    return new IssuedTicket(ticket, url, Instant.ofEpochSecond(expiresAt));
  }

  public boolean verify(String key, String ticket) {
    if (key == null || ticket == null || ticket.isBlank()) return false;
    int dot = ticket.indexOf('.');
    if (dot <= 0 || dot != ticket.lastIndexOf('.')) return false;
    long expiresAt;
    try {
      expiresAt = Long.parseLong(ticket.substring(0, dot));
    } catch (NumberFormatException e) {
      return false;
    }
    if (expiresAt <= clock.instant().getEpochSecond()) return false;
    String expected = sign(key, expiresAt);
    return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
        ticket.substring(dot + 1).getBytes(StandardCharsets.UTF_8));
  }

  private String sign(String key, long expiresAt) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      byte[] data = (DOMAIN + key + "|" + expiresAt).getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(mac.doFinal(data));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 is not available", e);
    }
  }

  private static Duration normalizeTtl(Duration ttl) {
    if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_TTL) > 0) {
      return DEFAULT_TTL;
    }
    return ttl;
  }

  public record IssuedTicket(String ticket, String url, Instant expiresAt) {}
}
