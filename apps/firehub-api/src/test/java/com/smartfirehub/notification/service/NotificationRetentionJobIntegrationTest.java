package com.smartfirehub.notification.service;

import static com.smartfirehub.jooq.Tables.NOTIFICATION_OUTBOX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.notification.repository.NotificationOutboxRepository.NotificationOutboxRow;
import com.smartfirehub.notification.repository.OAuthStateRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 보존 잡의 테넌트 순회 검증(P2-f Task 3).
 *
 * <p>정책(V107) 이후 컨텍스트 없이 돌면 보존 삭제가 <b>에러 없이 0행</b>이 되어 정책이 조용히
 * 죽는다. 그래서 잡이 스스로 {@code outbox_tenant_ids('{SENT,PERMANENT_FAILURE}')} 를 순회해야 하고,
 * 이 테스트는 <b>컨텍스트를 비운 채</b> {@code cleanup()} 을 불러 그것을 확인한다.
 *
 * <p><b>보존 기간을 10년으로 밀어 둔 이유(R5).</b> {@code deleteSentOlderThan} 은 테넌트 술어가 없는
 * 전역 DELETE 라(§ 멱등 집합 연산은 정책에 맡긴다는 판단), 기본값 90일로 부르면 공유 테스트 DB 에
 * 쌓인 <b>다른 세션의 오래된 SENT 행</b>(2026-08-16 실측: 최고령 sent_at = 2026-04-18, 120일 전)까지
 * 지운다. 컷오프를 10년 전으로 밀고 이 테스트가 만든 행만 2000년으로 백데이트하면, 실존할 수 있는
 * 어떤 잔재보다도 오래된 행은 내 것뿐이라 남의 행을 건드릴 여지가 원천적으로 없다.
 */
@TestPropertySource(
    // 5개 알림 통합 테스트가 완전히 동일한 프로퍼티 집합을 공유한다 — 스프링 컨텍스트 캐시
    // 키가 프로퍼티 배열이라, 한 글자만 달라도 컨텍스트가 하나 더 뜬다. 컨텍스트마다 스케줄러
    // 스레드가 따로 도는데 그중 일부는 @MockitoBean 을 건드려, 컨텍스트 수가 늘면 무관한 테스트의
    // 스터빙과 경합해 전체 스위트에서만 재현되는 플레이크가 난다(실측: DataExportServiceExtTest).
    // 값 자체는 각 테스트가 필요로 하는 것의 합집합이며 서로 무해하다.
    properties = {
      "notification.outbox.enabled=true",
      // 세 스케줄러의 기동 1회 실행 지연(notification.scheduler.initial_delay_ms)은
      // application-test.yml 로 옮겼다 — 스위트 전체에 같은 값이 필요하고, 이 노브의 실소비자가
      // 테스트뿐이라 프로덕션 프로퍼티로 남길 이유가 없었다.
      "notification.worker.poll_interval_ms=3600000",
      "notification.worker.listen_notify=false",
      "notification.worker.zombie_age_minutes=0",
      "notification.retention.sent_days=3650",
      "notification.retention.permanent_failure_days=3650",
      // 주기 자체도 스위트 길이보다 길게 잡아 두 번째 실행이 아예 오지 않게 한다.
      "notification.metrics.refresh_interval_ms=3600000"
    })
class NotificationRetentionJobIntegrationTest extends IntegrationTestBase {

  private static final OffsetDateTime ANCIENT =
      OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

  @Autowired private NotificationRetentionJob job;
  @Autowired private NotificationOutboxRepository outboxRepo;
  @Autowired private OAuthStateRepository oauthStateRepo;
  @Autowired private DSLContext dsl;

  /** outbox 픽스처를 담을 테넌트. */
  private long outboxTenant;

  /** oauth_state 픽스처만 담을 테넌트 — outbox 행이 없어야 "순회 밖"임이 드러난다. */
  private long oauthTenant;

  private Long oauthUserId;

  @BeforeEach
  void createScratchTenants() {
    outboxTenant = TenantRlsTestSupport.createActiveTenant(dsl, "outbox-retention");
    oauthTenant = TenantRlsTestSupport.createActiveTenant(dsl, "oauth-retention");
    TenantContext.set(outboxTenant);
  }

  @AfterEach
  void cleanupScratchTenants() {
    inTenantFixture(
        outboxTenant, () -> TenantRlsTestSupport.deleteChannelCascade(dsl, outboxTenant));
    inTenantFixture(oauthTenant, () -> TenantRlsTestSupport.deleteChannelCascade(dsl, oauthTenant));
    // deleteTenants 가 해당 테넌트의 oauth_state 도 함께 지운다(FK 때문).
    TenantRlsTestSupport.deleteTenants(dsl, outboxTenant, oauthTenant);
    TenantRlsTestSupport.deleteUser(dsl, oauthUserId);
  }

  @Test
  void cleanup_deletesAgedRowsPerTenantAndExpiredOauthStateOutsideLoop() {
    UUID sentCorr = insertAged("SENT", NOTIFICATION_OUTBOX.SENT_AT);
    UUID failCorr = insertAged("PERMANENT_FAILURE", NOTIFICATION_OUTBOX.LAST_ERROR_AT);

    // outbox 행이 하나도 없는 별도 테넌트에 만료된 state 를 심는다. 이 행이 지워진다는 것은
    // oauth_state 삭제가 테넌트 순회 밖에서 돈다는 뜻이다(R7 — 이 테이블에는 RLS 가 없다).
    String state = "retention-" + UUID.randomUUID();
    oauthUserId = TenantRlsTestSupport.insertUser(dsl, "oauthretention");
    inTenantFixture(
        oauthTenant,
        () ->
            oauthStateRepo.create(
                state, oauthUserId, ChannelType.SLACK, Instant.now().minusSeconds(3600)));

    // 배경 스레드 재현 — 컨텍스트 없이 부른다.
    TenantContext.clear();
    job.cleanup();
    TenantContext.set(outboxTenant);

    assertThat(outboxRepo.findByCorrelation(sentCorr))
        .as("보존 기간을 넘긴 SENT 행이 테넌트 순회 안에서 삭제돼야 한다")
        .isEmpty();
    assertThat(outboxRepo.findByCorrelation(failCorr))
        .as("보존 기간을 넘긴 PERMANENT_FAILURE 행도 같은 순회에서 삭제돼야 한다")
        .isEmpty();
    assertThat(oauthStateRowExists(state))
        .as("만료된 oauth_state 는 outbox 일감이 없는 테넌트에서도 삭제돼야 한다(순회 밖)")
        .isFalse();
  }

  /** 주어진 상태의 행을 만들고 지정한 타임스탬프 컬럼을 2000년으로 백데이트한다. */
  private UUID insertAged(String status, org.jooq.TableField<?, OffsetDateTime> agedColumn) {
    UUID corr = UUID.randomUUID();
    outboxRepo.insertIfAbsent(
        new NotificationOutboxRow(
            null,
            "retention-" + corr,
            corr,
            "TEST_RETENTION",
            null,
            ChannelType.CHAT,
            null,
            null,
            null,
            null,
            "{\"t\":\"x\"}",
            "STANDARD",
            "PENDING",
            0,
            Instant.now()));
    inTenantFixture(
        outboxTenant,
        () ->
            dsl.update(NOTIFICATION_OUTBOX)
                .set(NOTIFICATION_OUTBOX.STATUS, status)
                .set(agedColumn, ANCIENT)
                .where(NOTIFICATION_OUTBOX.CORRELATION_ID.eq(corr))
                .execute());
    return corr;
  }

  private boolean oauthStateRowExists(String state) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(name("oauth_state"))
            .where(field(name("state"), String.class).eq(state)));
  }
}
