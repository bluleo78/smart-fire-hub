package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.repository.TenantDataStoreRepository;
import com.smartfirehub.dataset.rowsearch.IndexRef;
import com.smartfirehub.dataset.rowsearch.RowFilter;
import com.smartfirehub.dataset.rowsearch.RowQueryFilter;
import com.smartfirehub.dataset.rowsearch.RowSearchIndex;
import com.smartfirehub.dataset.rowsearch.RowSearchService;
import com.smartfirehub.dataset.rowsearch.RowSearchSyncService;
import com.smartfirehub.dataset.rowsearch.SearchIndexSettingsService;
import com.smartfirehub.dataset.rowsearch.SearchSourceReader;
import com.smartfirehub.dataset.rowsearch.dto.RowSearchRequest;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStorePasswordGenerator;
import com.smartfirehub.dataset.storage.external.ExternalAccountProvisioner;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.service.TiberoIncrementalCursor;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoTestServer;
import com.smartfirehub.vectorstore.VectorStoreFeatures;
import com.smartfirehub.vectorstore.qdrant.QdrantProperties;
import com.smartfirehub.vectorstore.qdrant.QdrantTestContainer;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Tibero 데이터 저장소 테넌트 × qdrant 조합의 행 검색 실서버 끝-끝 테스트(WD-723 완료 기준) — 실행: {@code
 * scripts/tibero-test.sh api}.
 *
 * <p>일회용 Tibero 테넌트(예약 대역 id, 계정 12개)에 원본 테이블을 만들고 <b>진짜 빈</b>으로 확인한다: 색인(동기화)·검색(의미·키워드)·필터 (1단계
 * row_id, 여러 타입)·증분(수정 행 재색인 — 서버 벽시계 책갈피 비교)·재구축(테이블 재생성으로 OBJECT_ID 와 ID 가 바뀌면 STALE → 새 세대). 원본
 * 읽기의 IN 목록 나눔(1000 개 초과)도 본다. 만든 테넌트·계정만 정리한다. 접속 정보는 출력하지 않는다.
 *
 * <p><b>책갈피 후보.</b> 실제 {@link TiberoIncrementalCursor} 는 db-executor 를 거치는데 이 테스트에는 db-executor 가
 * 없다. 그래서 같은 「Tibero 서버 시계의 지금(UTC)」을 이 테넌트 연결로 직접 읽는 가짜로 바꾼다 — 열린 트랜잭션 보정은 db-executor 실서버 테스트
 * (TiberoLiveCursorTest)가 맡는다. 여기서 보는 것은 저장된 UTC 책갈피와 {@code _UPDATED_AT}(서버 벽시계)의 비교다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoQdrantRowSearchLiveTest extends com.smartfirehub.support.IntegrationTestBase {

  private static final String SRC = "rs_tb_src";
  private static final QdrantProperties PROPS = QdrantTestContainer.properties(1, true);
  private static final DateTimeFormatter UTC_MICROS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

  @DynamicPropertySource
  static void qdrant(DynamicPropertyRegistry r) {
    QdrantTestContainer.recordInstallType("qdrant");
    r.add("firehub.vector-store.type", () -> "qdrant");
    r.add("firehub.vector-store.qdrant.url", PROPS::url);
    r.add("firehub.vector-store.qdrant.api-key", PROPS::apiKey);
    r.add("firehub.vector-store.keyword-sweep-enabled", () -> "false");
    r.add("firehub.vector-store.outbox-worker-enabled", () -> "false");
    r.add("firehub.vector-store.reconcile-sweep-enabled", () -> "false");
  }

  @Autowired private DSLContext dsl;
  @Autowired private TenantDataStoreRepository storeRepository;
  @Autowired private ExternalAccountProvisioner provisioner;
  @Autowired private ExternalStoreConnections connections;
  @Autowired private EncryptionService encryption;
  @Autowired private DataStorePasswordGenerator passwordGenerator;
  @Autowired private TiberoDataTableStore tableStore;
  @Autowired private TiberoDataRowStore rowStore;
  @Autowired private RowSearchService service;
  @Autowired private RowSearchSyncService sync;
  @Autowired private SearchIndexSettingsService settings;
  @Autowired private RowSearchIndex index;
  @Autowired private SearchSourceReader reader;
  @Autowired private VectorStoreFeatures features;

  @MockitoBean private EmbeddingProviderFactory embeddingFactory;
  @MockitoBean private TiberoIncrementalCursor tiberoCursor;

  private TiberoTestEnvironment.Provisioned env;
  private long datasetId;
  private Long userId;

  /** 가짜 공급자가 받은 원문 — 교체 적재 뒤 임베딩이 바뀐 원문 수만큼만 일어나는지 센다(WD-745). */
  private final List<String> embedded = java.util.Collections.synchronizedList(new ArrayList<>());

  private static final List<String> COLUMNS = List.of("content", "status", "cnt", "done", "day");
  private static final Map<String, String> TYPES =
      Map.of(
          "content", "TEXT", "status", "VARCHAR", "cnt", "INTEGER", "done", "BOOLEAN", "day",
          "DATE");

  @BeforeAll
  void prepare() {
    TiberoTestServer.assumeDriverPresent();
    env =
        TiberoTestEnvironment.provision(
            dsl,
            fixtureTransactionTemplate,
            storeRepository,
            provisioner,
            connections,
            encryption,
            passwordGenerator);
  }

  @AfterAll
  void cleanUp() {
    try {
      if (env != null) {
        env.close(); // 테이블·시퀀스는 계정 DROP(CASCADE)과 함께 사라진다
        env = null;
      }
    } finally {
      TenantContext.clear();
      QdrantTestContainer.recordInstallType("platform");
    }
  }

  @BeforeEach
  void setUp() {
    TenantContext.set(env.tenantId);
    when(tiberoCursor.captureCandidate()).thenAnswer(inv -> tiberoNowUtc());
    when(embeddingFactory.current())
        .thenAnswer(
            inv ->
                new EmbeddingProvider() {
                  public List<float[]> embed(List<String> texts) {
                    embedded.addAll(texts);
                    return texts.stream()
                        .map(
                            t -> {
                              float[] v = new float[8];
                              v[(t.contains("누수") || t.contains("배관 문제")) ? 0 : 1] = 1f;
                              return v;
                            })
                        .toList();
                  }

                  public String modelId() {
                    return "fake";
                  }

                  public int dimension() {
                    return 8;
                  }
                });
    createSource();
    insert(
        row("파이프 누수 신고", "미처리", 1L, false, LocalDate.parse("2025-12-01")),
        row("소음 민원 A-1023", "완료", 2L, true, LocalDate.parse("2025-12-02")),
        row("누수 재발", "완료", 3L, true, LocalDate.parse("2025-12-03")));
    datasetId =
        inTenantFixture(
            env.tenantId,
            () -> {
              userId = TenantRlsTestSupport.insertUser(dsl, "rs_tb_u");
              Long id =
                  dsl.fetchOne(
                          "INSERT INTO dataset(name, table_name, storage_type, origin_type, created_by)"
                              + " VALUES ('rs tibero', ?, 'TABLE', 'SOURCE', ?) RETURNING id",
                          SRC,
                          userId)
                      .get(0, Long.class);
              dsl.execute(
                  "INSERT INTO dataset_column(dataset_id, column_name, display_name, data_type,"
                      + " is_nullable, is_indexed, column_order) VALUES"
                      + " (?, 'content', '내용', 'TEXT', true, false, 0),"
                      + " (?, 'status', '상태', 'VARCHAR', true, false, 1),"
                      + " (?, 'cnt', '건수', 'INTEGER', true, false, 2),"
                      + " (?, 'done', '완료', 'BOOLEAN', true, false, 3),"
                      + " (?, 'day', '일자', 'DATE', true, false, 4)",
                  id,
                  id,
                  id,
                  id,
                  id);
              return id;
            });
    settings.update(datasetId, List.of("content"));
    assertThat(sync.sync(datasetId)).isEqualTo(RowSearchSyncService.Outcome.COMPLETED);
    embedded.clear();
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(env.tenantId);
    try {
      index.drop(new IndexRef(env.tenantId, datasetId, SRC));
    } finally {
      tableStore.dropTable(SRC);
      inTenantFixture(
          env.tenantId,
          () -> {
            dsl.execute("DELETE FROM dataset_column WHERE dataset_id = ?", datasetId);
            dsl.execute("DELETE FROM dataset WHERE id = ?", datasetId);
            dsl.execute(
                "DELETE FROM vector_outbox WHERE kind IN ('ROWS_ALL_OF_DATASET', 'ROWS_DENSE_OF_DATASET')");
          });
      TenantRlsTestSupport.deleteUser(dsl, userId);
    }
  }

  // ---------- 시험 ----------

  /** qdrant 조합은 Tibero 데이터 저장소 테넌트에서도 행 검색을 켠다(관문·기능 응답·스윕이 같은 답). */
  @Test
  void rowSearchIsEnabledForTiberoTenant() {
    assertThat(features.supportsRowSearch(DataStoreBackend.TIBERO)).isTrue();
  }

  /** 색인·의미·키워드·하이브리드 — 원본 값은 Tibero 에서 읽어 돌려준다. */
  @Test
  void indexesAndSearches() {
    var hybrid = service.search(datasetId, req("누수", null, null));
    assertThat(hybrid.indexStatus().status()).isEqualTo("IDLE");
    assertThat(hybrid.hits())
        .extracting(h -> h.row().get("content"))
        .contains("파이프 누수 신고", "누수 재발");

    var semantic = service.search(datasetId, req("배관 문제", "SEMANTIC", null));
    assertThat(semantic.hits().get(0).row().get("content")).asString().contains("누수");

    var keyword = service.search(datasetId, req("A-1023", "KEYWORD", null));
    assertThat(keyword.hits())
        .extracting(h -> h.row().get("content"))
        .containsExactly("소음 민원 A-1023");
  }

  /** 필터는 Tibero SQL 로 평가한다 — 문자열·정수 in·BOOLEAN(NUMBER(1))·DATE 비교. */
  @Test
  void filtersAreEvaluatedOnTibero() {
    var byStatus =
        service.search(
            datasetId, req("누수", "HYBRID", List.of(new RowFilter.Condition("status", "eq", "완료"))));
    assertThat(byStatus.hits())
        .extracting(h -> h.row().get("content"))
        .doesNotContain("파이프 누수 신고")
        .first()
        .isEqualTo("누수 재발");

    var combined =
        service.search(
            datasetId,
            req(
                "누수",
                "HYBRID",
                List.of(
                    new RowFilter.Condition("cnt", "in", List.of(1, 3)),
                    new RowFilter.Condition("done", "eq", true),
                    new RowFilter.Condition("day", "gte", "2025-12-02"))));
    assertThat(combined.hits()).extracting(h -> h.row().get("content")).containsExactly("누수 재발");
  }

  /** 수정한 행은 다음 동기화가 다시 색인한다 — UTC 책갈피와 서버 벽시계 {@code _UPDATED_AT} 비교가 맞아야 한다. */
  @Test
  void incrementalSyncPicksUpUpdatedRow() {
    long id = idOf("소음 민원 A-1023");
    rowStore.updateRow(SRC, id, List.of("content"), Map.of("content", "배관 누수 새로 접수 B-77"), TYPES);

    assertThat(sync.sync(datasetId)).isEqualTo(RowSearchSyncService.Outcome.COMPLETED);

    var keyword = service.search(datasetId, req("B-77", "KEYWORD", null));
    assertThat(keyword.hits())
        .extracting(h -> h.row().get("content"))
        .containsExactly("배관 누수 새로 접수 B-77");
    assertThat(service.search(datasetId, req("A-1023", "KEYWORD", null)).hits()).isEmpty();
    // 책갈피 후보는 Tibero 시계에서 잡아야 한다 — 파사드가 PG 커서로 잘못 가면 이 테넌트 호출이 0 이다.
    verify(tiberoCursor, atLeastOnce()).captureCandidate();

    // 책갈피 비교(서버 시간대 변환 리터럴)가 실린 조회 뒤에도 같은 풀의 UPDATE·재동기화가 그대로 돼야 한다 — 색인 TSTZ 열의 AT TIME ZONE
    // 조회가 이후 UPDATE 세션을 끊던 결함(WD-260)이 _UPDATED_AT(TIMESTAMP)·리터럴 변환에는 없는지 지키는 회귀 확인이다.
    rowStore.updateRow(SRC, id, List.of("content"), Map.of("content", "두 번째 수정 C-88"), TYPES);
    assertThat(sync.sync(datasetId)).isEqualTo(RowSearchSyncService.Outcome.COMPLETED);
    assertThat(service.search(datasetId, req("C-88", "KEYWORD", null)).hits())
        .extracting(h -> h.row().get("content"))
        .containsExactly("두 번째 수정 C-88");
  }

  /**
   * 테이블 재생성(OBJECT_ID 가 바뀌고 ID 가 1 부터 다시)은 PG swap 과 같다 — 동기화 전에는 STALE 로 옛 결과를 내보내지 않고, 동기화 뒤에는 새
   * 세대만 보인다.
   */
  @Test
  void recreatedTableRebuildsGeneration() {
    long before = reader.currentOid(SRC);
    tableStore.dropTable(SRC);
    createSource();
    insert(row("전혀 다른 행", "완료", 9L, true, LocalDate.parse("2026-01-01")));
    assertThat(reader.currentOid(SRC)).isNotEqualTo(before);

    var stale = service.search(datasetId, req("누수", null, null));
    assertThat(stale.indexStatus().status()).isEqualTo("STALE");
    assertThat(stale.hits()).isEmpty();

    assertThat(sync.sync(datasetId)).isEqualTo(RowSearchSyncService.Outcome.COMPLETED);
    var after = service.search(datasetId, req("다른", "KEYWORD", null));
    assertThat(after.indexStatus().status()).isEqualTo("IDLE");
    assertThat(after.hits()).extracting(h -> h.row().get("content")).containsExactly("전혀 다른 행");
    assertThat(service.search(datasetId, req("누수", "KEYWORD", null)).hits()).isEmpty();
  }

  /**
   * 교체 적재(REPLACE)는 같은 테이블을 {@code DELETE FROM} 으로 비우고 새 ID 로 다시 넣는다 — OBJECT_ID 그대로라 세대가 바뀌지 않는다.
   * 같은 원문은 지금 세대 벡터를 복사하고 바뀐 원문만 임베딩한다. 옛 ID 점은 그 동기화에서 바로 정리돼 색인 행 수가 원본과 같다(WD-745).
   */
  @Test
  void replaceReusesVectorsAndSweepsOldIds() {
    long oid = reader.currentOid(SRC);
    long oldId = idOf("파이프 누수 신고");
    rowStore.truncateTable(SRC);
    insert(
        row("파이프 누수 신고", "미처리", 1L, false, LocalDate.parse("2025-12-01")),
        row("소음 민원 A-1023", "완료", 2L, true, LocalDate.parse("2025-12-02")),
        row("새로 들어온 정전 D-5", "완료", 4L, true, LocalDate.parse("2025-12-04")));
    assertThat(reader.currentOid(SRC)).isEqualTo(oid);
    assertThat(idOf("파이프 누수 신고")).isNotEqualTo(oldId);

    assertThat(sync.sync(datasetId)).isEqualTo(RowSearchSyncService.Outcome.COMPLETED);

    // 같은 원문 둘은 재사용 — 바뀐(새) 원문 하나만 임베딩한다.
    // 원문은 「표시 이름: 값」 꼴이다(SearchConfig.buildSourceText).
    assertThat(embedded).containsExactly("내용: 새로 들어온 정전 D-5");
    var status = service.search(datasetId, req("누수", null, null)).indexStatus();
    assertThat(status.status()).isEqualTo("IDLE");
    assertThat(status.indexedRows()).isEqualTo(3);
    // 재사용한 벡터로 의미 검색이 그대로 되고, 옛 ID(지운 '누수 재발' 포함)는 결과에 없다.
    var semantic = service.search(datasetId, req("배관 문제", "SEMANTIC", null));
    assertThat(semantic.hits().get(0).row().get("content")).isEqualTo("파이프 누수 신고");
    assertThat(service.search(datasetId, req("재발", "KEYWORD", null)).hits()).isEmpty();
  }

  /** id 목록이 IN 상한(1000)을 넘어도 나눠 묻는다 — 원본 대조·필터 2단계·원본 조회가 큰 후보를 받는 경로. */
  @Test
  void idListsOverInLimitAreChunked() {
    List<Long> ids = new ArrayList<>(LongStream.rangeClosed(1, 2500).boxed().toList());
    List<Long> real = List.of(idOf("파이프 누수 신고"), idOf("누수 재발"));
    ids.addAll(real);

    assertThat(reader.existingIds(SRC, ids)).containsAll(real);
    assertThat(reader.fetchRows(SRC, List.of("content"), ids).keySet()).containsAll(real);
    var done =
        RowQueryFilter.validated(
            new RowFilter(List.of(new RowFilter.Condition("done", "eq", true))), TYPES);
    // done=true 는 '소음 민원'·'누수 재발' 두 행 — 둘 다 1..2500 안에 있어 첫 조각·나머지 조각 어느 쪽에서든 잡혀야 한다.
    assertThat(reader.filterIds(SRC, ids, done))
        .containsExactlyInAnyOrder(idOf("소음 민원 A-1023"), idOf("누수 재발"));
    assertThat(reader.matchingIds(SRC, done, 1)).hasSize(1);
  }

  // ---------- 도우미 ----------

  private RowSearchRequest req(String q, String mode, List<RowFilter.Condition> filters) {
    return new RowSearchRequest(q, mode, 10, filters, null);
  }

  private void createSource() {
    tableStore.createTable(
        SRC,
        List.of(
            new DatasetColumnRequest("content", "내용", "TEXT", null, true, false, null),
            new DatasetColumnRequest("status", "상태", "VARCHAR", 20, true, false, null),
            new DatasetColumnRequest("cnt", "건수", "INTEGER", null, true, false, null),
            new DatasetColumnRequest("done", "완료", "BOOLEAN", null, true, false, null),
            new DatasetColumnRequest("day", "일자", "DATE", null, true, false, null)));
  }

  private static Map<String, Object> row(
      String content, String status, long cnt, boolean done, LocalDate day) {
    Map<String, Object> m = new HashMap<>();
    m.put("content", content);
    m.put("status", status);
    m.put("cnt", cnt);
    m.put("done", done);
    m.put("day", day);
    return m;
  }

  @SafeVarargs
  private void insert(Map<String, Object>... rows) {
    rowStore.insertBatch(SRC, COLUMNS, List.of(rows), TYPES);
  }

  /** content 가 같은 행의 ID(원본에서 직접). */
  private long idOf(String content) {
    return rowStore.queryData(SRC, List.of("content"), content, 0, 10, null, null, TYPES).stream()
        .filter(r -> content.equals(r.get("content")))
        .map(r -> ((Number) r.get("id")).longValue())
        .findFirst()
        .orElseThrow();
  }

  /** Tibero 서버 시계의 지금(UTC) — 일회용 OWNER 연결로 읽는다(묶인 DML 커넥션과 섞지 않는다). */
  private OffsetDateTime tiberoNowUtc() {
    return connections.withDdl(
        env.tenantId,
        c -> {
          try (Statement st = c.createStatement();
              ResultSet rs =
                  st.executeQuery(
                      "SELECT TO_CHAR(SYS_EXTRACT_UTC(SYSTIMESTAMP), 'YYYY-MM-DD HH24:MI:SS.FF6')"
                          + " FROM DUAL")) {
            rs.next();
            return LocalDateTime.parse(rs.getString(1), UTC_MICROS).atOffset(ZoneOffset.UTC);
          }
        });
  }
}
