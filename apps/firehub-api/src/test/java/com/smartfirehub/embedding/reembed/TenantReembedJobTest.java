package com.smartfirehub.embedding.reembed;

import static com.smartfirehub.support.EmbeddingTestFixtures.axis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.search.DatasetEmbeddingRepository;
import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingException;
import com.smartfirehub.embedding.EmbeddingNotConfiguredException;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.config.EmbeddingConfig;
import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.embedding.config.EmbeddingProviderType;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.support.EmbeddingTestFixtures;
import com.smartfirehub.support.EmbeddingTestFixtures.DocFixture;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * TenantReembedJob: 1024→1536 완주·옛 차원 정리, 재실행 멱등, 도중 설정 변경 시 중단, 임대 대기·만료, 실패 기록.
 *
 * <p>잡 메서드는 JobRunr 없이 직접 부른다(test 프로필은 background-job-server 가 꺼져 있다). provider 는 가짜로 바꾸되 설정 문서는
 * 실제 tenant_settings 에 저장한다 — 잡이 배치마다 읽는 "현재 공간"이 진짜 저장소에서 와야 도중 변경 감지가 검증된다.
 */
class TenantReembedJobTest extends IntegrationTestBase {

  private static final EmbeddingSpace OLD = new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3");
  private static final EmbeddingSpace NEW = new EmbeddingSpace(EmbeddingDimension.D1536, "m2");

  @Autowired private TenantReembedJob job;
  @Autowired private EmbeddingConfigService configService;
  @Autowired private EmbeddingReembedStateRepository states;
  @Autowired private DocumentChunkRepository chunks;
  @Autowired private DatasetEmbeddingRepository datasets;
  @Autowired private DSLContext dsl;
  @MockitoBean private EmbeddingProviderFactory providerFactory;
  @MockitoBean private JobScheduler jobScheduler;

  private long tenant;
  private DocFixture doc;
  private final AtomicInteger embedCalls = new AtomicInteger();
  private final List<String> embeddedTexts = new CopyOnWriteArrayList<>();
  private Runnable onFirstEmbed = () -> {};

  @BeforeEach
  void seed() {
    tenant = TenantRlsTestSupport.createActiveTenant(dsl, "reembed");
    doc =
        inTenantFixture(tenant, () -> EmbeddingTestFixtures.createDocumentDataset(dsl, "reembed"));
    storeConfig(NEW);
    when(providerFactory.current()).thenAnswer(inv -> fake(NEW));
  }

  @AfterEach
  void cleanup() {
    TenantRlsTestSupport.deleteOwnDatasetRows(dsl, fixtureTransactionTemplate, tenant);
    TenantRlsTestSupport.deleteTenants(
        dsl, tenant); // tenant_settings·embedding_reembed_state 는 CASCADE
    TenantRlsTestSupport.deleteUser(dsl, doc.userId());
  }

  private void storeConfig(EmbeddingSpace space) {
    TenantContext.runScoped(
        tenant,
        () ->
            configService.store(
                new EmbeddingConfig(
                    EmbeddingProviderType.OLLAMA, space.model(), "http://unused", "", 0),
                space.dimension(),
                ProviderHosting.EXTERNAL,
                null));
  }

  private EmbeddingProvider fake(EmbeddingSpace space) {
    return new EmbeddingProvider() {
      public List<float[]> embed(List<String> texts) {
        if (embedCalls.getAndIncrement() == 0) onFirstEmbed.run();
        embeddedTexts.addAll(texts);
        return texts.stream().map(t -> axis(space.dimension().size(), 0)).toList();
      }

      public String modelId() {
        return space.model();
      }

      public int dimension() {
        return space.dimension().size();
      }
    };
  }

  /** {@code n} 개 청크를 OLD 공간 벡터와 함께 넣는다. */
  private void seedChunks(int n) {
    List<Chunk> cs = new ArrayList<>();
    List<float[]> vs = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      cs.add(new Chunk(i, "c" + i, 1));
      vs.add(axis(1024, 0));
    }
    TenantContext.runScoped(
        tenant, () -> chunks.insertBatch(doc.fileId(), doc.datasetId(), cs, vs, OLD));
  }

  private <T> T inTenant(java.util.function.Supplier<T> s) {
    return TenantContext.runScopedGet(tenant, s);
  }

  @Test
  void reembedsFrom1024To1536AndCleansOldDimension() {
    seedChunks(3);
    TenantContext.runScoped(
        tenant,
        () -> {
          datasets.upsertSourceText(doc.datasetId(), "화재 카탈로그");
          datasets.upsertEmbedding(OLD, doc.datasetId(), axis(1024, 0));
        });

    job.run(tenant);

    assertThat(inTenant(() -> chunks.countEmbedded(NEW))).isEqualTo(3);
    assertThat(inTenant(() -> chunks.countEmbedded(OLD))).isZero(); // 완료 시 옛 차원 정리
    assertThat(inTenant(() -> datasets.countEmbedded(NEW))).isEqualTo(1);
    assertThat(inTenant(() -> datasets.countEmbedded(OLD))).isZero();
    assertThat(inTenant(() -> states.find()).orElseThrow().status()).isEqualTo("DONE");
  }

  @Test
  void completionCleansStrayOldDimensionRowsTheWritePathLeft() {
    // 쓰기 경로(upsertEmbeddings)는 옮길 때 옛 차원 행을 지우므로 위 테스트만으로는 완료 시 정리가 증명되지 않는다.
    // 이미 NEW 벡터가 있어 잡이 건드리지 않는 청크에 옛 차원 행을 직접 남겨, 완료 단계의 정리만이 지울 수 있게 한다.
    seedChunks(1);
    List<Long> ids =
        inTenantFixture(
            tenant, () -> dsl.fetch("SELECT id FROM document_chunk").getValues(0, Long.class));
    TenantContext.runScoped(
        tenant, () -> chunks.upsertEmbeddings(NEW, ids, List.of(axis(1536, 0))));
    inTenantFixture(
        tenant,
        () -> {
          dsl.execute(
              "INSERT INTO document_chunk_vec_1024 (chunk_id, tenant_id, dataset_id, embedding, embedding_model)"
                  + " SELECT id, tenant_id, dataset_id, array_fill(0.1::real, ARRAY[1024])::vector, 'bge-m3'"
                  + " FROM document_chunk WHERE id = ?",
              ids.get(0));
        });
    assertThat(inTenant(() -> chunks.countEmbedded(OLD))).isEqualTo(1);

    job.run(tenant);

    assertThat(embeddedTexts).isEmpty(); // 판정식상 할 일은 없다
    assertThat(inTenant(() -> chunks.countEmbedded(OLD))).isZero(); // 완료 단계 정리가 지웠다
    assertThat(inTenant(() -> chunks.countEmbedded(NEW))).isEqualTo(1);
  }

  @Test
  void rerunIsIdempotentAndResumesWhereItStopped() {
    seedChunks(3);
    List<Long> firstId =
        inTenantFixture(
            tenant, () -> dsl.fetch("SELECT min(id) FROM document_chunk").getValues(0, Long.class));
    TenantContext.runScoped(
        tenant, () -> chunks.upsertEmbeddings(NEW, firstId, List.of(axis(1536, 0))));

    job.run(tenant);
    assertThat(embeddedTexts).hasSize(2); // 이미 NEW 로 있던 1건은 다시 임베딩하지 않는다

    embeddedTexts.clear();
    job.run(tenant);
    assertThat(embeddedTexts).isEmpty(); // 할 일이 없으면 외부 호출 0
    assertThat(inTenant(() -> chunks.countEmbedded(NEW))).isEqualTo(3);
  }

  @Test
  void stopsWhenConfigChangesMidway() {
    seedChunks(TenantReembedJob.BATCH + 6); // 두 배치
    // 첫 배치 임베딩 도중 관리자가 모델을 또 바꿨다.
    onFirstEmbed = () -> storeConfig(new EmbeddingSpace(EmbeddingDimension.D1536, "m3"));

    job.run(tenant);

    assertThat(inTenant(() -> chunks.countEmbedded(NEW))).isEqualTo(TenantReembedJob.BATCH);
    assertThat(inTenant(() -> states.find()).orElseThrow().status()).isEqualTo("SUPERSEDED");
    // 임대는 풀려 있어 새 설정의 잡이 곧바로 잡을 수 있다.
    assertThat(inTenant(() -> states.tryAcquire(Duration.ofMinutes(1)))).isTrue();
  }

  @Test
  void revertMidwayReenqueuesSoRevertedSpaceIsRestored() {
    // 리뷰 지적(A→B→A): OLD 로 전부 임베딩된 테넌트에서 NEW 저장 → 잡 NEW 의 첫 배치 임베딩 도중 OLD 로 되돌림.
    // 되돌린 저장 시점엔 hasWork(OLD)=false 라 저장 경로는 잡을 투입하지 않는다. 그런데 잡이 그 배치를 NEW 로
    // 옮기면(OLD 벡터 삭제) 대기 잡 없이 OLD 벡터가 빠진 채 남는다 — 잡이 스스로 다시 투입해야 한다.
    seedChunks(3); // 전부 OLD
    storeConfig(OLD);
    storeConfig(NEW); // 관리자가 NEW 로 저장(이 테스트에선 잡을 직접 부른다)
    onFirstEmbed = () -> storeConfig(OLD);

    job.run(tenant);

    assertThat(inTenant(() -> chunks.countEmbedded(NEW))).isEqualTo(3); // 첫 배치는 NEW 로 옮겨졌다
    assertThat(inTenant(() -> chunks.countEmbedded(OLD))).isZero();
    assertThat(inTenant(() -> states.find()).orElseThrow().status()).isEqualTo("SUPERSEDED");
    verify(jobScheduler).enqueue(any(JobLambda.class)); // 이어받을 잡을 스스로 투입했다

    // 투입된 잡(= 같은 run) 을 되돌린 설정의 provider 로 실행하면 OLD 벡터가 복구된다.
    when(providerFactory.current()).thenAnswer(inv -> fake(OLD));
    job.run(tenant);
    assertThat(inTenant(() -> chunks.countEmbedded(OLD))).isEqualTo(3);
    assertThat(inTenant(() -> chunks.countEmbedded(NEW))).isZero();
    assertThat(inTenant(() -> states.find()).orElseThrow().status()).isEqualTo("DONE");
  }

  @Test
  void completedRunDoesNotReenqueue() {
    seedChunks(1);
    job.run(tenant);
    assertThat(inTenant(() -> states.find()).orElseThrow().status()).isEqualTo("DONE");
    verify(jobScheduler, never()).enqueue(any(JobLambda.class)); // 무한 재투입 방지
  }

  @Test
  void busyJobDefersThenCompletesAfterRelease() {
    // Review Focus: 옛 잡이 임대를 쥔 동안 새 잡은 포기하지 않고(예외 → JobRunr 재시도) 풀린 뒤 끝까지 처리한다.
    seedChunks(2);
    assertThat(inTenant(() -> states.tryAcquire(Duration.ofMinutes(10)))).isTrue();

    assertThatThrownBy(() -> job.run(tenant)).isInstanceOf(ReembedBusyException.class);
    assertThat(embeddedTexts).isEmpty();

    TenantContext.runScoped(tenant, states::release);
    job.run(tenant);
    assertThat(inTenant(() -> chunks.countEmbedded(NEW))).isEqualTo(2);
    assertThat(inTenant(() -> states.find()).orElseThrow().status()).isEqualTo("DONE");
  }

  @Test
  void expiredLeaseCanBeAcquired() {
    assertThat(inTenant(() -> states.tryAcquire(Duration.ofMinutes(10)))).isTrue();
    assertThat(inTenant(() -> states.tryAcquire(Duration.ofMinutes(10)))).isFalse();
    inTenantFixture(
        tenant,
        () -> {
          dsl.execute(
              "UPDATE embedding_reembed_state SET lease_until = now() - interval '1 minute'");
        });
    assertThat(inTenant(() -> states.tryAcquire(Duration.ofMinutes(10)))).isTrue();
  }

  @Test
  void providerFailureIsRecordedReleasedAndRethrownForRetry() {
    seedChunks(1);
    when(providerFactory.current())
        .thenAnswer(
            inv ->
                new EmbeddingProvider() {
                  public List<float[]> embed(List<String> texts) {
                    throw new EmbeddingException("Ollama 임베딩 호출 실패: down");
                  }

                  public String modelId() {
                    return NEW.model();
                  }

                  public int dimension() {
                    return NEW.dimension().size();
                  }
                });

    assertThatThrownBy(() -> job.run(tenant)).isInstanceOf(EmbeddingException.class);

    ReembedState s = inTenant(() -> states.find()).orElseThrow();
    assertThat(s.status()).isEqualTo("FAILED");
    assertThat(s.lastError()).contains("down");
    assertThat(inTenant(() -> states.tryAcquire(Duration.ofMinutes(1)))).isTrue(); // 임대 해제됨
  }

  @Test
  void notConfiguredIsRecordedAsFailedWithoutRetry() {
    when(providerFactory.current()).thenThrow(new EmbeddingNotConfiguredException());
    job.run(tenant); // 재시도해도 같으므로 삼킨다
    ReembedState s = inTenant(() -> states.find()).orElseThrow();
    assertThat(s.status()).isEqualTo("FAILED");
    assertThat(s.lastError()).isEqualTo(EmbeddingNotConfiguredException.MESSAGE);
  }

  @Test
  void reindexAllSyncsSourceTextReturnsImpactAndEnqueues() {
    seedChunks(2);
    TenantContext.set(tenant);
    var impact = job.requestReindexAll();
    assertThat(impact.chunks()).isEqualTo(2);
    assertThat(impact.datasets()).isEqualTo(1); // syncAllSourceText 가 dataset_embedding 행을 만들었다
    verify(jobScheduler).enqueue(any(JobLambda.class));
  }
}
