package org.flywaydb.database.oracle;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.internal.database.base.Schema;
import org.flywaydb.core.internal.jdbc.JdbcTemplate;

/**
 * Tibero 용 Flyway 플러그인(WD-488)이 Oracle 구현의 패키지 전용 생성자를 쓰기 위한 다리.
 *
 * <p>왜 Flyway 패키지에 두나: {@link OracleConnection}·{@link OracleSchema} 의 생성자가 패키지 전용이라, 상속해서 Tibero 에
 * 없는 사전 뷰 조회만 덮어쓰려면 같은 패키지에 있어야 한다. Flyway 내부 API 에 기대는 것이므로 Flyway 버전을 올릴 때 이 클래스가 컴파일·동작하는지 반드시
 * 다시 확인해야 한다(ADR 항목). 앱 코드는 이 클래스를 직접 쓰지 않고 {@code
 * com.smartfirehub.global.platformdb.tibero.flyway.TiberoFlywayDatabase} 만 쓴다.
 */
public final class TiberoOracleBridge {

  private TiberoOracleBridge() {}

  /** Tibero 보정이 들어간 Oracle 연결을 만든다. */
  public static OracleConnection connection(
      OracleDatabase database, java.sql.Connection connection) {
    return new TiberoConnection(database, connection);
  }

  /** 스키마 객체만 Tibero 보정판으로 바꾼 연결. 현재 스키마 조회·전환은 Oracle 구현(SYS_CONTEXT·ALTER SESSION)을 그대로 쓴다. */
  static final class TiberoConnection extends OracleConnection {
    TiberoConnection(OracleDatabase database, java.sql.Connection connection) {
      super(database, connection);
    }

    /** 스키마 객체를 Tibero 보정판으로 돌려준다. */
    @Override
    public Schema getSchema(String name) {
      return new TiberoSchema(jdbcTemplate, database, name);
    }
  }

  /**
   * Tibero 사전 뷰 차이를 덮어쓰고, 공유 서버에서 위험한 Oracle 동작(계정 생성·삭제)을 막은 스키마.
   *
   * <p>Oracle 스키마 = 계정이라 Oracle 구현의 {@code doCreate}/{@code doDrop} 은 Flyway 에 박힌 고정 비밀번호로 {@code
   * CREATE USER} 를 하고 {@code DROP USER CASCADE} 를 한다. 플랫폼 스키마 계정은 설치 절차가 만들고 지우므로 Flyway 가 만들거나 지우지
   * 못하게 한다.
   */
  static final class TiberoSchema extends OracleSchema {

    /** clean 이 지우는 순서. 트리거·뷰·PL/SQL 이 테이블에 기대므로 먼저, 테이블은 자기 스키마 FK 를 먼저 떼고 지운다. */
    private static final List<String> CLEAN_ORDER =
        List.of(
            "TRIGGER",
            "VIEW",
            "SYNONYM",
            "PACKAGE",
            "PROCEDURE",
            "FUNCTION",
            "TABLE",
            "SEQUENCE",
            "TYPE");

    /** 부모 객체와 함께 사라지는 종류 — 따로 지우지 않지만 있어도 된다(PACKAGE BODY·TYPE BODY·인덱스·LOB). */
    private static final Set<String> REMOVED_WITH_PARENT =
        Set.of("PACKAGE BODY", "TYPE BODY", "INDEX", "LOB");

    TiberoSchema(JdbcTemplate jdbcTemplate, OracleDatabase database, String name) {
      super(jdbcTemplate, database, name);
    }

    /**
     * 비어 있음 판정. Oracle 구현은 {@code ALL_TAB_PRIVS.TABLE_SCHEMA} 등 Tibero 에 없는 컬럼을 조회한다(2026-11-02
     * 실측). Tibero 에서는 {@code ALL_OBJECTS} 의 소유 객체 수로 판정한다. 휴지통({@code BIN$}) 객체는 clean 이 지우는 대상이
     * 아니므로 세지 않는다 — clean 직후 "비어 있지 않음" 으로 판정되지 않게 하기 위해서다.
     */
    @Override
    protected boolean doEmpty() throws SQLException {
      return jdbcTemplate.queryForInt(
              "SELECT COUNT(*) FROM ALL_OBJECTS WHERE OWNER = ? AND OBJECT_NAME NOT LIKE 'BIN$%'",
              name)
          == 0;
    }

    /** 스키마(계정) 생성 거부 — 설치 절차의 몫이다. {@code createSchemas} 기본값(true)으로 공유 서버에 계정이 생기는 것을 막는다. */
    @Override
    protected void doCreate() {
      throw new FlywayException(
          "Tibero 스키마(계정) " + name + " 이(가) 없습니다 — 계정은 설치 절차가 만듭니다(Flyway 는 만들지 않음)");
    }

    /** 스키마(계정) 삭제 거부 — 계정째 지우는 것은 설치 절차의 몫이다. */
    @Override
    protected void doDrop() {
      throw new FlywayException("Tibero 스키마(계정) " + name + " 삭제는 Flyway 가 하지 않습니다");
    }

    /**
     * Tibero 용 clean — 스키마 소유 객체를 종류별 순서로 지운다.
     *
     * <p>왜 Oracle 구현을 쓰지 않나: Oracle clean 은 Oracle 전용 사전 뷰(ALL_XML_TABLES·ALL_QUEUE_TABLES·
     * ALL_SDO_GEOM_METADATA·DM_USER_MODELS 등)를 30가지 가까이 조회하며 Tibero 에서 {@code JDBC-8026}(잘못된 식별자)로
     * 실패한다(2026-11-04 실측). 플랫폼 스키마가 만드는 객체 종류는 정해져 있어 {@code ALL_OBJECTS} 기준으로 직접 지우는 편이 단순하다.
     *
     * <p>안전 장치:
     *
     * <ul>
     *   <li>지우기 전에 모르는 객체 종류(구체화 뷰·DB 링크 등)가 있으면 아무것도 지우지 않고 실패한다 — 반쯤 지운 채 끝나지 않게.
     *   <li>{@code CASCADE CONSTRAINTS}·{@code FORCE} 를 쓰지 않는다. 자기 스키마 FK 만 먼저 떼고 테이블을 지우므로, 다른
     *       스키마가 이 스키마를 참조하고 있으면 DROP 이 실패한다 — 남의 제약·객체를 조용히 없애지 않기 위해서다.
     *   <li>마지막에 남은 객체가 있으면 종류를 밝혀 실패한다.
     * </ul>
     */
    @Override
    protected void doClean() throws SQLException {
      List<String> unknown = new ArrayList<>();
      for (String type : objectTypes()) {
        if (!CLEAN_ORDER.contains(type) && !REMOVED_WITH_PARENT.contains(type)) unknown.add(type);
      }
      if (!unknown.isEmpty()) {
        throw new FlywayException(
            "Tibero clean 이 다루지 않는 객체 종류가 있어 아무것도 지우지 않았습니다: " + unknown + " (스키마 " + name + ")");
      }
      dropOwnForeignKeys();
      for (String type : CLEAN_ORDER) {
        for (String object :
            jdbcTemplate.queryForStringList(
                "SELECT OBJECT_NAME FROM ALL_OBJECTS WHERE OWNER = ? AND OBJECT_TYPE = ?"
                    + " AND OBJECT_NAME NOT LIKE 'BIN$%'",
                name, type)) {
          jdbcTemplate.execute("DROP " + type + " " + database.quote(name, object));
        }
      }
      List<String> left = objectTypes();
      if (!left.isEmpty()) {
        throw new FlywayException("Tibero clean 후 남은 객체 종류: " + left + " (스키마 " + name + ")");
      }
    }

    /** 휴지통을 뺀 소유 객체 종류 목록. */
    private List<String> objectTypes() throws SQLException {
      return jdbcTemplate.queryForStringList(
          "SELECT DISTINCT OBJECT_TYPE FROM ALL_OBJECTS WHERE OWNER = ?"
              + " AND OBJECT_NAME NOT LIKE 'BIN$%'",
          name);
    }

    /** 이 스키마가 가진 FK 제약만 뗀다(테이블 간 참조 때문에 DROP TABLE 순서가 막히지 않게). 다른 스키마의 제약은 건드리지 않는다. */
    private void dropOwnForeignKeys() throws SQLException {
      for (Map<String, String> fk :
          jdbcTemplate.queryForList(
              "SELECT TABLE_NAME, CONSTRAINT_NAME FROM ALL_CONSTRAINTS WHERE OWNER = ? AND"
                  + " CONSTRAINT_TYPE = 'R'",
              name)) {
        jdbcTemplate.execute(
            "ALTER TABLE "
                + database.quote(name, fk.get("TABLE_NAME"))
                + " DROP CONSTRAINT "
                + database.quote(fk.get("CONSTRAINT_NAME")));
      }
    }
  }
}
