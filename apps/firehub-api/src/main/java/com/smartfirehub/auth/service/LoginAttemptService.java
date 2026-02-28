package com.smartfirehub.auth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.stereotype.Service;

@Service
public class LoginAttemptService {

  private static final int MAX_ATTEMPTS = 5;
  private static final int LOCK_DURATION_MINUTES = 15;

  private final Cache<String, Integer> attemptsCache =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(LOCK_DURATION_MINUTES)).build();

  public void loginFailed(String username) {
    Integer attempts = attemptsCache.getIfPresent(username);
    attemptsCache.put(username, (attempts == null ? 0 : attempts) + 1);
  }

  public void loginSucceeded(String username) {
    attemptsCache.invalidate(username);
  }

  public boolean isBlocked(String username) {
    Integer attempts = attemptsCache.getIfPresent(username);
    return attempts != null && attempts >= MAX_ATTEMPTS;
  }
}
