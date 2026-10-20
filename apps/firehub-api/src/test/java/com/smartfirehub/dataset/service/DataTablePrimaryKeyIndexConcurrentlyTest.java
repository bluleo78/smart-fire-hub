package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * WD-103 — CONCURRENTLY 경로({@code createPrimaryKeyIndexConcurrently})도 REPLACE 뒤 이름이 바뀐 옛 PK 유니크를
 * 카탈로그로 찾아 지우는지.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — {@code CREATE INDEX CONCURRENTLY} 는 트랜잭션 블록 안에서 실행할 수
 * 없다. 픽스처·정리·검증 조회만 {@link #inTenantFixture} 로 감싸고, 검증 대상 호출은 그 밖(autocommit)에서 부른다.
 */
class DataTablePrimaryKeyIndexConcurrentlyTest extends IntegrationTestBase {

  @Autowired private DataTableService dataTableService;
  @Autowired private DataTableRowService dataTableRowService;
  @Autowired private DSLContext dsl;

  private static final String TABLE = "wd103_pk_cic";

  /**
   * 트랜잭션 롤백이 없는 테스트라 직접 지운다 — 원본과 REPLACE 임시 테이블(rtmp_*, 테스트가 맞바꿈 전에 실패하면 남는다)까지. 남기면 같은 JVM 의 공유
   * 테스트 DB 를 오염시킨다.
   */
  @AfterEach
  void dropTable() {
    inTenantFixture(
        () -> {
          dataTableService.dropTempTable(TABLE);
          dataTableService.dropTable(TABLE);
        });
  }

  private void createPkTable() {
    inTenantFixture(
        () ->
            dataTableService.createTable(
                TABLE,
                List.of(
                    new DatasetColumnRequest(
                        "ab_x", "ab_x", "TEXT", null, false, false, null, true),
                    new DatasetColumnRequest(
                        "ab_y", "ab_y", "TEXT", null, false, false, null, false))));
  }

  /** 그 테이블의 비-primary 유니크 인덱스 (키 컬럼 → VALID 여부). */
  private Map<String, Boolean> uniqueValidity() {
    return inTenantFixture(
        () ->
            dsl.fetch(
                    "SELECT (SELECT string_agg(a.attname::text, ',' ORDER BY a.attname)"
                        + "   FROM pg_attribute a WHERE a.attrelid = tc.oid"
                        + "   AND a.attnum = ANY(i.indkey::int2[])) AS cols, i.indisvalid AS v"
                        + " FROM pg_index i"
                        + " JOIN pg_class tc ON tc.oid = i.indrelid"
                        + " JOIN pg_namespace ns ON ns.oid = tc.relnamespace"
                        + " WHERE ns.nspname = ? AND tc.relname = ?"
                        + " AND i.indisunique AND NOT i.indisprimary",
                    DataSchema.current(),
                    TABLE)
                .intoMap(r -> r.get("cols", String.class), r -> r.get("v", Boolean.class)));
  }

  /** 비-primary 유니크 인덱스의 키 컬럼 집합들(정렬된 "a,b"). 시스템 PK 는 따로 센다. */
  private List<String> uniqueColumnSets(boolean primary) {
    return inTenantFixture(
        () ->
            dsl.fetch(
                    "SELECT (SELECT string_agg(a.attname::text, ',' ORDER BY a.attname)"
                        + "   FROM pg_attribute a WHERE a.attrelid = tc.oid"
                        + "   AND a.attnum = ANY(i.indkey::int2[])) AS cols"
                        + " FROM pg_index i"
                        + " JOIN pg_class tc ON tc.oid = i.indrelid"
                        + " JOIN pg_namespace ns ON ns.oid = tc.relnamespace"
                        + " WHERE ns.nspname = ? AND tc.relname = ?"
                        + " AND i.indisunique AND i.indisprimary = ?"
                        + " ORDER BY 1",
                    DataSchema.current(),
                    TABLE,
                    primary)
                .getValues(0, String.class));
  }

  @Test
  void REPLACE_뒤_CONCURRENTLY로_PK를_바꾸면_옛_PK_유니크가_지워진다() {
    createPkTable();
    inTenantFixture(
        () -> {
          dataTableService.createTempTable(TABLE);
          dsl.execute(
              "INSERT INTO "
                  + DataSchema.qualify(DataTableService.tempTableName(TABLE))
                  + " (ab_x, ab_y) VALUES ('k1', 'a')");
          dataTableService.finishReplace(TABLE, 1);
        });
    assertThat(uniqueColumnSets(false)).containsExactly("ab_x");

    // 검증 대상 — 트랜잭션 밖(autocommit)에서 부른다.
    dataTableService.createPrimaryKeyIndexConcurrently(TABLE, List.of("ab_y"));

    assertThat(uniqueColumnSets(false)).containsExactly("ab_y");
    assertThat(uniqueColumnSets(true)).as("시스템 PK 보존").containsExactly("id");
    assertThat(
            inTenantFixture(
                () ->
                    dsl.fetchValue(
                        "SELECT count(*) FROM pg_indexes WHERE schemaname = ? AND indexname = ?",
                        DataSchema.current(),
                        "ux_" + TABLE + "_pk")))
        .as("REPLACE 뒤 옛 유니크는 rtmp_* 이름이라 규칙 이름이 비어 있어 그것을 쓴다")
        .isEqualTo(1L);
  }

  /**
   * 새 PK 컬럼에 중복값이 있어 CONCURRENTLY 빌드가 실패해도 옛 PK 유니크가 유효하게 남아야 한다 — 옛 것을 먼저 지우던 순서에서는 이 시점에 PK 유니크가
   * 하나도 없어 옛 PK 중복이 그대로 들어갔다. 이어서 데이터를 고치고 다시 부르면 실패가 남긴 INVALID 인덱스가 정리돼 새 PK 유니크 하나만 남는다.
   */
  @Test
  void CONCURRENTLY_빌드가_실패해도_옛_PK_유니크는_유효하게_남고_다음_호출이_잔재를_정리한다() {
    createPkTable();
    inTenantFixture(
        () ->
            dsl.execute(
                "INSERT INTO "
                    + DataSchema.qualify(TABLE)
                    + " (ab_x, ab_y) VALUES ('k1', 'dup'), ('k2', 'dup')"));

    // 검증 대상 — ab_y 에 중복값이 있어 유니크 빌드가 실패한다.
    assertThatThrownBy(
            () -> dataTableService.createPrimaryKeyIndexConcurrently(TABLE, List.of("ab_y")))
        .hasMessageContaining("could not create unique index");

    assertThat(uniqueValidity())
        .as("옛 PK(ab_x) 유니크는 VALID 로 남고, 실패한 새 인덱스는 INVALID 로 남는다")
        .containsEntry("ab_x", true)
        .containsEntry("ab_y", false);
    assertThatThrownBy(
            () ->
                dataTableRowService.insertBatch(
                    TABLE,
                    List.of("ab_x", "ab_y"),
                    List.of(Map.of("ab_x", "k1", "ab_y", "z")),
                    Map.of()))
        .as("옛 PK 중복은 여전히 거부된다")
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);

    // 데이터를 고치고 다시 부르면 INVALID 잔재가 1단계에서 정리되고, 성공 뒤 옛 PK 유니크도 지워진다.
    inTenantFixture(() -> dsl.execute("UPDATE " + DataSchema.qualify(TABLE) + " SET ab_y = ab_x"));
    dataTableService.createPrimaryKeyIndexConcurrently(TABLE, List.of("ab_y"));

    assertThat(uniqueValidity()).containsExactly(Map.entry("ab_y", true));
    assertThat(uniqueColumnSets(true)).as("시스템 PK 보존").containsExactly("id");
  }
}
