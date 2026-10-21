package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.smartfirehub.dataset.dto.AddColumnRequest;
import com.smartfirehub.dataset.dto.CloneDatasetRequest;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.dto.UpdateColumnRequest;
import com.smartfirehub.dataset.exception.InvalidTableNameException;
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
 * WD-95 — 테이블명·컬럼명 길이 상한(테이블 56자, 컬럼 63자).
 *
 * <p>PostgreSQL 은 식별자를 63바이트에서 조용히 자른다. 상한이 없을 때는 앞 63자가 같은 68자 이름으로 데이터셋 B 를 만들면 {@code
 * DataTableService.createTable} 의 고아 회수 {@code DROP TABLE IF EXISTS} 가 잘린 이름 = A 의 물리 테이블을 지웠다(중복
 * 검사는 원래 문자열로 비교해 통과). 여기서는 68자 A 데이터셋을 만들 수 없으므로(이제 거부된다) <b>A 가 남겼을 바로 그 물리 테이블(63자)</b>을 DSL 로
 * 직접 만들어 행을 넣고, 긴 이름 생성이 거부되며 그 행들이 그대로인지 행 단위로 단언한다 — 길이 검사를 지우면 DROP 이 실제로 일어나 이 단언이 깨진다.
 *
 * <p>클래스 레벨 {@code @Transactional}: PostgreSQL DDL 은 트랜잭션에 묶이므로 테스트가 만든 테이블은 롤백으로 사라진다. 실패한 요청의
 * DROP 도 같은 트랜잭션 안에서는 보이므로 "DROP 이 일어났는가"를 그대로 관찰할 수 있다.
 */
@Transactional
class DatasetNameLengthTest extends IntegrationTestBase {

  @Autowired private DataTableService dataTableService;
  @Autowired private DataTableRowService dataTableRowService;
  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  private Long userId;

  private static final List<DatasetColumnRequest> ONE_COLUMN =
      List.of(new DatasetColumnRequest("val", "Val", "TEXT", null, true, false, null));

  @BeforeEach
  void setUp() {
    userId =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                encoder,
                "wd95user",
                "wd95@example.com",
                "Password123",
                "WD95 User",
                DEFAULT_TEST_TENANT_ID)
            .id();
  }

  /** {@code prefix} 뒤를 'x' 로 채워 정확히 {@code length} 자인 이름을 만든다. */
  private static String nameOf(String prefix, int length) {
    return prefix + "x".repeat(length - prefix.length());
  }

  /**
   * 데이터셋 카탈로그에 없는 물리 테이블을 DSL 로 직접 만들고 행 두 개를 넣는다 — 운영에 이미 있는 긴 이름 테이블, 또는 68자 데이터셋이 남긴 잘린 이름의 물리
   * 테이블을 흉내 낸다.
   */
  private void createPhysicalTableWithRows(String tableName) {
    dsl.execute(
        "CREATE TABLE "
            + DataSchema.qualify(tableName)
            + " (id BIGSERIAL PRIMARY KEY, import_id BIGINT, val TEXT,"
            + " created_at TIMESTAMP DEFAULT NOW(), _updated_at TIMESTAMPTZ NOT NULL DEFAULT now())");
    dsl.execute(
        "INSERT INTO " + DataSchema.qualify(tableName) + " (val) VALUES ('alpha'), ('beta')");
  }

  /** 테이블의 val 값을 id 순으로 읽는다 — 행 단위 보존 단언용. */
  private List<String> valuesOf(String tableName) {
    return dsl.fetch("SELECT val FROM " + DataSchema.qualify(tableName) + " ORDER BY id")
        .getValues(0, String.class);
  }

  /** count(*) 질의 결과를 Long 으로 읽는다. */
  private Long countOf(String sql, Object... binds) {
    return ((Number) dsl.fetchValue(sql, binds)).longValue();
  }

  private boolean physicalTableExists(String tableName) {
    return dsl.fetchValue("SELECT to_regclass(?) IS NOT NULL", DataSchema.qualify(tableName))
        .equals(Boolean.TRUE);
  }

  private DatasetDetailResponse createDataset(String tableName, List<DatasetColumnRequest> cols) {
    return datasetService.createDataset(
        new CreateDatasetRequest(
            "WD95 " + tableName, tableName, null, null, "TABLE", "SOURCE", cols, null),
        userId);
  }

  // ---------------------------------------------------------------------------------------------
  // ① 테이블명 56/57 경계
  // ---------------------------------------------------------------------------------------------

  @Test
  void createTable_56자_테이블명은_성공하고_파생_인덱스명도_잘리지_않는다() {
    String name = nameOf("wd95_ok_", DataTableService.MAX_TABLE_NAME_LENGTH);
    assertThat(name).hasSize(56);

    dataTableService.createTable(name, ONE_COLUMN);

    assertThat(physicalTableExists(name)).isTrue();
    // 가장 긴 파생 이름 ix_<table>_upd(63자)가 잘리지 않고 그대로 존재해야 V125 규약과 맞는다.
    String updIndex = "ix_" + name + "_upd";
    assertThat(updIndex).hasSize(63);
    Long idx =
        countOf(
            "SELECT count(*) FROM pg_indexes WHERE schemaname = ? AND indexname = ?",
            DataSchema.current(),
            updIndex);
    assertThat(idx).isEqualTo(1L);
  }

  @Test
  void createTable_57자_테이블명은_거부되고_같은_이름의_기존_물리테이블을_DROP하지_않는다() {
    String name = nameOf("wd95_ng_", DataTableService.MAX_TABLE_NAME_LENGTH + 1);
    assertThat(name).hasSize(57);
    // 같은 이름의 물리 테이블(카탈로그 없음)이 있으면 고아 회수 DROP 의 대상이 된다 — 거부가 DROP 보다 먼저인지 본다.
    createPhysicalTableWithRows(name);

    Throwable thrown = catchThrowable(() -> dataTableService.createTable(name, ONE_COLUMN));

    // 행 보존을 먼저 단언한다 — 길이 검사가 사라지면 DROP 후 빈 테이블이 다시 만들어져 여기서 깨진다.
    assertThat(valuesOf(name)).containsExactly("alpha", "beta");
    assertThat(thrown)
        .isInstanceOf(InvalidTableNameException.class)
        .hasMessage("테이블명은 최대 56자까지 쓸 수 있습니다(입력 57자)");
  }

  // ---------------------------------------------------------------------------------------------
  // ② 결함 재현 — 앞 63자가 같은 긴 이름이 기존 테이블을 지우지 못한다
  // ---------------------------------------------------------------------------------------------

  @Test
  void createTable_앞63자가_같은_68자_이름은_거부되고_잘린_이름의_기존테이블이_보존된다() {
    // 68자 데이터셋 A 가 남겼을 물리 테이블 = 앞 63자로 잘린 이름.
    String truncated = nameOf("wd95_a_", 63);
    String longName = truncated + "_tail";
    assertThat(longName).hasSize(68);
    createPhysicalTableWithRows(truncated);

    Throwable thrown = catchThrowable(() -> dataTableService.createTable(longName, ONE_COLUMN));

    assertThat(valuesOf(truncated)).containsExactly("alpha", "beta");
    assertThat(thrown).isInstanceOf(InvalidTableNameException.class).hasMessageContaining("입력 68자");
  }

  @Test
  void createDataset_앞63자가_같은_68자_이름은_중복검사를_통과해도_거부되고_기존테이블이_보존된다() {
    String truncated = nameOf("wd95_b_", 63);
    String longName = truncated + "_tail";
    createPhysicalTableWithRows(truncated);

    // 실제 결함 경로: existsByTableName(68자 문자열) 은 통과한다 → save → createTable 의 DROP.
    Throwable thrown = catchThrowable(() -> createDataset(longName, ONE_COLUMN));

    assertThat(valuesOf(truncated)).containsExactly("alpha", "beta");
    assertThat(thrown).isInstanceOf(InvalidTableNameException.class);
  }

  @Test
  void cloneDataset_57자_대상_테이블명은_거부된다() {
    DatasetDetailResponse source = createDataset("wd95_clone_src", ONE_COLUMN);
    String target = nameOf("wd95_clone_", 57);

    assertThatThrownBy(
            () ->
                datasetService.cloneDataset(
                    source.id(),
                    new CloneDatasetRequest("WD95 clone", target, null, true, false),
                    userId))
        .isInstanceOf(InvalidTableNameException.class)
        .hasMessageContaining("최대 56자");
    // 복제 원시연산도 직접 막는다(DatasetService 를 거치지 않는 호출부 대비).
    assertThatThrownBy(
            () -> dataTableService.cloneTable("wd95_clone_src", target, List.of("val"), List.of()))
        .isInstanceOf(InvalidTableNameException.class);
    assertThat(physicalTableExists(target)).isFalse();
  }

  // ---------------------------------------------------------------------------------------------
  // ③ 컬럼명 63/64 경계 — 생성·추가·이름 변경
  // ---------------------------------------------------------------------------------------------

  @Test
  void createTable_컬럼명_63자는_성공하고_64자는_거부된다() {
    String col63 = nameOf("c_", 63);
    String col64 = nameOf("c_", 64);

    dataTableService.createTable(
        "wd95_col_ok",
        List.of(new DatasetColumnRequest(col63, "C", "TEXT", null, true, false, null)));
    // 컬럼이 잘리지 않고 그 이름 그대로 존재한다.
    Long cnt =
        countOf(
            "SELECT count(*) FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = ? AND column_name = ?",
            DataSchema.current(),
            "wd95_col_ok",
            col63);
    assertThat(cnt).isEqualTo(1L);

    // 거부는 고아 회수 DROP 보다 먼저다 — 같은 이름의 기존 물리 테이블이 보존돼야 한다.
    createPhysicalTableWithRows("wd95_col_ng");
    Throwable thrown =
        catchThrowable(
            () ->
                dataTableService.createTable(
                    "wd95_col_ng",
                    List.of(
                        new DatasetColumnRequest(col64, "C", "TEXT", null, true, false, null))));
    assertThat(valuesOf("wd95_col_ng")).containsExactly("alpha", "beta");
    assertThat(thrown)
        .isInstanceOf(InvalidTableNameException.class)
        .hasMessage("필드명은 최대 63자까지 쓸 수 있습니다(입력 64자)");

    // 데이터셋 생성 경로도 같은 상한
    assertThatThrownBy(
            () ->
                createDataset(
                    "wd95_col_ds",
                    List.of(new DatasetColumnRequest(col64, "C", "TEXT", null, true, false, null))))
        .isInstanceOf(InvalidTableNameException.class);
  }

  @Test
  void addColumn_컬럼명_63자는_성공하고_64자는_거부된다() {
    DatasetDetailResponse ds = createDataset("wd95_add", ONE_COLUMN);
    String col63 = nameOf("a_", 63);
    String col64 = nameOf("b_", 64);

    datasetService.addColumn(
        ds.id(), new AddColumnRequest(col63, "A", "TEXT", null, true, false, null));
    assertThat(datasetService.getDatasetById(ds.id()).columns())
        .extracting(c -> c.columnName())
        .contains(col63);

    assertThatThrownBy(
            () ->
                datasetService.addColumn(
                    ds.id(), new AddColumnRequest(col64, "B", "TEXT", null, true, false, null)))
        .isInstanceOf(InvalidTableNameException.class)
        .hasMessageContaining("입력 64자");
    assertThatThrownBy(
            () ->
                dataTableService.addColumn(
                    "wd95_add",
                    new DatasetColumnRequest(col64, "B", "TEXT", null, true, false, null)))
        .isInstanceOf(InvalidTableNameException.class);
  }

  @Test
  void renameColumn_새이름_63자는_성공하고_64자는_거부된다() {
    DatasetDetailResponse ds = createDataset("wd95_ren", ONE_COLUMN);
    Long columnId = ds.columns().get(0).id();
    String col63 = nameOf("r_", 63);
    String col64 = nameOf("s_", 64);

    assertThatThrownBy(
            () ->
                datasetService.updateColumn(
                    ds.id(),
                    columnId,
                    new UpdateColumnRequest(col64, null, null, null, null, null, null)))
        .isInstanceOf(InvalidTableNameException.class)
        .hasMessageContaining("입력 64자");
    assertThatThrownBy(() -> dataTableService.renameColumn("wd95_ren", "val", col64))
        .isInstanceOf(InvalidTableNameException.class);

    datasetService.updateColumn(
        ds.id(), columnId, new UpdateColumnRequest(col63, null, null, null, null, null, null));
    assertThat(datasetService.getDatasetById(ds.id()).columns())
        .extracting(c -> c.columnName())
        .containsExactly(col63);
  }

  // ---------------------------------------------------------------------------------------------
  // ④ 기존 긴 이름 — 조회·편집 경로(validateName)는 계속 통과
  // ---------------------------------------------------------------------------------------------

  @Test
  void 기존_60자_테이블은_조회와_컬럼추가가_계속_동작한다() {
    // 상한 도입 전에 만들어진 긴 이름 테이블(운영에 있을 수 있다)을 흉내 낸다.
    String legacy = nameOf("wd95_legacy_", 60);
    createPhysicalTableWithRows(legacy);

    assertThat(dataTableRowService.countRows(legacy)).isEqualTo(2L);
    assertThat(dataTableRowService.queryData(legacy, List.of("val"), null, 0, 10))
        .extracting(r -> r.get("val"))
        .containsExactly("alpha", "beta");
    // 테이블명은 기존 이름이라 형식만 본다 — 새 컬럼만 길이 상한 대상.
    dataTableService.addColumn(
        legacy, new DatasetColumnRequest("extra", "Extra", "TEXT", null, true, false, null));
    assertThat(valuesOf(legacy)).containsExactly("alpha", "beta");
  }

  @Test
  void createTable_예약어_컬럼이나_잘못된_VARCHAR_길이는_고아회수_DROP_전에_거부된다() {
    // "실패하는 요청은 DROP 을 일으키지 않는다" — 이름 길이뿐 아니라 예약어·타입 검사도 DROP 보다 먼저여야 한다.
    createPhysicalTableWithRows("wd95_pre_drop");

    Throwable reserved =
        catchThrowable(
            () ->
                dataTableService.createTable(
                    "wd95_pre_drop",
                    List.of(
                        new DatasetColumnRequest("id", "Id", "TEXT", null, true, false, null))));
    assertThat(valuesOf("wd95_pre_drop")).containsExactly("alpha", "beta");
    assertThat(reserved)
        .isInstanceOf(InvalidTableNameException.class)
        .hasMessageContaining("시스템 예약어");

    Throwable badVarchar =
        catchThrowable(
            () ->
                dataTableService.createTable(
                    "wd95_pre_drop",
                    List.of(new DatasetColumnRequest("v", "V", "VARCHAR", 0, true, false, null))));
    assertThat(valuesOf("wd95_pre_drop")).containsExactly("alpha", "beta");
    assertThat(badVarchar).isInstanceOf(IllegalArgumentException.class);
  }

  // ---------------------------------------------------------------------------------------------
  // ⑤ REPLACE 임시 테이블은 원본 이름에서 파생하지 않는 고정 길이 예약 이름이다
  // ---------------------------------------------------------------------------------------------

  /** REPLACE 한 번(임시 생성 → 적재 → 마무리)을 실제 실행기와 같은 순서로 돌린다. */
  private void replaceWith(String tableName, String value) {
    String tmp = dataTableService.createTempTable(tableName);
    dsl.execute("INSERT INTO " + DataSchema.qualify(tmp) + " (val) VALUES (?)", value);
    dataTableService.finishReplace(tableName, tmp, 1);
  }

  @Test
  void 기존_63자_테이블도_REPLACE로_행이_교체되고_다시_REPLACE해도_된다() {
    // 예전 "<t>_tmp" 는 63자 테이블에서 잘려 원본 이름 자체가 됐다(정리 DROP 이 원본을 지움). 고정 길이 이름은 길이와 무관하다.
    String legacy = nameOf("wd95_r63_", 63);
    createPhysicalTableWithRows(legacy);

    replaceWith(legacy, "gamma");
    assertThat(valuesOf(legacy)).containsExactly("gamma");
    assertThat(physicalTableExists(DataTableService.legacyTempTableName(legacy))).isFalse();

    // 맞바꿈 후 시퀀스 이름이 테이블 이름과 겹치지 않아 두 번째 REPLACE·이후 삽입도 된다.
    replaceWith(legacy, "delta");
    dsl.execute("INSERT INTO " + DataSchema.qualify(legacy) + " (val) VALUES ('epsilon')");
    assertThat(valuesOf(legacy)).containsExactly("delta", "epsilon");

    // 실패 정리 경로도 원본을 건드리지 않는다.
    String tmp = dataTableService.createTempTable(legacy);
    assertThat(physicalTableExists(tmp)).isTrue();
    dataTableService.dropTempTable(legacy, tmp);
    assertThat(physicalTableExists(tmp)).isFalse();
    assertThat(valuesOf(legacy)).containsExactly("delta", "epsilon");
    // 임시 이름은 원본 길이와 무관하게 50자(시퀀스 57자) — 잘림이 생기지 않는다(WD-151: rtmp_<난수 12>_<md5 32>).
    assertThat(tmp).hasSize(50).startsWith("rtmp_");
    assertThat(tmp + "_id_seq").hasSizeLessThanOrEqualTo(63);
  }

  @Test
  void orders_REPLACE가_orders_tmp_데이터셋을_지우지_않는다() {
    // 예전 규칙에서는 orders 의 임시 테이블 이름이 곧 orders_tmp 라, 실제 데이터셋 orders_tmp 가 DROP 으로 사라졌다.
    createPhysicalTableWithRows("wd95_orders");
    createPhysicalTableWithRows("wd95_orders_tmp");

    replaceWith("wd95_orders", "gamma");
    String tmp = dataTableService.createTempTable("wd95_orders");
    dataTableService.dropTempTable("wd95_orders", tmp);

    assertThat(valuesOf("wd95_orders")).containsExactly("gamma");
    assertThat(valuesOf("wd95_orders_tmp")).containsExactly("alpha", "beta");
  }

  @Test
  void 임시_테이블_예약_접두어로_시작하는_테이블명은_생성할_수_없다() {
    String reserved = DataTableService.TEMP_TABLE_PREFIX + "orders";
    assertThatThrownBy(() -> dataTableService.createTable(reserved, ONE_COLUMN))
        .isInstanceOf(InvalidTableNameException.class)
        .hasMessageContaining("시스템 예약어");
    assertThatThrownBy(
            () -> dataTableService.cloneTable("any_source", reserved, List.of("val"), List.of()))
        .isInstanceOf(InvalidTableNameException.class);
    assertThat(physicalTableExists(reserved)).isFalse();
  }

  @Test
  void 앞_56자가_같은_긴_테이블_둘에서_REPLACE가_시퀀스_이름_충돌없이_된다() {
    // PostgreSQL 은 두 번째 테이블의 시퀀스 이름을 잘린 이름 + 번호로 짓는다. 맞바꿈이 시퀀스 이름을 규칙으로 다시 만들면 첫 번째 테이블의
    // 시퀀스 이름과 겹쳐 실패한다 — 원본 시퀀스의 실제 이름을 물려받아야 한다.
    String prefix56 = nameOf("wd95_seq_", 56);
    String first = prefix56 + "_a";
    String second = prefix56 + "_b";
    createPhysicalTableWithRows(first);
    createPhysicalTableWithRows(second);
    String firstSeqBefore = serialSequenceOf(first);
    String secondSeqBefore = serialSequenceOf(second);
    assertThat(firstSeqBefore).isNotEqualTo(secondSeqBefore);

    replaceWith(second, "gamma");

    assertThat(valuesOf(second)).containsExactly("gamma");
    assertThat(valuesOf(first)).containsExactly("alpha", "beta");
    // 두 테이블 모두 시퀀스가 살아 있어 nextval 로 삽입된다. 맞바꾼 쪽은 원본 시퀀스 이름을 그대로 물려받았다.
    assertThat(serialSequenceOf(second)).isEqualTo(secondSeqBefore);
    assertThat(serialSequenceOf(first)).isEqualTo(firstSeqBefore);
    dsl.execute("INSERT INTO " + DataSchema.qualify(first) + " (val) VALUES ('first_next')");
    dsl.execute("INSERT INTO " + DataSchema.qualify(second) + " (val) VALUES ('second_next')");
    assertThat(valuesOf(first)).containsExactly("alpha", "beta", "first_next");
    assertThat(valuesOf(second)).containsExactly("gamma", "second_next");
  }

  /** 테이블 id 컬럼이 소유한 시퀀스의 실제 이름(스키마 제외). */
  private String serialSequenceOf(String tableName) {
    return (String)
        dsl.fetchValue(
            "SELECT s.relname::text FROM pg_class s WHERE s.oid = pg_get_serial_sequence(?, 'id')::regclass",
            DataSchema.qualify(tableName));
  }

  // ---------------------------------------------------------------------------------------------
  // ⑥ 사전 검증이 다른 오류 분류를 바꾸지 않는다 / 예약 이름 공간
  // ---------------------------------------------------------------------------------------------
  // (필드명 중복의 409 변환은 DatasetServiceExtTest·DatasetColumnNameRaceTest 가 본다 — WD-99)

  @Test
  void DOCUMENT_데이터셋도_예약_접두어_테이블명을_쓸_수_없다() {
    // DOCUMENT/FILE 은 물리 테이블을 만들지 않아 createTable 을 타지 않는다 — 서비스의 테이블명 사전 검증이 막아야 한다.
    String reserved = DataTableService.TEMP_TABLE_PREFIX + "doc";
    assertThatThrownBy(
            () ->
                datasetService.createDataset(
                    new CreateDatasetRequest(
                        "WD95 doc", reserved, null, null, "DOCUMENT", "SOURCE", List.of(), null),
                    userId))
        .isInstanceOf(InvalidTableNameException.class)
        .hasMessageContaining("시스템 예약어");
  }

  @Test
  void 컬럼_이름변경으로_시스템_예약어를_쓸_수_없다() {
    DatasetDetailResponse ds = createDataset("wd95_ren_reserved", ONE_COLUMN);
    Long columnId = ds.columns().get(0).id();
    for (String reserved : List.of("_updated_at", "id")) {
      assertThatThrownBy(
              () ->
                  datasetService.updateColumn(
                      ds.id(),
                      columnId,
                      new UpdateColumnRequest(reserved, null, null, null, null, null, null)))
          .isInstanceOf(InvalidTableNameException.class)
          .hasMessageContaining("시스템 예약어");
      assertThatThrownBy(() -> dataTableService.renameColumn("wd95_ren_reserved", "val", reserved))
          .isInstanceOf(InvalidTableNameException.class)
          .hasMessageContaining("시스템 예약어");
    }
    assertThat(datasetService.getDatasetById(ds.id()).columns())
        .extracting(c -> c.columnName())
        .containsExactly("val");
  }
}
