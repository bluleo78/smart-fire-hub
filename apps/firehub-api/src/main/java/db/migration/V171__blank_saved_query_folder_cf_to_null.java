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
 * V171 — Tibero V171 의 PG 짝(MigrationParityTest, WD-1144).
 *
 * <p><b>무엇을</b> — {@code saved_query.folder} 가 공백·Cf·채움 문자만인 행을 NULL 로 비운다.
 *
 * <p><b>왜</b> — Tibero V170 은 적용됐을 수 있어 체크섬을 바꿀 수 없으므로(forward-only) 빠진 Cf·채움 문자는 새 버전 V171 로 옮겼고, PG
 * 도 같은 버전을 맞춘다. PG V170 은 이미 같은 범위를 비워 보통 0행이다(멱등).
 *
 * <p><b>판정을 이 클래스 안에 고정한 까닭</b> — Java 마이그레이션은 체크섬이 없어 런타임 클래스({@code TextRules})나 다른 마이그레이션의 판정을
 * 빌려 쓰면, 그쪽이 바뀌어도 알아채지 못하고 이 마이그레이션의 결과가 조용히 달라진다. api CLAUDE.md 규칙대로 판정({@link #HAS_TEXT_AT_V171})을
 * 이 클래스 안에 복사해 고정한다(WD-1161). V170 과 정규식 문자열은 같지만 서로 독립이라 한쪽을 고쳐도 다른 쪽은 영향이 없다.
 *
 * <p><b>바로잡는 글</b> — Tibero V170 헤더의 "PG 는 TextRules.hasText 로 판정·목록을 바꿀 때 TextRules 와 함께 고칠 것" 문구는 낡았다 —
 * PG 는 고정 정규식을 쓰고, Tibero 파일은 체크섬 때문에 고치지 못한다(WD-1161).
 */
public class V171__blank_saved_query_folder_cf_to_null extends BaseJavaMigration {

  /**
   * V171 을 쓸 때의 「보이는 글자가 하나 이상 있다」 판정 — V170 의 정규식 문자열을 그대로 복사했다. 마이그레이션은 그때의 판정을 기록하므로
   * TextRules·V170 이 바뀌어도 따라 고치지 않는다(WD-1161).
   */
  private static final Pattern HAS_TEXT_AT_V171 =
      Pattern.compile(
          "(?U)(?s).*[^\\s\\p{Cf}\\p{Cc}\\p{Mn}\\p{Me}\\u115F\\u1160\\u3164\\uFFA0\\u2800"
              + "\\p{Cs}\\u2065\\uFFF0-\\uFFF8"
              + "\\x{E0000}-\\x{E0FFF}].*");

  @Override
  public void migrate(Context context) throws Exception {
    blankOutBlankFolders(context.getConnection());
  }

  /**
   * folder 가 공백·Cf 만인 행의 folder 를 NULL 로 바꾸고, 바꾼 행 수를 돌려준다.
   *
   * <p>테스트가 픽스처에 대해 직접 호출할 수 있도록 public static 으로 둔다. 판정은 {@link #HAS_TEXT_AT_V171} 로 이 클래스 안에서 끝낸다.
   * 판정을 Java 에서 하므로 {@code SELECT} 로 id 를 고른 뒤 {@code UPDATE ... WHERE id = ANY(?)} 로 한 번에 바꾼다(id 는 파라미터로
   * 바인딩). 0건이어도 성공이며, 이미 비워진 값은 다시 걸리지 않아 멱등이다.
   *
   * @param connection Flyway 가 넘긴 소유자 롤 연결
   * @return folder 를 NULL 로 바꾼 행 수
   */
  public static int blankOutBlankFolders(Connection connection) throws SQLException {
    // 1) 비울 대상 id 를 고른다 — 글자가 하나도 없는 값만 골라야 Tibero V171 판정과 어긋나지 않는다.
    List<Long> blankIds = new ArrayList<>();
    try (PreparedStatement ps =
            connection.prepareStatement(
                "SELECT id, folder FROM saved_query WHERE folder IS NOT NULL");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        // WHERE ... IS NOT NULL 로 걸러져 folder 는 null 이 아니다.
        String folder = rs.getString("folder");
        if (!HAS_TEXT_AT_V171.matcher(folder).matches()) {
          blankIds.add(rs.getLong("id"));
        }
      }
    }
    if (blankIds.isEmpty()) {
      return 0; // 비울 값이 없다 — 성공(멱등).
    }
    // 2) 한 번에 NULL 로 비운다 — id 목록은 파라미터로 바인딩한다(SQL 문자열을 조립하지 않는다).
    try (PreparedStatement ps =
        connection.prepareStatement("UPDATE saved_query SET folder = NULL WHERE id = ANY(?)")) {
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
