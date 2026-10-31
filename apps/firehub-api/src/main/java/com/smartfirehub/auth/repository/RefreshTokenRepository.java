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

  /**
   * 웹(워크스페이스) 평면. {@code refresh_token.plane}(V154, WD-314) 기본값과 같다 — 웹 로그아웃이 같은 사람의 운영자 콘솔 세션을 끊지
   * 않게 평면별로 폐기하는 데 쓴다.
   */
  public static final String PLANE_WEB = "WEB";

  /** 운영자 콘솔 평면. */
  public static final String PLANE_PLATFORM = "PLATFORM";

  private final DSLContext dsl;

  /** 웹 평면 토큰을 저장한다. */
  public void save(Long userId, String tokenHash, LocalDateTime expiresAt, UUID familyId) {
    save(userId, tokenHash, expiresAt, familyId, PLANE_WEB);
  }

  /** 지정한 평면({@link #PLANE_WEB}/{@link #PLANE_PLATFORM})의 토큰을 저장한다. */
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

  /**
   * 사용자의 웹 평면 토큰만 모두 폐기한다(웹 로그아웃, WD-314). 같은 사람의 운영자 콘솔 세션(PLATFORM)은 남긴다 — 운영자 콘솔 로그아웃이 웹 세션을
   * 건드리지 않는 것과 같은 원칙이다.
   */
  public void revokeAllWebByUserId(Long userId) {
    dsl.update(REFRESH_TOKEN)
        .set(REFRESH_TOKEN.REVOKED, true)
        .where(REFRESH_TOKEN.USER_ID.eq(userId))
        .and(REFRESH_TOKEN.PLANE.eq(PLANE_WEB))
        .and(REFRESH_TOKEN.REVOKED.eq(false))
        .execute();
  }

  /** 사용자의 모든 평면 토큰을 폐기한다(계정 비활성화·비밀번호 변경처럼 모든 세션을 끊어야 할 때). */
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
