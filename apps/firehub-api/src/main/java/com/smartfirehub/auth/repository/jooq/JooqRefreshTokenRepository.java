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

/** RefreshTokenRepository 의 jOOQ(PostgreSQL) 구현 — WD-485. */
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
  public Optional<UUID> findFamilyIdByTokenHash(String tokenHash) {
    return dsl.select(FAMILY_ID)
        .from(REFRESH_TOKEN)
        .where(REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash))
        .fetchOptional(FAMILY_ID);
  }

  @Override
  public boolean revokeByTokenHash(String tokenHash) {
    return dsl.update(REFRESH_TOKEN)
            .set(REFRESH_TOKEN.REVOKED, true)
            .where(REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash))
            .and(REFRESH_TOKEN.REVOKED.eq(false))
            .execute()
        > 0;
  }

  @Override
  public void revokeByFamilyId(UUID familyId) {
    dsl.update(REFRESH_TOKEN)
        .set(REFRESH_TOKEN.REVOKED, true)
        .where(FAMILY_ID.eq(familyId))
        .and(REFRESH_TOKEN.REVOKED.eq(false))
        .execute();
  }

  @Override
  public void revokeAllWebByUserId(Long userId) {
    dsl.update(REFRESH_TOKEN)
        .set(REFRESH_TOKEN.REVOKED, true)
        .where(REFRESH_TOKEN.USER_ID.eq(userId))
        .and(REFRESH_TOKEN.PLANE.eq(PLANE_WEB))
        .and(REFRESH_TOKEN.REVOKED.eq(false))
        .execute();
  }

  @Override
  public void revokeAllByUserId(Long userId) {
    dsl.update(REFRESH_TOKEN)
        .set(REFRESH_TOKEN.REVOKED, true)
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
