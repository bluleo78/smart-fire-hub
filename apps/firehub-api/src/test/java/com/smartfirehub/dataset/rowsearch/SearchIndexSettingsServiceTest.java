package com.smartfirehub.dataset.rowsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.UpdateColumnRequest;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 검색 설정 저장·끄기·재색인, 타입 변경 409, 데이터셋 삭제 시 색인 정리. */
class SearchIndexSettingsServiceTest extends IntegrationTestBase {

  private static final String SRC = "rs_settings_src";

  @Autowired private SearchIndexSettingsService settings;
  @Autowired private SearchIndexStateRepository states;
  @Autowired private PgRowSearchIndex index;
  @Autowired private DatasetService datasetService;
  @Autowired private DataTableService dataTableService;
  @Autowired private RowSearchSyncService sync;
  @Autowired private DSLContext dsl;
  @MockitoBean private EmbeddingProviderFactory embeddingFactory;

  private long datasetId;
  private long contentColumnId;
  private Long userId;

  @BeforeEach
  void setUp() {
    // 이전 실행의 tearDown 실패로 남은 데이터셋·원본·색인 테이블·사용자를 먼저 지운다(unique 위반 연쇄 방지).
    RowSearchTestSupport.cleanup(dsl, dataTableService, SRC);
    EmbeddingProvider p = new EmbeddingProvider() {
      public List<float[]> embed(List<String> t) { return t.stream().map(x -> new float[1024]).toList(); }
      public String modelId() { return "fake"; }
      public int dimension() { return 1024; }
    };
    when(embeddingFactory.current()).thenReturn(p);
    datasetId = inTenantFixture(() -> {
      userId = TenantRlsTestSupport.insertUser(dsl, "rs_set_u");
      Long id = dsl.fetchOne("INSERT INTO dataset(name, table_name, storage_type, origin_type, created_by) VALUES ('rs settings', ?, 'TABLE', 'SOURCE', ?) RETURNING id", SRC, userId).get(0, Long.class);
      dsl.execute("INSERT INTO dataset_column(dataset_id, column_name, display_name, data_type, is_nullable, is_indexed, column_order) VALUES (?, 'content', '내용', 'TEXT', true, false, 0), (?, 'cnt', '건수', 'INTEGER', true, false, 1)", id, id);
      dataTableService.createTable(SRC, List.of(
          new DatasetColumnRequest("content", "내용", "TEXT", null, true, false, null),
          new DatasetColumnRequest("cnt", "건수", "INTEGER", null, true, false, null)));
      return id;
    });
    contentColumnId = inTenantFixture(() -> dsl.fetchOne("SELECT id FROM dataset_column WHERE dataset_id = ? AND column_name='content'", datasetId).get(0, Long.class));
  }

  @AfterEach
  void tearDown() {
    inTenantFixture(() -> {
      index.drop(new IndexRef(DEFAULT_TEST_TENANT_ID, datasetId, SRC));
      dataTableService.dropTable(SRC);
      dsl.execute("DELETE FROM dataset_column WHERE dataset_id = ?", datasetId);
      dsl.execute("DELETE FROM dataset WHERE id = ?", datasetId);
    });
    TenantRlsTestSupport.deleteUser(dsl, userId);
  }

  @Test
  void update_enables_thenStatusSyncing_andEmptyDisables() {
    var on = settings.update(datasetId, List.of("content"));
    assertThat(on.enabled()).isTrue();
    assertThat(on.fields()).containsExactly("content");
    assertThat(on.status()).isEqualTo("SYNCING");

    var off = settings.update(datasetId, List.of());
    assertThat(off.enabled()).isFalse();
    assertThat(off.status()).isEqualTo("OFF");
    assertThat(states.find(datasetId)).isEmpty();
  }

  @Test
  void update_changingFieldsOnIdleIndex_showsSyncing() {
    settings.update(datasetId, List.of("content"));
    inTenantFixture(() -> { dsl.execute("UPDATE dataset_search_index SET status='IDLE' WHERE dataset_id=?", datasetId); });
    inTenantFixture(() -> { dsl.execute("UPDATE dataset_column SET data_type='TEXT' WHERE dataset_id=? AND column_name='cnt'", datasetId); });

    assertThat(settings.update(datasetId, List.of("content", "cnt")).status()).isEqualTo("SYNCING");
    // 같은 필드로 다시 저장하면 상태를 건드리지 않는다
    inTenantFixture(() -> { dsl.execute("UPDATE dataset_search_index SET status='IDLE' WHERE dataset_id=?", datasetId); });
    assertThat(settings.update(datasetId, List.of("content", "cnt")).status()).isEqualTo("IDLE");
  }

  @Test
  void update_changingFields_clearsBackoff_soSweepPicksItUpImmediately() {
    // 이전 동기화 실패로 백오프가 걸린 상태(ERROR, next_attempt_at 미래)에서 필드를 바꾸면, 사용자가 방금 확인한
    // "다시 색인"이 백오프만큼 밀리면 안 된다 — 다음 스윕 대상에 바로 들어가야 한다.
    settings.update(datasetId, List.of("content"));
    states.markFailed(datasetId, "boom", OffsetDateTime.now().plusHours(1));
    assertThat(states.findDueDatasetIds()).doesNotContain(datasetId);
    inTenantFixture(() -> { dsl.execute("UPDATE dataset_column SET data_type='TEXT' WHERE dataset_id=? AND column_name='cnt'", datasetId); });

    settings.update(datasetId, List.of("content", "cnt"));

    assertThat(states.findDueDatasetIds()).contains(datasetId);
    var s = states.find(datasetId).orElseThrow();
    assertThat(s.status()).isEqualTo("SYNCING");
    assertThat(s.consecutiveFailures()).isZero();
    assertThat(s.lastError()).isNull();
  }

  @Test
  void update_rejectsUnknownOrNonTextField() {
    assertThatThrownBy(() -> settings.update(datasetId, List.of("nope"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> settings.update(datasetId, List.of("cnt"))).hasMessageContaining("TEXT");
  }

  @Test
  void changingSearchableColumnTypeToNonText_isConflict() {
    settings.update(datasetId, List.of("content"));
    assertThatThrownBy(
            () -> datasetService.updateColumn(datasetId, contentColumnId,
                new UpdateColumnRequest(null, null, "INTEGER", null, null, null, null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("검색 탭");
  }

  @Test
  void deleteDataset_dropsIndexTable() {
    settings.update(datasetId, List.of("content"));
    IndexRef ref = new IndexRef(DEFAULT_TEST_TENANT_ID, datasetId, SRC);
    inTenantFixture(() -> index.recreate(ref, 1024));
    java.util.function.Supplier<Boolean> exists =
        () ->
            inTenantFixture(
                () ->
                    dsl.fetchOne(
                            "SELECT to_regclass(?) IS NOT NULL",
                            com.smartfirehub.global.tenant.DataSchema.qualify(ref.indexTable()))
                        .get(0, Boolean.class));
    assertThat(exists.get()).isTrue();

    datasetService.deleteDataset(datasetId);

    // 색인 테이블은 FK 가 없어 CASCADE 로 지워지지 않으므로 deleteDataset 이 직접 지워야 한다.
    assertThat(exists.get()).isFalse();
  }

  @Test
  void droppingLastSearchableColumn_turnsSearchOffImmediately() {
    // 검색 대상 필드(content)를 컬럼 삭제로 없애면 설정이 비게 된다 — 스윕을 기다리지 않고 그 자리에서 검색 끄기와
    // 같게 상태 행과 색인 테이블을 지운다(낡은 색인이 "사용 가능"으로 남아 검색되면 안 된다).
    settings.update(datasetId, List.of("content"));
    assertThat(sync.sync(datasetId)).isEqualTo(RowSearchSyncService.Outcome.COMPLETED);
    assertThat(indexTableExists()).isTrue();

    datasetService.deleteColumn(datasetId, contentColumnId);

    assertThat(states.find(datasetId)).isEmpty();
    assertThat(indexTableExists()).isFalse();
    assertThat(settings.getStatus(datasetId).status()).isEqualTo("OFF");
  }

  @Test
  void searchFieldsEmptyButStateLeft_syncDropsStaleIndex() {
    // 방어 검사: 상태 행·색인 테이블이 남았는데 검색 대상 필드만 비었으면(컬럼 삭제 외 경로) 상태 조회는 곧바로
    // OFF 로 보이고, 다음 스윕이 끄기와 같게 정리한다.
    settings.update(datasetId, List.of("content"));
    assertThat(sync.sync(datasetId)).isEqualTo(RowSearchSyncService.Outcome.COMPLETED);
    inTenantFixture(() -> { dsl.execute("UPDATE dataset_column SET is_searchable = false WHERE dataset_id = ?", datasetId); });

    assertThat(settings.getStatus(datasetId).enabled()).isFalse();
    assertThat(settings.getStatus(datasetId).status()).isEqualTo("OFF");

    assertThat(sync.sync(datasetId)).isEqualTo(RowSearchSyncService.Outcome.SKIPPED);

    assertThat(states.find(datasetId)).isEmpty();
    assertThat(indexTableExists()).isFalse();
  }

  /** 이 데이터셋의 색인 테이블이 현재 테넌트 데이터 스키마에 있는지. */
  private boolean indexTableExists() {
    IndexRef ref = new IndexRef(DEFAULT_TEST_TENANT_ID, datasetId, SRC);
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT to_regclass(?) IS NOT NULL",
                    com.smartfirehub.global.tenant.DataSchema.qualify(ref.indexTable()))
                .get(0, Boolean.class));
  }

  @Test
  void reindex_forcesFullPass() {
    settings.update(datasetId, List.of("content"));
    inTenantFixture(() -> { dsl.execute("UPDATE dataset_search_index SET config_hash='x', status='IDLE' WHERE dataset_id=?", datasetId); });
    settings.reindex(datasetId);
    var s = states.find(datasetId).orElseThrow();
    assertThat(s.configHash()).isEmpty();
    assertThat(s.status()).isEqualTo("SYNCING");
  }
}
