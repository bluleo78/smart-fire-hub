package com.smartfirehub.notification.repository;

import com.smartfirehub.notification.repository.mybatis.MyBatisNotificationOutboxRepository;
import com.smartfirehub.notification.repository.mybatis.NotificationOutboxMapper;
import com.smartfirehub.support.tibero.TiberoPlatformPoc;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * NotificationOutboxRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-487 PoC).
 *
 * <p>게이트·준비 방식은 {@code TiberoMyBatisReviewItemRepositoryContractTest} 와 같다. 테넌트는 Tibero {@code
 * tenant} 표에 예약 대역(900,000,000+) id 로 만든다. RLS 가 없으므로 {@link #supportsRowLevelSecurity()} 는 false —
 * 다른 테넌트 행 재투입 차단 시험은 건너뛴다(Tibero 쓰기 문장에 테넌트 술어가 없다, WD-492 범위).
 */
@EnabledIfEnvironmentVariable(named = "TIBERO_TEST_HOST", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisNotificationOutboxRepositoryContractTest
    extends NotificationOutboxRepositoryContractTest {

  private TiberoPlatformPoc poc;
  private NotificationOutboxRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformPoc.shared();
    repository =
        poc.transactional(
            NotificationOutboxRepository.class,
            new MyBatisNotificationOutboxRepository(poc.mapper(NotificationOutboxMapper.class)));
  }

  @Override
  protected NotificationOutboxRepository repository() {
    return repository;
  }

  @Override
  protected long createTenant(String slugPrefix) {
    long id = 900_000_000L + ThreadLocalRandom.current().nextLong(1_000_000L);
    poc.jdbc()
        .update(
            "INSERT INTO tenant (id, slug, status) VALUES (?, ?, 'ACTIVE')",
            id,
            slugPrefix + "-" + id);
    return id;
  }

  @Override
  protected void cleanupTenant(long id) {
    poc.jdbc().update("DELETE FROM notification_outbox WHERE tenant_id = ?", id);
    poc.jdbc().update("DELETE FROM tenant WHERE id = ?", id);
  }

  @Override
  protected void deleteTenant(long id) {
    poc.jdbc().update("DELETE FROM tenant WHERE id = ?", id);
  }

  /** DB 시계(UTC 벽시계) 기준 1분 전으로 당긴다 — 컬럼이 UTC 벽시계 TIMESTAMP 라 SYS_EXTRACT_UTC 를 쓴다. */
  @Override
  protected void makeRowDue(UUID correlationId) {
    poc.jdbc()
        .update(
            "UPDATE notification_outbox SET next_attempt_at = SYS_EXTRACT_UTC(SYSTIMESTAMP) - INTERVAL '1' MINUTE"
                + " WHERE correlation_id = ?",
            correlationId.toString());
  }

  @Override
  protected Long stampedTenantOf(UUID correlationId) {
    return poc.jdbc()
        .queryForObject(
            "SELECT tenant_id FROM notification_outbox WHERE correlation_id = ?",
            Long.class,
            correlationId.toString());
  }

  /** Tibero 에는 RLS 가 없다(WD-492 범위). */
  @Override
  protected boolean supportsRowLevelSecurity() {
    return false;
  }
}
