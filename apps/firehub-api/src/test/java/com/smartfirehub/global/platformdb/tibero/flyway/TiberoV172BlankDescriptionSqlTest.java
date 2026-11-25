package com.smartfirehub.global.platformdb.tibero.flyway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tibero V172 파일({@code db/migration-tibero/V172__blank_analytics_description_to_null.sql})의 문장 자체를
 * Tibero 실서버 없이 읽어 검사한다(WD-1161).
 *
 * <p><b>왜 문장을 검사하나.</b> 이 마이그레이션은 Tibero 전용이라 Pod 에서 실행할 수 없다. 실행으로 증명되는 부분은 PG 쪽 {@link
 * com.smartfirehub.analytics.AnalyticsDescriptionBlankMigrationTest} 가 맡고, 여기서는 "Tibero 에만 존재하는 SQL 텍스트"가 빈 값 비교·코드점
 * 목록·ASCII 규칙을 지키는지 본다. 파일은 문장 넷(saved_query·chart·dashboard 의 description, saved_query 의 folder)이라 {@code ;} 로
 * 잘라 문장별로 본다.
 */
class TiberoV172BlankDescriptionSqlTest {

  private static final Path V172 =
      Path.of("src/main/resources/db/migration-tibero/V172__blank_analytics_description_to_null.sql");
  private static final Path V171 =
      Path.of(
          "src/main/resources/db/migration-tibero/V171__blank_saved_query_folder_cf_to_null.sql");

  private static final Pattern UNISTR_ARG = Pattern.compile("UNISTR\\('([^']*)'\\)");
  private static final Pattern HEX_ESCAPE = Pattern.compile("\\\\([0-9A-Fa-f]{4})");
  private static final Pattern EMPTY_EQ = Pattern.compile("(description|folder)\\s*=\\s*''");

  /** BMP Cc — U+0001–001F 와 U+007F–009F. */
  private static final int[] CC = concat(expand(0x0001, 0x001F), expand(0x007F, 0x009F));

  /** 줄·문단 구분자 — PG 의 {@code (?U)\s} 와 맞추려 네 문장 모두 넣는다(WD-1161 코드 리뷰). */
  private static final int[] LINE_SEPARATORS = {0x2028, 0x2029};

  @Test
  @DisplayName("V172 는 세 표의 description 문장과 saved_query folder 문장을 가진다")
  void 문장이_네_개다() throws IOException {
    String sql = read(V172);
    statement("UPDATE saved_query\n   SET description"); // 없으면 AssertionError
    assertThat(sql).contains("UPDATE chart\n   SET description = NULL");
    assertThat(sql).contains("UPDATE dashboard\n   SET description = NULL");
    assertThat(sql).contains("UPDATE saved_query\n   SET folder = NULL");
    assertThat(statements(sql)).hasSize(4);
  }

  @Test
  @DisplayName("V172 는 잘라 낸 결과를 IS NULL 로 본다 — `= ''` 비교는 Tibero 에서 한 행도 못 고른다")
  void 빈_값_비교는_IS_NULL_이다() throws IOException {
    String code = code(read(V172));
    assertThat(EMPTY_EQ.matcher(code).find()).as("`= ''` 는 Tibero 에서 0행").isFalse();
    for (String stmt : statements(read(V172))) {
      // folder 는 `…) IS NULL`, description 은 길이 가드 CASE 라 `END IS NULL` 로 끝난다.
      assertThat(stmt).contains("IS NOT NULL").containsPattern("(\\)|END) IS NULL");
    }
  }

  @Test
  @DisplayName("V172 의 모든 UNISTR 인자는 `\\XXXX` 연속만이다")
  void UNISTR_인자는_16진_이스케이프_연속만이다() throws IOException {
    Matcher u = UNISTR_ARG.matcher(code(read(V172)));
    int count = 0;
    while (u.find()) {
      count++;
      assertThat(u.group(1)).as("UNISTR 인자").matches("(\\\\[0-9A-F]{4})+");
    }
    assertThat(count).as("UNISTR 인자를 찾을 수 없다").isPositive();
  }

  @Test
  @DisplayName("description 문장의 코드점은 V171 목록과 BMP Cc 를 모두 포함한다")
  void description_문장은_V171_목록과_Cc_를_든다() throws IOException {
    Set<Integer> required = new HashSet<>(v171CodePoints());
    Arrays.stream(CC).forEach(required::add);
    for (String stmt : statements(read(V172))) {
      if (!stmt.contains("SET description")) {
        continue;
      }
      Set<Integer> cps = codePoints(stmt);
      for (int cp : required) {
        assertThat(cps.contains(cp)).as("U+%04X 이 description 목록에 없다", cp).isTrue();
      }
      for (int cp : LINE_SEPARATORS) {
        assertThat(cps.contains(cp)).as("U+%04X 이 description 목록에 없다", cp).isTrue();
      }
      assertThat(stmt).contains("REGEXP_REPLACE(TO_NCHAR(description),");
      // VARCHAR2(65532) 를 TO_NCHAR 로 바꿀 때 길이 한도에 걸리지 않게 2,000자(DescriptionLimits) 이하만 정규식으로 본다.
      assertThat(stmt).contains("CASE\n         WHEN LENGTH(description) <= 2000 THEN");
      assertThat(stmt).contains("ELSE TO_NCHAR('x')");
      // 패턴 전체를 N 형식으로 — VARCHAR 머리와 섞여 DB 문자셋으로 바뀌지 않게.
      assertThat(stmt).contains("TO_NCHAR('[[:space:]')");
    }
  }

  @Test
  @DisplayName("folder 문장도 V171 목록·BMP Cc·줄 구분자를 모두 든다 — PG V170·V171 과 같은 결과")
  void folder_문장은_Cc_와_줄_구분자를_든다() throws IOException {
    String stmt = statement("UPDATE saved_query\n   SET folder");
    Set<Integer> cps = codePoints(stmt);
    Set<Integer> required = new HashSet<>(v171CodePoints());
    Arrays.stream(CC).forEach(required::add);
    Arrays.stream(LINE_SEPARATORS).forEach(required::add);
    for (int cp : required) {
      assertThat(cps.contains(cp)).as("U+%04X 이 folder 목록에 없다", cp).isTrue();
    }
    assertThat(stmt).contains("REGEXP_REPLACE(TO_NCHAR(folder),");
    assertThat(stmt).contains("TO_NCHAR('[[:space:]')");
  }

  @Test
  @DisplayName("문자 클래스에 `]`·`^`·`-`·`\\` 가 없다 — 괄호 식이 깨지거나 범위로 샌다")
  void 정규식_특수문자가_없다() throws IOException {
    for (String stmt : statements(read(V172))) {
      Set<Integer> cps = codePoints(stmt);
      for (int cp : new int[] {']', '^', '-', '\\'}) {
        assertThat(cps.contains(cp)).as("U+%04X 은 문자 클래스에 못 둔다", cp).isFalse();
      }
    }
  }

  @Test
  @DisplayName("V172 의 주석이 아닌 줄에는 ASCII 밖 글자가 없다")
  void nonAsciiOutsideComments_none() throws IOException {
    code(read(V172))
        .codePoints()
        .filter(c -> c > 0x7F)
        .findFirst()
        .ifPresent(c -> org.junit.jupiter.api.Assertions.fail("주석 아닌 줄에 U+%04X".formatted(c)));
  }

  // ── 도우미 ──────────────────────────────────────────────────────────────────

  /** 주석 줄({@code --})을 뺀 본문. */
  private static String code(String sql) {
    return Arrays.stream(sql.split("\\R", -1))
        .filter(l -> !l.startsWith("--"))
        .collect(Collectors.joining("\n"));
  }

  /** 주석을 뺀 본문을 {@code ;} 로 잘라 비지 않은 문장 목록으로 만든다. */
  private static List<String> statements(String sql) {
    return Arrays.stream(code(sql).split(";"))
        .map(String::strip)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toList());
  }

  /** 주어진 머리로 시작하는 문장 하나. */
  private static String statement(String prefix) throws IOException {
    return statements(read(V172)).stream()
        .filter(s -> s.startsWith(prefix))
        .findFirst()
        .orElseThrow(() -> new AssertionError("문장이 없다: " + prefix));
  }

  /** 한 문장의 UNISTR 인자에서 {@code \XXXX} 를 코드점으로 푼 집합. */
  private static Set<Integer> codePoints(String stmt) {
    Set<Integer> out = new HashSet<>();
    Matcher u = UNISTR_ARG.matcher(stmt);
    while (u.find()) {
      Matcher h = HEX_ESCAPE.matcher(u.group(1));
      while (h.find()) {
        out.add(Integer.parseInt(h.group(1), 16));
      }
    }
    return out;
  }

  /** V171 파일의 UNISTR 코드점 목록 — 기준이 되는 "V171 목록". */
  private static Set<Integer> v171CodePoints() throws IOException {
    Set<Integer> cps = codePoints(code(read(V171)));
    assertThat(cps).as("V171 UNISTR 목록").isNotEmpty();
    return cps;
  }

  private static int[] expand(int from, int to) {
    return IntStream.rangeClosed(from, to).toArray();
  }

  private static int[] concat(int[]... parts) {
    return Arrays.stream(parts).flatMapToInt(Arrays::stream).toArray();
  }

  private static String read(Path path) throws IOException {
    assertThat(Files.exists(path)).as("%s 가 없다", path).isTrue();
    return Files.readString(path);
  }
}
