package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.AddColumnRequest;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.dto.ReorderColumnsRequest;
import com.smartfirehub.dataset.dto.RowDataRequest;
import com.smartfirehub.dataset.exception.ColumnModificationException;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TestUsers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * WD-551·WD-566 — 같은 데이터셋에 구조 변경(필드 순서·truncate·NOT NULL 필드 추가·데이터셋 삭제)과 행 쓰기가 겹칠 때 교착(409)·무결성
 * 오류(409)·테이블 없음(500)이 새어 나가던 문제.
 *
 * <p>규칙: 구조 변경은 데이터셋 행을 배타 잠금(lockForColumnChange/lockForDelete)으로, 행 쓰기는 같은 행을 FOR SHARE 로 먼저 잡는다
 * — 행 쓰기끼리는 막지 않고 구조 변경과만 줄 선다. 잠금 순서는 항상 「데이터셋 행 → 데이터 테이블」이다.
 *
 * <p>교착·틈은 타이밍에 달려 있어 두 요청을 장벽(CyclicBarrier)으로 같은 순간에 출발시키는 것을 여러 회 되풀이한다. 테스트 커넥션 풀이 2개라 동시에 2건씩
 * 겹친다. 클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 각 요청이 실제처럼 자기 트랜잭션으로 커밋해야 서로를 기다린다
 * (DatasetColumnConcurrentDdlTest 와 같은 방식).
 */
class DatasetStructureRowWriteConcurrencyTest extends IntegrationTestBase {

  private static final int ROUNDS = 20;

  @Autowired private DatasetService datasetService;
  @Autowired private DatasetDataService datasetDataService;
  @Autowired private DatasetColumnRepository columnRepository;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  private Long userId;
  private final List<Long> createdDatasetIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    // 트랜잭션 롤백이 없어 테스트마다 사용자가 남는다 — 이름이 겹치지 않게 시각을 붙인다.
    String suffix = Long.toString(System.nanoTime());
    userId =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                encoder,
                "wd551_" + suffix,
                "wd551_" + suffix + "@example.com",
                "Password123",
                "WD551",
                DEFAULT_TEST_TENANT_ID)
            .id();
  }

  @AfterEach
  void tearDown() {
    for (Long id : createdDatasetIds) {
      try {
        inTenantFixture(() -> datasetService.deleteDataset(id));
      } catch (DatasetNotFoundException ignored) {
        // 삭제 시나리오에서 이미 지워진 데이터셋
      }
    }
  }

  /** NULL 허용 TEXT 필드 셋(a·b·c)을 가진 빈 데이터셋을 만든다. */
  private Long createDataset() {
    String table = "wd551_" + System.nanoTime();
    DatasetDetailResponse ds =
        inTenantFixture(
            () ->
                datasetService.createDataset(
                    new CreateDatasetRequest(
                        "WD551 " + table,
                        table,
                        null,
                        null,
                        "TABLE",
                        "SOURCE",
                        List.of(
                            new DatasetColumnRequest("a", "A", "TEXT", null, true, false, null),
                            new DatasetColumnRequest("b", "B", "TEXT", null, true, false, null),
                            new DatasetColumnRequest("c", "C", "TEXT", null, true, false, null)),
                        null),
                    userId));
    createdDatasetIds.add(ds.id());
    return ds.id();
  }

  /** 두 작업을 같은 순간에 출발시키고, 각 작업이 던진 예외(성공이면 null)를 순서대로 돌려준다. */
  private List<Throwable> runTogether(Runnable left, Runnable right) throws Exception {
    CyclicBarrier start = new CyclicBarrier(2);
    List<CompletableFuture<Throwable>> futures = new ArrayList<>();
    for (Runnable task : List.of(left, right)) {
      futures.add(
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  start.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
                try {
                  inTenantFixture(task);
                  return null;
                } catch (Throwable t) {
                  return t;
                }
              }));
    }
    List<Throwable> results = new ArrayList<>();
    for (CompletableFuture<Throwable> f : futures) {
      results.add(f.get(60, TimeUnit.SECONDS));
    }
    return results;
  }

  private List<DatasetColumnResponse> columns(Long datasetId) {
    return inTenantFixture(() -> columnRepository.findByDatasetId(datasetId));
  }

  private long rowCount(Long datasetId) {
    return inTenantFixture(() -> datasetDataService.getRowCount(datasetId)).rowCount();
  }

  private void addRow(Long datasetId, Map<String, Object> data) {
    datasetDataService.addRow(datasetId, new RowDataRequest(data), userId);
  }

  /** 시나리오 1: 순서 변경 동시 요청은 교착 없이 직렬화돼 모두 성공하고, 둘 중 나중 요청의 순서가 남는다. */
  @Test
  void 필드_순서를_동시에_바꿔도_교착_없이_모두_성공한다() throws Exception {
    Long datasetId = createDataset();
    List<Long> ids = columns(datasetId).stream().map(DatasetColumnResponse::id).toList();
    List<Long> forward = List.of(ids.get(0), ids.get(1), ids.get(2));
    List<Long> backward = List.of(ids.get(2), ids.get(1), ids.get(0));

    List<Throwable> failures = new ArrayList<>();
    for (int i = 0; i < ROUNDS; i++) {
      failures.addAll(
          runTogether(
              () ->
                  datasetService.reorderColumns(
                      datasetId, new ReorderColumnsRequest(forward), userId),
              () ->
                  datasetService.reorderColumns(
                      datasetId, new ReorderColumnsRequest(backward), userId)));
    }

    assertThat(failures).allMatch(t -> t == null);
    List<Long> finalOrder = columns(datasetId).stream().map(DatasetColumnResponse::id).toList();
    assertThat(finalOrder).isIn(forward, backward);
  }

  /** 시나리오 2: truncate 끼리, truncate 와 행 추가가 겹쳐도 교착 없이 모두 성공한다. */
  @Test
  void truncate_와_행_추가가_겹쳐도_교착_없이_모두_성공한다() throws Exception {
    Long datasetId = createDataset();
    // 비어 있으면 truncate 가 금방 끝나 겹침이 줄어든다 — 미리 행을 채워 둔다.
    inTenantFixture(() -> addRow(datasetId, Map.of("a", "seed")));

    List<Throwable> failures = new ArrayList<>();
    for (int i = 0; i < ROUNDS; i++) {
      String value = "v" + i;
      failures.addAll(
          runTogether(
              () -> datasetDataService.truncateDatasetData(datasetId),
              () -> datasetDataService.truncateDatasetData(datasetId)));
      failures.addAll(
          runTogether(
              () -> datasetDataService.truncateDatasetData(datasetId),
              () -> addRow(datasetId, Map.of("a", value))));
    }

    assertThat(failures).allMatch(t -> t == null);
  }

  /**
   * 시나리오 3: 행 추가와 NOT NULL 필드 추가가 겹치면, 필드 추가는 성공하거나 「데이터가 있는 데이터셋에는 NULL 허용을 끈 필드…」 로 거절된다 — 행 수 검사
   * 뒤에 행이 끼어들어 ALTER 가 무결성 오류로 깨지면 안 된다.
   */
  @Test
  void 행_추가와_NOT_NULL_필드_추가가_겹쳐도_무결성_오류가_나지_않는다() throws Exception {
    Long datasetId = createDataset();

    List<Throwable> columnFailures = new ArrayList<>();
    List<Throwable> rowFailures = new ArrayList<>();
    for (int i = 0; i < ROUNDS; i++) {
      String name = "n" + i;
      List<Throwable> round =
          runTogether(
              () ->
                  datasetService.addColumn(
                      datasetId,
                      new AddColumnRequest(name, name, "TEXT", null, false, false, null),
                      userId),
              // 새 필드 값도 함께 보내 필드 추가가 먼저 끝나도 행 추가가 필수값 검증에 걸리지 않게 한다.
              () -> addRow(datasetId, Map.of("a", "x", name, "y")));
      columnFailures.add(round.get(0));
      rowFailures.add(round.get(1));

      // 다음 회차를 빈 데이터셋에서 시작한다 — 추가된 NOT NULL 필드는 지우고 행은 비운다.
      columns(datasetId).stream()
          .filter(c -> c.columnName().equals(name))
          .findFirst()
          .ifPresent(c -> inTenantFixture(() -> datasetService.deleteColumn(datasetId, c.id())));
      inTenantFixture(() -> datasetDataService.truncateDatasetData(datasetId));
    }

    assertThat(rowFailures).allMatch(t -> t == null);
    assertThat(columnFailures)
        .allMatch(
            t ->
                t == null
                    || (t instanceof ColumnModificationException
                        && t.getMessage().startsWith("데이터가 있는 데이터셋에는 NULL 허용을 끈 필드")),
            "성공 또는 NOT NULL 거절(400)만 허용");
  }

  /** 시나리오 4: 데이터셋 삭제와 행 추가가 겹치면 행 추가는 성공하거나 404(DatasetNotFound) 여야 한다 — 테이블 없음(500)이 아니다. */
  @Test
  void 데이터셋_삭제와_행_추가가_겹치면_행_추가는_404_로_끝난다() throws Exception {
    List<Throwable> rowFailures = new ArrayList<>();
    for (int i = 0; i < ROUNDS; i++) {
      Long datasetId = createDataset();
      List<Throwable> round =
          runTogether(
              () -> datasetService.deleteDataset(datasetId),
              () -> addRow(datasetId, Map.of("a", "x")));
      assertThat(round.get(0)).as("삭제는 성공").isNull();
      rowFailures.add(round.get(1));
    }

    assertThat(rowFailures)
        .allMatch(t -> t == null || t instanceof DatasetNotFoundException, "성공 또는 404 만 허용");
  }
}
