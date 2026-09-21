package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link MergeSqlBuilder}가 만든 SQL 을 실제 테이블에 실행해 upsert 의미를 증명한다.
 *
 * <p>단위 테스트({@code MergeSqlBuilderTest})는 문자열 조립만 확인한다 — "만든 SQL 이 PostgreSQL 에서
 * 실제로 그 의미대로 도는가"는 별개 질문이다. 이 테스트는 (1) 기존 PK 행이 갱신되고(중복 삽입이 아님),
 * (2) 새 PK 행이 삽입되고, (3) 값이 그대로인 행은 {@code IS DISTINCT FROM} 가드로 건드리지 않는다는
 * 것을 실제 DB 행 수·값·{@code _updated_at} 타임스탬프로 확인한다.
 *
 * <p><b>테스트 트랜잭션을 끈다({@code NOT_SUPPORTED})</b> — {@code _updated_at} 트리거가 도는 것과
 * "행이 실제로 건드려지지 않았다"는 것을 커밋 경계 없이 같은 트랜잭션 안에서도 관찰할 수 있지만,
 * 이 테이블은 이 테스트가 직접 만들고 지우는 물리 테이블이라 클래스 레벨 트랜잭션 롤백에 기대지 않고
 * {@link #tearDown()} 에서 명시적으로 정리한다(다른 테스트의 공유 자원이 아니므로 안전하다).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MergeSqlBuilderIntegrationTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private DataTableService dataTableService;

  private static final String TABLE = "merge_builder_it_out";
  private static final String SRC_TABLE = "merge_builder_it_src";

  @BeforeEach
  void createTables() {
    // 출력 테이블 — code 를 PK(유니크 인덱스)로, name/cnt 를 일반 컬럼으로.
    dataTableService.createTable(
        TABLE,
        List.of(
            new DatasetColumnRequest("code", "Code", "TEXT", null, false, false, null, true),
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null, false),
            new DatasetColumnRequest("cnt", "Count", "DECIMAL", null, true, false, null, false)));

    // 소스 테이블(SELECT 대상) — 여기 값을 바꿔 가며 MergeSqlBuilder 가 만든 SQL 을 실행한다.
    dsl.execute(
        "CREATE TABLE "
            + DataSchema.qualify(SRC_TABLE)
            + " (code TEXT, name TEXT, cnt NUMERIC(18,6))");

    // 기존 행 시드: code=A1(갱신 대상), code=A2(변경 없음 확인용).
    dsl.execute(
        "INSERT INTO "
            + DataSchema.qualify(TABLE)
            + " (code, name, cnt) VALUES ('A1', 'old-name', 1), ('A2', 'unchanged', 2)");
  }

  @AfterEach
  void tearDown() {
    dsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(TABLE));
    dsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(SRC_TABLE));
  }

  /** MergeSqlBuilder 가 만든 SQL 을 그대로 실행한다 — 실행 경로(PipelineAsyncRunner)를 흉내내지 않고 빌더 출력만 검증한다. */
  private void runMerge(String selectSql) {
    String sql =
        MergeSqlBuilder.build(
            DataSchema.qualify(TABLE), List.of("code", "name", "cnt"), List.of("code"), selectSql);
    dsl.execute(sql);
  }

  @Test
  void 기존_PK는_갱신되고_새_PK는_삽입되고_무변경_행은_건드리지_않는다() {
    OffsetDateTime unchangedUpdatedAtBefore = updatedAtOf("A2");

    // A1: 기존 행 값 변경(갱신 대상), A3: 신규 PK(삽입 대상), A2: 소스에 없음(그대로 남아야 함).
    dsl.execute(
        "INSERT INTO "
            + DataSchema.qualify(SRC_TABLE)
            + " (code, name, cnt) VALUES ('A1', 'new-name', 10), ('A3', 'brand-new', 30)");

    runMerge("SELECT code, name, cnt FROM " + DataSchema.qualify(SRC_TABLE));

    // 전체 행 수: 갱신 1(A1) + 무변경 1(A2, 손대지 않음) + 신규 1(A3) = 3. 중복 삽입이었다면 4 이상이 된다.
    Integer total =
        dsl.fetchOne(
                "SELECT count(*) FROM " + DataSchema.qualify(TABLE) + " WHERE code IN ('A1','A2','A3')")
            .get(0, Integer.class);
    assertThat(total).as("A1 은 삽입이 아니라 갱신이어야 하므로 행 수가 3이어야 한다").isEqualTo(3);

    // A1: 값이 실제로 갱신됐다.
    var a1 =
        dsl.fetchOne("SELECT name, cnt FROM " + DataSchema.qualify(TABLE) + " WHERE code = 'A1'");
    assertThat(a1.get("name")).isEqualTo("new-name");
    assertThat(a1.get(1, Integer.class)).isEqualTo(10);

    // A3: 신규 삽입됐다.
    var a3 =
        dsl.fetchOne("SELECT name, cnt FROM " + DataSchema.qualify(TABLE) + " WHERE code = 'A3'");
    assertThat(a3.get("name")).isEqualTo("brand-new");

    // A2: 소스에 없었으므로 값도, _updated_at 도 그대로다.
    var a2 =
        dsl.fetchOne("SELECT name, cnt FROM " + DataSchema.qualify(TABLE) + " WHERE code = 'A2'");
    assertThat(a2.get("name")).isEqualTo("unchanged");
    assertThat(updatedAtOf("A2")).isEqualTo(unchangedUpdatedAtBefore);
  }

  @Test
  void 값이_같은_행은_IS_DISTINCT_FROM_가드에_의해_건드리지_않는다() {
    OffsetDateTime before = updatedAtOf("A2");

    // A2 를 기존 값과 완전히 동일한 값으로 다시 보낸다 — ON CONFLICT 는 발동하지만 WHERE 가드가
    // false 이므로 DO UPDATE 액션 자체가 그 행에 적용되지 않아야 한다(=트리거도 안 돈다).
    dsl.execute(
        "INSERT INTO "
            + DataSchema.qualify(SRC_TABLE)
            + " (code, name, cnt) VALUES ('A2', 'unchanged', 2)");

    runMerge("SELECT code, name, cnt FROM " + DataSchema.qualify(SRC_TABLE));

    assertThat(updatedAtOf("A2"))
        .as("값이 같은 행은 IS DISTINCT FROM 가드가 UPDATE 를 막아야 하므로 _updated_at 이 변하면 안 된다")
        .isEqualTo(before);
  }

  @Test
  void 소스에_같은_키가_중복되면_PostgreSQL_오류를_던진다() {
    // MergeSqlBuilder.DUPLICATE_KEY_PG_MESSAGE 가 실제로 이 상황에서 나는 오류 문구인지 실측한다 —
    // PipelineAsyncRunner 의 한국어 번역 분기가 근거로 삼는 계약이다.
    dsl.execute(
        "INSERT INTO "
            + DataSchema.qualify(SRC_TABLE)
            + " (code, name, cnt) VALUES ('A1', 'dup-1', 11), ('A1', 'dup-2', 12)");

    assertThatThrownBy(
            () -> runMerge("SELECT code, name, cnt FROM " + DataSchema.qualify(SRC_TABLE)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasMessageContaining(MergeSqlBuilder.DUPLICATE_KEY_PG_MESSAGE);
  }

  /**
   * Fix round 2, must 1 — {@code MergeSqlBuilderTest}의 문자열 단위 테스트는 "생성되는 SQL 모양"만
   * 고정한다. 이 테스트는 세미콜론 + 후행 한 줄 주석이 함께 있는 실제로 더 위험한 조합
   * ({@code "...; -- note"})을 실제 PostgreSQL에 실행해, 문자열이 그럴듯해 보이는 것과 실제로
   * 파싱·실행되는 것의 차이를 없앤다 — 서브쿼리를 자기 줄에 얹는 개행 처리와 세미콜론 제거가 함께
   * 맞물려야 성립하는 케이스라 문자열 비교만으로는 증명력이 약하다.
   */
  @Test
  void 세미콜론과_후행_주석이_있는_SELECT도_실제로_실행된다() {
    dsl.execute(
        "INSERT INTO "
            + DataSchema.qualify(SRC_TABLE)
            + " (code, name, cnt) VALUES ('A3', 'brand-new', 30)");

    runMerge(
        "SELECT code, name, cnt FROM " + DataSchema.qualify(SRC_TABLE) + "; -- trailing comment");

    var a3 =
        dsl.fetchOne("SELECT name, cnt FROM " + DataSchema.qualify(TABLE) + " WHERE code = 'A3'");
    assertThat(a3).as("세미콜론+후행 주석이 있는 SELECT 도 정상 실행되어 신규 행이 삽입돼야 한다").isNotNull();
    assertThat(a3.get("name")).isEqualTo("brand-new");
  }

  /**
   * 코드리뷰 MEDIUM — 주석이 세미콜론과 <b>다음 줄</b>에 있는 형태({@code "...;\n-- note"}). 예전
   * 구현은 물리적 마지막 줄만 봤기 때문에 이 형태에서 세미콜론이 살아남아 서브쿼리 안에 들어갔고,
   * 실행할 때마다 PostgreSQL 문법 오류가 났다. 문자열 단위 테스트와 별개로 실제 실행으로 증명한다 —
   * 이 결함은 "그럴듯한 문자열"과 "실제로 파싱되는 SQL"의 차이 그 자체이기 때문이다.
   */
  @Test
  void 세미콜론_다음_줄에_주석이_있는_SELECT도_실제로_실행된다() {
    dsl.execute(
        "INSERT INTO "
            + DataSchema.qualify(SRC_TABLE)
            + " (code, name, cnt) VALUES ('A4', 'next-line', 40)");

    runMerge(
        "SELECT code, name, cnt FROM " + DataSchema.qualify(SRC_TABLE) + ";\n-- trailing comment");

    var a4 =
        dsl.fetchOne("SELECT name, cnt FROM " + DataSchema.qualify(TABLE) + " WHERE code = 'A4'");
    assertThat(a4).as("세미콜론 다음 줄 주석이 있는 SELECT 도 정상 실행되어 신규 행이 삽입돼야 한다").isNotNull();
    assertThat(a4.get("name")).isEqualTo("next-line");
  }

  private OffsetDateTime updatedAtOf(String code) {
    Map<String, Object> row =
        dsl.fetchOne(
                "SELECT _updated_at FROM " + DataSchema.qualify(TABLE) + " WHERE code = ?", code)
            .intoMap();
    return (OffsetDateTime) row.get("_updated_at");
  }
}
