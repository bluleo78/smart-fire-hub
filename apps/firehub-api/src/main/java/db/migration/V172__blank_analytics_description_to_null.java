package db.migration;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * V172 — Tibero V172 의 PG 짝(MigrationParityTest, WD-1161).
 *
 * <p><b>무엇을</b> — {@code saved_query}·{@code chart}·{@code dashboard} 의 {@code description} 이 빈 문자열·공백·Cf·Cc·채움
 * 문자만인 행을 NULL 로 비운다.
 *
 * <p><b>왜</b> — 저장소는 이제 글자 없는 설명을 NULL 한 가지로 저장한다(TextRules). 그 전에 들어간 옛 행이 남아 있으면 "설명 없음"이 NULL 과 빈 값 두
 * 가지로 갈리므로 한 번 정리한다. 이미 NULL 이거나 글자가 있는 행은 건드리지 않아 다시 돌려도 결과가 같다(멱등).
 *
 * <p><b>왜 Java 인가</b> — 판정이 Unicode 속성(Cf·Cc·Mn·Me)이라 SQL 정규식으로는 Tibero 짝과 같은 범위를 안정적으로 쓸 수 없어, 앞선 V170·V171
 * 처럼 Java 마이그레이션으로 판정한다.
 *
 * <p><b>판정을 이 클래스 안에 고정한 까닭</b> — Java 마이그레이션은 체크섬이 없어 런타임 클래스({@code TextRules})나 다른 마이그레이션의 판정을
 * 빌려 쓰면, 그쪽이 바뀌어도 알아채지 못하고 이 마이그레이션의 결과가 조용히 달라진다. api CLAUDE.md 규칙대로 판정({@link #HAS_TEXT_AT_V172})을
 * 이 클래스 안에 복사해 고정한다. V171 과 정규식 문자열은 같지만 서로 독립이라 한쪽을 고쳐도 다른 쪽은 영향이 없다.
 *
 * <p><b>Tibero 와의 차이</b> — Tibero V172 는 같은 파일에서 {@code saved_query.folder} 의 BMP Cc 도 보충하지만, PG V171 은 이미 Cc 까지
 * 판정하므로 PG 에는 folder 단계가 없다.
 */
public class V172__blank_analytics_description_to_null extends BaseJavaMigration {

  /**
   * V172 를 쓸 때의 「보이는 글자가 하나 이상 있다」 판정 — V171 의 정규식 문자열을 그대로 복사했다. 마이그레이션은 그때의 판정을 기록하므로
   * TextRules·V171 이 바뀌어도 따라 고치지 않는다(WD-1161).
   */
  private static final Pattern HAS_TEXT_AT_V172 =
      Pattern.compile(
          "(?U)(?s).*[^\\s\\p{Cf}\\p{Cc}\\p{Mn}\\p{Me}\\u115F\\u1160\\u3164\\uFFA0\\u2800"
              + "\\p{Cs}\\u2065\\uFFF0-\\uFFF8"
              + "\\x{E0000}-\\x{E0FFF}].*");

  /** 정리할 표 — 이름은 상수라 SQL 문자열에 이어 붙여도 주입 위험이 없다. */
  private static final String[] TABLES = {"saved_query", "chart", "dashboard"};

  @Override
  public void migrate(Context context) throws Exception {
    blankOutBlankDescriptions(context.getConnection());
  }

  /**
   * 세 표(saved_query·chart·dashboard 순)에서 description 이 글자 없는 행을 NULL 로 바꾸고, 바꾼 행 수의 합계를 돌려준다.
   *
   * <p>테스트가 픽스처에 대해 직접 호출할 수 있도록 public static 으로 둔다. 판정을 Java 에서 하므로 표마다 {@code SELECT} 로 id 를 고른 뒤
   * {@code UPDATE ... WHERE id = ANY(?)} 로 한 번에 바꾼다(id 는 파라미터로 바인딩). 0건이어도 성공이며 멱등이다.
   *
   * @param connection Flyway 가 넘긴 소유자 롤 연결
   * @return description 을 NULL 로 바꾼 행 수 합계
   */
  public static int blankOutBlankDescriptions(Connection connection) throws SQLException {
    int total = 0;
    for (String table : TABLES) {
      total += blankOutTable(connection, table);
    }
    return total;
  }

  /** 한 표의 글자 없는 description 을 NULL 로 바꾸고 바꾼 행 수를 돌려준다. */
  private static int blankOutTable(Connection connection, String table) throws SQLException {
    // 1) 비울 대상 id 를 고른다 — 글자가 하나도 없는 값만 골라야 Tibero V172 판정과 어긋나지 않는다.
    List<Long> blankIds = new ArrayList<>();
    try (PreparedStatement ps =
        connection.prepareStatement(
            "SELECT id, description FROM " + table + " WHERE description IS NOT NULL")) {
      // Flyway 는 autocommit 을 끄고 돌리므로 PG 드라이버가 fetchSize 만큼씩 커서로 나눠 읽는다 — 큰 표도 메모리를 한꺼번에 쓰지 않는다.
      ps.setFetchSize(1000);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          // WHERE ... IS NOT NULL 로 걸러져 description 은 null 이 아니다.
          String description = rs.getString("description");
          if (!HAS_TEXT_AT_V172.matcher(description).matches()) {
            blankIds.add(rs.getLong("id"));
          }
        }
      }
    }
    if (blankIds.isEmpty()) {
      return 0; // 비울 값이 없다 — 성공(멱등).
    }
    // 2) 한 번에 NULL 로 비운다 — id 목록은 파라미터로 바인딩한다.
    try (PreparedStatement ps =
        connection.prepareStatement(
            "UPDATE " + table + " SET description = NULL WHERE id = ANY(?)")) {
      Array ids = connection.createArrayOf("bigint", blankIds.toArray());
      try {
        ps.setArray(1, ids);
        return ps.executeUpdate();
      } finally {
        ids.free();
      }
    }
  }
}
