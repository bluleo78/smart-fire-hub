package com.smartfirehub.auth.repository;

import com.smartfirehub.auth.repository.mybatis.MyBatisRefreshTokenRepository;
import com.smartfirehub.auth.repository.mybatis.RefreshTokenMapper;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * RefreshTokenRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-706). 리포지토리는 런타임 계정 위(테넌트
 * 컨텍스트 없음), 사용자·행 픽스처는 정책을 우회하는 소유자 계정으로 만진다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisRefreshTokenRepositoryContractTest extends RefreshTokenRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private RefreshTokenRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            RefreshTokenRepository.class,
            new MyBatisRefreshTokenRepository(poc.mapper(RefreshTokenMapper.class)));
  }

  @Override
  protected RefreshTokenRepository repository() {
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

  /** Tibero 는 RETURNING 이 없어 생성 키로 id 를 받는다. 사용자 표는 따옴표 대문자 "USER". */
  @Override
  protected long createUser(String username) {
    return poc.insertReturningId(
        "INSERT INTO \"USER\" (username, password, name) VALUES (?, 'x', ?)", username, username);
  }

  @Override
  protected void deleteUser(long userId) {
    poc.jdbc().update("DELETE FROM \"USER\" WHERE id = ?", userId);
  }
}
