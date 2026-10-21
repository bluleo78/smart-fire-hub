package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.dto.UpdateColumnRequest;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TestUsers;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * WD-103 — REPLACE 적재(createTempTable → swapTable)를 거친 데이터셋에서 PK 를 해제·변경해도 옛 PK 유니크 인덱스가 남던 결함.
 *
 * <p>{@code LIKE ... INCLUDING ALL} 은 인덱스 이름을 복사하지 않고 기본 이름({@code rtmp_<난수>_<md5>_<col>_idx}, 길면
 * 앞부분)을 붙인다. 맞바꿈 뒤 {@code ux_<table>_pk} 이름이 사라지는데, 예전 코드는 그 이름으로만 지워서 옛 PK 유니크를 영영 지우지 못했다. 여기서는
 * 유니크 인덱스를 카탈로그(pg_index)로 직접 세고, 사용자 현상(같은 값 삽입 성공·거부)으로도 단언한다 — 이름이 아니라 "실제로 어떤 컬럼 집합에 유니크가 걸려
 * 있는가"를 본다.
 *
 * <p>클래스 레벨 {@code @Transactional}: DDL 이 트랜잭션에 묶이므로 테스트가 만든 테이블·인덱스는 롤백으로 사라진다. 중복 거부 단언은 트랜잭션을
 * 중단(abort)시키므로 각 테스트의 마지막에만 둔다.
 */
@Transactional
class DataTablePrimaryKeyIndexAfterReplaceTest extends IntegrationTestBase {

  @Autowired private DataTableService dataTableService;
  @Autowired private PostgresDataTableStore tableStore;
  @Autowired private DataTableRowService dataTableRowService;
  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  private static final String TABLE = "wd103_pk";

  private Long userId;

  @BeforeEach
  void setUp() {
    userId =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                encoder,
                "wd103user",
                "wd103@example.com",
                "Password123",
                "WD103 User",
                DEFAULT_TEST_TENANT_ID)
            .id();
  }

  // ---------------------------------------------------------------------------------------------
  // 헬퍼
  // ---------------------------------------------------------------------------------------------

  private static DatasetColumnRequest pkText(String name, boolean pk) {
    return new DatasetColumnRequest(name, name, "TEXT", null, false, false, null, pk);
  }

  /** 카탈로그에서 본 유니크 인덱스 한 개 — 이름, 키 컬럼(정렬), PRIMARY KEY 여부. */
  private record Unique(String name, String columns, boolean primary) {}

  /** 그 테이블의 유니크 인덱스 전부를 카탈로그에서 직접 읽는다 — 제품 코드의 식별 기준과 독립적으로 "실제로 무엇이 걸려 있는가"를 본다. */
  private List<Unique> uniques(String table) {
    return dsl.fetch(
            "SELECT ic.relname::text AS n, i.indisprimary AS p,"
                + " (SELECT string_agg(a.attname::text, ',' ORDER BY a.attname)"
                + "    FROM pg_attribute a WHERE a.attrelid = tc.oid AND a.attnum = ANY(i.indkey::int2[]))"
                + "   AS cols"
                + " FROM pg_index i"
                + " JOIN pg_class ic ON ic.oid = i.indexrelid"
                + " JOIN pg_class tc ON tc.oid = i.indrelid"
                + " JOIN pg_namespace ns ON ns.oid = tc.relnamespace"
                + " WHERE ns.nspname = ? AND tc.relname = ? AND i.indisunique"
                + " ORDER BY ic.relname",
            DataSchema.current(),
            table)
        .map(
            r ->
                new Unique(
                    r.get("n", String.class),
                    r.get("cols", String.class),
                    r.get("p", Boolean.class)));
  }

  /** 비-primary 유니크 인덱스의 키 컬럼 집합들(정렬된 "a,b" 문자열). */
  private List<String> nonPrimaryUniqueColumnSets(String table) {
    return uniques(table).stream().filter(u -> !u.primary()).map(Unique::columns).toList();
  }

  /** 시스템 PK(id, indisprimary)가 정확히 하나 남아 있는지 — 이름은 보지 않는다(REPLACE 뒤에는 rtmp_<md5>_pkey). */
  private void assertSystemPkeyKept(String table) {
    assertThat(uniques(table).stream().filter(Unique::primary).map(Unique::columns).toList())
        .as("시스템 PK (전체 %s)", uniques(table))
        .containsExactly("id");
  }

  /** REPLACE 적재 — 파이프라인(SQL/API_CALL, loadStrategy=REPLACE)과 같은 순서로 임시 테이블에 적재하고 맞바꾼다. */
  private void replaceLoad(String table, String... abXabY) {
    String tmp = dataTableService.createTempTable(table);
    for (int i = 0; i < abXabY.length; i += 2) {
      dsl.execute(
          "INSERT INTO " + DataSchema.qualify(tmp) + " (ab_x, ab_y) VALUES (?, ?)",
          abXabY[i],
          abXabY[i + 1]);
    }
    dataTableService.finishReplace(table, tmp, abXabY.length / 2);
  }

  private void insertRow(String table, String abX, String abY) {
    dataTableRowService.insertBatch(
        table, List.of("ab_x", "ab_y"), List.of(Map.of("ab_x", abX, "ab_y", abY)), Map.of());
  }

  private DatasetDetailResponse createDataset(String table, boolean pkX, boolean pkY) {
    return datasetService.createDataset(
        new CreateDatasetRequest(
            "WD103 " + table,
            table,
            null,
            null,
            "TABLE",
            "SOURCE",
            List.of(pkText("ab_x", pkX), pkText("ab_y", pkY)),
            null),
        userId);
  }

  private static Long columnId(DatasetDetailResponse ds, String column) {
    return ds.columns().stream()
        .filter(c -> c.columnName().equals(column))
        .findFirst()
        .orElseThrow()
        .id();
  }

  // ---------------------------------------------------------------------------------------------
  // ① 결함 재현 — REPLACE 뒤 PK 해제·변경
  // ---------------------------------------------------------------------------------------------

  @Test
  void REPLACE_뒤_PK를_해제하면_유니크가_모두_사라지고_같은_값을_넣을_수_있다() {
    DatasetDetailResponse ds = createDataset(TABLE, true, false);
    replaceLoad(TABLE, "k1", "a");
    // 전제: 맞바꿈 뒤 옛 PK 유니크는 ux_<table>_pk 가 아닌 이름으로 남아 있다(결함 조건).
    assertThat(uniques(TABLE)).noneMatch(u -> u.name().equals("ux_" + TABLE + "_pk"));
    assertThat(nonPrimaryUniqueColumnSets(TABLE)).containsExactly("ab_x");

    datasetService.updateColumn(
        ds.id(),
        columnId(ds, "ab_x"),
        new UpdateColumnRequest(null, null, null, null, null, null, null, false));

    assertThat(nonPrimaryUniqueColumnSets(TABLE)).as("전체 %s", uniques(TABLE)).isEmpty();
    assertSystemPkeyKept(TABLE);
    insertRow(TABLE, "k1", "b"); // 예전에는 rtmp_<md5>_ab_x_idx 로 「중복 키」 거부
    assertThat(dataTableRowService.countRows(TABLE)).isEqualTo(2);
  }

  @Test
  void REPLACE_뒤_PK를_다른_필드로_바꾸면_새_PK_유니크_하나만_걸린다() {
    DatasetDetailResponse ds = createDataset(TABLE, true, false);
    replaceLoad(TABLE, "k1", "a");

    datasetService.updatePrimaryKeys(ds.id(), List.of(columnId(ds, "ab_y")));

    assertThat(nonPrimaryUniqueColumnSets(TABLE))
        .as("전체 %s", uniques(TABLE))
        .containsExactly("ab_y");
    assertThat(uniques(TABLE))
        .as("옛 PK 유니크가 지워져 규칙 이름이 다시 비었으므로 규칙 이름을 쓴다")
        .anyMatch(u -> u.name().equals("ux_" + TABLE + "_pk") && u.columns().equals("ab_y"));
    assertSystemPkeyKept(TABLE);
    insertRow(TABLE, "k1", "b"); // 옛 PK(ab_x) 중복은 허용
    assertThatThrownBy(() -> insertRow(TABLE, "k2", "b")) // 새 PK(ab_y) 중복은 거부
        .isInstanceOf(DuplicateKeyException.class);
  }

  // ---------------------------------------------------------------------------------------------
  // ② 운영 잔존 — REPLACE 후 옛 코드로 PK 를 바꿔 유니크가 두 개 걸린 상태
  // ---------------------------------------------------------------------------------------------

  /**
   * 옛 코드가 REPLACE 이후 PK 를 ab_x → ab_y 로 바꾼 상태를 SQL 로 만든다: 옛 ab_x 유니크(rtmp_..._ab_x_idx)는 남고, 옛 코드가
   * ux_<table>_pk 를 ab_y 로 새로 만들었으며, 메타데이터 PK 는 ab_y 다.
   */
  private DatasetDetailResponse legacyResidualState() {
    DatasetDetailResponse ds = createDataset(TABLE, true, false);
    replaceLoad(TABLE, "k1", "a");
    dsl.execute(
        "CREATE UNIQUE INDEX \"ux_" + TABLE + "_pk\" ON " + DataSchema.qualify(TABLE) + " (ab_y)");
    dsl.execute(
        "UPDATE dataset_column SET is_primary_key = (column_name = 'ab_y') WHERE dataset_id = ?",
        ds.id());
    assertThat(nonPrimaryUniqueColumnSets(TABLE)).containsExactlyInAnyOrder("ab_x", "ab_y");
    return ds;
  }

  @Test
  void 옛_PK_잔존_유니크가_있는_상태에서_PK를_다시_바꾸면_잔존이_정리된다() {
    DatasetDetailResponse ds = legacyResidualState();

    datasetService.updatePrimaryKeys(ds.id(), List.of(columnId(ds, "ab_x")));

    assertThat(nonPrimaryUniqueColumnSets(TABLE))
        .as("전체 %s", uniques(TABLE))
        .containsExactly("ab_x");
    assertSystemPkeyKept(TABLE);
  }

  @Test
  void 옛_PK_잔존_유니크는_같은_PK를_다시_저장해도_정리된다() {
    // 배포 후 운영 잔존 정리 수단 — 마이그레이션 없이 PK 저장(updatePrimaryKeys)을 같은 값으로 다시 하면 된다.
    DatasetDetailResponse ds = legacyResidualState();

    datasetService.updatePrimaryKeys(ds.id(), List.of(columnId(ds, "ab_y")));

    assertThat(nonPrimaryUniqueColumnSets(TABLE))
        .as("전체 %s", uniques(TABLE))
        .containsExactly("ab_y");
    assertSystemPkeyKept(TABLE);
    insertRow(TABLE, "k1", "b"); // 잔존 ab_x 유니크가 사라졌으므로 성공
  }

  // ---------------------------------------------------------------------------------------------
  // ③ 복합 PK · REPLACE 2회 · 긴 이름 · 이름 충돌
  // ---------------------------------------------------------------------------------------------

  @Test
  void 복합_PK도_REPLACE_뒤_한_필드만_남기면_그_필드_유니크_하나만_남는다() {
    DatasetDetailResponse ds = createDataset(TABLE, true, true);
    replaceLoad(TABLE, "k1", "a", "k1", "b");
    assertThat(nonPrimaryUniqueColumnSets(TABLE)).containsExactly("ab_x,ab_y");

    datasetService.updatePrimaryKeys(ds.id(), List.of(columnId(ds, "ab_y")));

    assertThat(nonPrimaryUniqueColumnSets(TABLE))
        .as("전체 %s", uniques(TABLE))
        .containsExactly("ab_y");
    assertSystemPkeyKept(TABLE);
    insertRow(TABLE, "k1", "c"); // ab_x 단독 중복은 허용
  }

  @Test
  void REPLACE를_두_번_연속_거쳐도_PK_해제가_유니크를_모두_지운다() {
    // 두 번째 LIKE 는 원본에 아직 남은 rtmp_<md5>_ab_x_idx 와 이름이 겹쳐 PostgreSQL 이 숫자 접미사(..._idx1)를 붙인다.
    DatasetDetailResponse ds = createDataset(TABLE, true, false);
    replaceLoad(TABLE, "k1", "a");
    replaceLoad(TABLE, "k2", "b");
    assertThat(nonPrimaryUniqueColumnSets(TABLE)).containsExactly("ab_x");

    datasetService.updateColumn(
        ds.id(),
        columnId(ds, "ab_x"),
        new UpdateColumnRequest(null, null, null, null, null, null, null, false));

    assertThat(nonPrimaryUniqueColumnSets(TABLE)).as("전체 %s", uniques(TABLE)).isEmpty();
    assertSystemPkeyKept(TABLE);
    insertRow(TABLE, "k2", "c");
  }

  @Test
  void 최대_길이_테이블명은_규칙_이름이_잘리지_않는다() {
    // 새 테이블명 상한 56자 → ux_<table>_pk 는 62자로 63바이트 안이다.
    String table = "wd103_" + "a".repeat(DataTableService.MAX_TABLE_NAME_LENGTH - 6);
    assertThat(table).hasSize(56);
    DatasetDetailResponse ds = createDataset(table, true, false);
    replaceLoad(table, "k1", "a");

    datasetService.updatePrimaryKeys(ds.id(), List.of(columnId(ds, "ab_y")));

    assertThat(uniques(table).stream().filter(u -> !u.primary()).toList())
        .containsExactly(new Unique("ux_" + table + "_pk", "ab_y", false));
    assertSystemPkeyKept(table);
  }

  @Test
  void WD95_이전의_긴_테이블명은_해시_이름을_쓰고_REPLACE_뒤에도_정리된다() {
    // 60자 테이블(WD-95 이전 생성분 흉내) — ux_<table>_pk 가 66자라 63바이트에서 잘린다. 파사드는 56자를 넘는 새 이름을 막으므로 저장소에 직접
    // 만든다.
    String table = "wd103_" + "b".repeat(54);
    assertThat(table).hasSize(60);
    tableStore.createTable(table, List.of(pkText("ab_x", true), pkText("ab_y", false)));
    String hashed = PostgresDataTableStore.hashedPrimaryKeyIndexName(table);
    assertThat(uniques(table).stream().filter(u -> !u.primary()).toList())
        .containsExactly(new Unique(hashed, "ab_x", false));

    replaceLoad(table, "k1", "a");
    dataTableService.recreatePrimaryKeyIndex(table, List.of("ab_y"));

    assertThat(uniques(table).stream().filter(u -> !u.primary()).toList())
        .as("전체 %s", uniques(table))
        .containsExactly(new Unique(hashed, "ab_y", false));
    assertSystemPkeyKept(table);
  }

  @Test
  void 규칙_이름을_다른_테이블이_쓰고_있어도_PK_유니크를_만든다() {
    // 사용자 테이블 "ux_wd103_pk_pk" 가 있으면 예전 createTable("wd103_pk") 은 「relation already exists」 로 실패했다.
    String clash = "ux_" + TABLE + "_pk";
    dataTableService.createTable(clash, List.of(pkText("v", false)));

    createDataset(TABLE, true, false);

    assertThat(uniques(TABLE).stream().filter(u -> !u.primary()).toList())
        .containsExactly(
            new Unique(PostgresDataTableStore.hashedPrimaryKeyIndexName(TABLE), "ab_x", false));
  }
}
