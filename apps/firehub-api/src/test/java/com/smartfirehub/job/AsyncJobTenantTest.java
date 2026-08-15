package com.smartfirehub.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.job.dto.AsyncJobStatusResponse;
import com.smartfirehub.job.service.AsyncJobService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code async_job} 요청 경로의 테넌트 배선 검증 (P2-b Task 7).
 *
 * <p>Task 9(V96)에서 {@code async_job} 에 정책이 붙었다. 이 테스트의 핵심 판별력은 여전히
 * "앰비언트 트랜잭션 없이 {@link AsyncJobService} 를 호출해도 GUC 가 정상 주입되는가" 이고,
 * 여기에 정책이 실제로 격리하는지 보는 양방향 단언({@code assertTwoSidedIsolation})을 더한다.
 *
 * <p>{@code AsyncJobService} 의 모든 쓰기/조회 메서드는 {@code asyncJobRepository} 를 경유하고,
 * 그 리포지토리는 이미 클래스 레벨 {@code @Transactional} 을 갖고 있다(Task 1). 즉 이 서비스에 붙인
 * {@code @Transactional} 은 결함 수정이 아니라 심층 방어다 — 리포지토리 호출이 있는 한 이 테스트는
 * 서비스 애노테이션을 지워도 통과한다. 그럼에도 회귀 커버리지로서 end-to-end 배선을 고정해 둔다.
 *
 * <p><b>클래스 레벨 {@code @Transactional} 이 없다 — 의도된 것이다.</b> 픽스처만
 * {@link TenantRlsTestSupport#runInTenantTransaction} 으로 감싸고, 검증 대상 호출(서비스 메서드)은
 * 트랜잭션 밖에 남긴다(선례: {@code apiconnection/ApiConnectionTenantTest}).
 */
class AsyncJobTenantTest extends IntegrationTestBase {

  @Autowired private AsyncJobService asyncJobService;
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate tx;

  private Long tenantA;
  private Long tenantB;
  private Long userA;

  @BeforeEach
  void seedTenant() {
    tx = new TransactionTemplate(transactionManager);
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "asyncjob-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "asyncjob-b");
    userA = TenantRlsTestSupport.insertUser(dsl, "asyncjob_a_");
  }

  @AfterEach
  void cleanUp() {
    // V96 이후 async_job 은 RLS 대상이라 트랜잭션 밖 삭제는 0행이 되고, 뒤이은 user 삭제가 FK 로
    // 터진다. 두 테넌트 컨텍스트 각각에서 지운다(격리 테스트가 B 쪽에도 행을 만든다).
    for (Long tenantId : new Long[] {tenantA, tenantB}) {
      TenantRlsTestSupport.runInTenantTransaction(
          tx,
          tenantId,
          () ->
              dsl.deleteFrom(table(name("async_job")))
                  .where(field(name("user_id"), Long.class).eq(userA))
                  .execute());
    }
    TenantRlsTestSupport.deleteUser(dsl, userA);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
  }

  @Test
  @DisplayName("createJob 은 앰비언트 트랜잭션 없이도 tenant_id DEFAULT 를 GUC 로 채운다")
  void createJob_withoutAmbientTransaction_fillsTenantIdFromGuc() {
    // 컨텍스트만 세우고 트랜잭션은 열지 않는다 — 운영의 컨트롤러 요청 스레드와 동일 조건
    // (JwtAuthenticationFilter 가 컨텍스트만 세우고, 트랜잭션은 서비스/리포지토리 프록시가 연다).
    TenantContext.set(tenantA);
    String jobId;
    try {
      jobId =
          asyncJobService.createJob(
              "IMPORT", "dataset", UUID.randomUUID().toString(), userA, Map.of());
    } finally {
      TenantContext.clear();
    }

    assertThat(jobId).isNotNull();

    // 검증 조회도 RLS 대상이다 — 트랜잭션 밖에서 읽으면 GUC 가 없어 정책이 막고 null 이 된다.
    Long storedTenantId =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            tenantA,
            () ->
                dsl.select(field(name("tenant_id"), Long.class))
                    .from(table(name("async_job")))
                    .where(field(name("id"), String.class).eq(jobId))
                    .fetchOne(field(name("tenant_id"), Long.class)));
    assertThat(storedTenantId)
        .as("tenant_id DEFAULT 가 GUC 에서 채워져야 한다 — 트랜잭션이 없으면 NULL 이 되어 NOT NULL 위반이 난다")
        .isEqualTo(tenantA);
  }

  @Test
  @DisplayName("subscribe 는 앰비언트 트랜잭션 없이도 초기 상태를 조회해 즉시 이벤트로 보낸다")
  void subscribe_withoutAmbientTransaction_sendsInitialState() {
    String jobId = createJobInTenantTx(tenantA, userA, "dataset", UUID.randomUUID().toString());

    // subscribe 도 요청 스레드에서 컨텍스트만 있고 트랜잭션은 없는 조건으로 호출한다.
    TenantContext.set(tenantA);
    SseEmitter emitter;
    try {
      emitter = asyncJobService.subscribe(jobId, userA);
    } finally {
      TenantContext.clear();
    }

    assertThat(emitter).as("subscribe 는 findById 가 트랜잭션 없이도 성공해 emitter 를 반환해야 한다").isNotNull();
  }

  @Test
  @DisplayName("subscribe 는 소유자가 아닌 사용자의 접근을 거부한다(트랜잭션 부재와 무관하게)")
  void subscribe_deniesNonOwner() {
    Long otherUser = TenantRlsTestSupport.insertUser(dsl, "asyncjob_other_");
    String jobId = createJobInTenantTx(tenantA, userA, "dataset", UUID.randomUUID().toString());

    TenantContext.set(tenantA);
    try {
      assertThatThrownBy(() -> asyncJobService.subscribe(jobId, otherUser))
          .isInstanceOf(AccessDeniedException.class);
    } finally {
      TenantContext.clear();
      TenantRlsTestSupport.deleteUser(dsl, otherUser);
    }
  }

  @Test
  @DisplayName("failJob 은 워커 스레드 조건(컨텍스트만 있고 트랜잭션 없음)에서도 진행률을 보존한 채 실패 처리한다")
  void failJob_withoutAmbientTransaction_preservesLastProgress() {
    String jobId = createJobInTenantTx(tenantA, userA, "pipeline", UUID.randomUUID().toString());

    // updateProgress 로 진행률을 올려둔다 — 역시 트랜잭션 없이, 워커 스레드 조건 그대로.
    TenantContext.set(tenantA);
    try {
      asyncJobService.updateProgress(
          jobId, "RUNNING", 42, "halfway", Map.of("processedRows", 42));
      asyncJobService.failJob(jobId, "boom");
    } finally {
      TenantContext.clear();
    }

    var row =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            tenantA,
            () ->
                dsl.select(
                        field(name("stage"), String.class),
                        field(name("progress"), Integer.class),
                        field(name("error_message"), String.class))
                    .from(table(name("async_job")))
                    .where(field(name("id"), String.class).eq(jobId))
                    .fetchOne());

    assertThat(row).isNotNull();
    assertThat(row.get(field(name("stage"), String.class))).isEqualTo("FAILED");
    assertThat(row.get(field(name("progress"), Integer.class)))
        .as("실패 직전 진행률(42)이 보존돼야 한다")
        .isEqualTo(42);
    assertThat(row.get(field(name("error_message"), String.class))).isEqualTo("boom");
  }

  /**
   * V96 정책이 실제로 격리하는지 양방향으로 확인한다.
   *
   * <p>{@code async_job} 의 PK 는 문자열 id 라 {@code assertTwoSidedIsolation}(id=bigint 전제)을
   * 쓸 수 없어 같은 형태를 여기서 직접 편다 — 소유 테넌트에서 보이고, 타 테넌트에서 안 보이는
   * 두 쪽을 모두 본다(단방향만 보면 빈 결과에서 공허하게 통과한다).
   */
  @Test
  @DisplayName("async_job 은 테넌트 간 양방향으로 격리된다")
  void asyncJobIsolatedBothWays() {
    String jobId = createJobInTenantTx(tenantA, userA, "dataset", UUID.randomUUID().toString());

    Integer visibleToOwner =
        TenantRlsTestSupport.runInTenantTransaction(
            tx, tenantA, () -> countJobRows(jobId));
    assertThat(visibleToOwner).as("소유 테넌트에서 자기 잡이 보여야 한다").isEqualTo(1);

    Integer visibleToOther =
        TenantRlsTestSupport.runInTenantTransaction(
            tx, tenantB, () -> countJobRows(jobId));
    assertThat(visibleToOther).as("다른 테넌트에서 남의 잡이 보이면 격리 실패다").isZero();
  }

  // ── 픽스처 헬퍼 ────────────────────────────────────────────────────────────

  /** 현재 테넌트 컨텍스트에서 해당 잡 행이 몇 개 보이는지(RLS 적용 결과). */
  private int countJobRows(String jobId) {
    return dsl.fetchCount(table(name("async_job")), field(name("id"), String.class).eq(jobId));
  }


  /**
   * 지정한 테넌트 컨텍스트의 트랜잭션 안에서 잡 하나를 만든다. 픽스처는 검증 대상이 아니므로
   * 트랜잭션으로 감싸도 무방하다 — 검증은 별도 호출(트랜잭션 밖)에서 이뤄진다.
   */
  private String createJobInTenantTx(Long tenantId, Long userId, String resource, String resourceId) {
    return TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantId,
        () -> asyncJobService.createJob("IMPORT", resource, resourceId, userId, Map.of()));
  }
}
