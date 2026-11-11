package com.smartfirehub.dataset.rowsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 행 검색 원본 리더({@link SearchSourceReader})를 실제 데이터 저장소로 검증한다.
 *
 * <p>원래 {@code SearchIndexStateRepositoryContractTest} 안에 있던 시나리오다. 그 계약을 Tibero 플랫폼 DB 에서도 돌리게
 * 되면서(WD-707) 데이터 저장소(PG) 전용인 리더 시험을 여기로 옮겼다 — 리더는 플랫폼 DB 와 무관하다.
 */
class SearchSourceReaderIntegrationTest extends IntegrationTestBase {

  @Autowired private SearchSourceReader reader;
  @Autowired private DataTableService dataTableService;
  @Autowired private DSLContext dsl;

  /** 데이터 저장소(data 스키마) 테이블 조작·검사용 — 분리 모드에서는 플랫폼 DB 와 다른 DB 다(WD-494). */
  @Autowired @DataStoreDb private DSLContext dataDsl;

  private static final String SRC = "rs_state_src";
  private Long userId;

  @BeforeEach
  void setUp() {
    // 이전 실행의 tearDown 실패로 남은 데이터셋·원본 테이블·사용자를 먼저 지운다(unique 위반 연쇄 방지).
    RowSearchTestSupport.cleanup(dsl, dataDsl, dataTableService, SRC);
    inTenantFixture(
        () -> {
          userId = TenantRlsTestSupport.insertUser(dsl, "rs_state_u");
          dsl.execute(
              "INSERT INTO dataset(name, table_name, storage_type, origin_type, created_by)"
                  + " VALUES ('rs state', ?, 'TABLE', 'SOURCE', ?)",
              SRC,
              userId);
          dataTableService.createTable(
              SRC,
              List.of(
                  new DatasetColumnRequest("title", "제목", "TEXT", null, true, false, null),
                  new DatasetColumnRequest("content", "내용", "TEXT", null, true, false, null),
                  new DatasetColumnRequest("cnt", "건수", "INTEGER", null, true, false, null)));
          dataDsl.execute(
              "INSERT INTO "
                  + DataSchema.qualify(SRC)
                  + " (title, content, cnt) VALUES ('t1','c1',1),('t2','c2',2),('t3','c3',3)");
          return null;
        });
  }

  @AfterEach
  void tearDown() {
    RowSearchTestSupport.cleanup(dsl, dataDsl, dataTableService, SRC);
    TenantRlsTestSupport.deleteUser(dsl, userId);
  }

  @Test
  @DisplayName("키셋 조회·OID·행 수·id 목록 조회가 실제 데이터 테이블에서 맞다")
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
  @DisplayName("책갈피 분기(_updated_at >= ?)도 실제 DB 에서 바인딩·비교된다")
  void reader_fetchChanged_withCursor_filtersByUpdatedAt() {
    var future =
        reader.fetchChanged(SRC, List.of("title"), OffsetDateTime.now().plusDays(1), 0L, 10);
    assertThat(future).isEmpty();
    var past =
        reader.fetchChanged(SRC, List.of("title"), OffsetDateTime.now().minusDays(1), 0L, 10);
    assertThat(past).extracting(SearchSourceReader.SourceRow::id).containsExactly(1L, 2L, 3L);
  }
}
