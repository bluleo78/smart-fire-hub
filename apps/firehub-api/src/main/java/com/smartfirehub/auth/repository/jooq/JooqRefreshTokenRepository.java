package com.smartfirehub.auth.repository.jooq;

import static com.smartfirehub.jooq.Tables.*;
import static org.jooq.impl.DSL.field;

import com.smartfirehub.auth.repository.RefreshTokenRepository;
import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.stereotype.Repository;

/**
 * RefreshTokenRepository 의 jOOQ(PostgreSQL) 구현 — WD-485.
 *
 * <p>네 폐기 경로 전부가 {@code revoked_at} 을 함께 갱신한다 — 회전 유예 판정(WD-1141)이 이 시각에 걸리기 때문이다.
 * 어느 경로에서만 빠지면 그 경로의 유예는 영원히 동작하지 않는다. 시각은 {@link RefreshTokenRepository#revokedAtNow()} —
 * 유예를 잴 쪽도 같은 기준이라야 5초가 5초다(WD-1141 반려 3차 5).
 */
@Repository
@ConditionalOnPostgresPlatformDb
@RequiredArgsConstructor
public class JooqRefreshTokenRepository implements RefreshTokenRepository {

  private static final Field<UUID> FAMILY_ID = field("family_id", UUID.class);

  private final DSLContext dsl;

  @Override
  public void save(Long userId, String tokenHash, LocalDateTime expiresAt, UUID familyId) {
    save(userId, tokenHash, expiresAt, familyId, PLANE_WEB);
  }

  @Override
  public void save(
      Long userId, String tokenHash, LocalDateTime expiresAt, UUID familyId, String plane) {
    dsl.insertInto(REFRESH_TOKEN)
        .set(REFRESH_TOKEN.USER_ID, userId)
        .set(REFRESH_TOKEN.TOKEN_HASH, tokenHash)
        .set(REFRESH_TOKEN.EXPIRES_AT, expiresAt)
        .set(FAMILY_ID, familyId)
        .set(REFRESH_TOKEN.PLANE, plane)
        .execute();
  }

  @Override
  public boolean existsValidToken(String tokenHash) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(REFRESH_TOKEN)
            .where(REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash))
            .and(REFRESH_TOKEN.REVOKED.eq(false))
            .and(REFRESH_TOKEN.EXPIRES_AT.gt(LocalDateTime.now())));
  }

  @Override
  public boolean isTokenRevoked(String tokenHash) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(REFRESH_TOKEN)
            .where(REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash))
            .and(REFRESH_TOKEN.REVOKED.eq(true)));
  }

  @Override
  public Optional<LocalDateTime> findRevokedAt(String tokenHash) {
    return dsl.select(REFRESH_TOKEN.REVOKED_AT)
        .from(REFRESH_TOKEN)
        .where(REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash))
        .and(REFRESH_TOKEN.REVOKED.eq(true))
        .fetchOptional(REFRESH_TOKEN.REVOKED_AT);
  }

  @Override
  public Optional<UUID> findFamilyIdByTokenHash(String tokenHash) {
    return dsl.select(FAMILY_ID)
        .from(REFRESH_TOKEN)
        .where(REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash))
        .fetchOptional(FAMILY_ID);
  }

  @Override
  public Optional<TokenOwner> findOwnerByTokenHash(String tokenHash) {
    return dsl.select(FAMILY_ID, REFRESH_TOKEN.USER_ID, REFRESH_TOKEN.PLANE)
        .from(REFRESH_TOKEN)
        .where(REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash))
        .fetchOptional(r -> new TokenOwner(r.value1(), r.value2(), r.value3()));
  }

  @Override
  public boolean revokeByTokenHash(String tokenHash) {
    return dsl.update(REFRESH_TOKEN)
            .set(REFRESH_TOKEN.REVOKED, true)
            .set(REFRESH_TOKEN.REVOKED_AT, RefreshTokenRepository.revokedAtNow())
            .where(REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash))
            .and(REFRESH_TOKEN.REVOKED.eq(false))
            .execute()
        > 0;
  }

  @Override
  public void revokeByFamilyId(UUID familyId) {
    dsl.update(REFRESH_TOKEN)
        .set(REFRESH_TOKEN.REVOKED, true)
        .set(REFRESH_TOKEN.REVOKED_AT, RefreshTokenRepository.revokedAtNow())
        .where(FAMILY_ID.eq(familyId))
        .and(REFRESH_TOKEN.REVOKED.eq(false))
        .execute();
  }

  @Override
  public void revokeAllWebByUserId(Long userId) {
    dsl.update(REFRESH_TOKEN)
        .set(REFRESH_TOKEN.REVOKED, true)
        .set(REFRESH_TOKEN.REVOKED_AT, RefreshTokenRepository.revokedAtNow())
        .where(REFRESH_TOKEN.USER_ID.eq(userId))
        .and(REFRESH_TOKEN.PLANE.eq(PLANE_WEB))
        .and(REFRESH_TOKEN.REVOKED.eq(false))
        .execute();
  }

  @Override
  public void revokeAllByUserId(Long userId) {
    dsl.update(REFRESH_TOKEN)
        .set(REFRESH_TOKEN.REVOKED, true)
        .set(REFRESH_TOKEN.REVOKED_AT, RefreshTokenRepository.revokedAtNow())
        .where(REFRESH_TOKEN.USER_ID.eq(userId))
        .and(REFRESH_TOKEN.REVOKED.eq(false))
        .execute();
  }

  @Override
  public int deleteExpiredTokens() {
    return dsl.deleteFrom(REFRESH_TOKEN)
        .where(
            REFRESH_TOKEN
                .EXPIRES_AT
                .lt(LocalDateTime.now())
                .or(
                    REFRESH_TOKEN
                        .REVOKED
                        .eq(true)
                        .and(REFRESH_TOKEN.CREATED_AT.lt(LocalDateTime.now().minusDays(7)))))
        .execute();
  }
}
