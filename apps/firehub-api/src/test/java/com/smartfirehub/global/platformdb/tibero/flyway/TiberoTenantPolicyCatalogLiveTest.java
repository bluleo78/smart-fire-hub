package com.smartfirehub.global.platformdb.tibero.flyway;

import static com.smartfirehub.support.tibero.TiberoSql.strings;
import static com.smartfirehub.support.tibero.TiberoTestEvidence.write;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.jooq.Public;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoTenantRlsSchema;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jooq.Field;
import org.jooq.Table;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tibero 테넌트 격리(WD-492) 카탈로그 시험 — 소유자 스키마의 사전 뷰로 정책·DEFAULT 가 빠짐없이 걸렸는지 본다.
 *
 * <p>왜: 새 마이그레이션이 tenant_id 테이블을 더하고 정책을 빠뜨리면 그 테이블은 테넌트를 넘어 읽힌다. 행 단위 시험은 고른 테이블만 보므로 여기서 {@code
 * TENANT_ID} 열이 있는 모든 테이블 = 정책이 걸린 테이블(허용 목록 제외)을 고정한다. 허용 목록도 낡으면(정책이 생기거나 tenant_id 를 잃으면) 실패한다.
 * DEFAULT 는 PG(jOOQ 생성 코드)에 {@code app.tenant_id} DEFAULT 가 있는 테이블과 같은지 대조한다.
 */
@TiberoLive
class TiberoTenantPolicyCatalogLiveTest {

  /** tenant_id 가 있지만 정책을 걸지 않는 테이블(PG 와 같음) — 로그인·테넌트 해석이 테넌트를 넘어 읽는다(설계 §4). */
  private static final Set<String> NO_POLICY =
      Set.of("MEMBERSHIP", "OAUTH_STATE", "TENANT_DATA_STORE", "TENANT_DATA_STORE_ACCOUNT");

  /** Tibero 기준선에 없는 PG 벡터 테이블(WD-495) — PG 쪽 집합에서 뺀다. */
  private static final Set<String> VECTOR_TABLES =
      Set.of(
          "DATASET_EMBEDDING_VEC_1024",
          "DATASET_EMBEDDING_VEC_1536",
          "DOCUMENT_CHUNK_VEC_1024",
          "DOCUMENT_CHUNK_VEC_1536");

  /** PG 정책 이름 규칙(<table>_tenant_isolation)의 유일한 예외. */
  private static final Map<String, String> POLICY_NAME_EXCEPTIONS =
      Map.of("TENANT_CANARY", "TENANT_CANARY_ISOLATION");

  private static TiberoTenantRlsSchema schema;

  @BeforeAll
  static void setUp() {
    schema = TiberoTenantRlsSchema.shared();
  }

  @Test
  void TENANT_ID_열이_있는_테이블은_모두_정책이_있다() throws SQLException {
    Set<String> tenantTables = tenantIdTables();
    tenantTables.removeAll(NO_POLICY);
    Set<String> policyTables = new TreeSet<>(policies().keySet());

    write(
        "정책 카탈로그: tenant_id 테이블(허용 목록 제외) "
            + tenantTables.size()
            + "개, 정책 "
            + policyTables.size()
            + "개");
    assertThat(policyTables).isEqualTo(tenantTables);
    assertThat(policyTables).hasSize(58); // V162 vector_outbox(WD-721) 추가
  }

  @Test
  void 허용_목록은_정책이_없고_TENANT_ID_가_있다() throws SQLException {
    assertThat(tenantIdTables()).as("허용 목록 테이블은 tenant_id 가 있어야 한다").containsAll(NO_POLICY);
    assertThat(policies().keySet())
        .as("허용 목록 테이블에는 정책이 없어야 한다")
        .doesNotContainAnyElementsOf(NO_POLICY);
  }

  /**
   * 정책 한 행마다 네 문장·update_check·사용 중·동적이고, 이름은 PG 이름 그대로, 함수는 audit_log 만 NULLABLE 이다. YES/NO 만 보면
   * 함수를 잘못 이어도 통과하므로 이름·함수까지 본다.
   */
  @Test
  void 정책은_네_문장_모두_update_check_동적이다() throws SQLException {
    Map<String, List<String>> rows = policies();
    assertThat(rows).isNotEmpty();
    for (Map.Entry<String, List<String>> e : rows.entrySet()) {
      String table = e.getKey();
      List<String> r = e.getValue();
      String expectedName = POLICY_NAME_EXCEPTIONS.getOrDefault(table, table + "_TENANT_ISOLATION");
      String expectedFunction =
          "AUDIT_LOG".equals(table) ? "FH_TENANT_POLICY_NULLABLE" : "FH_TENANT_POLICY";
      // 열 순서: POLICY_NAME, PF_OWNER, FUNCTION, SEL, INS, UPD, DEL, CHK_OPTION, ENABLE,
      // STATIC_POLICY
      assertThat(r)
          .as(table)
          .containsExactly(
              expectedName,
              schema.owner().name().toUpperCase(Locale.ROOT),
              expectedFunction,
              "YES",
              "YES",
              "YES",
              "YES",
              "YES",
              "YES",
              "NO");
    }
  }

  @Test
  void PG_DEFAULT_가_있는_TENANT_ID_열은_Tibero_에도_컨텍스트_DEFAULT_가_있다() throws SQLException {
    // A: PG — jOOQ 생성 코드의 tenant_id DEFAULT 식에 app.tenant_id 가 든 테이블(벡터 4개 제외).
    Set<String> pg = new TreeSet<>();
    for (Table<?> t : Public.PUBLIC.getTables()) {
      Field<?> f = t.field("tenant_id");
      if (f == null || !f.getDataType().defaulted()) continue;
      if (String.valueOf(f.getDataType().defaultValue()).contains("app.tenant_id")) {
        pg.add(t.getName().toUpperCase(Locale.ROOT));
      }
    }
    pg.removeAll(VECTOR_TABLES);

    // B: Tibero — TENANT_ID 의 DATA_DEFAULT 에 SYS_CONTEXT('<ctx>','TENANT_ID') 가 든 테이블. DATA_DEFAULT
    // 는 LONG 이라 WHERE 에서
    //   비교하지 못한다 — 가져와서 공백·대소문자를 고른 뒤 Java 에서 본다.
    String expected = ("SYS_CONTEXT('" + schema.contextName() + "','TENANT_ID')");
    Set<String> tibero = new TreeSet<>();
    try (Connection c = schema.owner().dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT c.TABLE_NAME, c.DATA_DEFAULT FROM USER_TAB_COLUMNS c"
                    + " JOIN USER_TABLES t ON t.TABLE_NAME = c.TABLE_NAME"
                    + " WHERE c.COLUMN_NAME = 'TENANT_ID' AND c.TABLE_NAME NOT LIKE 'BIN$%'")) {
      while (rs.next()) {
        String table = rs.getString(1);
        String def = rs.getString(2);
        if (def != null && normalize(def).contains(normalize(expected))) tibero.add(table);
      }
    }

    write(
        "tenant_id DEFAULT: PG(벡터 제외) "
            + pg.size()
            + "곳, Tibero 컨텍스트 DEFAULT "
            + tibero.size()
            + "곳");
    assertThat(pg).as("PG 쪽 집합이 비면 헛통과한다").isNotEmpty();
    assertThat(tibero).isEqualTo(pg);
  }

  /** 소유자 스키마에서 TENANT_ID 열이 있는 테이블(뷰·휴지통 제외). */
  private static Set<String> tenantIdTables() throws SQLException {
    try (Connection c = schema.owner().dataSource().getConnection()) {
      return new TreeSet<>(
          strings(
              c,
              "SELECT c.TABLE_NAME FROM USER_TAB_COLUMNS c"
                  + " JOIN USER_TABLES t ON t.TABLE_NAME = c.TABLE_NAME"
                  + " WHERE c.COLUMN_NAME = 'TENANT_ID' AND c.TABLE_NAME NOT LIKE 'BIN$%'"));
    }
  }

  /** 소유자 스키마의 정책 — 테이블 이름 → 이름·함수·플래그 열. 한 테이블에 정책이 둘이면 뒤 행이 덮지 않게 실패시킨다. */
  private static Map<String, List<String>> policies() throws SQLException {
    Map<String, List<String>> rows = new TreeMap<>();
    try (Connection c = schema.owner().dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT OBJECT_NAME, POLICY_NAME, PF_OWNER, FUNCTION, SEL, INS, UPD, DEL,"
                    + " CHK_OPTION, ENABLE, STATIC_POLICY FROM USER_POLICIES")) {
      while (rs.next()) {
        List<String> r = new ArrayList<>();
        for (int i = 2; i <= 11; i++) r.add(rs.getString(i));
        List<String> previous = rows.put(rs.getString(1), r);
        assertThat(previous).as("테이블당 정책 하나: " + rs.getString(1)).isNull();
      }
    }
    return rows;
  }

  private static String normalize(String s) {
    return s.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
  }
}
