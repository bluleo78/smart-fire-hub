package com.smartfirehub.auth.repository;

import static com.smartfirehub.jooq.Tables.*;
import static org.jooq.impl.DSL.field;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RefreshTokenRepository {

  private static final Field<UUID> FAMILY_ID = field("family_id", UUID.class);

  private final DSLContext dsl;

  public void save(Long userId, String tokenHash, LocalDateTime expiresAt, UUID familyId) {
    dsl.insertInto(REFRESH_TOKEN)
        .set(REFRESH_TOKEN.USER_ID, userId)
        .set(REFRESH_TOKEN.TOKEN_HASH, tokenHash)
        .set(REFRESH_TOKEN.EXPIRES_AT, expiresAt)
        .set(FAMILY_ID, familyId)
        .execute();
  }

  public boolean existsValidToken(String tokenHash) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(REFRESH_TOKEN)
            .where(REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash))
            .and(REFRESH_TOKEN.REVOKED.eq(false))
            .and(REFRESH_TOKEN.EXPIRES_AT.gt(LocalDateTime.now())));
  }

  public boolean isTokenRevoked(String tokenHash) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(REFRESH_TOKEN)
            .where(REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash))
            .and(REFRESH_TOKEN.REVOKED.eq(true)));
  }

  public Optional<UUID> findFamilyIdByTokenHash(String tokenHash) {
    return dsl.select(FAMILY_ID)
        .from(REFRESH_TOKEN)
        .where(REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash))
        .fetchOptional(FAMILY_ID);
  }

  /**
   * 아직 살아 있는 토큰 하나를 폐기한다.
   *
   * @return 이번 호출이 폐기했으면 true. 같은 토큰으로 동시에 들어온 다른 갱신이 먼저 폐기했다면(행 잠금을 기다린 뒤 {@code revoked=true} 를
   *     보게 된다) false — 회전은 한 번만 성공해야 하므로 호출자는 이 갱신을 거부한다(WD-268).
   */
  public boolean revokeByTokenHash(String tokenHash) {
    return dsl.update(REFRESH_TOKEN)
            .set(REFRESH_TOKEN.REVOKED, true)
            .where(REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash))
            .and(REFRESH_TOKEN.REVOKED.eq(false))
            .execute()
        > 0;
  }

  public void revokeByFamilyId(UUID familyId) {
    dsl.update(REFRESH_TOKEN)
        .set(REFRESH_TOKEN.REVOKED, true)
        .where(FAMILY_ID.eq(familyId))
        .and(REFRESH_TOKEN.REVOKED.eq(false))
        .execute();
  }

  public void revokeAllByUserId(Long userId) {
    dsl.update(REFRESH_TOKEN)
        .set(REFRESH_TOKEN.REVOKED, true)
        .where(REFRESH_TOKEN.USER_ID.eq(userId))
        .and(REFRESH_TOKEN.REVOKED.eq(false))
        .execute();
  }

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
