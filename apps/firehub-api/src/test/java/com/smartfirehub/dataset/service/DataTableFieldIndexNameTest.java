package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.smartfirehub.dataset.dto.CloneDatasetRequest;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.dto.UpdateColumnRequest;
import com.smartfirehub.dataset.service.PostgresDataTableStore.FieldIndexKind;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TestUsers;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * WD-100 — 데이터셋 필드 인덱스 이름이 63바이트에서 잘려 서로 다른 필드의 인덱스가 겹치던 결함.
 *
 * <p>예전 규칙 {@code idx_<table>_<column>} 은 테이블명이 길면 PostgreSQL 이 63바이트에서 잘라, 앞부분이 같은 두
 * 필드(ab_x·ab_y)가 같은 이름이 됐다. {@code CREATE INDEX IF NOT EXISTS} 는 두 번째를 조용히 건너뛰고(메타데이터는 둘 다 「인덱스
 * 있음」), {@code DROP INDEX IF EXISTS} 는 다른 필드의 인덱스를 지웠다. 여기서는 물리 인덱스를 카탈로그(pg_index)로 직접 세어 단언한다 —
 * 이름이 아니라 "그 컬럼에 실제로 인덱스가 있는가"를 본다.
 *
 * <p>클래스 레벨 {@code @Transactional}: DDL 이 트랜잭션에 묶이므로 테스트가 만든 테이블·인덱스는 롤백으로 사라진다.
 */
@Transactional
class DataTableFieldIndexNameTest extends IntegrationTestBase {

  @Autowired private DataTableService dataTableService;
  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  /** 결함 재현에 쓰인 55자 테이블명 — {@code idx_<table>_ab_x} 가 64자가 돼 63자에서 잘린다. */
  private static final String LONG_TABLE =
      "inspection_results_for_fire_station_equipment_region_se";

  private Long userId;

  @BeforeEach
  void setUp() {
    assertThat(LONG_TABLE).hasSize(55);
    userId =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                encoder,
                "wd100user",
                "wd100@example.com",
                "Password123",
                "WD100 User",
                DEFAULT_TEST_TENANT_ID)
            .id();
  }

  private static DatasetColumnRequest text(String name, boolean indexed) {
    return new DatasetColumnRequest(name, name, "TEXT", null, true, indexed, null);
  }

  private static DatasetColumnRequest geometry(String name, boolean indexed) {
    return new DatasetColumnRequest(name, name, "GEOMETRY", null, true, indexed, null);
  }

  /** 단일 컬럼 비유니크 인덱스 한 개(이름 + 접근 방법). */
  private record Idx(String name, String am) {}

  /**
   * 그 테이블의 그 컬럼 하나만 키로 갖는 비유니크 인덱스를 카탈로그에서 직접 센다. 제품 코드의 이름 규칙과 독립적으로 "실제로 인덱스가 몇 개 있는가"를 보기 위한
   * 것이다.
   */
  private List<Idx> indexesOn(String table, String column) {
    return dsl.fetch(
            "SELECT ic.relname::text AS n, am.amname::text AS am"
                + " FROM pg_index i"
                + " JOIN pg_class ic ON ic.oid = i.indexrelid"
                + " JOIN pg_class tc ON tc.oid = i.indrelid"
                + " JOIN pg_namespace ns ON ns.oid = tc.relnamespace"
                + " JOIN pg_am am ON am.oid = ic.relam"
                + " JOIN pg_attribute a ON a.attrelid = tc.oid AND a.attnum = i.indkey[0]"
                + " WHERE ns.nspname = ? AND tc.relname = ? AND a.attname = ?"
                + " AND i.indnatts = 1 AND NOT i.indisunique"
                + " ORDER BY ic.relname",
            DataSchema.current(),
            table,
            column)
        .map(r -> new Idx(r.get("n", String.class), r.get("am", String.class)));
  }

  /** 그 테이블의 인덱스 이름 전부(진단 메시지용). */
  private List<String> allIndexNames(String table) {
    return dsl.fetch(
            "SELECT indexname FROM pg_indexes WHERE schemaname = ? AND tablename = ? ORDER BY 1",
            DataSchema.current(),
            table)
        .getValues(0, String.class);
  }

  private boolean indexNameExists(String name) {
    return dsl.fetchValue(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = ? AND indexname = ?",
                DataSchema.current(),
                name)
            instanceof Number n
        && n.longValue() > 0;
  }

  /** 옛 규칙 그대로(잘림 포함) 인덱스를 직접 만든다 — 운영에 이미 있는 옛 인덱스를 흉내 낸다. */
  private String createLegacyIndex(String table, String column) {
    String legacyFull = "idx_" + table + "_" + column;
    dsl.execute(
        "CREATE INDEX \""
            + legacyFull
            + "\" ON "
            + DataSchema.qualify(table)
            + " (\""
            + column
            + "\")");
    return legacyFull.length() > 63 ? legacyFull.substring(0, 63) : legacyFull;
  }

  // ---------------------------------------------------------------------------------------------
  // ① 결함 재현 — 55자 테이블 + ab_x/ab_y
  // ---------------------------------------------------------------------------------------------

  @Test
  void 긴_테이블명의_두_필드는_인덱스가_각각_만들어지고_하나를_꺼도_다른_하나는_남는다() {
    dataTableService.createTable(LONG_TABLE, List.of(text("ab_x", true), text("ab_y", true)));

    List<Idx> x = indexesOn(LONG_TABLE, "ab_x");
    List<Idx> y = indexesOn(LONG_TABLE, "ab_y");
    assertThat(x).as("ab_x 인덱스 (전체 %s)", allIndexNames(LONG_TABLE)).hasSize(1);
    assertThat(y).as("ab_y 인덱스 (전체 %s)", allIndexNames(LONG_TABLE)).hasSize(1);
    // 규칙 이름이 63자를 넘으므로 고정 길이 해시 이름을 쓴다.
    assertThat(x.get(0).name())
        .isEqualTo(
            PostgresDataTableStore.hashedFieldIndexName(LONG_TABLE, "ab_x", FieldIndexKind.BTREE))
        .hasSizeLessThanOrEqualTo(63);
    assertThat(y.get(0).name()).isNotEqualTo(x.get(0).name());

    dataTableService.setColumnIndex(LONG_TABLE, "ab_y", false, "TEXT");

    assertThat(indexesOn(LONG_TABLE, "ab_y")).isEmpty();
    assertThat(indexesOn(LONG_TABLE, "ab_x")).as("ab_y 를 꺼도 ab_x 는 남아야 한다").hasSize(1);
  }

  @Test
  void 컬럼_추가와_인덱스_켜기도_긴_테이블에서_필드마다_인덱스를_만든다() {
    dataTableService.createTable(LONG_TABLE, List.of(text("ab_x", true)));
    dataTableService.addColumn(LONG_TABLE, text("ab_y", true));
    dataTableService.addColumn(LONG_TABLE, text("ab_z", false));
    dataTableService.setColumnIndex(LONG_TABLE, "ab_z", true, "TEXT");
    // 이미 있으면 다시 켜도 중복으로 만들지 않는다.
    dataTableService.setColumnIndex(LONG_TABLE, "ab_x", true, "TEXT");

    assertThat(indexesOn(LONG_TABLE, "ab_x")).hasSize(1);
    assertThat(indexesOn(LONG_TABLE, "ab_y")).hasSize(1);
    assertThat(indexesOn(LONG_TABLE, "ab_z")).hasSize(1);
  }

  // ---------------------------------------------------------------------------------------------
  // ② 하위 호환 — 운영에 이미 잘린 옛 이름으로 만들어진 인덱스
  // ---------------------------------------------------------------------------------------------

  @Test
  void 옛_잘린_이름_인덱스가_있어도_중복_생성하지_않고_다른_필드의_끄기에_지워지지_않는다() {
    dataTableService.createTable(LONG_TABLE, List.of(text("ab_x", false), text("ab_y", false)));
    String legacy = createLegacyIndex(LONG_TABLE, "ab_x");
    assertThat(legacy).hasSize(63);

    // 이미 그 컬럼에 (옛 이름으로) 있으므로 새로 만들지 않는다.
    dataTableService.setColumnIndex(LONG_TABLE, "ab_x", true, "TEXT");
    assertThat(indexesOn(LONG_TABLE, "ab_x")).extracting(Idx::name).containsExactly(legacy);

    // 옛 규칙이면 ab_y 의 이름도 잘려 legacy 와 같아져 IF NOT EXISTS 로 건너뛰었다.
    dataTableService.setColumnIndex(LONG_TABLE, "ab_y", true, "TEXT");
    assertThat(indexesOn(LONG_TABLE, "ab_y")).hasSize(1);

    // 옛 규칙이면 DROP INDEX IF EXISTS <잘린 이름> 이 ab_x 의 인덱스를 지웠다.
    dataTableService.setColumnIndex(LONG_TABLE, "ab_y", false, "TEXT");
    assertThat(indexesOn(LONG_TABLE, "ab_y")).isEmpty();
    assertThat(indexesOn(LONG_TABLE, "ab_x")).extracting(Idx::name).containsExactly(legacy);

    // 옛 이름 인덱스도 그 필드의 끄기로는 지워진다.
    dataTableService.setColumnIndex(LONG_TABLE, "ab_x", false, "TEXT");
    assertThat(indexesOn(LONG_TABLE, "ab_x")).isEmpty();
  }

  @Test
  void 옛_잘린_이름이_다른_필드의_63자_규칙_이름과_같아도_그_필드의_인덱스를_만든다() {
    // abcd 의 옛 이름(64자)이 잘리면 idx_<55자>_abc(63자) = 필드 abc 의 규칙 이름과 같다.
    dataTableService.createTable(LONG_TABLE, List.of(text("abcd", false)));
    String legacy = createLegacyIndex(LONG_TABLE, "abcd");
    assertThat(legacy)
        .isEqualTo(
            PostgresDataTableStore.preferredFieldIndexName(
                LONG_TABLE, "abc", FieldIndexKind.BTREE));

    dataTableService.addColumn(LONG_TABLE, text("abc", true));

    assertThat(indexesOn(LONG_TABLE, "abc"))
        .extracting(Idx::name)
        .containsExactly(
            PostgresDataTableStore.hashedFieldIndexName(LONG_TABLE, "abc", FieldIndexKind.BTREE));
    assertThat(indexesOn(LONG_TABLE, "abcd")).extracting(Idx::name).containsExactly(legacy);
  }

  // ---------------------------------------------------------------------------------------------
  // ③ 짧은 이름은 기존 이름 그대로
  // ---------------------------------------------------------------------------------------------

  @Test
  void 짧은_이름은_기존_규칙_이름을_그대로_쓴다() {
    dataTableService.createTable(
        "wd100_short", List.of(text("val", true), geometry("geo", false), geometry("geo2", true)));

    assertThat(indexesOn("wd100_short", "val"))
        .containsExactly(new Idx("idx_wd100_short_val", "btree"));
    assertThat(indexesOn("wd100_short", "geo"))
        .containsExactly(new Idx("idx_wd100_short_geo_gist", "gist"));
    assertThat(indexesOn("wd100_short", "geo2"))
        .containsExactly(new Idx("idx_wd100_short_geo2_gist", "gist"));

    dataTableService.setColumnIndex("wd100_short", "val", false, "TEXT");
    assertThat(indexNameExists("idx_wd100_short_val")).isFalse();
    dataTableService.setColumnIndex("wd100_short", "val", true, "TEXT");
    assertThat(indexesOn("wd100_short", "val"))
        .containsExactly(new Idx("idx_wd100_short_val", "btree"));
  }

  @Test
  void 이름이_겹치는_두_테이블의_인덱스가_서로를_지우지_않는다() {
    // idx_wd100_a_b + _c 와 idx_wd100_a + _b_c 는 63자 안에서도 같은 이름이다(인덱스 이름은 스키마 단위로 유일).
    dataTableService.createTable("wd100_a_b", List.of(text("c", true)));
    dataTableService.createTable("wd100_a", List.of(text("b_c", true)));

    assertThat(indexesOn("wd100_a_b", "c"))
        .extracting(Idx::name)
        .containsExactly("idx_wd100_a_b_c");
    assertThat(indexesOn("wd100_a", "b_c"))
        .extracting(Idx::name)
        .containsExactly(
            PostgresDataTableStore.hashedFieldIndexName("wd100_a", "b_c", FieldIndexKind.BTREE));

    dataTableService.setColumnIndex("wd100_a", "b_c", false, "TEXT");
    assertThat(indexesOn("wd100_a", "b_c")).isEmpty();
    assertThat(indexesOn("wd100_a_b", "c")).as("다른 테이블의 인덱스는 남는다").hasSize(1);
  }

  // ---------------------------------------------------------------------------------------------
  // ④ GEOMETRY(GiST)·PK
  // ---------------------------------------------------------------------------------------------

  @Test
  void 긴_테이블의_GEOMETRY_필드는_각각_GiST_인덱스를_갖고_끄기에도_GiST가_남는다() {
    dataTableService.createTable(
        LONG_TABLE, List.of(geometry("geo_a", true), geometry("geo_b", false)));

    assertThat(indexesOn(LONG_TABLE, "geo_a"))
        .containsExactly(
            new Idx(
                PostgresDataTableStore.hashedFieldIndexName(
                    LONG_TABLE, "geo_a", FieldIndexKind.GIST),
                "gist"));
    assertThat(indexesOn(LONG_TABLE, "geo_b")).extracting(Idx::am).containsExactly("gist");

    // GEOMETRY 는 항상 GiST — 인덱스를 꺼도 공간 인덱스는 남는다(끄면 공간 질의가 순차 스캔이 된다).
    dataTableService.setColumnIndex(LONG_TABLE, "geo_a", false, "GEOMETRY");
    assertThat(indexesOn(LONG_TABLE, "geo_a")).extracting(Idx::am).containsExactly("gist");
    assertThat(indexesOn(LONG_TABLE, "geo_b")).hasSize(1);

    // 다시 켜도 중복으로 만들지 않는다.
    dataTableService.setColumnIndex(LONG_TABLE, "geo_a", true, "GEOMETRY");
    assertThat(indexesOn(LONG_TABLE, "geo_a")).extracting(Idx::am).containsExactly("gist");
  }

  @Test
  void 짧은_이름의_GEOMETRY_필드도_인덱스를_끄면_GiST가_남는다() {
    dataTableService.createTable("wd100_geo", List.of(geometry("geo", true), text("val", true)));

    dataTableService.setColumnIndex("wd100_geo", "geo", false, "GEOMETRY");
    dataTableService.setColumnIndex("wd100_geo", "val", false, "TEXT");

    assertThat(indexesOn("wd100_geo", "geo"))
        .containsExactly(new Idx("idx_wd100_geo_geo_gist", "gist"));
    assertThat(indexesOn("wd100_geo", "val")).isEmpty();
  }

  @Test
  void 인덱스를_꺼도_PK_유니크_인덱스는_남는다() {
    dataTableService.createTable(
        LONG_TABLE,
        List.of(
            new DatasetColumnRequest("ab_x", "ab_x", "TEXT", null, false, true, null, true),
            text("ab_y", true)));
    String pkIndex = "ux_" + LONG_TABLE + "_pk";
    assertThat(indexNameExists(pkIndex)).isTrue();

    dataTableService.setColumnIndex(LONG_TABLE, "ab_x", false, "TEXT");

    assertThat(indexesOn(LONG_TABLE, "ab_x")).isEmpty();
    assertThat(indexNameExists(pkIndex)).as("PK 유니크 인덱스는 필드 인덱스가 아니다").isTrue();
    assertThat(indexesOn(LONG_TABLE, "ab_y")).hasSize(1);
  }

  // ---------------------------------------------------------------------------------------------
  // ⑤ 컬럼 이름 바꾸기
  // ---------------------------------------------------------------------------------------------

  @Test
  void 컬럼_이름을_바꾸면_인덱스_이름도_새_규칙으로_맞춘다() {
    DatasetDetailResponse ds =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "WD100 rename",
                LONG_TABLE,
                null,
                null,
                "TABLE",
                "SOURCE",
                List.of(text("ab_x", true), text("ab_y", true), geometry("geo", false)),
                null),
            userId);
    Long abX = columnId(ds, "ab_x");
    Long geo = columnId(ds, "geo");

    datasetService.updateColumn(
        ds.id(), abX, new UpdateColumnRequest("ab_z", null, null, null, null, null, null));
    // 인덱스를 켜지 않은 GEOMETRY 필드의 자동 GiST 도 함께 맞춘다.
    datasetService.updateColumn(
        ds.id(), geo, new UpdateColumnRequest("geo2", null, null, null, null, null, null));

    assertThat(indexesOn(LONG_TABLE, "ab_z"))
        .extracting(Idx::name)
        .containsExactly(
            PostgresDataTableStore.hashedFieldIndexName(LONG_TABLE, "ab_z", FieldIndexKind.BTREE));
    assertThat(indexesOn(LONG_TABLE, "ab_y")).hasSize(1);
    assertThat(indexesOn(LONG_TABLE, "geo2"))
        .extracting(Idx::name)
        .containsExactly(
            PostgresDataTableStore.hashedFieldIndexName(LONG_TABLE, "geo2", FieldIndexKind.GIST));
  }

  @Test
  void 새_인덱스_이름이_이미_쓰이고_있어도_컬럼_이름_바꾸기가_실패하지_않는다() {
    dataTableService.createTable("wd100_rn", List.of(text("p", true)));
    // 새 규칙 이름 idx_wd100_rn_q 를 다른 객체가 이미 쓰고 있다(옛 규칙이면 ALTER INDEX ... RENAME TO 가 42P07 → 500).
    dsl.execute("CREATE SEQUENCE " + DataSchema.qualify("idx_wd100_rn_q"));

    assertThatCode(
            () -> {
              dataTableService.renameIndex("wd100_rn", "p", "q");
              dataTableService.renameColumn("wd100_rn", "p", "q");
            })
        .doesNotThrowAnyException();

    assertThat(indexesOn("wd100_rn", "q"))
        .extracting(Idx::name)
        .containsExactly(
            PostgresDataTableStore.hashedFieldIndexName("wd100_rn", "q", FieldIndexKind.BTREE));
  }

  @Test
  void 인덱스가_이미_새_규칙_이름이면_자기_자신에_막혀_해시_이름으로_바뀌지_않는다() {
    dataTableService.createTable("wd100_self", List.of(text("val", true), geometry("geo", false)));

    // 새 이름 후보가 지금 자기 이름과 같다 — 자기 자신을 "사용 중"으로 세면 해시 이름으로 바뀐다.
    dataTableService.renameIndex("wd100_self", "val", "val");
    dataTableService.renameIndex("wd100_self", "geo", "geo");

    assertThat(indexesOn("wd100_self", "val"))
        .extracting(Idx::name)
        .containsExactly("idx_wd100_self_val");
    assertThat(indexesOn("wd100_self", "geo"))
        .extracting(Idx::name)
        .containsExactly("idx_wd100_self_geo_gist");
  }

  @Test
  void 옛_잘린_이름_인덱스도_컬럼_이름_바꾸기에서_새_이름으로_맞춘다() {
    dataTableService.createTable(LONG_TABLE, List.of(text("ab_x", false), text("ab_y", true)));
    createLegacyIndex(LONG_TABLE, "ab_x");

    dataTableService.renameIndex(LONG_TABLE, "ab_x", "q");
    dataTableService.renameColumn(LONG_TABLE, "ab_x", "q");

    // idx_<55자>_q = 61자 → 규칙 이름이 한도 안이다.
    assertThat(indexesOn(LONG_TABLE, "q"))
        .extracting(Idx::name)
        .containsExactly("idx_" + LONG_TABLE + "_q");
    assertThat(indexesOn(LONG_TABLE, "ab_y")).hasSize(1);
  }

  // ---------------------------------------------------------------------------------------------
  // ⑥ 테이블 재생성·복제·REPLACE 교체
  // ---------------------------------------------------------------------------------------------

  @Test
  void 같은_이름으로_테이블을_다시_만들어도_필드마다_인덱스가_있다() {
    dataTableService.createTable(LONG_TABLE, List.of(text("ab_x", true), text("ab_y", true)));
    // 고아 회수 DROP TABLE 이 인덱스도 함께 지우므로 다시 만들 때 이름이 비어 있다.
    dataTableService.createTable(LONG_TABLE, List.of(text("ab_x", true), text("ab_y", true)));

    assertThat(indexesOn(LONG_TABLE, "ab_x")).hasSize(1);
    assertThat(indexesOn(LONG_TABLE, "ab_y")).hasSize(1);
  }

  @Test
  void 데이터셋_복제는_긴_대상_테이블에서도_필드마다_인덱스를_만든다() {
    DatasetDetailResponse src =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "WD100 clone src",
                "wd100_clone_src",
                null,
                null,
                "TABLE",
                "SOURCE",
                List.of(text("ab_x", true), text("ab_y", true), geometry("geo", true)),
                null),
            userId);

    datasetService.cloneDataset(
        src.id(),
        new CloneDatasetRequest("WD100 clone tgt", LONG_TABLE, null, true, false),
        userId);

    assertThat(indexesOn(LONG_TABLE, "ab_x")).hasSize(1);
    assertThat(indexesOn(LONG_TABLE, "ab_y")).hasSize(1);
    // cloneTable 이 GiST 를 먼저 만들고 복제 후속 setColumnIndex 가 다시 켠다 — 중복으로 만들지 않는다.
    assertThat(indexesOn(LONG_TABLE, "geo")).extracting(Idx::am).containsExactly("gist");
  }

  @Test
  void REPLACE_교체_후에도_필드_인덱스를_찾아_끄고_켤_수_있다() {
    dataTableService.createTable(LONG_TABLE, List.of(text("ab_x", true), text("ab_y", true)));
    dataTableService.createTempTable(LONG_TABLE);
    dsl.execute(
        "INSERT INTO "
            + DataSchema.qualify(DataTableService.tempTableName(LONG_TABLE))
            + " (ab_x, ab_y) VALUES ('x', 'y')");
    dataTableService.swapTable(LONG_TABLE);

    // LIKE ... INCLUDING ALL 이 인덱스를 복사하며 PostgreSQL 기본 이름(rtmp_<md5>_..._idx)을 붙인다 — 규칙 이름이 사라진다.
    List<Idx> x = indexesOn(LONG_TABLE, "ab_x");
    assertThat(x).as("교체 후 인덱스 %s", allIndexNames(LONG_TABLE)).hasSize(1);
    assertThat(x.get(0).name()).startsWith(DataTableService.TEMP_TABLE_PREFIX);
    assertThat(indexesOn(LONG_TABLE, "ab_y")).hasSize(1);

    // 이름이 규칙과 달라도 카탈로그로 찾으므로 중복으로 만들지 않고, 끄면 그 필드 것만 지운다.
    dataTableService.setColumnIndex(LONG_TABLE, "ab_x", true, "TEXT");
    assertThat(indexesOn(LONG_TABLE, "ab_x")).hasSize(1);
    dataTableService.setColumnIndex(LONG_TABLE, "ab_y", false, "TEXT");
    assertThat(indexesOn(LONG_TABLE, "ab_y")).isEmpty();
    assertThat(indexesOn(LONG_TABLE, "ab_x")).hasSize(1);
    dataTableService.setColumnIndex(LONG_TABLE, "ab_y", true, "TEXT");
    assertThat(indexesOn(LONG_TABLE, "ab_y")).hasSize(1);
  }

  private static Long columnId(DatasetDetailResponse ds, String name) {
    return ds.columns().stream()
        .filter(c -> c.columnName().equals(name))
        .map(DatasetColumnResponse::id)
        .findFirst()
        .orElseThrow();
  }
}
