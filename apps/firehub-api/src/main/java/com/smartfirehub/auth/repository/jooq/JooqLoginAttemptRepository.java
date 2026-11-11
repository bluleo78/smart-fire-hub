package com.smartfirehub.auth.repository.jooq;

import static com.smartfirehub.jooq.Tables.LOGIN_ATTEMPTS;

import com.smartfirehub.auth.repository.LoginAttemptRepository;
import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * LoginAttemptRepository 의 jOOQ(PostgreSQL) 구현 — WD-485.
 *
 * <p>로그인 실패 카운터 영속 저장소(#144).
 *
 * <p>{@link #incrementAttempts(String)}는 UPSERT + RETURNING으로 동시 실패 요청의 카운터 증가를 원자적으로 처리한다. 잠금
 * 기간(15분)은 SQL의 {@code interval '15 minutes'}로 인라인된다 — 변경 시 V58 마이그레이션 주석과 본 클래스 SQL을 함께 동기화한다.
 *
 * <p>카운터 쓰기({@link #incrementAttempts}·{@link #clear})는 호출자 트랜잭션에 참여한다(REQUIRED, WD-552). 예전의
 * REQUIRES_NEW 는 로그인 트랜잭션이 커넥션 1개를 쥔 채 두 번째 커넥션을 요구해, 동시 로그인이 풀 크기 이상이면 모두 두 번째 커넥션을 기다리며 교착했다. 요청당
 * 커넥션 1개 원칙(SecurityAuditRecorder 와 같은 원칙)을 지키는 대신, 실패 카운터를 남기려면 호출자가 실패 예외에서 롤백하지 않아야
 * 한다(AuthService·PlatformAuthService 의 login 이 noRollbackFor 로 지킨다). 바깥 트랜잭션이 없으면 여기서 자기 트랜잭션을 연다.
 *
 * <p>받아들인 절충: 카운터 행 잠금이 로그인 트랜잭션이 끝날 때까지 유지되므로, 같은 계정의 동시 로그인은 그 뒤에 잠깐 줄을 선다. 각 요청은 커넥션 1개만 쥐고 잠금을
 * 쥔 쪽은 진행하므로 풀 교착은 아니다. 또 증가분은 커밋 시점에야 {@code isBlocked} 에 보이므로, 독립 커밋하던 예전보다 조금 늦게 반영된다.
 */
@Repository
@ConditionalOnPostgresPlatformDb
@RequiredArgsConstructor
public class JooqLoginAttemptRepository implements LoginAttemptRepository {

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

  @Override
  @Transactional
  public int incrementAttempts(String username) {
    return dsl.fetchOne(INCREMENT_SQL, username).get(0, Integer.class);
  }

  @Override
  public int getAttempts(String username) {
    Integer attempts =
        dsl.select(LOGIN_ATTEMPTS.ATTEMPTS)
            .from(LOGIN_ATTEMPTS)
            .where(LOGIN_ATTEMPTS.USERNAME.eq(username))
            .and(LOGIN_ATTEMPTS.EXPIRES_AT.gt(LocalDateTime.now()))
            .fetchOne(LOGIN_ATTEMPTS.ATTEMPTS);
    return attempts == null ? 0 : attempts;
  }

  @Override
  @Transactional
  public void clear(String username) {
    dsl.deleteFrom(LOGIN_ATTEMPTS).where(LOGIN_ATTEMPTS.USERNAME.eq(username)).execute();
  }

  @Override
  @Transactional
  public int deleteExpired() {
    return dsl.deleteFrom(LOGIN_ATTEMPTS)
        .where(LOGIN_ATTEMPTS.EXPIRES_AT.lt(LocalDateTime.now()))
        .execute();
  }
}
