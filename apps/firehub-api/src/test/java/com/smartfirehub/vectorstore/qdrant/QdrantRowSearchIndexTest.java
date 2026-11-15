package com.smartfirehub.vectorstore.qdrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.rowsearch.IndexRef;
import com.smartfirehub.dataset.rowsearch.IndexedRow;
import com.smartfirehub.dataset.rowsearch.ReuseCandidate;
import com.smartfirehub.dataset.rowsearch.RowFilter;
import com.smartfirehub.dataset.rowsearch.RowHit;
import com.smartfirehub.dataset.rowsearch.RowQueryFilter;
import com.smartfirehub.dataset.rowsearch.SearchSourceReader;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.vectorstore.repository.jooq.JooqVectorOutboxRepository;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QueryFactory;
import io.qdrant.client.grpc.Points.QueryPoints;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Qdrant 행 검색 색인(WD-722) — PG 데이터 저장소 테넌트 × Qdrant. 완료 기준의 SEMANTIC·KEYWORD·필터 2단계·swap 재구축과, 테넌트
 * 컬렉션 토큰이 다른 테넌트 컬렉션을 서버에서 거부하는지 본다. 하이브리드 융합은 서비스(RankFusion)가 하므로 두 목록이 각각 맞으면 된다.
 *
 * <p>벡터는 축 방향 단위벡터라 코사인 순위가 결정적이다.
 */
class QdrantRowSearchIndexTest extends IntegrationTestBase {

  private static final long DS = 990_722L;
  private static final String SRC = "rs_qdrant_src";
  private static final int DIM = 8;

  private static QdrantProperties props;
  private static QdrantClient client;

  @Autowired private DataTableService dataTableService;
  @Autowired private SearchSourceReader reader;
  @Autowired private JooqVectorOutboxRepository outbox;
  @Autowired @DataStoreDb private DSLContext dataDsl;

  private QdrantRowClients rowClients;
  private QdrantVectorStoreLifecycle lifecycle;
  private QdrantRowSearchIndex index;
  private final AtomicLong now = new AtomicLong(1_000_000L);

  private final IndexRef gen1 = new IndexRef(DEFAULT_TEST_TENANT_ID, DS, SRC, 1L);
  private final IndexRef gen2 = new IndexRef(DEFAULT_TEST_TENANT_ID, DS, SRC, 2L);

  @BeforeAll
  static void startClient() {
    props = QdrantTestContainer.properties(2, true);
    client = QdrantClientConfig.create(props);
  }

  @AfterAll
  static void closeClient() {
    client.close();
  }

  static float[] axis(int axis) {
    float[] v = new float[DIM];
    v[axis] = 1f;
    return v;
  }

  @BeforeEach
  void setUp() throws Exception {
    QdrantTestContainer.deleteAllCollections(client);
    lifecycle = new QdrantVectorStoreLifecycle(client, props);
    lifecycle.prepare();
    rowClients = new QdrantRowClients(props);
    index = newIndex(3);
    inTenantFixture(
        () -> {
          dataDsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(SRC));
          dataTableService.createTable(
              SRC,
              List.of(
                  new DatasetColumnRequest("content", "내용", "TEXT", null, true, false, null),
                  new DatasetColumnRequest("status", "상태", "VARCHAR", 20, true, false, null)));
          dataDsl.execute(
              "INSERT INTO "
                  + DataSchema.qualify(SRC)
                  + " (content, status) VALUES ('배관 누수 발생', '미처리'), ('소음 민원', '완료'),"
                  + " ('누수 재발', '완료'), ('정전 신고', '완료'), ('누수 점검', '완료')");
        });
    index.recreate(gen1, DIM);
    index.upsert(
        gen1,
        List.of(
            new IndexedRow(1, "배관 누수 발생", "h1", axis(0), "fake"),
            new IndexedRow(2, "소음 민원", "h2", axis(1), "fake"),
            new IndexedRow(3, "누수 재발", "h3", axis(2), "fake"),
            new IndexedRow(4, "정전 신고", "h4", axis(3), "fake"),
            new IndexedRow(5, "누수 점검", "h5", axis(4), "fake")));
  }

  @AfterEach
  void tearDown() throws Exception {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    inTenantFixture(() -> dataTableService.dropTable(SRC));
    rowClients.destroy();
  }

  /** filterIdCap 만 바꾼 색인 — 필터 2단계의 사후 확인 경로를 적은 행으로 재현한다. */
  private QdrantRowSearchIndex newIndex(int filterIdCap) {
    return new QdrantRowSearchIndex(
        rowClients, lifecycle, props, outbox, reader, 100_000, 60_000, now::get, filterIdCap);
  }

  private static RowQueryFilter statusIs(String status) {
    return RowQueryFilter.validated(
        new RowFilter(List.of(new RowFilter.Condition("status", "eq", status))),
        Map.of("content", "TEXT", "status", "VARCHAR"));
  }

  @Test
  @DisplayName("의미 검색은 코사인 순, 키워드 검색은 음절 바이그램으로 찾고, 해시·행 수가 지금 세대 기준이다")
  void semanticKeywordHashesAndCount() {
    assertThat(index.semantic(gen1, axis(1), RowQueryFilter.none(), 2))
        .extracting(RowHit::rowId)
        .first()
        .isEqualTo(2L);
    assertThat(index.keyword(gen1, "누수", RowQueryFilter.none(), 10))
        .extracting(RowHit::rowId)
        .containsExactlyInAnyOrder(1L, 3L, 5L);
    assertThat(index.existingHashes(gen1, List.of(1L, 2L, 99L)))
        .containsExactlyInAnyOrderEntriesOf(Map.of(1L, "h1", 2L, "h2"));
    assertThat(index.count(gen1)).isEqualTo(5);
  }

  @Test
  @DisplayName("필터 1단계 — 만족하는 행이 상한 이하면 id 필터로 넘겨 그 행만 나온다")
  void filterByIdsWhenUnderCap() {
    // 미처리는 1행뿐이라 상한(3) 이하 → id 필터
    assertThat(index.semantic(gen1, axis(1), statusIs("미처리"), 5))
        .extracting(RowHit::rowId)
        .containsExactly(1L);
    assertThat(index.keyword(gen1, "누수", statusIs("미처리"), 5))
        .extracting(RowHit::rowId)
        .containsExactly(1L);
  }

  @Test
  @DisplayName("필터 2단계 — 만족하는 행이 상한을 넘으면 넉넉히 받아 데이터 저장소에서 사후 확인한다")
  void postCheckWhenOverCap() {
    // 완료는 4행이라 상한(3) 초과 → 사후 확인. 1행(미처리)은 키워드가 맞아도 빠진다.
    assertThat(index.keyword(gen1, "누수", statusIs("완료"), 5))
        .extracting(RowHit::rowId)
        .containsExactlyInAnyOrder(3L, 5L);
    assertThat(index.semantic(gen1, axis(0), statusIs("완료"), 2))
        .extracting(RowHit::rowId)
        .doesNotContain(1L)
        .hasSize(2);
  }

  @Test
  @DisplayName("swap 재구축 — 새 세대는 같은 원문 벡터를 옛 세대에서 읽어 다시 쓰고, 검색은 지금 세대만, 완주하면 옛 세대를 지운다")
  void generationRebuildReusesVectors() {
    index.startGeneration(gen2, DIM);
    assertThat(index.hasPreviousGeneration(gen2)).isTrue();
    // 새 세대에서 row_id 가 바뀌었다(10 ← 옛 1). 같은 해시·모델이면 벡터를 재사용한다.
    // 배치째(WD-743): 해시 묶음 조회 한 번으로 h1 을 찾고, 모르는 해시(11)는 빠진다.
    assertThat(
            index.upsertReusing(
                gen2,
                List.of(
                    new ReuseCandidate(10, "배관 누수 발생", "h1"),
                    new ReuseCandidate(11, "새 내용", "nope")),
                "fake"))
        .containsExactly(10L);
    assertThat(
            index.upsertReusing(
                gen2, List.of(new ReuseCandidate(12, "소음 민원", "h2")), "other-model"))
        .isEmpty();

    // 새 세대 검색에는 옛 세대 점이 섞이지 않는다
    assertThat(index.semantic(gen2, axis(0), RowQueryFilter.none(), 10))
        .extracting(RowHit::rowId)
        .containsExactly(10L);
    assertThat(index.existingHashes(gen2, List.of(1L, 10L))).containsOnlyKeys(10L);

    index.dropPreviousGenerations(gen2);
    assertThat(index.hasPreviousGeneration(gen2)).isFalse();
    assertThat(index.count(gen1)).isZero();
    assertThat(index.count(gen2)).isEqualTo(1);
  }

  @Test
  @DisplayName("데이터셋 삭제·벡터 비우기는 아웃박스로 쌓이고 워커가 반영한다 — 벡터만 비우면 키워드 검색은 남는다")
  void dropAndClearGoThroughOutbox() {
    VectorOutboxWorker worker =
        new VectorOutboxWorker(
            null, outbox, new QdrantOutboxApplier(client, props, rowClients), f -> true, false);

    index.clearEmbeddings(gen1);
    assertThat(index.semantic(gen1, axis(0), RowQueryFilter.none(), 5)).isNotEmpty(); // 아직 반영 전
    worker.drainCurrentTenant();
    assertThat(index.semantic(gen1, axis(0), RowQueryFilter.none(), 5)).isEmpty();
    assertThat(index.keyword(gen1, "누수", RowQueryFilter.none(), 5)).hasSize(3);

    index.drop(gen1);
    worker.drainCurrentTenant();
    assertThat(index.count(gen1)).isZero();
  }

  @Test
  @DisplayName("API 키가 없으면 행 아웃박스는 지울 것 없이 반영된 것으로 치고, 뒤의 다른 삭제를 막지 않는다")
  void rowOutboxWithoutKeyDoesNotBlockTenantOutbox() throws Exception {
    QdrantRowClients noKey =
        new QdrantRowClients(
            new QdrantProperties(props.url(), "", 2, true, java.time.Duration.ofSeconds(5)));
    VectorOutboxWorker worker =
        new VectorOutboxWorker(
            null, outbox, new QdrantOutboxApplier(client, props, noKey), f -> true, false);
    outbox.enqueue(com.smartfirehub.vectorstore.VectorOutboxKind.ROWS_ALL_OF_DATASET, DS, null);
    outbox.enqueue(
        com.smartfirehub.vectorstore.VectorOutboxKind.CHUNK_ALL_OF_DOCUMENT, 990_723L, null);

    assertThat(worker.drainCurrentTenant()).isGreaterThanOrEqualTo(2);
    assertThat(outbox.findPending(10)).isEmpty();
    noKey.destroy();
  }

  @Test
  @DisplayName("늦게 반영된 전체 삭제는 그 뒤에 다시 쓴 점을 지우지 않는다(순번 가드)")
  void lateDropKeepsRewrittenRows() {
    VectorOutboxWorker worker =
        new VectorOutboxWorker(
            null, outbox, new QdrantOutboxApplier(client, props, rowClients), f -> true, false);
    index.drop(gen1);
    index.upsert(gen1, List.of(new IndexedRow(1, "배관 누수 발생", "h1", axis(0), "fake")));
    worker.drainCurrentTenant();
    assertThat(index.existingHashes(gen1, List.of(1L, 2L))).containsOnlyKeys(1L);
  }

  @Test
  @DisplayName("원본에서 지운 행의 점은 원본 대조가 지우고, 대조는 간격 안에서 다시 돌지 않는다")
  void deleteMissingSweepsOrphansWithInterval() {
    inTenantFixture(
        () -> dataDsl.execute("DELETE FROM " + DataSchema.qualify(SRC) + " WHERE id = 2"));
    assertThat(index.deleteMissing(gen1)).isEqualTo(1);
    assertThat(index.existingHashes(gen1, List.of(2L))).isEmpty();
    assertThat(index.semantic(gen1, axis(1), RowQueryFilter.none(), 5))
        .extracting(RowHit::rowId)
        .doesNotContain(2L);

    inTenantFixture(
        () -> dataDsl.execute("DELETE FROM " + DataSchema.qualify(SRC) + " WHERE id = 3"));
    assertThat(index.deleteMissing(gen1)).isZero(); // 간격(60초) 안
    now.addAndGet(60_000);
    assertThat(index.deleteMissing(gen1)).isEqualTo(1);
  }

  @Test
  @DisplayName("키워드 전용·낱말 없는 원문 — 벡터 없는 점으로 남아 해시 조회에 잡힌다")
  void keywordOnlyAndTokenlessRows() {
    index.upsert(
        gen1,
        List.of(
            new IndexedRow(6, "!!!", "h6", null, "__policy_keyword_only__"),
            new IndexedRow(7, "단수 안내", "h7", null, "__policy_keyword_only__")));
    assertThat(index.existingHashes(gen1, List.of(6L, 7L))).containsOnlyKeys(6L, 7L);
    assertThat(index.keyword(gen1, "단수", RowQueryFilter.none(), 5))
        .extracting(RowHit::rowId)
        .containsExactly(7L);
  }

  @Test
  @DisplayName("세대 없는 참조로 쓰기·검색하면 거부하고, 테넌트 문맥이 다르면 거부한다")
  void rejectsGenerationlessRefAndTenantMismatch() {
    IndexRef any = new IndexRef(DEFAULT_TEST_TENANT_ID, DS, SRC);
    assertThatThrownBy(() -> index.upsert(any, List.of(new IndexedRow(1, "x", "h", null, "m"))))
        .isInstanceOf(IllegalStateException.class);
    IndexRef other = new IndexRef(DEFAULT_TEST_TENANT_ID + 1, DS, SRC, 1L);
    assertThatThrownBy(() -> index.count(other)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("테넌트 컬렉션 토큰은 다른 테넌트 컬렉션을 서버에서 거부한다(403)")
  void tenantTokenCannotReachOtherTenantCollections() throws Exception {
    lifecycle.ensureRowCollections(2L, DIM);
    QdrantClient tenant1 =
        rowClients.forCollections(lifecycle.rowCollections(DEFAULT_TEST_TENANT_ID));
    // 자기 컬렉션은 된다
    tenant1
        .queryAsync(
            QueryPoints.newBuilder()
                .setCollectionName(QdrantCollectionSpec.rowsDenseName(DEFAULT_TEST_TENANT_ID, DIM))
                .setUsing(QdrantCollectionSpec.DENSE)
                .setQuery(QueryFactory.nearest(axis(0)))
                .setLimit(1)
                .build())
        .get(30, TimeUnit.SECONDS);
    // 다른 테넌트 컬렉션은 앱 필터와 상관없이 서버가 막는다
    assertThatThrownBy(
            () ->
                tenant1
                    .queryAsync(
                        QueryPoints.newBuilder()
                            .setCollectionName(QdrantCollectionSpec.rowsDenseName(2L, DIM))
                            .setUsing(QdrantCollectionSpec.DENSE)
                            .setQuery(QueryFactory.nearest(axis(0)))
                            .setLimit(1)
                            .build())
                    .get(30, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .cause()
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
  }
}
