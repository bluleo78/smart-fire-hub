package com.smartfirehub.embedding.reembed;

import static com.smartfirehub.support.EmbeddingTestFixtures.axis;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.config.EmbeddingConfig;
import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.embedding.config.EmbeddingProviderType;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.support.EmbeddingTestFixtures;
import com.smartfirehub.support.EmbeddingTestFixtures.DocFixture;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.Duration;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 백로그 스윕(A1): 잡이 이미 DONE 인데 늦게 끝난 쓰기가 옛 공간 벡터를 남긴 경우를 주기 스윕이 잡아 다시 투입한다.
 *
 * <p>설정·판정식·상태 행은 실제 저장소를 쓰고 잡만 가짜로 둔다. 활성 테넌트 전체를 도므로 단언은 이 테스트 테넌트 id 로만 한정한다(공유 컨테이너의 다른 테넌트
 * 무관).
 */
class EmbeddingBacklogSweepSchedulerTest extends IntegrationTestBase {

  private static final EmbeddingSpace OLD = new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3");
  private static final EmbeddingSpace NEW = new EmbeddingSpace(EmbeddingDimension.D1536, "m2");

  @Autowired private TenantScopedRunner tenantRunner;
  @Autowired private EmbeddingConfigService configService;
  @Autowired private EmbeddingBacklogService backlogService;
  @Autowired private EmbeddingReembedStateRepository states;
  @Autowired private DocumentChunkRepository chunks;
  @Autowired private DSLContext dsl;

  private final TenantReembedJob job = mock(TenantReembedJob.class);
  private EmbeddingBacklogSweepScheduler scheduler;
  private long tenant;
  private DocFixture doc;

  @BeforeEach
  void seed() {
    scheduler =
        new EmbeddingBacklogSweepScheduler(
            tenantRunner, configService, backlogService, states, job, true);
    tenant = TenantRlsTestSupport.createActiveTenant(dsl, "bsweep");
    doc = inTenantFixture(tenant, () -> EmbeddingTestFixtures.createDocumentDataset(dsl, "bsweep"));
  }

  @AfterEach
  void cleanup() {
    TenantRlsTestSupport.deleteOwnDatasetRows(dsl, fixtureTransactionTemplate, tenant);
    TenantRlsTestSupport.deleteTenants(
        dsl, tenant); // tenant_settings·embedding_reembed_state 는 CASCADE
    TenantRlsTestSupport.deleteUser(dsl, doc.userId());
  }

  private void configure(EmbeddingSpace space) {
    TenantContext.runScoped(
        tenant,
        () ->
            configService.store(
                new EmbeddingConfig(
                    EmbeddingProviderType.OLLAMA, space.model(), "http://unused", "", 0),
                space.dimension(),
                null));
  }

  /** 늦게 끝난 적재가 옛 공간으로 쓴 청크 1건을 재현한다. */
  private void lateWriteOldVector() {
    TenantContext.runScoped(
        tenant,
        () ->
            chunks.insertBatch(
                doc.fileId(),
                doc.datasetId(),
                List.of(new Chunk(0, "late", 1)),
                List.of(axis(1024, 0)),
                OLD));
  }

  /** 잡이 한 번 완주한 뒤의 상태(DONE, 임대 해제). */
  private void markJobDone() {
    TenantContext.runScoped(
        tenant,
        () -> {
          states.tryAcquire(Duration.ofMinutes(10));
          states.markDone();
          states.release();
        });
  }

  @Test
  void doneJobWithLateOldVector_isReenqueued() {
    configure(NEW);
    markJobDone();
    lateWriteOldVector();

    scheduler.sweep();

    verify(job).enqueue(tenant);
  }

  @Test
  void noBacklog_isNotEnqueued() {
    configure(NEW);
    markJobDone();

    scheduler.sweep();

    verify(job, never()).enqueue(tenant);
  }

  @Test
  void runningWithValidLease_isNotEnqueued() {
    configure(NEW);
    lateWriteOldVector();
    TenantContext.runScoped(tenant, () -> states.tryAcquire(Duration.ofMinutes(10)));

    scheduler.sweep();

    verify(job, never()).enqueue(tenant);
  }

  @Test
  void runningWithExpiredLease_isEnqueued() {
    // 임대가 만료된 RUNNING(죽은 워커) 은 도는 잡이 아니다 — 다시 투입해야 수렴한다.
    configure(NEW);
    lateWriteOldVector();
    TenantContext.runScoped(tenant, () -> states.tryAcquire(Duration.ofSeconds(-60)));

    scheduler.sweep();

    verify(job).enqueue(tenant);
  }

  /** 잡이 결정적으로 실패한 뒤의 상태(FAILED, 임대 해제) — runInTenant 의 catch/finally 와 같은 순서. */
  private void markJobFailed() {
    TenantContext.runScoped(
        tenant,
        () -> {
          states.tryAcquire(Duration.ofMinutes(10));
          states.markFailed("OpenAI 임베딩 호출 실패: 401 Unauthorized");
          states.release();
        });
  }

  @Test
  void failedWithoutConfigChange_isNotEnqueued() {
    // 결정적 실패(잘못된 키·가드 거부)는 설정이 바뀌기 전까지 다시 돌려도 같다 — 5분마다 재투입하면 잡·401·로그가 쌓인다.
    configure(NEW);
    lateWriteOldVector();
    markJobFailed();

    scheduler.sweep();

    verify(job, never()).enqueue(tenant);
  }

  @Test
  void failedThenConfigSaved_isEnqueued() throws InterruptedException {
    configure(NEW);
    lateWriteOldVector();
    markJobFailed();
    // 설정 저장 시각(앱 시계)이 실패 기록 시각(DB now())보다 확실히 뒤가 되게 한다.
    Thread.sleep(50);
    configure(NEW); // 관리자가 키 등을 고쳐 다시 저장(공간은 같다)

    scheduler.sweep();

    verify(job).enqueue(tenant);
  }

  @Test
  void unconfiguredTenant_isSkipped() {
    lateWriteOldVector();

    scheduler.sweep();

    verify(job, never()).enqueue(tenant);
  }

  @Test
  void disabled_doesNothing() {
    configure(NEW);
    lateWriteOldVector();

    new EmbeddingBacklogSweepScheduler(
            tenantRunner, configService, backlogService, states, job, false)
        .sweep();

    verify(job, never()).enqueue(tenant);
  }
}
