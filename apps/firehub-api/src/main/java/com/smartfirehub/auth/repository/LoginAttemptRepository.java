package com.smartfirehub.auth.repository;

import static com.smartfirehub.jooq.Tables.LOGIN_ATTEMPTS;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 실패 카운터 영속 저장소(#144).
 *
 * <p>{@link #incrementAttempts(String)}는 UPSERT + RETURNING으로 동시 실패 요청의 카운터 증가를 원자적으로 처리한다.
 * 잠금 기간(15분)은 SQL의 {@code interval '15 minutes'}로 인라인된다 — 변경 시 V58 마이그레이션 주석과
 * 본 클래스 SQL을 함께 동기화한다.
 */
@Repository
@RequiredArgsConstructor
public class LoginAttemptRepository {

  /** UPSERT: 신규면 attempts=1, 기존이면 +1. 매 호출마다 expires_at을 now()+15min로 갱신(rolling window). */
  private static final String INCREMENT_SQL =
      "INSERT INTO login_attempts (username, attempts, expires_at) "
          + "VALUES (?, 1, now() + interval '15 minutes') "
          + "ON CONFLICT (username) DO UPDATE SET "
          + "  attempts = login_attempts.attempts + 1, "
          + "  expires_at = now() + interval '15 minutes', "
          + "  updated_at = now() "
          + "RETURNING attempts";

  private final DSLContext dsl;

  /**
   * 실패 카운터를 원자적으로 1 증가시킨다. 부모 트랜잭션이 롤백돼도 카운터는 유지되도록 REQUIRES_NEW로 독립 커밋한다.
   *
   * @return 증가 후 카운터 값
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int incrementAttempts(String username) {
    return dsl.fetchOne(INCREMENT_SQL, username).get(0, Integer.class);
  }

  /** 만료되지 않은 시도 카운터. row가 없거나 만료됐으면 0. */
  public int getAttempts(String username) {
    Integer attempts =
        dsl.select(LOGIN_ATTEMPTS.ATTEMPTS)
            .from(LOGIN_ATTEMPTS)
            .where(LOGIN_ATTEMPTS.USERNAME.eq(username))
            .and(LOGIN_ATTEMPTS.EXPIRES_AT.gt(LocalDateTime.now()))
            .fetchOne(LOGIN_ATTEMPTS.ATTEMPTS);
    return attempts == null ? 0 : attempts;
  }

  /** 성공 로그인 시 카운터 제거. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void clear(String username) {
    dsl.deleteFrom(LOGIN_ATTEMPTS).where(LOGIN_ATTEMPTS.USERNAME.eq(username)).execute();
  }

  /** 만료된 row를 일괄 삭제하고 삭제 건수를 반환한다(스케줄러용). */
  @Transactional
  public int deleteExpired() {
    return dsl.deleteFrom(LOGIN_ATTEMPTS)
        .where(LOGIN_ATTEMPTS.EXPIRES_AT.lt(LocalDateTime.now()))
        .execute();
  }
}
