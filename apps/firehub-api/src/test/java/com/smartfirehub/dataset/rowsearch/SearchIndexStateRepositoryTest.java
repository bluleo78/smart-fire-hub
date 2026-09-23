package com.smartfirehub.dataset.rowsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 상태 저장소(임대·패스·백오프), 검색 필드 저장소, 원본 리더를 실제 DB 로 검증한다. */
class SearchIndexStateRepositoryTest extends IntegrationTestBase {

  @Autowired private SearchIndexStateRepository states;
  @Autowired private SearchColumnRepository searchColumns;
  @Autowired private SearchSourceReader reader;
  @Autowired private DataTableService dataTableService;
  @Autowired private DSLContext dsl;

  private long datasetId;
  private static final String SRC = "rs_state_src";
  private Long userId;

  @BeforeEach
  void setUp() {
    // 이전 실행의 tearDown 실패로 남은 데이터셋·원본 테이블·사용자를 먼저 지운다(unique 위반 연쇄 방지).
    RowSearchTestSupport.cleanup(dsl, dataTableService, SRC);
    datasetId =
        inTenantFixture(
            () -> {
              userId = TenantRlsTestSupport.insertUser(dsl, "rs_state_u");
              Long id =
                  dsl.fetchOne(
                          "INSERT INTO dataset(name, table_name, storage_type, origin_type, created_by)"
                              + " VALUES ('rs state', ?, 'TABLE', 'SOURCE', ?) RETURNING id",
                          SRC, userId)
                      .get(0, Long.class);
              dsl.execute(
                  "INSERT INTO dataset_column(dataset_id, column_name, display_name, data_type, is_nullable, is_indexed, column_order)"
                      + " VALUES (?, 'title', '제목', 'TEXT', true, false, 0), (?, 'content', '내용', 'TEXT', true, false, 1),"
                      + " (?, 'cnt', '건수', 'INTEGER', true, false, 2)",
                  id, id, id);
              dataTableService.createTable(
                  SRC,
                  List.of(
                      new DatasetColumnRequest("title", "제목", "TEXT", null, true, false, null),
                      new DatasetColumnRequest("content", "내용", "TEXT", null, true, false, null),
                      new DatasetColumnRequest("cnt", "건수", "INTEGER", null, true, false, null)));
              dsl.execute(
                  "INSERT INTO " + DataSchema.qualify(SRC) + " (title, content, cnt) VALUES ('t1','c1',1),('t2','c2',2),('t3','c3',3)");
              return id;
            });
  }

  @AfterEach
  void tearDown() {
    // dataset 삭제 시 dataset_search_index 는 CASCADE 로 함께 지워진다. 사용자는 dataset 을 지운 뒤에 지운다.
    RowSearchTestSupport.cleanup(dsl, dataTableService, SRC);
    TenantRlsTestSupport.deleteUser(dsl, userId);
  }

  @Test
  void setSearchable_findConfig_inColumnOrder() {
    searchColumns.setSearchable(datasetId, List.of("content", "title"));
    SearchConfig cfg = searchColumns.findConfig(datasetId);
    assertThat(cfg.columnNames()).containsExactly("title", "content"); // column_order 순
    searchColumns.setSearchable(datasetId, List.of("content"));
    assertThat(searchColumns.findConfig(datasetId).columnNames()).containsExactly("content");
  }

  @Test
  void lease_isExclusiveUntilReleased() {
    states.createIfAbsent(datasetId);
    assertThat(states.tryAcquireLease(datasetId, Duration.ofMinutes(10))).isTrue();
    assertThat(states.tryAcquireLease(datasetId, Duration.ofMinutes(10))).isFalse();
    states.releaseLease(datasetId);
    assertThat(states.tryAcquireLease(datasetId, Duration.ofMinutes(10))).isTrue();
  }

  @Test
  void pass_progress_complete_and_failure_backoff() {
    states.createIfAbsent(datasetId);
    states.resetForFullPass(datasetId, "hash", "fake", 1024, 42L);
    OffsetDateTime candidate = OffsetDateTime.now().minusSeconds(5);
    states.startPassIfNeeded(datasetId, candidate, 10L);
    states.startPassIfNeeded(datasetId, OffsetDateTime.now(), 99L); // 이미 시작된 패스는 후보·분모를 덮지 않는다
    states.saveProgress(datasetId, 2L, 2L);

    var mid = states.find(datasetId).orElseThrow();
    assertThat(mid.resumeAfterId()).isEqualTo(2L);
    assertThat(mid.totalRows()).isEqualTo(10L);
    assertThat(mid.indexedRows()).isEqualTo(2L);
    assertThat(mid.passCursor().toInstant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
        .isEqualTo(candidate.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));

    states.markCompleted(datasetId, 3, 3);
    var done = states.find(datasetId).orElseThrow();
    assertThat(done.status()).isEqualTo("IDLE");
    // 패스 시작 시 후보가 책갈피가 된다(PG 세션 오프셋과 JVM 오프셋이 다를 수 있어 Instant 로 비교)
    assertThat(done.syncCursor().toInstant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
        .isEqualTo(candidate.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    assertThat(done.resumeAfterId()).isNull();
    assertThat(done.passCursor()).isNull();

    states.markFailed(datasetId, "boom", OffsetDateTime.now().plusMinutes(5));
    var failed = states.find(datasetId).orElseThrow();
    assertThat(failed.status()).isEqualTo("ERROR");
    assertThat(failed.consecutiveFailures()).isEqualTo(1);
    assertThat(states.findDueDatasetIds()).doesNotContain(datasetId); // 백오프 중
  }

  @Test
  void reader_fetchChanged_keyset_oid_count_fetchRows() {
    var rows = reader.fetchChanged(SRC, List.of("title", "content"), null, 1L, 10);
    assertThat(rows).extracting(SearchSourceReader.SourceRow::id).containsExactly(2L, 3L);
    assertThat(rows.get(0).values()).containsEntry("title", "t2");
    assertThat(reader.currentOid(SRC)).isPositive();
    assertThat(reader.countRows(SRC)).isEqualTo(3);
    assertThat(reader.fetchRows(SRC, List.of("title", "cnt"), List.of(3L, 1L)))
        .containsOnlyKeys(1L, 3L)
        .extractingByKey(3L)
        // INTEGER 컬럼은 데이터 테이블에서 BIGINT 로 만들어지므로 Long 으로 읽힌다.
        .satisfies(m -> assertThat(m).containsEntry("title", "t3").containsEntry("cnt", 3L));
  }

  @Test
  void reader_fetchChanged_withCursor_filtersByUpdatedAt() {
    // 책갈피 분기(_updated_at >= ?)도 실제 DB 에서 바인딩·비교가 되는지 확인한다.
    var future = reader.fetchChanged(SRC, List.of("title"), OffsetDateTime.now().plusDays(1), 0L, 10);
    assertThat(future).isEmpty();
    var past = reader.fetchChanged(SRC, List.of("title"), OffsetDateTime.now().minusDays(1), 0L, 10);
    assertThat(past).extracting(SearchSourceReader.SourceRow::id).containsExactly(1L, 2L, 3L);
  }
}
