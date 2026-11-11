package com.smartfirehub.auth.repository;

import com.smartfirehub.auth.repository.mybatis.LoginAttemptMapper;
import com.smartfirehub.auth.repository.mybatis.MyBatisLoginAttemptRepository;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * LoginAttemptRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-706). 리포지토리는 런타임 계정 위 트랜잭션
 * 프록시로(테넌트 컨텍스트 없이), 픽스처는 정책을 우회하는 소유자 계정으로 만진다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisLoginAttemptRepositoryContractTest extends LoginAttemptRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private LoginAttemptRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            LoginAttemptRepository.class,
            new MyBatisLoginAttemptRepository(poc.mapper(LoginAttemptMapper.class)));
  }

  @Override
  protected LoginAttemptRepository repository() {
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

  /** UTC 벽시계 TIMESTAMP 열과 같은 시계 — 저장소의 SYS_EXTRACT_UTC(SYSTIMESTAMP) 와 같은 식이다. */
  @Override
  protected String dbNow() {
    return "SYS_EXTRACT_UTC(SYSTIMESTAMP)";
  }
}
