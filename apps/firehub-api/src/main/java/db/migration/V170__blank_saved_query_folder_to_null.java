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
 * V170 — {@code saved_query.folder} 중 공백·Cf 만인 값을 NULL 로 비운다(WD-1103).
 *
 * <p><b>무엇을</b> — folder 가 비어 있지 않으면서 공백(전각 공백·NBSP 포함)·Cf(폭 없는 문자)만인 행의 folder 값을 NULL 로 바꾼다. 글자가
 * 하나라도 있는 값은 그대로 둔다.
 *
 * <p><b>왜 Java 인가</b> — PG 정규식은 {@code \p{Cf}} 를 지원하지 않는다(실측 {@code invalid escape \ sequence}).
 * 공백·Cf 목록을 SQL 에 적으면 갈라지므로 Java 정규식으로 판단한다 ({@code V125} 가 트랜잭션·동적 대상 때문에 Java 마이그레이션을 쓴 것과 같은
 * 자리). 판정 정규식은 {@link #HAS_TEXT_AT_V170} 참고.
 *
 * <p><b>NULL 을 택한 까닭</b> — 「이름 없음」 같은 가짜 폴더는 무관한 쿼리들을 한 폴더로 뭉치고, NULL 은 이미 「폴더 없음」으로 읽힌다(WD-1103 설계
 * 6).
 *
 * <p><b>바로잡는 글</b> — Tibero V170 헤더의 "PG 는 TextRules.hasText 로 판정·목록을 바꿀 때 TextRules 와 함께 고칠 것" 문구는 낡았다 — PG
 * 는 고정 정규식을 쓰고, Tibero 파일은 체크섬 때문에 고치지 못한다(WD-1161).
 */
public class V170__blank_saved_query_folder_to_null extends BaseJavaMigration {

  /**
   * V170 을 쓸 때의 「보이는 글자가 하나 이상 있다」 판정 — 당시 {@code TextRules.HAS_TEXT} 의 정규식을 {@code
   * NON_VISIBLE}·{@code HAS_VISIBLE} 까지 펼쳐 그대로 복사했다. 마이그레이션은 그때의 판정을 기록하므로 TextRules 가 바뀌어도 따라 고치지
   * 않는다. Java 마이그레이션은 체크섬이 없어 런타임 클래스를 참조하면 바뀌어도 알아채지 못한다(WD-1144).
   */
  private static final Pattern HAS_TEXT_AT_V170 =
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
   * <p>테스트가 픽스처에 대해 직접 호출할 수 있도록 public static 으로 둔다 — 복사본 SQL 로 흉내 내면 파일이 바뀌어도 테스트가 따라가지 못한다. 판정은
   * {@link #HAS_TEXT_AT_V170} 참고.
   *
   * <p>{@code SELECT} 로 id 를 골라 {@code UPDATE ... WHERE id = ANY(?)} 로 한 번에 바꾼다 — 판정을 Java 에서 하기
   * 때문이다. 셸 대신 PreparedStatement 파라미터로 바인딩한다. 0건이어도 성공이다(재실행해도 이미 비워진 값은 다시 걸리지 않아 멱등).
   *
   * @param connection Flyway 가 넘긴 소유자 롤 연결
   * @return folder 를 NULL 로 바꾼 행 수
   */
  public static int blankOutBlankFolders(Connection connection) throws SQLException {
    // 1) 비울 대상 id 를 고른다 — 공백·Cf 만인 값(문자열에 글자가 하나도 없음)만 골라야 V170 판정과 어긋나지 않는다.
    List<Long> blankIds = new ArrayList<>();
    try (PreparedStatement ps =
            connection.prepareStatement(
                "SELECT id, folder FROM saved_query WHERE folder IS NOT NULL");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        // folder IS NOT NULL 로 걸렀지만 방어적으로 한 번 더 본다 — getString 은 SQL NULL 이면 null 이고, null 은 글자가
        // 없으므로 false.
        String folder = rs.getString("folder");
        if (folder == null || !HAS_TEXT_AT_V170.matcher(folder).matches()) {
          blankIds.add(rs.getLong("id"));
        }
      }
    }
    if (blankIds.isEmpty()) {
      return 0; // 비울 값이 없다 — 성공(멱등). UPDATE 를 굳이 붙이지 않는다.
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
