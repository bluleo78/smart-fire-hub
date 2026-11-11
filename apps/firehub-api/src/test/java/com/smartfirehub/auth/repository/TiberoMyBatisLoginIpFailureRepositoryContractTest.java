package com.smartfirehub.auth.repository;

import static org.mockito.Mockito.mock;

import com.smartfirehub.auth.repository.mybatis.LoginIpFailureMapper;
import com.smartfirehub.auth.repository.mybatis.MyBatisLoginIpFailureRepository;
import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * LoginIpFailureRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-706).
 *
 * <p>잠금은 가짜({@code PlatformLocks} 목)를 쓴다 — 하네스 런타임 계정에는 {@code SYS.DBMS_LOCK} 권한이 없고, 실제 DBMS_LOCK
 * 동작은 {@code TiberoPlatformLocksLiveTest} 가 고정한다. 여기서는 MANDATORY 전파(트랜잭션 밖 거절)와 트랜잭션 안 호출만 본다. 키
 * 접두는 단위 시험({@code MyBatisLoginIpFailureLockKeyTest})이 고정한다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisLoginIpFailureRepositoryContractTest
    extends LoginIpFailureRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private LoginIpFailureRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            LoginIpFailureRepository.class,
            new MyBatisLoginIpFailureRepository(
                poc.mapper(LoginIpFailureMapper.class), mock(PlatformLocks.class)));
  }

  @Override
  protected LoginIpFailureRepository repository() {
    return repository;
  }

  @Override
  protected void fixtureUpdate(String sql, Object... args) {
    poc.jdbc().update(sql, args);
  }

  @Override
  protected long fixtureLong(String sql, Object... args) {
    return poc.jdbc().queryForObject(sql, Long.class, args);
  }

  @Override
  protected String dbNow() {
    return "SYS_EXTRACT_UTC(SYSTIMESTAMP)";
  }

  /** 리포지토리와 같은 런타임 풀 트랜잭션 관리자로 연다 — MANDATORY 가 이 트랜잭션에 합류한다. */
  @Override
  protected void inTx(Runnable action) {
    poc.tx().executeWithoutResult(status -> action.run());
  }
}
