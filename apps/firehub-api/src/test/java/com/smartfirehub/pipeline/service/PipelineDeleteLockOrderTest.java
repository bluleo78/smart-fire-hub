package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.PipelineDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.UpdatePipelineRequest;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrantSync;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 회귀(WD-655·WD-666): 파이프라인 삭제·스텝 제거 저장도 잠금 순서가 한 가지다 — 데이터셋 행 → 테넌트 PYTHON 읽기 권한 동기화 잠금(advisory) →
 * 테이블. 데이터셋 삭제·임시 데이터셋 일괄 삭제·출력 맞바꿈(WD-666)이 모두 이 순서라, 파이프라인 경로가 advisory 를 먼저 잡으면 같은 임시 데이터셋에 대한
 * 동시 {@code DELETE /datasets/{id}}(행 → advisory)와 엇갈려 교착할 수 있다(분리 모드에서는 DB 가 교착을 보지 못해 영원히 멈춘다).
 *
 * <p>검증: 다른 트랜잭션이 advisory 를 쥔 동안 파이프라인 쪽 트랜잭션(자기 pid)은 advisory 대기에 서고, 그때 이미 {@code dataset} 테이블에
 * 쓰기 잠금(RowExclusiveLock — 스텝 삭제의 FK {@code ON DELETE SET NULL} UPDATE 가 잡는다)을 쥐고 있어야 한다. 행 잠금 자체는
 * pg_locks 에 행 단위로 나오지 않으므로 행을 고친 문장이 남기는 테이블 수준 RowExclusiveLock 으로 가른다. 변이: advisory 를 스텝 삭제 앞으로
 * 옮기면 (WD-655 첫 수정) 대기 중 RowExclusiveLock 이 없어 실패한다. 커밋해야 서로를 기다리므로 클래스 레벨 {@code @Transactional} 을
 * 쓰지 않는다.
 */
class PipelineDeleteLockOrderTest extends IntegrationTestBase {

  @Autowired private PipelineService pipelineService;
  @Autowired private DatasetService datasetService;
  @Autowired private TempDatasetService tempDatasetService;
  @Autowired private DSLContext dsl;
  @Autowired @DataStoreDb private DSLContext dataDsl;
  @Autowired private PasswordEncoder encoder;

  private Long userId;
  private String suffix;
  private String srcTable;
  private Long srcDatasetId;

  /** 테스트가 만든 파이프라인 — 삭제 테스트가 이미 지운 것도 있어 정리에서는 없는 id 를 건너뛴다. */
  private final List<Long> pipelineIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    // 트랜잭션 롤백이 없어 테스트마다 픽스처가 남는다 — 이름이 겹치지 않게 시각을 붙인다.
    suffix = Long.toString(System.nanoTime() % 1_000_000_000L);
    userId =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                encoder,
                "wd655p_" + suffix,
                "wd655p_" + suffix + "@example.com",
                "Password123",
                "WD655P",
                DEFAULT_TEST_TENANT_ID)
            .id();
    // 스텝 SQL 이 참조하는 원천 테이블 — 데이터셋에 매핑되지 않은 테이블은 저장 판정이 거부한다.
    srcTable = "wd655p_src_" + suffix;
    srcDatasetId =
        inTenantFixture(
                () ->
                    datasetService.createDataset(
                        new CreateDatasetRequest(
                            "wd655p src " + suffix,
                            srcTable,
                            null,
                            null,
                            "TABLE",
                            "SOURCE",
                            List.of(
                                new DatasetColumnRequest(
                                    "code", "Code", "TEXT", null, true, false, null)),
                            null),
                        userId))
            .id();
  }

  /**
   * 커밋된 픽스처(원천 데이터셋·파이프라인·사용자)를 지운다(WD-655 회귀 테스트 정리 누락 보정).
   *
   * <p>클래스 레벨 트랜잭션이 없어 픽스처가 공유 테스트 DB 에 그대로 남는다. 특히 원천 데이터셋은 임베딩 카탈로그 행(dataset_embedding)만 있고 벡터가
   * 없어 테넌트 전역 재임베딩 판정식({@code EmbeddingBacklogService.hasWork})을 참으로 만든다 — 같은 JVM 에서 뒤에 도는
   * AiVectorPurgeTest(「할 일이 없으면 저장이 재임베딩 잡을 투입하지 않는다」)가 전체 회귀에서만 실패했다. 정리 단계는 서로 독립으로 돌리고
   * (cleanupAll), 감사 로그 FK 때문에 사용자 정리는 마지막이다.
   */
  @AfterEach
  void tearDown() {
    TenantRlsTestSupport.cleanupAll(
        () ->
            pipelineIds.forEach(
                id ->
                    inTenantFixture(
                        () -> {
                          if (pipelineExists(id)) pipelineService.deletePipelineUnchecked(id);
                        })),
        () -> {
          if (srcDatasetId != null) {
            inTenantFixture(() -> datasetService.deleteDataset(srcDatasetId));
          }
        },
        () -> {
          if (userId != null) {
            TestUsers.cleanup(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID);
          }
        });
  }

  /** 파이프라인 삭제는 스텝을 지워 임시 데이터셋 행을 잡은(SET NULL) 뒤에 advisory 를 기다린다. */
  @Test
  void 파이프라인_삭제는_임시_데이터셋_행을_잡은_뒤에_테넌트_동기화_잠금을_기다린다() throws Exception {
    PipelineDetailResponse p = createPipeline("wd655p 삭제 " + suffix, List.of(sqlStep("추출")));
    Long tempId = createTemp(p, p.steps().get(0).id(), "추출");

    runWhileAdvisoryHeld(() -> pipelineService.deletePipelineUnchecked(p.id()));

    assertThat(datasetExists(tempId)).as("잠금이 풀린 뒤 임시 데이터셋도 정상 삭제").isFalse();
  }

  /** 스텝 제거 저장도 그 스텝을 지워 임시 데이터셋 행을 잡은(SET NULL) 뒤에 advisory 를 기다린다. */
  @Test
  void 스텝_제거_저장은_임시_데이터셋_행을_잡은_뒤에_테넌트_동기화_잠금을_기다린다() throws Exception {
    PipelineDetailResponse p =
        createPipeline("wd655p 저장 " + suffix, List.of(sqlStep("빼는스텝"), sqlStep("남는스텝")));
    Long removedStepId =
        p.steps().stream().filter(s -> s.name().equals("빼는스텝")).findFirst().orElseThrow().id();
    Long tempId = createTemp(p, removedStepId, "빼는스텝");

    runWhileAdvisoryHeld(
        () ->
            pipelineService.updatePipeline(
                p.id(),
                new UpdatePipelineRequest(p.name(), null, null, List.of(sqlStep("남는스텝"))),
                userId));

    assertThat(datasetExists(tempId)).as("잠금이 풀린 뒤 뺀 스텝의 임시 데이터셋도 정상 삭제").isFalse();
  }

  /**
   * 다른 트랜잭션이 테넌트 동기화 잠금을 쥔 채 {@code action} 을 별도 스레드의 테넌트 트랜잭션으로 돌리고, 그 트랜잭션이 advisory 대기에 설 때 이미
   * {@code dataset} 테이블 쓰기 잠금을 쥐었는지(행 → advisory) 본다. 잠금을 풀면 action 이 정상으로 끝나야 한다.
   */
  private void runWhileAdvisoryHeld(Runnable action) throws Exception {
    // 픽스처 커밋 뒤 걸린 동기화가 advisory 대기 행으로 섞이지 않게 끝난 뒤에 시작한다.
    awaitPythonReadGrantSync();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      AtomicReference<Future<?>> running = new AtomicReference<>();
      CompletableFuture<int[]> pids = new CompletableFuture<>();
      AtomicBoolean returned = new AtomicBoolean();
      dataDsl.transaction(
          cfg -> {
            DSLContext holder = cfg.dsl();
            PythonReadGrantSync.lockTenantSync(holder, DEFAULT_TEST_TENANT_ID);
            running.set(
                pool.submit(
                    () ->
                        TenantRlsTestSupport.runInTenantTransaction(
                            fixtureTransactionTemplate,
                            DEFAULT_TEST_TENANT_ID,
                            () -> {
                              // advisory 는 데이터 저장소 연결이, 데이터셋 행은 플랫폼 연결이 잡는다 — 각각의 pid 를 본다.
                              pids.complete(
                                  new int[] {
                                    dataDsl
                                        .fetchOne("SELECT pg_backend_pid()")
                                        .get(0, Integer.class),
                                    dsl.fetchOne("SELECT pg_backend_pid()").get(0, Integer.class)
                                  });
                              action.run();
                              returned.set(true);
                            })));
            int[] pid = pids.get(10, TimeUnit.SECONDS);
            assertThat(TenantRlsTestSupport.awaitAdvisoryWait(holder, pid[0], returned))
                .as("파이프라인 트랜잭션(pid=%d)이 테넌트 동기화 잠금을 기다려야 한다", pid[0])
                .isTrue();
            // public.dataset 은 플랫폼 DB 에만 있다 — 데이터 저장소 연결(holder)로 regclass 를 풀면 분리 모드에서
            // relation 이 없어 실패한다(WD-704). 플랫폼 pid 의 잠금이므로 플랫폼 dsl 로 본다.
            assertThat(
                    dsl.fetchOne(
                            "SELECT count(*) FROM pg_locks WHERE pid = ?"
                                + " AND relation = 'public.dataset'::regclass"
                                + " AND mode = 'RowExclusiveLock' AND granted",
                            pid[1])
                        .get(0, Integer.class))
                .as("advisory 를 기다릴 때 임시 데이터셋 행은 이미 잡혀 있다(행 → advisory 순서, WD-666)")
                .isPositive();
          });
      running.get().get(60, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
  }

  private PipelineDetailResponse createPipeline(String name, List<PipelineStepRequest> steps) {
    PipelineDetailResponse p =
        inTenantFixture(
            () ->
                pipelineService.createPipeline(
                    new CreatePipelineRequest(name, null, steps), userId));
    // 정리 대상으로 기록한다(WD-655) — 커밋되므로 테스트가 끝나도 남는다.
    pipelineIds.add(p.id());
    return p;
  }

  private PipelineStepRequest sqlStep(String name) {
    return new PipelineStepRequest(
        name, null, "SQL", "SELECT code FROM data." + srcTable, null, null, null);
  }

  /** 러너와 같은 진입점으로 스텝의 TEMP 를 만든다. */
  private Long createTemp(PipelineDetailResponse p, Long stepId, String stepName) {
    return inTenantFixture(
        () ->
            tempDatasetService.createTempDataset(
                List.of(new ColumnInfo("code", "TEXT")),
                p.id(),
                p.name(),
                stepId,
                stepName,
                userId));
  }

  private boolean pipelineExists(Long pipelineId) {
    return dsl.fetchExists(dsl.selectOne().from("pipeline").where("id = ?", pipelineId));
  }

  private boolean datasetExists(Long datasetId) {
    return inTenantFixture(
        () -> dsl.fetchExists(dsl.selectOne().from("dataset").where("id = ?", datasetId)));
  }
}
