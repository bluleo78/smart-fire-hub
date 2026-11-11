package com.smartfirehub.auth.repository.mybatis;

import com.smartfirehub.auth.repository.LoginIpFailureRepository;
import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * LoginIpFailureRepository 의 MyBatis 구현(WD-706) — {@code JooqLoginIpFailureRepository} 와 같은 의미.
 *
 * <p>로그인 전(테넌트 없음)·전역 테이블이라 클래스 레벨 트랜잭션 없이 jOOQ 구현과 같은 메서드별 전파를 쓴다. 쓰기는 호출자 트랜잭션에 참여한다(REQUIRED,
 * WD-552) — 로그인 트랜잭션이 쥔 커넥션 하나로 끝내 동시 로그인에서 풀이 교착하지 않게 한다. 실패 기록이 남으려면 호출자가 실패 예외에서 롤백하지 않아야 한다
 * (noRollbackFor). 바깥 트랜잭션이 없으면 여기서 자기 트랜잭션을 연다.
 *
 * <p>빈 문자열 키·계정은 {@link EmptyKeys} 센티널로 바꿔 저장·조회한다(Tibero 가 {@code ''} 를 NULL 로 보기 때문).
 */
@PlatformMyBatisRepository
public class MyBatisLoginIpFailureRepository implements LoginIpFailureRepository {

  /** IP 키 잠금 문자열의 접두 — 다른 advisory 잠금 키와 섞이지 않게 하는 이름 공간(jOOQ 구현과 같은 값). */
  static final String LOCK_NAMESPACE = "login-ip-limit:";

  private final LoginIpFailureMapper mapper;

  /** 플랫폼 DB 잠금 추상화(WD-486) — PG advisory·Tibero DBMS_LOCK 을 이 클래스가 직접 알지 않는다. */
  private final PlatformLocks locks;

  public MyBatisLoginIpFailureRepository(LoginIpFailureMapper mapper, PlatformLocks locks) {
    this.mapper = mapper;
    this.locks = locks;
  }

  /**
   * Tibero 의 MERGE 는 같은 쌍이 처음으로 동시에 들어오면 한쪽이 PK 위반으로 실패할 수 있다(PG ON CONFLICT 는 그렇지 않다). 문장 단위 실패라
   * 트랜잭션이 살아 있으므로 한 번 다시 시도하면 이미 들어간 행의 만료만 미룬다.
   */
  @Override
  @Transactional
  public void recordFailure(String ipKey, String username) {
    String key = EmptyKeys.key(ipKey);
    String user = EmptyKeys.key(username);
    try {
      mapper.upsertFailure(key, user);
    } catch (DuplicateKeyException e) {
      mapper.upsertFailure(key, user);
    }
  }

  /** IP 키 잠금 — MANDATORY: 트랜잭션 밖에서 부르면 잠금이 즉시 풀려 막지 못하는데도 조용히 지나가므로 예외로 드러낸다. */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockKey(String ipKey) {
    locks.xactLock(LOCK_NAMESPACE + ipKey);
  }

  @Override
  public int countActiveAccounts(String ipKey) {
    return mapper.countActive(EmptyKeys.key(ipKey));
  }

  @Override
  @Transactional
  public int deleteExpired() {
    return mapper.deleteExpired();
  }
}
