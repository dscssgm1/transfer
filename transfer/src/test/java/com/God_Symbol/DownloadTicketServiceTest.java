package com.God_Symbol;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class DownloadTicketServiceTest {
  private static final String SECRET = "0123456789abcdef0123456789abcdef";
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void issuedTicketVerifiesOnlyForItsKey() {
    DownloadTicketService service = service(SECRET, Duration.ofSeconds(60), NOW);
    DownloadTicketService.IssuedTicket issued = service.issue("ABCD23");

    assertTrue(service.verify("ABCD23", issued.ticket()));
    assertFalse(service.verify("WXYZ45", issued.ticket()));
    assertTrue(issued.url().contains("/api/transfers/ABCD23/download?ticket="));
    assertEquals(NOW.plusSeconds(60), issued.expiresAt());
  }

  @Test
  void expiredTicketIsRejected() {
    DownloadTicketService issuer = service(SECRET, Duration.ofSeconds(60), NOW);
    String ticket = issuer.issue("ABCD23").ticket();

    DownloadTicketService atExpiry = service(SECRET, Duration.ofSeconds(60), NOW.plusSeconds(60));
    assertFalse(atExpiry.verify("ABCD23", ticket), "ticket must not be valid at its expiry second");

    DownloadTicketService beforeExpiry = service(SECRET, Duration.ofSeconds(60), NOW.plusSeconds(59));
    assertTrue(beforeExpiry.verify("ABCD23", ticket));
  }

  @Test
  void tamperedOrMalformedTicketsAreRejected() {
    DownloadTicketService service = service(SECRET, Duration.ofSeconds(60), NOW);
    String ticket = service.issue("ABCD23").ticket();
    int dot = ticket.indexOf('.');

    assertFalse(service.verify("ABCD23", ticket.substring(0, dot) + ".0" + ticket.substring(dot + 1)));
    assertFalse(service.verify("ABCD23", ticket.substring(0, dot)));
    assertFalse(service.verify("ABCD23", "not-a-number" + ticket.substring(dot)));
    assertFalse(service.verify("ABCD23", ticket + ".extra"));
    assertFalse(service.verify("ABCD23", ""));
    assertFalse(service.verify("ABCD23", null));
  }

  @Test
  void ticketsCannotBeForgedWithAnotherSecret() {
    DownloadTicketService issuer = service(SECRET, Duration.ofSeconds(60), NOW);
    DownloadTicketService other = service("another-secret-another-secret", Duration.ofSeconds(60), NOW);
    assertFalse(other.verify("ABCD23", issuer.issue("ABCD23").ticket()));
  }

  @Test
  void invalidTtlFallsBackToSixtySeconds() {
    for (Duration ttl : new Duration[]{null, Duration.ZERO, Duration.ofSeconds(-5), Duration.ofMinutes(10)}) {
      DownloadTicketService service = service(SECRET, ttl, NOW);
      assertEquals(NOW.plusSeconds(60), service.issue("ABCD23").expiresAt(), "ttl=" + ttl);
    }
  }

  private static DownloadTicketService service(String secret, Duration ttl, Instant now) {
    return new DownloadTicketService(secret, ttl, Clock.fixed(now, ZoneOffset.UTC));
  }
}
