package com.smartfirehub.auth.service;

import com.smartfirehub.auth.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class RefreshTokenCleanupService {

  private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupService.class);

  private final RefreshTokenRepository refreshTokenRepository;

  public RefreshTokenCleanupService(RefreshTokenRepository refreshTokenRepository) {
    this.refreshTokenRepository = refreshTokenRepository;
  }

  /** Delete expired tokens and revoked tokens older than 7 days. Runs daily at 4 AM. */
  @Scheduled(cron = "0 0 4 * * *")
  public void cleanupExpiredTokens() {
    int deleted = refreshTokenRepository.deleteExpiredTokens();
    log.info("Cleaned up {} expired/revoked refresh tokens", deleted);
  }
}
