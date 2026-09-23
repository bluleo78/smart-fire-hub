package com.smartfirehub.dataset.rowsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.rowsearch.dto.RowSearchRequest;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.embedding.EmbeddingException;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 행 검색 E2E(서비스 수준): 모드별 결과·matchedBy·필터·원본 조회·STALE 차단·degraded 폴백·미설정 400.
 * 가짜 임베딩은 텍스트에 '누수'가 있으면 축 0, 없으면 축 1 — 질의 '배관 문제' 는 축 0 으로 보내 의미 검색을 흉내 낸다.
 */
class RowSearchServiceTest extends IntegrationTestBase {

  private static final String SRC = "rs_svc_src";

  @Autowired private RowSearchService service;
  @Autowired private RowSearchSyncService sync;
  @Autowired private SearchIndexSettingsService settings;
  @Autowired private PgRowSearchIndex index;
  @Autowired private DataTableService dataTableService;
  @Autowired private DSLContext dsl;
  @MockitoBean private EmbeddingProviderFactory embeddingFactory;

  private final AtomicBoolean down = new AtomicBoolean(false);
  // 색인 이후 임베딩 모델·차원이 바뀐 상황(다음 스윕 전)을 흉내 내려고 가변으로 둔다.
  private volatile String model = "fake";
  private volatile int dim = 1024;
  private long datasetId;
  private Long userId;

  @BeforeEach
  void setUp() {
    // 이전 실행의 tearDown 실패로 남은 데이터셋·원본·색인 테이블·사용자를 먼저 지운다(unique 위반 연쇄 방지).
    RowSearchTestSupport.cleanup(dsl, dataTableService, SRC);
    when(embeddingFactory.current()).thenAnswer(inv -> new EmbeddingProvider() {
      public List<float[]> embed(List<String> texts) {
        if (down.get()) throw new EmbeddingException("down");
        return texts.stream().map(t -> { float[] v = new float[dim]; v[(t.contains("누수") || t.contains("배관 문제")) ? 0 : 1] = 1f; return v; }).toList();
      }
      public String modelId() { return model; }
      public int dimension() { return dim; }
    });
    datasetId = inTenantFixture(() -> {
      userId = TenantRlsTestSupport.insertUser(dsl, "rs_svc_u");
      Long id = dsl.fetchOne("INSERT INTO dataset(name, table_name, storage_type, origin_type, created_by) VALUES ('rs svc', ?, 'TABLE', 'SOURCE', ?) RETURNING id", SRC, userId).get(0, Long.class);
      dsl.execute("INSERT INTO dataset_column(dataset_id, column_name, display_name, data_type, is_nullable, is_indexed, column_order) VALUES (?, 'content', '내용', 'TEXT', true, false, 0), (?, 'status', '상태', 'VARCHAR', true, false, 1)", id, id);
      dataTableService.createTable(SRC, List.of(
          new DatasetColumnRequest("content", "내용", "TEXT", null, true, false, null),
          new DatasetColumnRequest("status", "상태", "VARCHAR", 20, true, false, null)));
      dsl.execute("INSERT INTO " + DataSchema.qualify(SRC) + " (content, status) VALUES ('파이프 누수 신고', '미처리'), ('소음 민원 A-1023', '완료'), ('누수 재발', '완료')");
      return id;
    });
    settings.update(datasetId, List.of("content"));
    sync.sync(datasetId);
  }

  @AfterEach
  void tearDown() {
    inTenantFixture(() -> {
      index.drop(new IndexRef(DEFAULT_TEST_TENANT_ID, datasetId, SRC));
      dataTableService.dropTable(SRC);
      dataTableService.dropTempTable(SRC);
      dsl.execute("DELETE FROM dataset_column WHERE dataset_id = ?", datasetId);
      dsl.execute("DELETE FROM dataset WHERE id = ?", datasetId);
    });
    TenantRlsTestSupport.deleteUser(dsl, userId);
  }

  private RowSearchRequest req(String q, String mode, List<RowFilter.Condition> filters) {
    return new RowSearchRequest(q, mode, 10, filters, null);
  }

  @Test
  void hybrid_returnsRowsWithMatchedBy_andHydratedValues() {
    var res = service.search(datasetId, req("누수", null, null));
    assertThat(res.indexStatus().status()).isEqualTo("IDLE");
    assertThat(res.degraded()).isFalse();
    assertThat(res.hits()).extracting(h -> h.row().get("content"))
        .contains("파이프 누수 신고", "누수 재발");
    var top = res.hits().get(0);
    assertThat(top.matchedBy()).containsExactlyInAnyOrder("SEMANTIC", "KEYWORD");
  }

  @Test
  void semantic_findsByMeaningWithoutKeyword() {
    var res = service.search(datasetId, req("배관 문제", "SEMANTIC", null));
    assertThat(res.hits().get(0).row().get("content")).asString().contains("누수");
  }

  @Test
  void keyword_findsExactCode() {
    var res = service.search(datasetId, req("A-1023", "KEYWORD", null));
    assertThat(res.hits()).extracting(h -> h.row().get("content")).containsExactly("소음 민원 A-1023");
  }

  @Test
  void filter_isApplied() {
    var res = service.search(datasetId, req("누수", "HYBRID", List.of(new RowFilter.Condition("status", "eq", "완료"))));
    // 의미 검색은 limit 까지 전부 돌려주므로 '소음 민원'도 낮은 순위로 섞일 수 있다 — 필터로 '미처리' 행이 빠졌는지와 1위를 본다
    assertThat(res.hits()).extracting(h -> h.row().get("content"))
        .doesNotContain("파이프 누수 신고")
        .first().isEqualTo("누수 재발");
    assertThat(res.hits()).allMatch(h -> "완료".equals(h.row().get("status")));
  }

  @Test
  void embeddingDown_hybridDegradesToKeyword_semanticFails() {
    down.set(true);
    var res = service.search(datasetId, req("누수", "HYBRID", null));
    assertThat(res.degraded()).isTrue();
    assertThat(res.hits()).isNotEmpty();
    assertThatThrownBy(() -> service.search(datasetId, req("누수", "SEMANTIC", null))).isInstanceOf(EmbeddingException.class);
  }

  @Test
  void afterSwap_beforeSweep_returnsStaleAndNoHits() {
    inTenantFixture(() -> {
      dataTableService.createTempTable(SRC);
      dsl.execute("INSERT INTO " + DataSchema.qualify(SRC + "_tmp") + " (content, status) VALUES ('전혀 다른 행', '완료')");
      dataTableService.swapTable(SRC);
    });

    var res = service.search(datasetId, req("누수", null, null));

    // 예전 벡터(row_id=1 '파이프 누수')가 새 행 id=1('전혀 다른 행')으로 나가면 안 된다
    assertThat(res.indexStatus().status()).isEqualTo("STALE");
    assertThat(res.hits()).isEmpty();
  }

  @Test
  void modelChanged_beforeSweep_hybridDegradesToKeyword_semanticReportsSyncing() {
    // 색인은 fake(1024) 벡터인데 현재 모델이 바뀌었다 — 같은 차원이면 조용히 틀린 결과, 다른 차원이면 pgvector 오류.
    model = "fake-new";

    var hybrid = service.search(datasetId, req("누수", "HYBRID", null));
    assertThat(hybrid.degraded()).isTrue();
    assertThat(hybrid.hits()).isNotEmpty().allMatch(h -> h.matchedBy().equals(List.of("KEYWORD")));

    var semantic = service.search(datasetId, req("배관 문제", "SEMANTIC", null));
    assertThat(semantic.indexStatus().status()).isEqualTo("SYNCING");
    assertThat(semantic.hits()).isEmpty();
  }

  @Test
  void dimensionChanged_beforeSweep_doesNotFail() {
    dim = 768;

    var hybrid = service.search(datasetId, req("누수", "HYBRID", null));
    assertThat(hybrid.degraded()).isTrue();
    assertThat(hybrid.hits()).isNotEmpty();

    var semantic = service.search(datasetId, req("배관 문제", "SEMANTIC", null));
    assertThat(semantic.indexStatus().status()).isEqualTo("SYNCING");
    assertThat(semantic.hits()).isEmpty();
  }

  @Test
  void beforeFirstSweep_reportsSyncingNotStale() {
    // 끄고 다시 켜면 상태 행이 새로 생기고(원본 OID 미기록) 색인 테이블도 없다 — 첫 스윕 전이다.
    settings.update(datasetId, List.of());
    settings.update(datasetId, List.of("content"));

    var res = service.search(datasetId, req("누수", null, null));

    assertThat(res.indexStatus().status()).isEqualTo("SYNCING");
    assertThat(res.hits()).isEmpty();
  }

  @Test
  void hybrid_limitAboveCandidatePool_canBeFilled() {
    // 후보 풀(50)보다 큰 limit 을 요청하면 풀도 limit 만큼 넓혀야 채울 수 있다.
    inTenantFixture(() -> {
      dsl.execute("INSERT INTO " + DataSchema.qualify(SRC) + " (content, status) SELECT '누수 ' || g, '완료' FROM generate_series(1, 60) g");
    });
    sync.sync(datasetId);
    down.set(true); // 키워드 풀만으로 결정적으로 판별한다

    var res = service.search(datasetId, new RowSearchRequest("누수", "HYBRID", 100, null, null));

    assertThat(res.hits().size()).isGreaterThan(50);
  }

  @Test
  void otherTenant_cannotSearch() {
    // RLS 로 dataset 이 보이지 않아야 한다 — 다른 테넌트 스코프에서는 404 계열
    assertThatThrownBy(
            () ->
                com.smartfirehub.global.tenant.TenantContext.runScopedGet(
                    987_654L, () -> service.search(datasetId, req("누수", null, null))))
        .isInstanceOf(com.smartfirehub.dataset.exception.DatasetNotFoundException.class);
  }

  @Test
  void searchFieldsEmptyButStateLeft_isNotConfigured() {
    // 방어 검사: 상태 행·색인 테이블이 남았는데 검색 대상 필드가 비었으면(컬럼 삭제 경로는 이제 즉시 끄지만, 그 밖의
    // 경로로 필드만 비는 경우) 낡은 색인으로 결과를 내면 안 된다 — 미설정으로 본다(getStatus 의 OFF 판정과 같은 기준).
    inTenantFixture(() -> { dsl.execute("UPDATE dataset_column SET is_searchable = false WHERE dataset_id = ?", datasetId); });

    assertThatThrownBy(() -> service.search(datasetId, req("누수", "KEYWORD", null)))
        .isInstanceOf(SearchIndexNotConfiguredException.class);
  }

  @Test
  void notConfigured_is400Exception() {
    settings.update(datasetId, List.of());
    assertThatThrownBy(() -> service.search(datasetId, req("누수", null, null)))
        .isInstanceOf(SearchIndexNotConfiguredException.class);
  }
}
