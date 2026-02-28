package com.smartfirehub.auth.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.dto.SignupRequest;
import com.smartfirehub.auth.dto.TokenResponse;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.LocalDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RefreshTokenCleanupServiceTest extends IntegrationTestBase {

  private static final Field<UUID> FAMILY_ID = field("family_id", UUID.class);

  @Autowired private RefreshTokenCleanupService cleanupService;

  @Autowired private AuthService authService;

  @Autowired private DSLContext dsl;

  @Test
  void cleanupExpiredTokens_deletesExpiredTokens() {
    authService.signup(
        new SignupRequest("test@example.com", "test@example.com", "Password123", "Test User"));
    TokenResponse loginResult =
        authService.login(new LoginRequest("test@example.com", "Password123"));

    // Manually expire the token
    dsl.update(REFRESH_TOKEN)
        .set(REFRESH_TOKEN.EXPIRES_AT, LocalDateTime.now().minusDays(1))
        .execute();

    int before =
        dsl.fetchCount(dsl.selectFrom(REFRESH_TOKEN));
    assertThat(before).isGreaterThan(0);

    cleanupService.cleanupExpiredTokens();

    int after =
        dsl.fetchCount(dsl.selectFrom(REFRESH_TOKEN));
    assertThat(after).isEqualTo(0);
  }

  @Test
  void cleanupExpiredTokens_deletesOldRevokedTokens() {
    authService.signup(
        new SignupRequest("test@example.com", "test@example.com", "Password123", "Test User"));
    authService.login(new LoginRequest("test@example.com", "Password123"));

    // Revoke all tokens and backdate created_at
    dsl.update(REFRESH_TOKEN)
        .set(REFRESH_TOKEN.REVOKED, true)
        .set(REFRESH_TOKEN.CREATED_AT, LocalDateTime.now().minusDays(8))
        .execute();

    cleanupService.cleanupExpiredTokens();

    int after =
        dsl.fetchCount(dsl.selectFrom(REFRESH_TOKEN));
    assertThat(after).isEqualTo(0);
  }

  @Test
  void cleanupExpiredTokens_keepsValidTokens() {
    authService.signup(
        new SignupRequest("test@example.com", "test@example.com", "Password123", "Test User"));
    authService.login(new LoginRequest("test@example.com", "Password123"));

    int before =
        dsl.fetchCount(dsl.selectFrom(REFRESH_TOKEN));

    cleanupService.cleanupExpiredTokens();

    int after =
        dsl.fetchCount(dsl.selectFrom(REFRESH_TOKEN));
    assertThat(after).isEqualTo(before);
  }
}
