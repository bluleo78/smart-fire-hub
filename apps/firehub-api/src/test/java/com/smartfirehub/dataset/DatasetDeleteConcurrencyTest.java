package com.smartfirehub.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.service.TempDatasetService;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrantSync;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * WD-583 — 같은 데이터셋에 삭제 요청이 동시에 여러 번 들어오면 모두 204 로 끝나고 감사 로그 DELETE 가 요청 수만큼 남던 문제.
 *
 * <p>예전 {@code deleteDataset} 은 잠금 없이 findById 로 존재만 확인하고 진행해, 앞 요청이 커밋되기 전에 들어온 요청도 행을 본 채 색인
 * 삭제·DROP·감사를 되풀이했다. 서비스가 진입 직후 데이터셋 행을 {@code FOR UPDATE} 로 잠그면 뒤 요청은 앞 요청 커밋을 기다렸다가 행이 없어 {@link
 * DatasetNotFoundException}(404)으로 끝나야 한다 — 성공 1, 404 나머지, 감사 1건.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 각 삭제가 실제처럼 자기 트랜잭션으로 커밋해야 서로를 기다린다. 테스트 커넥션 풀이 2개라
 * 동시에 2건씩 겹치므로, 경합이 한 번에 드러나지 않을 수 있어 여러 데이터셋으로 회차를 되풀이한다.
 */
class DatasetDeleteConcurrencyTest extends IntegrationTestBase {

  /** 한 데이터셋에 동시에 보내는 삭제 요청 수(이슈 재현과 같은 6). */
  private static final int CONCURRENT_DELETES = 6;

  /** 경합 재현 회차 — 회차마다 새 데이터셋을 만든다. */
  private static final int ROUNDS = 3;

  @Autowired private DatasetService datasetService;
  @Autowired private TempDatasetService tempDatasetService;
  @Autowired private DSLContext dsl;
  @Autowired @DataStoreDb private DSLContext dataDsl;
  @Autowired private PasswordEncoder encoder;

  private Long userId;

  @BeforeEach
  void setUp() {
    // 트랜잭션 롤백이 없어 테스트마다 사용자가 남는다 — 이름이 겹치지 않게 시각을 붙인다.
    String suffix = Long.toString(System.nanoTime());
    userId =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                encoder,
                "wd583_" + suffix,
                "wd583_" + suffix + "@example.com",
                "Password123",
                "WD583",
                DEFAULT_TEST_TENANT_ID)
            .id();
  }

  /**
   * 테이블 이름용 고유 접미사(WD-675) — 예전 {@code nanoTime % 100000} 은 실행·회차가 겹치면 같은 이름이 나올 수 있었다. 테이블 이름
   * 규칙({@code [a-z][a-z0-9_]*})에 맞게 UUID 의 16진 12자를 쓴다.
   */
  private static String uniqueSuffix() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  /** 테이블형 데이터셋 하나를 만든다(색인·DROP 경로까지 타도록). */
  private DatasetDetailResponse createDataset(String tableName) {
    return inTenantFixture(
        () ->
            datasetService.createDataset(
                new CreateDatasetRequest(
                    "WD583 " + tableName,
                    tableName,
                    null,
                    null,
                    "TABLE",
                    "SOURCE",
                    List.of(new DatasetColumnRequest("a", "A", "TEXT", null, true, false, null)),
                    null),
                userId));
  }

  /**
   * 요청 스레드처럼 테넌트 컨텍스트와 인증(principal = Long userId — 감사 로그가 남는 조건)을 세운 뒤 삭제를 부른다. 검증 대상 호출이므로 픽스처
   * 트랜잭션({@code inTenantFixture}) 밖에서 서비스가 스스로 트랜잭션을 연다.
   */
  private void deleteAsUser(Long datasetId) {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    try {
      datasetService.deleteDataset(datasetId);
    } finally {
      SecurityContextHolder.clearContext();
      TenantContext.clear();
    }
  }

  /** 이 데이터셋의 DELETE 감사 행 수. */
  private long deleteAuditCount(Long datasetId) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT count(*) FROM audit_log WHERE resource = 'dataset'"
                        + " AND action_type = 'DELETE' AND resource_id = ?",
                    String.valueOf(datasetId))
                .get(0, Long.class));
  }

  /** data 저장소에 물리 테이블이 남아 있는지. */
  private long physicalTableCount(String tableName) {
    return dataDsl
        .selectCount()
        .from("information_schema.tables")
        .where("table_schema = 'data' AND table_name = ?", tableName)
        .fetchOne(0, Long.class);
  }

  @Test
  void 같은_데이터셋을_동시에_여러_번_삭제하면_한_번만_성공하고_나머지는_404_감사도_한_건이다() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      String tableName = "wd583_race_" + round + "_" + uniqueSuffix();
      Long datasetId = createDataset(tableName).id();

      // 모든 요청을 장벽으로 같은 순간에 출발시키고, 결과(성공 또는 예외)를 모은다.
      CyclicBarrier start = new CyclicBarrier(CONCURRENT_DELETES);
      List<CompletableFuture<Throwable>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_DELETES; i++) {
        futures.add(
            CompletableFuture.supplyAsync(
                () -> {
                  try {
                    start.await(10, TimeUnit.SECONDS);
                    deleteAsUser(datasetId);
                    return null;
                  } catch (Throwable t) {
                    return t;
                  }
                }));
      }
      int success = 0;
      int notFound = 0;
      List<Throwable> others = new ArrayList<>();
      for (CompletableFuture<Throwable> f : futures) {
        Throwable t = f.get(120, TimeUnit.SECONDS);
        if (t == null) {
          success++;
        } else if (t instanceof DatasetNotFoundException) {
          notFound++;
        } else {
          others.add(t);
        }
      }

      assertThat(others).as("round %d: 404 외 예외", round).isEmpty();
      assertThat(success).as("round %d: 성공 수", round).isEqualTo(1);
      assertThat(notFound).as("round %d: 404 수", round).isEqualTo(CONCURRENT_DELETES - 1);
      assertThat(deleteAuditCount(datasetId)).as("round %d: 감사 DELETE 행 수", round).isEqualTo(1);
      assertThat(physicalTableCount(tableName)).as("round %d: 물리 테이블", round).isZero();
    }
  }

  @Test
  void 이미_삭제한_데이터셋을_다시_삭제하면_404_이고_감사는_늘지_않는다() {
    String tableName = "wd583_seq_" + uniqueSuffix();
    Long datasetId = createDataset(tableName).id();

    deleteAsUser(datasetId);
    assertThatThrownBy(() -> deleteAsUser(datasetId)).isInstanceOf(DatasetNotFoundException.class);

    assertThat(deleteAuditCount(datasetId)).isEqualTo(1);
  }

  /**
   * 회귀(WD-655·WD-666): 잠금 순서는 한 가지 — 데이터셋 행(FOR UPDATE) → 테넌트 PYTHON 읽기 권한 동기화 잠금(advisory) → 테이블.
   * 임시 데이터셋 일괄 삭제·출력 맞바꿈(WD-666)도 이 순서다. 다른 트랜잭션이 advisory 를 쥔 동안 삭제 트랜잭션(자기 pid)은 advisory 대기에 서고,
   * 그때 이미 행 잠금(= 트랜잭션 ID 할당)을 쥐고 있어야 한다. 풀리면 정상으로 지운다. 행 잠금은 pg_locks 에 행 단위로 나오지 않으므로, FOR UPDATE
   * 가 트랜잭션 ID 를 받으면 생기는 transactionid 잠금으로 가른다. 변이: lockTenantGrantSyncForDdl 을 lockForDelete 앞으로
   * 옮기면(WD-655 첫 수정) 대기 중 transactionid 잠금이 없어 실패.
   *
   * <p>pid 는 두 개를 본다(WD-704) — advisory 는 데이터 저장소 연결이, 데이터셋 행 잠금은 플랫폼 연결이 잡는다. 분리 모드에서는 두 연결이 다른 PG
   * 서버에 있으므로 transactionid 잠금은 플랫폼 pid 로 플랫폼 DB({@code dsl})의 pg_locks 에서 센다. 기본 모드에서는 두 pid 가 같다.
   */
  @Test
  void 삭제는_데이터셋_행_잠금을_테넌트_동기화_잠금보다_먼저_잡는다() throws Exception {
    String tableName = "wd655_order_" + uniqueSuffix();
    Long datasetId = createDataset(tableName).id();
    // 생성 커밋 뒤 걸린 동기화가 advisory 대기 행으로 섞이지 않게 끝난 뒤에 시작한다.
    awaitPythonReadGrantSync();

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      AtomicReference<Future<?>> running = new AtomicReference<>();
      CompletableFuture<int[]> actionPids = new CompletableFuture<>();
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
                              // [0] = 데이터 저장소 연결(advisory 대기), [1] = 플랫폼 연결(데이터셋 행 잠금)
                              actionPids.complete(
                                  new int[] {
                                    dataDsl
                                        .fetchOne("SELECT pg_backend_pid()")
                                        .get(0, Integer.class),
                                    dsl.fetchOne("SELECT pg_backend_pid()").get(0, Integer.class)
                                  });
                              datasetService.deleteDataset(datasetId);
                              returned.set(true);
                            })));
            int[] pid = actionPids.get(10, TimeUnit.SECONDS);
            assertThat(TenantRlsTestSupport.awaitAdvisoryWait(holder, pid[0], returned))
                .as("삭제 트랜잭션(pid=%d)이 테넌트 동기화 잠금을 기다려야 한다", pid[0])
                .isTrue();
            // 행 잠금(transactionid)은 플랫폼 DB 에 있다 — 데이터 저장소 연결(holder)이 아니라 플랫폼 dsl 로 본다.
            assertThat(
                    dsl.fetchOne(
                            "SELECT count(*) FROM pg_locks WHERE pid = ?"
                                + " AND locktype = 'transactionid' AND granted",
                            pid[1])
                        .get(0, Integer.class))
                .as("advisory 를 기다릴 때 데이터셋 행은 이미 FOR UPDATE 로 잡혀 있다(행 → advisory 순서)")
                .isPositive();
          });
      running.get().get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    assertThat(physicalTableCount(tableName)).as("잠금이 풀린 뒤 정상 삭제").isZero();
  }

  /**
   * 회귀(WD-655): 없는 데이터셋 삭제는 테넌트 동기화 잠금을 잡지 않고 곧바로 404 다 — 행 잠금에서 없으면 끝낸다. advisory 를 먼저 잡으면 동기화가
   * 잠금을 쥔 동안 없는 id 삭제까지 그 뒤에 줄을 선다.
   */
  @Test
  void 없는_데이터셋_삭제는_테넌트_동기화_잠금을_기다리지_않고_404_다() throws Exception {
    awaitPythonReadGrantSync();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      dataDsl.transaction(
          cfg -> {
            PythonReadGrantSync.lockTenantSync(cfg.dsl(), DEFAULT_TEST_TENANT_ID);
            Future<?> f = pool.submit(() -> deleteAsUser(Long.MAX_VALUE));
            assertThatThrownBy(() -> f.get(10, TimeUnit.SECONDS))
                .as("advisory 를 쥔 동안에도 막히지 않고 404 로 끝난다")
                .hasCauseInstanceOf(DatasetNotFoundException.class);
          });
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * 회귀(WD-675): 테이블 없는 데이터셋(DOCUMENT)만 일괄 삭제하면 테넌트 동기화 잠금을 잡지 않는다 — 그 잠금은 기존 테이블 DROP 앞에서만 필요하다. 예전
   * 일괄 삭제({@code TempDatasetService.deleteTempDatasets})는 행을 잠근 뒤 무조건 동기화 잠금을 잡아, 다른 트랜잭션이 그 잠금을 쥔
   * 동안 문서 데이터셋만 지우는 삭제도 동기화가 끝날 때까지 줄을 섰다. 변이: 일괄 삭제가 다시 무조건 동기화 잠금을 잡으면 기한 안에 끝나지 않아 실패.
   */
  @Test
  void 문서_데이터셋만_일괄_삭제하면_테넌트_동기화_잠금을_기다리지_않는다() throws Exception {
    Long docA = createDocumentDataset("wd675_doc_a_" + uniqueSuffix());
    Long docB = createDocumentDataset("wd675_doc_b_" + uniqueSuffix());
    awaitPythonReadGrantSync();

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      dataDsl.transaction(
          cfg -> {
            PythonReadGrantSync.lockTenantSync(cfg.dsl(), DEFAULT_TEST_TENANT_ID);
            Future<?> f =
                pool.submit(
                    () ->
                        TenantRlsTestSupport.runInTenantTransaction(
                            fixtureTransactionTemplate,
                            DEFAULT_TEST_TENANT_ID,
                            () -> tempDatasetService.deleteTempDatasets(List.of(docB, docA))));
            // 동기화 잠금을 쥔 채로 기다린다 — 일괄 삭제가 그 잠금을 잡으려 하면 여기서 기한을 넘긴다
            f.get(10, TimeUnit.SECONDS);
          });
    } finally {
      pool.shutdownNow();
    }
    assertThat(datasetExists(docA)).as("문서 데이터셋 A 삭제").isFalse();
    assertThat(datasetExists(docB)).as("문서 데이터셋 B 삭제").isFalse();
  }

  /** 테이블 없는 문서 데이터셋 하나를 만든다(WD-675 — 동기화 잠금이 필요 없는 삭제 대상). */
  private Long createDocumentDataset(String tableName) {
    return inTenantFixture(
            () ->
                datasetService.createDataset(
                    new CreateDatasetRequest(
                        "WD675 " + tableName,
                        tableName,
                        null,
                        null,
                        "DOCUMENT",
                        "SOURCE",
                        List.of(),
                        null),
                    userId))
        .id();
  }

  /** 데이터셋 행이 남아 있는지. */
  private boolean datasetExists(Long datasetId) {
    return inTenantFixture(() -> TenantRlsTestSupport.rowExists(dsl, "dataset", "id", datasetId));
  }
}
