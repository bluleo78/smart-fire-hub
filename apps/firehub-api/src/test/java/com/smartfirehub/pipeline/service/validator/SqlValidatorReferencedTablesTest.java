package com.smartfirehub.pipeline.service.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import com.smartfirehub.pipeline.service.validator.SqlValidator.TableName;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 보안 등급 SQL 판정의 입력 — 참조 테이블 추출이 우회되지 않는지 고정한다(스펙 §4.1, §8 "SQL 우회 시도").
 *
 * <p>순수 테스트(Spring 없음). 스키마 이름 "data" 로 생성 — 추출은 스키마 허용 여부를 판단하지 않는다(그건 가드의 몫).
 */
class SqlValidatorReferencedTablesTest {

  private final SqlValidator v = new SqlValidator("data", true);

  private static TableName t(String name) {
    return new TableName(null, name);
  }

  private static TableName t(String schema, String name) {
    return new TableName(schema, name);
  }

  /** Review Focus 3: 어느 위치에 있든 hidden 이 읽기 집합에 잡혀야 한다. */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "SELECT * FROM hidden",
        "SELECT * FROM pub JOIN hidden ON true",
        "SELECT * FROM pub WHERE EXISTS (SELECT 1 FROM hidden)",
        "SELECT * FROM pub WHERE id IN (SELECT id FROM hidden)",
        "SELECT 1 FROM pub UNION ALL SELECT 1 FROM hidden",
        "SELECT * FROM (SELECT * FROM hidden) q",
        "WITH x AS (SELECT * FROM hidden) SELECT * FROM x",
        "SELECT (SELECT max(v) FROM hidden) FROM pub",
        "SELECT * FROM HIDDEN",
        "SELECT * FROM pub, LATERAL (SELECT * FROM hidden) l"
      })
  void referencedTables_bypassMatrix(String sql) {
    assertThat(v.referencedTables(sql).reads()).contains(t("hidden"));
  }

  @Test
  void schemaQualified_isFoldedAndKept() {
    assertThat(v.referencedTables("SELECT * FROM DATA.Foo").reads())
        .containsExactly(t("data", "foo"));
    assertThat(v.referencedTables("SELECT * FROM \"data\".\"Foo\"").reads())
        .containsExactly(t("data", "Foo"));
  }

  @Test
  void quotedIdentifier_keepsCase_unquotedLowercases() {
    assertThat(v.referencedTables("SELECT * FROM \"Hidden\", Hidden2").reads())
        .containsExactlyInAnyOrder(t("Hidden"), t("hidden2"));
  }

  @Test
  void cteShadow_isNotATable_butNonRecursiveSelfReferenceIs() {
    assertThat(v.referencedTables("WITH hidden AS (SELECT 1 AS a) SELECT * FROM hidden").reads())
        .isEmpty();
    // 비재귀 CTE 정의 안의 같은 이름은 아직 CTE 가 아니다 — 실제 테이블(#385 규칙).
    assertThat(v.referencedTables("WITH t AS (SELECT * FROM t) SELECT * FROM t").reads())
        .containsExactly(t("t"));
  }

  @Test
  void dml_splitsWriteTargetsFromReads() {
    var ins = v.referencedTables("INSERT INTO low (a) SELECT a FROM high");
    assertThat(ins.writes()).containsExactly(t("low"));
    assertThat(ins.reads()).containsExactly(t("high"));

    var upd = v.referencedTables("UPDATE low SET a = h.a FROM high h WHERE low.id = h.id");
    assertThat(upd.writes()).containsExactly(t("low"));
    assertThat(upd.reads()).containsExactly(t("high"));

    var del = v.referencedTables("DELETE FROM low WHERE id IN (SELECT id FROM high)");
    assertThat(del.writes()).containsExactly(t("low"));
    assertThat(del.reads()).containsExactly(t("high"));
  }

  @Test
  void noTables_isEmpty() {
    var r = v.referencedTables("SELECT 1");
    assertThat(r.reads()).isEmpty();
    assertThat(r.writes()).isEmpty();
  }

  @Test
  void failClosed_onUnparseableDdlUnicodeOrThreePartName() {
    for (String bad :
        Set.of(
            "SELEC * FRM x",
            "DROP TABLE x",
            "SELECT * FROM U&\"\\0068idden\"",
            "SELECT * FROM db.data.hidden")) {
      assertThatThrownBy(() -> v.referencedTables(bad))
          .as(bad)
          .isInstanceOf(UnsafeSqlException.class);
    }
  }

  /** DML 대상은 같은 이름의 CTE 가 있어도 실제 테이블이다(PG 동작) — 두 집합에서 사라지면 안 된다. */
  @Test
  void dmlTarget_sharingCteName_isStillWrite() {
    var del = v.referencedTables("WITH hidden AS (SELECT 1) DELETE FROM hidden");
    assertThat(del.writes()).containsExactly(t("hidden"));
    var upd = v.referencedTables("WITH hidden AS (SELECT 1 AS a) UPDATE hidden SET a = 1");
    assertThat(upd.writes()).containsExactly(t("hidden"));
    var ins = v.referencedTables("WITH low AS (SELECT 1 AS a) INSERT INTO low SELECT a FROM high");
    assertThat(ins.writes()).containsExactly(t("low"));
    assertThat(ins.reads()).contains(t("high"));
  }

  /**
   * 쓰기 CTE(WITH d AS (DELETE ... RETURNING ...))는 쓰기 대상 수집이 최상위 문장만 보므로 통과하면 하향·CTE 그림자 우회가 된다.
   * JSqlParser 5.0 이 이 구문을 파싱하지 못해 지금은 fail-closed 다 — 파서 업그레이드로 파싱되기 시작하면 이 테스트가 깨져 쓰기 대상 수집을 중첩
   * DML 까지 넓히라고 알린다.
   */
  @Test
  void writableCte_isRejected_untilNestedDmlTargetsAreCollected() {
    for (String sql :
        Set.of(
            "WITH d AS (INSERT INTO pub SELECT a FROM high RETURNING 1) SELECT 1",
            "WITH pub AS (SELECT 1), d AS (DELETE FROM pub RETURNING 1) SELECT 1",
            "WITH d AS (UPDATE pub SET a = 1 RETURNING 1) SELECT * FROM d",
            // 코드리뷰 CR1 의 두 재현 SQL — 쓰기 하향(secret → low)과 CTE 이름 그림자(hidden)
            "WITH x AS (INSERT INTO low SELECT * FROM secret RETURNING 1) SELECT 1",
            "WITH hidden AS (SELECT 1), d AS (DELETE FROM hidden RETURNING 1) SELECT * FROM d",
            // 중첩 형태 — CTE 안의 CTE, DML 본문 안의 쓰기 CTE, 파생 테이블 안의 쓰기 CTE, MERGE 쓰기 CTE
            "WITH a AS (WITH b AS (DELETE FROM t RETURNING 1) SELECT * FROM b) SELECT * FROM a",
            "INSERT INTO z WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d",
            "SELECT * FROM (WITH d AS (DELETE FROM t RETURNING 1) SELECT * FROM d) s",
            "WITH d AS (DELETE FROM t RETURNING 1) DELETE FROM u",
            "WITH d AS (MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN DELETE RETURNING 1)"
                + " SELECT * FROM d")) {
      assertThatThrownBy(() -> v.referencedTables(sql))
          .as(sql)
          .isInstanceOf(UnsafeSqlException.class);
      assertThatThrownBy(() -> v.validate(sql)).as(sql).isInstanceOf(UnsafeSqlException.class);
    }
  }
}
