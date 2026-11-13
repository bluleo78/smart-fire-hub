package com.smartfirehub.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.platformdb.mybatis.TiberoTextLimits;
import com.smartfirehub.notification.repository.mybatis.MyBatisNotificationOutboxRepository;
import com.smartfirehub.notification.repository.mybatis.NotificationOutboxMapper;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * NotificationOutboxRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-487 PoC).
 *
 * <p>게이트·준비 방식은 {@code TiberoMyBatisReviewItemRepositoryContractTest} 와 같다. 테넌트는 Tibero {@code
 * tenant} 표에 예약 대역(900,000,000+) id 로 만든다. 리포지토리는 런타임 계정 + DBMS_RLS(WD-492) 위에서 돌므로 다른 테넌트 행 재투입 차단
 * 시험도 PG 와 같이 돈다(WD-706). 픽스처(테넌트·정리·시각 당기기·원시 조회)는 정책을 우회하는 소유자 계정으로 한다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoMyBatisNotificationOutboxRepositoryContractTest
    extends NotificationOutboxRepositoryContractTest {

  private TiberoPlatformSchema poc;
  private NotificationOutboxRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformSchema.shared();
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
    // 공유 서버의 다른 행과 id 가 겹치면 새 난수로 다시 시도한다(WD-736).
    return poc.insertTenantWithRandomId(slugPrefix);
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

  /**
   * {@link TiberoTextLimits} 의 글자→바이트 전제를 실서버에서 고정한다(WD-728). DB 문자 집합이 UTF-8 이고, 보충 문자(Java char 두
   * 개)가 4바이트, 한글(char 하나)이 3바이트로 저장되면 Java char 하나는 최대 3바이트다. 보충 문자를 6바이트로 저장하는 CESU-8 류여도 char 당
   * 3바이트라 한도 계산은 같지만, 문자 집합이 바뀌면 이 시험이 먼저 알린다.
   */
  @Test
  @DisplayName("Tibero 는 바인딩한 한글을 3바이트, 보충 문자를 4바이트로 센다(UTF-8)")
  void databaseCharset_countsUtf8Bytes() {
    // 시험 계정에서는 NLS_DATABASE_PARAMETERS 가 0행이라(실측) 문자 집합 이름 대신 DB 가 실제로 세는 바이트 수(LENGTHB)를 고정한다.
    assertThat(poc.jdbc().queryForObject("SELECT LENGTHB(?) FROM DUAL", Integer.class, "가"))
        .isEqualTo(3);
    assertThat(poc.jdbc().queryForObject("SELECT LENGTHB(?) FROM DUAL", Integer.class, "😀"))
        .isEqualTo(4);
  }

  /** last_error 원시 조회 — 소유자 계정이라 정책을 거치지 않는다(WD-728). */
  @Override
  protected String lastErrorOf(UUID correlationId) {
    return poc.jdbc()
        .queryForObject(
            "SELECT last_error FROM notification_outbox WHERE correlation_id = ?",
            String.class,
            correlationId.toString());
  }
}
