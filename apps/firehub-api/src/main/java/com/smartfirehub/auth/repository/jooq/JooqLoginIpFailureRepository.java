package com.smartfirehub.auth.repository.jooq;

import static com.smartfirehub.jooq.Tables.LOGIN_IP_FAILURES;

import com.smartfirehub.auth.repository.LoginIpFailureRepository;
import com.smartfirehub.global.platformdb.PlatformLocks;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * LoginIpFailureRepository 의 jOOQ(PostgreSQL) 구현 — 로그인 IP 기준 제한(WD-578).
 *
 * <p>{@link #recordFailure}는 {@code INSERT ... ON CONFLICT DO UPDATE} 한 문장이라, 같은 (IP 키, 계정)으로 동시에
 * 들어온 실패 요청도 PK 충돌 없이 행 하나로 합쳐진다. 만료 기간(15분)은 SQL 의 {@code interval '15 minutes'}로 인라인된다 — 바꿀 때
 * V156 마이그레이션 주석과 함께 고친다.
 *
 * <p>만료 판정은 모두 DB 시계({@code now()})로 한다. 만료 시각을 DB 의 {@code now()}로 쓰므로 비교도 같은 시계여야 JVM·DB 시간대나 시계
 * 차이에 흔들리지 않는다(WD-235 원칙).
 *
 * <p>쓰기는 호출자 트랜잭션에 참여한다(REQUIRED, WD-552) — 로그인 트랜잭션이 쥔 커넥션 하나로 끝내 동시 로그인에서 풀이 교착하지 않게 한다. 실패 기록이
 * 남으려면 호출자가 실패 예외에서 롤백하지 않아야 한다(AuthService·PlatformAuthService 의 login 이 noRollbackFor 로 지킨다). 바깥
 * 트랜잭션이 없으면 여기서 자기 트랜잭션을 연다.
 */
@Repository
@RequiredArgsConstructor
public class JooqLoginIpFailureRepository implements LoginIpFailureRepository {

  /** UPSERT: 신규 쌍이면 행을 넣고, 이미 있으면(만료된 행 포함) 만료만 now()+15분으로 미룬다(rolling window). */
  private static final String RECORD_SQL =
      "INSERT INTO login_ip_failures (ip_key, username, expires_at) "
          + "VALUES (?, ?, now() + interval '15 minutes') "
          + "ON CONFLICT (ip_key, username) DO UPDATE SET "
          + "  expires_at = now() + interval '15 minutes'";

  /** IP 키 잠금 문자열의 접두 — 다른 advisory 잠금 키와 섞이지 않게 하는 이름 공간. */
  static final String LOCK_NAMESPACE = "login-ip-limit:";

  private final DSLContext dsl;

  /** 플랫폼 DB advisory 잠금 추상화(WD-486) — PG 전용 SQL 을 이 클래스에 두지 않는다. */
  private final PlatformLocks locks;

  @Override
  @Transactional
  public void recordFailure(String ipKey, String username) {
    dsl.execute(RECORD_SQL, ipKey, username);
  }

  /**
   * IP 키 잠금 — {@link PlatformLocks#xactLock(String)}(PG 는 {@code hashtextextended} 64비트 해시)로 잡는다.
   *
   * <p>키 앞에 {@link #LOCK_NAMESPACE} 를 붙여 다른 문자열 잠금(가입·멤버 관리 가드 등)과 잠금 공간이 겹치지 않게 한다. MANDATORY 인
   * 이유: 트랜잭션 밖에서 부르면 잠금이 즉시 풀려 동시 로그인을 막지 못하는데도 조용히 지나가므로, 그런 호출은 예외로 드러낸다.
   *
   * <p>같은 인스턴스 안의 줄 세우기는 트랜잭션 밖 {@code LoginIpGate} 가 먼저 한다(WD-630).
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockKey(String ipKey) {
    locks.xactLock(LOCK_NAMESPACE + ipKey);
  }

  @Override
  public int countActiveAccounts(String ipKey) {
    // PK 가 (ip_key, username) 이라 행 수가 곧 서로 다른 계정 수다.
    return dsl.fetchCount(
        dsl.selectOne()
            .from(LOGIN_IP_FAILURES)
            .where(LOGIN_IP_FAILURES.IP_KEY.eq(ipKey))
            .and(LOGIN_IP_FAILURES.EXPIRES_AT.gt(DSL.currentLocalDateTime())));
  }

  @Override
  @Transactional
  public int deleteExpired() {
    return dsl.deleteFrom(LOGIN_IP_FAILURES)
        .where(LOGIN_IP_FAILURES.EXPIRES_AT.le(DSL.currentLocalDateTime()))
        .execute();
  }
}
