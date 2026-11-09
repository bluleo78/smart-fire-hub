package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.AddColumnRequest;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TestUsers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * WD-531 — 같은 데이터셋에 필드 추가·기본 키 변경이 동시에 들어오면 Postgres 교착(deadlock)이 500 으로 새어 나가던 문제.
 *
 * <p>두 요청이 각자 데이터 테이블을 읽어(행 수 세기 — ACCESS SHARE) 쥔 채 서로의 ALTER TABLE·인덱스 재생성(ACCESS EXCLUSIVE)을 기다려
 * 교착이 났다. 서비스가 진입 직후 데이터셋 행을 잠가 같은 데이터셋의 필드 변경을 줄 세우면, 몇 번을 겹쳐 보내도 모두 성공해야 한다.
 *
 * <p>교착은 타이밍에 달려 있어 한 번 겹치는 것으로는 드러나지 않을 수 있다 — 두 요청을 장벽(CyclicBarrier)으로 같은 순간에 출발시키는 것을 여러 회
 * 되풀이한다(직렬화가 없던 코드에서는 이 반복 안에서 교착이 난다). 테스트 커넥션 풀이 2개라 동시에 2건씩 겹친다. 클래스 레벨 {@code @Transactional} 을
 * 쓰지 않는다 — 각 요청이 실제처럼 자기 트랜잭션으로 커밋해야 서로를 기다린다.
 */
class DatasetColumnConcurrentDdlTest extends IntegrationTestBase {

  private static final int ROUNDS = 10;

  @Autowired private DatasetService datasetService;
  @Autowired private DatasetColumnRepository columnRepository;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  private Long datasetId;

  @BeforeEach
  void setUp() {
    // 트랜잭션 롤백이 없어 테스트마다 사용자가 남는다 — 이름이 겹치지 않게 시각을 붙인다.
    String suffix = Long.toString(System.nanoTime());
    Long userId =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                encoder,
                "wd531_" + suffix,
                "wd531_" + suffix + "@example.com",
                "Password123",
                "WD531",
                DEFAULT_TEST_TENANT_ID)
            .id();
    // 기본 키 후보가 되도록 NOT NULL 필드 셋을 둔다(행은 없다).
    DatasetDetailResponse ds =
        inTenantFixture(
            () ->
                datasetService.createDataset(
                    new CreateDatasetRequest(
                        "WD531 race",
                        "wd531_race",
                        null,
                        null,
                        "TABLE",
                        "SOURCE",
                        List.of(
                            new DatasetColumnRequest("a", "A", "TEXT", null, false, false, null),
                            new DatasetColumnRequest("b", "B", "TEXT", null, false, false, null),
                            new DatasetColumnRequest("c", "C", "TEXT", null, false, false, null)),
                        null),
                    userId));
    datasetId = ds.id();
  }

  @AfterEach
  void tearDown() {
    if (datasetId != null) {
      inTenantFixture(() -> datasetService.deleteDataset(datasetId));
    }
  }

  /** 두 작업을 같은 순간에 출발시키고 둘 다 끝날 때까지 기다린다 — 어느 쪽이든 예외로 끝나면 그대로 드러낸다. */
  private void runTogether(Runnable left, Runnable right) throws Exception {
    CyclicBarrier start = new CyclicBarrier(2);
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (Runnable task : List.of(left, right)) {
      futures.add(
          CompletableFuture.runAsync(
              () -> {
                try {
                  start.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
                inTenantFixture(task);
              }));
    }
    for (CompletableFuture<Void> f : futures) {
      f.get(60, TimeUnit.SECONDS);
    }
  }

  private List<DatasetColumnResponse> columns() {
    return inTenantFixture(() -> columnRepository.findByDatasetId(datasetId));
  }

  @Test
  void 서로_다른_이름의_필드를_동시에_추가해도_교착_없이_모두_추가된다() throws Exception {
    for (int i = 0; i < ROUNDS; i++) {
      String left = "l" + i;
      String right = "r" + i;
      runTogether(
          () ->
              datasetService.addColumn(
                  datasetId, new AddColumnRequest(left, left, "TEXT", null, true, false, null)),
          () ->
              datasetService.addColumn(
                  datasetId, new AddColumnRequest(right, right, "TEXT", null, true, false, null)));
    }

    // 기존 3개 + 회차마다 2개
    assertThat(columns()).hasSize(3 + ROUNDS * 2);
  }

  /**
   * WD-591 — 같은 이름의 필드 추가 8건을 한꺼번에 보내면 데드락으로 7건이 500 이 되던 문제. 데이터셋 행 잠금으로 줄 서면 먼저 잠근 1건만 추가되고, 나머지는
   * 잠금을 얻은 뒤 중복 이름 확인에서 409(COLUMN_NAME_DUPLICATE)로 거부돼야 한다 — 교착·무결성 500 은 하나도 없어야 한다.
   */
  @Test
  void 같은_이름의_필드를_동시에_여러_번_추가하면_한_건만_추가되고_나머지는_409() throws Exception {
    int requests = 8;
    CyclicBarrier start = new CyclicBarrier(requests);
    // 공용 ForkJoinPool 은 다른 비동기 작업·부하에 스레드를 뺏겨 8건이 장벽에 다 모이지 못하고 시간 초과가 났다(커밋 훅에서 재현) —
    // 요청 수만큼 전용 스레드를 둬 8건이 반드시 함께 출발하게 한다.
    ExecutorService pool = Executors.newFixedThreadPool(requests);
    List<CompletableFuture<Integer>> futures = new ArrayList<>();
    for (int i = 0; i < requests; i++) {
      futures.add(
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  start.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
                // 성공은 201, 중복 거부는 예외가 실은 상태 코드로 센다 — 그 밖의 예외는 그대로 드러나 테스트가 실패한다.
                try {
                  inTenantFixture(
                      () ->
                          datasetService.addColumn(
                              datasetId,
                              new AddColumnRequest("dup", "dup", "TEXT", null, true, false, null)));
                  return 201;
                } catch (CodedApiException e) {
                  return e.status().value();
                }
              },
              pool));
    }
    List<Integer> statuses = new ArrayList<>();
    try {
      for (CompletableFuture<Integer> f : futures) {
        statuses.add(f.get(60, TimeUnit.SECONDS));
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
    assertThat(statuses).filteredOn(s -> s == 409).hasSize(requests - 1);
    assertThat(columns().stream().filter(c -> c.columnName().equals("dup"))).hasSize(1);
  }

  @Test
  void 기본_키를_동시에_바꿔도_교착_없이_마지막_요청의_집합으로_끝난다() throws Exception {
    List<DatasetColumnResponse> cols = columns();
    Long a = cols.get(0).id();
    Long b = cols.get(1).id();
    Long c = cols.get(2).id();
    for (int i = 0; i < ROUNDS; i++) {
      runTogether(
          () -> datasetService.updatePrimaryKeys(datasetId, List.of(a, b)),
          () -> datasetService.updatePrimaryKeys(datasetId, List.of(b, c)));
    }

    // 둘 중 나중에 커밋된 쪽의 집합이 남는다 — 어느 쪽이든 기본 키는 정확히 2개다.
    assertThat(columns().stream().filter(DatasetColumnResponse::isPrimaryKey)).hasSize(2);
  }

  @Test
  void 필드_추가와_기본_키_변경이_겹쳐도_교착_없이_둘_다_반영된다() throws Exception {
    List<DatasetColumnResponse> cols = columns();
    Long a = cols.get(0).id();
    Long b = cols.get(1).id();
    for (int i = 0; i < ROUNDS; i++) {
      String name = "m" + i;
      List<Long> pk = i % 2 == 0 ? List.of(a) : List.of(a, b);
      runTogether(
          () ->
              datasetService.addColumn(
                  datasetId, new AddColumnRequest(name, name, "TEXT", null, true, false, null)),
          () -> datasetService.updatePrimaryKeys(datasetId, pk));
    }

    assertThat(columns()).hasSize(3 + ROUNDS);
  }
}
