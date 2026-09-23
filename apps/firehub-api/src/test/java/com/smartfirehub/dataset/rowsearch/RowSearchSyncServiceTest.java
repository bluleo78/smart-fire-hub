package com.smartfirehub.dataset.rowsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.embedding.EmbeddingException;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 동기화 알고리즘: 증분·해시 생략·삭제 정리·전체 재색인 트리거·swap 재사용·주기 상한·실패 시 커서 불변.
 * 임베딩 호출 텍스트를 기록해 "임베딩을 생략했는가"를 호출 횟수로 단언한다.
 */
@TestPropertySource(
    properties = {"row-search.sync.max-rows-per-cycle=4", "row-search.sync.batch-size=2"})
class RowSearchSyncServiceTest extends IntegrationTestBase {

  private static final String SRC = "rs_sync_src";

  @Autowired private RowSearchSyncService sync;
  @Autowired private PgRowSearchIndex index;
  @Autowired private SearchIndexStateRepository states;
  @Autowired private SearchColumnRepository searchColumns;
  @Autowired private DataTableService dataTableService;
  @Autowired private DSLContext dsl;
  @MockitoBean private EmbeddingProviderFactory embeddingFactory;

  private final List<String> embedded = new ArrayList<>();
  private final AtomicBoolean failEmbedding = new AtomicBoolean(false);
  private String model = "fake-a";
  private long datasetId;
  private Long userId;

  private IndexRef ref() {
    return new IndexRef(DEFAULT_TEST_TENANT_ID, datasetId, SRC);
  }

  @BeforeEach
  void setUp() {
    // 이전 실행의 tearDown 실패로 남은 데이터셋·원본·색인 테이블·사용자를 먼저 지운다(unique 위반 연쇄 방지).
    RowSearchTestSupport.cleanup(dsl, dataTableService, SRC);
    // 가짜 provider: 호출된 텍스트를 기록하고, 텍스트 해시로 한 칸만 1 인 벡터를 만든다.
    when(embeddingFactory.current())
        .thenAnswer(
            inv ->
                new EmbeddingProvider() {
                  public List<float[]> embed(List<String> texts) {
                    if (failEmbedding.get()) throw new EmbeddingException("down");
                    embedded.addAll(texts);
                    return texts.stream()
                        .map(
                            t -> {
                              float[] v = new float[1024];
                              v[Math.abs(t.hashCode()) % 1024] = 1f;
                              return v;
                            })
                        .toList();
                  }

                  public String modelId() {
                    return model;
                  }

                  public int dimension() {
                    return 1024;
                  }
                });
    datasetId =
        inTenantFixture(
            () -> {
              userId = TenantRlsTestSupport.insertUser(dsl, "rs_sync_u");
              Long id =
                  dsl.fetchOne(
                          "INSERT INTO dataset(name, table_name, storage_type, origin_type, created_by) VALUES ('rs sync', ?, 'TABLE', 'SOURCE', ?) RETURNING id",
                          SRC, userId)
                      .get(0, Long.class);
              dsl.execute(
                  "INSERT INTO dataset_column(dataset_id, column_name, display_name, data_type, is_nullable, is_indexed, column_order) VALUES (?, 'content', '내용', 'TEXT', true, false, 0), (?, 'status', '상태', 'VARCHAR', true, false, 1)",
                  id, id);
              dataTableService.createTable(
                  SRC,
                  List.of(
                      new DatasetColumnRequest("content", "내용", "TEXT", null, true, false, null),
                      new DatasetColumnRequest("status", "상태", "VARCHAR", 20, true, false, null)));
              dsl.execute(
                  "INSERT INTO " + DataSchema.qualify(SRC) + " (content, status) VALUES ('a','x'),('b','x'),('c','x')");
              return id;
            });
    searchColumns.setSearchable(datasetId, List.of("content"));
    states.createIfAbsent(datasetId);
  }

  @AfterEach
  void tearDown() {
    // 색인·_prev·_tmp·원본·메타 전부(dataset 삭제 시 상태 행은 CASCADE). 사용자는 dataset 을 지운 뒤에 지운다.
    RowSearchTestSupport.cleanup(dsl, dataTableService, SRC);
    TenantRlsTestSupport.deleteUser(dsl, userId);
  }

  private void exec(String sql) {
    inTenantFixture(
        () -> {
          dsl.execute(sql.replace("$T", DataSchema.qualify(SRC)));
        });
  }

  @Test
  void firstSync_indexesAll_thenIdle() {
    assertThat(sync.sync(datasetId)).isEqualTo(RowSearchSyncService.Outcome.COMPLETED);
    assertThat(embedded).containsExactlyInAnyOrder("내용: a", "내용: b", "내용: c");
    var s = states.find(datasetId).orElseThrow();
    assertThat(s.status()).isEqualTo("IDLE");
    assertThat(s.indexedRows()).isEqualTo(3);
    assertThat(s.syncCursor()).isNotNull();
  }

  @Test
  void nonSearchableColumnChange_skipsEmbedding_butRowStaysIndexed() {
    sync.sync(datasetId);
    embedded.clear();
    exec("UPDATE $T SET status = 'y' WHERE id = 1"); // _updated_at 갱신, 검색 텍스트는 동일

    sync.sync(datasetId);

    assertThat(embedded).isEmpty();
    assertThat(index.existingHashes(ref(), List.of(1L))).containsKey(1L);
  }

  @Test
  void contentChange_and_delete_areReflected() {
    sync.sync(datasetId);
    embedded.clear();
    exec("UPDATE $T SET content = 'a2' WHERE id = 1");
    exec("DELETE FROM $T WHERE id = 2");

    sync.sync(datasetId);

    assertThat(embedded).containsExactly("내용: a2");
    assertThat(index.existingHashes(ref(), List.of(1L, 2L, 3L))).containsOnlyKeys(1L, 3L);
  }

  @Test
  void configOrModelChange_triggersFullReindex() {
    sync.sync(datasetId);
    embedded.clear();
    model = "fake-b";

    sync.sync(datasetId);

    assertThat(embedded).hasSize(3);
    assertThat(states.find(datasetId).orElseThrow().embeddingModel()).isEqualTo("fake-b");
  }

  @Test
  void truncateThenInsert_keepsSequence_orphansRemoved_newRowsIndexed() {
    sync.sync(datasetId);
    exec("TRUNCATE $T");
    exec("INSERT INTO $T (content, status) VALUES ('d','x')");

    sync.sync(datasetId);

    // TRUNCATE 는 시퀀스를 이어가므로 새 행 id=4, 예전 1~3 은 고아로 정리된다
    assertThat(index.existingHashes(ref(), List.of(1L, 2L, 3L, 4L))).containsOnlyKeys(4L);
  }

  @Test
  void swap_rebuildsReusingVectorsBySameContent() {
    sync.sync(datasetId);
    embedded.clear();
    // swap: 새 시퀀스의 _tmp 를 채우고 맞바꾼다 → id 1 부터 다시, 내용 일부는 같음
    inTenantFixture(
        () -> {
          dataTableService.createTempTable(SRC);
          dsl.execute(
              "INSERT INTO " + DataSchema.qualify(SRC + "_tmp") + " (content, status) VALUES ('c','x'),('z','x')");
          dataTableService.swapTable(SRC);
        });

    sync.sync(datasetId);

    // 'c' 는 _prev 에서 재사용(임베딩 안 함), 'z' 만 임베딩
    assertThat(embedded).containsExactly("내용: z");
    assertThat(index.existingHashes(ref(), List.of(1L, 2L))).containsOnlyKeys(1L, 2L);
    assertThat(index.hasPrev(ref())).isFalse();
  }

  @Test
  void cycleCap_resumesAcrossCycles() {
    exec("INSERT INTO $T (content, status) SELECT 'n' || g, 'x' FROM generate_series(1, 6) g"); // 총 9행, 상한 4

    assertThat(sync.sync(datasetId)).isEqualTo(RowSearchSyncService.Outcome.PARTIAL); // 1~4
    var mid = states.find(datasetId).orElseThrow();
    assertThat(mid.resumeAfterId()).isEqualTo(4L);
    // 여러 주기에 걸친 색인 중에도 진행률이 0/0 이 아니어야 한다(완주 때만 쓰면 UI 가 멈춘 것처럼 보인다).
    assertThat(mid.totalRows()).isEqualTo(9);
    assertThat(mid.indexedRows()).isEqualTo(4);
    assertThat(sync.sync(datasetId)).isEqualTo(RowSearchSyncService.Outcome.PARTIAL); // 5~8, 9 남음
    assertThat(sync.sync(datasetId)).isEqualTo(RowSearchSyncService.Outcome.COMPLETED); // 9
    assertThat(index.count(ref())).isEqualTo(9);
  }

  @Test
  void cycleCap_exactBoundary_completesWithoutExtraCycle() {
    exec("INSERT INTO $T (content, status) VALUES ('d','x')"); // 총 4행 = 상한 4

    assertThat(sync.sync(datasetId)).isEqualTo(RowSearchSyncService.Outcome.COMPLETED);
    assertThat(index.count(ref())).isEqualTo(4);
  }

  @Test
  void embeddingFailure_keepsCursor_andBacksOff() {
    sync.sync(datasetId);
    var before = states.find(datasetId).orElseThrow().syncCursor();
    exec("UPDATE $T SET content = 'a3' WHERE id = 1");
    failEmbedding.set(true);

    assertThat(sync.sync(datasetId)).isEqualTo(RowSearchSyncService.Outcome.FAILED);

    var s = states.find(datasetId).orElseThrow();
    assertThat(s.status()).isEqualTo("ERROR");
    assertThat(s.syncCursor()).isEqualTo(before);
    assertThat(states.findDueDatasetIds()).doesNotContain(datasetId);
  }
}
