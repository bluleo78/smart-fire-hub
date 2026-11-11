package com.smartfirehub.audit.repository;

import com.smartfirehub.audit.repository.mybatis.AuditLogMapper;
import com.smartfirehub.audit.repository.mybatis.MyBatisAuditLogRepository;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

/**
 * AuditLogRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-706). 리포지토리는 런타임 계정 + DBMS_RLS
 * 위(구현의 클래스 레벨 {@code @Transactional} 을 하네스 프록시가 그대로 읽어 호출마다 테넌트 트랜잭션을 연다), 픽스처는 정책을 우회하는 소유자 계정으로
 * 만들고 지운다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisAuditLogRepositoryContractTest extends AuditLogRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private AuditLogRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
    repository =
        poc.transactional(
            AuditLogRepository.class,
            new MyBatisAuditLogRepository(poc.mapper(AuditLogMapper.class)));
  }

  @Override
  protected AuditLogRepository repository() {
    return repository;
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }
}
