package com.smartfirehub.global.platformdb.tibero.flyway;

import com.smartfirehub.dataset.storage.tibero.TiberoNames;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tibero 런타임 계정 권한 부여 콜백(WD-492) — migrate 가 끝날 때마다 소유자 스키마의 객체 권한을 런타임 계정에 준다.
 *
 * <p>무엇을 주나(소유자 연결로, 이름은 모두 큰따옴표로 감싼다):
 *
 * <ul>
 *   <li>테이블 전부(휴지통 {@code BIN$*}·Flyway 이력 제외): SELECT·INSERT·UPDATE·DELETE
 *   <li>뷰 전부: SELECT
 *   <li>시퀀스 전부(IDENTITY 의 {@code ISEQ$$_*} 포함 — 런타임이 id 를 빼고 넣으려면 필요하다): SELECT
 *   <li>패키지 {@code FH_TENANT}: EXECUTE — 런타임은 이 패키지로만 테넌트 컨텍스트를 바꾼다
 * </ul>
 *
 * <p>왜 마이그레이션이 아니라 콜백인가: PG 의 {@code ALTER DEFAULT PRIVILEGES}(V83)가 Tibero 에는 없다. {@code
 * AFTER_MIGRATE} 는 적용할 마이그레이션이 없어도 migrate 마다 돌므로, 이후 마이그레이션이 테이블을 더해도 다음 기동에서 권한이 따라 붙는다. 이미 준 권한은
 * {@code USER_TAB_PRIVS} 에서 한 번 읽어 빼고 빠진 (객체, 권한) 짝만 준다 — 기동마다 객체 수만큼 GRANT 를 다시 보내지 않기 위해서다(같은 권한을
 * 다시 줘도 Tibero 는 오류를 내지 않으므로 정확성 때문이 아니라 기동 비용 때문이다, 실측).
 *
 * <p>단일 계정 모드(런타임 이름 null)에서는 아무것도 하지 않는다.
 */
public class TiberoRuntimeGrantCallback extends BaseCallback {

  private static final Logger log = LoggerFactory.getLogger(TiberoRuntimeGrantCallback.class);

  /** 권한을 줄 테넌트 패키지(V156.1). */
  static final String TENANT_PACKAGE = "FH_TENANT";

  /** Flyway 이력 테이블(따옴표 소문자) — 앱이 쓰지 않으므로 권한을 주지 않는다. */
  static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";

  /**
   * 객체 유형 → 주는 권한. 테이블은 DML 넷, 뷰·시퀀스(IDENTITY 의 {@code ISEQ$$_*} 포함 — 런타임이 id 를 빼고 넣으려면 필요하다)는
   * SELECT, 패키지({@link #TENANT_PACKAGE} 만)는 EXECUTE. 함수는 주지 않는다 — 정책 함수·보안 등급 기본값 함수는 소유자 권한으로만 돈다.
   */
  private static final Map<String, List<String>> PRIVILEGES_BY_TYPE =
      Map.of(
          "TABLE", List.of("SELECT", "INSERT", "UPDATE", "DELETE"),
          "VIEW", List.of("SELECT"),
          "SEQUENCE", List.of("SELECT"),
          "PACKAGE", List.of("EXECUTE"));

  /**
   * 권한을 줄 소유자 객체 — 테이블·뷰·시퀀스 전부(휴지통 {@code BIN$*}·Flyway 이력 제외)와 테넌트 패키지. 패키지는 V156.1 이 만든다 —
   * target 을 그 앞으로 둔 migrate 에서도 콜백이 실패하지 않게, 있을 때만 목록에 나온다.
   */
  private static final String OBJECTS_SQL =
      "SELECT OBJECT_NAME, OBJECT_TYPE FROM USER_OBJECTS"
          + " WHERE (OBJECT_TYPE IN ('TABLE', 'VIEW', 'SEQUENCE')"
          + " AND OBJECT_NAME NOT LIKE 'BIN$%' AND OBJECT_NAME <> ?)"
          + " OR (OBJECT_TYPE = 'PACKAGE' AND OBJECT_NAME = ?)";

  /**
   * 런타임 계정이 이 스키마 객체에 이미 가진 권한. USER_TAB_PRIVS 는 이 연결(= 소유자) 기준이라 OWNER = USER 로 이 스키마 객체만 고른다. 휴지통
   * 객체의 권한은 BIN$ 이름으로 남으므로(WD-645), 같은 이름으로 다시 만든 테이블이 「이미 있음」으로 잘못 세지지 않는다.
   */
  private static final String EXISTING_GRANTS_SQL =
      "SELECT TABLE_NAME, PRIVILEGE FROM USER_TAB_PRIVS WHERE GRANTEE = ? AND OWNER = USER";

  private final String ownerSchema;
  private final String runtimeUser;

  /**
   * @param ownerSchema 소유자 스키마(대문자)
   * @param runtimeUser 런타임 계정(대문자), 단일 계정 모드면 null
   */
  public TiberoRuntimeGrantCallback(String ownerSchema, String runtimeUser) {
    this.ownerSchema = ownerSchema;
    this.runtimeUser = runtimeUser;
  }

  @Override
  public boolean supports(Event event, Context context) {
    return event == Event.AFTER_MIGRATE;
  }

  @Override
  public void handle(Event event, Context context) {
    if (runtimeUser == null) {
      return;
    }
    Connection c = context.getConnection();
    try {
      Map<String, List<String>> objects = objectsByType(c);
      // 이미 준 권한은 한 번만 읽어 둔다 — 기동마다 객체 수만큼 GRANT 를 다시 보내지 않고, 빠진 (객체, 권한) 짝만 준다.
      Map<String, Set<String>> existing = existingGrants(c);
      String grantee = TiberoNames.quote(runtimeUser);
      int requested = 0;
      int granted = 0;
      try (Statement st = c.createStatement()) {
        for (Map.Entry<String, List<String>> e : objects.entrySet()) {
          List<String> privileges = PRIVILEGES_BY_TYPE.get(e.getKey());
          for (String name : e.getValue()) {
            requested += privileges.size();
            granted +=
                grantMissing(st, existing.getOrDefault(name, Set.of()), name, privileges, grantee);
          }
        }
      }
      log.info(
          "Tibero 런타임 계정 {} 권한 점검: 테이블 {}, 뷰 {}, 시퀀스 {}, 패키지 {} — 새로 부여 {}건, 이미 있음 {}건",
          runtimeUser,
          objects.get("TABLE").size(),
          objects.get("VIEW").size(),
          objects.get("SEQUENCE").size(),
          objects.get("PACKAGE").size(),
          granted,
          requested - granted);
    } catch (SQLException e) {
      throw new IllegalStateException("Tibero 런타임 계정 권한 부여 실패(오류 코드 " + e.getErrorCode() + ")", e);
    }
  }

  @Override
  public String getCallbackName() {
    return "tiberoRuntimeGrant";
  }

  /** 소유자 스키마로 한정하고 따옴표로 감싼 객체 이름 — "USER" 같은 예약어 테이블도 그대로 다룬다. */
  private String object(String name) {
    return TiberoNames.quote(ownerSchema) + "." + TiberoNames.quote(name);
  }

  /** 권한을 줄 소유자 객체 — 유형(TABLE·VIEW·SEQUENCE·PACKAGE, 없는 유형도 빈 목록) → 이름 목록. */
  private static Map<String, List<String>> objectsByType(Connection c) throws SQLException {
    Map<String, List<String>> out = new HashMap<>();
    PRIVILEGES_BY_TYPE.keySet().forEach(type -> out.put(type, new ArrayList<>()));
    try (PreparedStatement ps = c.prepareStatement(OBJECTS_SQL)) {
      ps.setString(1, FLYWAY_HISTORY_TABLE);
      ps.setString(2, TENANT_PACKAGE);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.get(rs.getString(2)).add(rs.getString(1));
        }
      }
    }
    return out;
  }

  /** 런타임 계정이 이 스키마 객체에 이미 가진 권한 — 객체 이름 → 권한 집합. */
  private Map<String, Set<String>> existingGrants(Connection c) throws SQLException {
    Map<String, Set<String>> out = new HashMap<>();
    try (PreparedStatement ps = c.prepareStatement(EXISTING_GRANTS_SQL)) {
      ps.setString(1, runtimeUser);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
        }
      }
    }
    return out;
  }

  /** 객체 하나에 빠진 권한만 한 문장으로 주고 준 권한 수를 돌려준다. 모두 있으면 아무것도 보내지 않는다. */
  private int grantMissing(
      Statement st, Set<String> have, String name, List<String> privileges, String grantee)
      throws SQLException {
    List<String> missing = privileges.stream().filter(p -> !have.contains(p)).toList();
    if (!missing.isEmpty()) {
      st.execute("GRANT " + String.join(", ", missing) + " ON " + object(name) + " TO " + grantee);
    }
    return missing.size();
  }
}
