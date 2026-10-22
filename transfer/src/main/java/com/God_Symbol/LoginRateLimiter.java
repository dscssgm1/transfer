package com.God_Symbol;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理员登录失败限流。内存实现，适用于当前单实例部署；
 * 若将来改为多实例，需要换成数据库或 Redis 等共享存储。
 */
@Component
public class LoginRateLimiter {
  private final int maxAttempts;
  private final Duration window;
  private final Clock clock;
  private final ConcurrentHashMap<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

  public LoginRateLimiter(AdminProperties properties, Clock clock) {
    this.maxAttempts = properties.effectiveLoginMaxAttempts();
    this.window = properties.effectiveLoginWindow();
    this.clock = clock;
  }

  /** 在尝试认证前调用；超过阈值直接 429。 */
  public void check(String clientKey) {
    Deque<Instant> attempts = failures.get(clientKey);
    if (attempts == null) return;
    synchronized (attempts) {
      prune(attempts);
      if (attempts.size() >= maxAttempts) {
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "too many login attempts, try again later");
      }
    }
  }

  public void recordFailure(String clientKey) {
    Deque<Instant> attempts = failures.computeIfAbsent(clientKey, key -> new ArrayDeque<>());
    synchronized (attempts) {
      prune(attempts);
      attempts.addLast(clock.instant());
    }
  }

  public void reset(String clientKey) {
    failures.remove(clientKey);
  }

  private void prune(Deque<Instant> attempts) {
    Instant cutoff = clock.instant().minus(window);
    while (!attempts.isEmpty() && !attempts.peekFirst().isAfter(cutoff)) attempts.removeFirst();
  }
}
