package com.smartfirehub.global.platformdb.tibero.flyway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tibero V171 파일({@code db/migration-tibero/V171__blank_saved_query_folder_cf_to_null.sql})의 문장 자체를
 * Tibero 실서버 없이 읽어 검사한다(WD-1103 리뷰).
 *
 * <p><b>왜 문장을 검사하나.</b> 이 마이그레이션은 Tibero 전용이라 Pod 에서 실행할 수 없고, 실서버에 올라간 뒤에야 틀린 문장이 드러난다. 리뷰에서 지적한 세
 * 가지 — {@code = ''} 는 Tibero 에서 한 행도 고르지 못하고, {@code [[:space:]]} 만 쓰면 NBSP·전각 공백이 새고, 문자 클래스에
 * {@code -} 를 넣으면 다른 문자가 범위로 통째로 새어 들어온다 — 를 실행 없이 잡는다.
 *
 * <p>실행으로 증명되는 부분은 PG 쪽 {@link com.smartfirehub.analytics.SavedQueryFolderBlankMigrationTest} 가
 * 맡는다(같은 일이 Java 마이그레이션으로 간다). 여기는 "Tibero 에만 존재하는 SQL 텍스트"를 본다.
 */
class TiberoV171BlankFolderSqlTest {

  private static final Path TIBERO =
      Path.of(
          "src/main/resources/db/migration-tibero/V171__blank_saved_query_folder_cf_to_null.sql");

  /**
   * 문자 클래스에 들어야 하는 실자 — 파일 머리말에 적은 {@code TextRules} 짝 목록.
   *
   * <p>{@code [[:space:]]} 외에는 전부 한 문자씩 적어야 한다(Tibero 정규식은 {@code \p{Cf}} 와 유니코드 속성을 못 쓴다). 목록을 바꿀
   * 때는 이 배열과 머리말 주석을 함께 고친다.
   */
  private static final int[] REQUIRED = {
    0x00AD, 0x00A0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008,
    0x2009, 0x200A, 0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0x202F, 0x205F, 0x2060, 0x2066, 0x2067,
    0x2068, 0x2069, 0x206A, 0x206B, 0x206C, 0x206D, 0x206E, 0x206F, 0x3000, 0xFEFF
  };

  /** {@code (?U)\s} 에 들지만 파일에 새면 문장이 깨지는 줄바꿈 문자 — 머리말에 뺀 이유를 적어 뒀다. */
  private static final int[] LINE_SEPARATORS = {0x0085, 0x2028, 0x2029};

  @Test
  @DisplayName("V171 은 잘라 낸 결과를 IS NULL 로 본다 — `= ''` 비교는 Tibero 에서 한 행도 못 고른다")
  void 빈_값_비교는_IS_NULL_이다() throws IOException {
    String sql = read();

    assertThat(EMPTY_EQ.matcher(sql).find())
        .as("Tibero 는 `''` 를 NULL 로 보므로 `= ''` 은 비교 결과가 NULL 이 되어 0행이다")
        .isFalse();
    assertThat(sql)
        .as("WHERE 에서는 `folder IS NOT NULL`, 잘라 낸 결과 비교는 `IS NULL` 로 본다")
        .contains("WHERE folder IS NOT NULL")
        .contains("IS NULL");
  }

  private static final Pattern UNISTR_ARG = Pattern.compile("UNISTR\\('([^']*)'\\)");
  private static final Pattern HEX_ESCAPE = Pattern.compile("\\\\([0-9A-Fa-f]{4})");
  private static final Pattern EMPTY_EQ = Pattern.compile("folder\\s*=\\s*''");

  /** 주석 줄({@code --})을 뺀 줄들 — 두 검사가 함께 쓴다. */
  private static Stream<String> codeLines(String sql) {
    return Arrays.stream(sql.split("\\R", -1)).filter(l -> !l.startsWith("--"));
  }

  /** WD-1144 에서 더한 코드점 — 범위는 {@link #expand} 로 한 글자씩 펼친다. */
  private static final int[] ADDED =
      concat(
          expand(0x0600, 0x0605),
          new int[] {0x061C, 0x06DD, 0x070F, 0x0890, 0x0891, 0x08E2, 0x180E},
          expand(0x202A, 0x202E),
          expand(0x2061, 0x2064),
          expand(0xFFF9, 0xFFFB),
          new int[] {0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800, 0x2065},
          expand(0xFFF0, 0xFFF8));

  /** 시험 코드에서 범위를 한 글자씩 펼친다(SQL 에는 범위를 쓰지 않는다). */
  private static int[] expand(int from, int to) {
    return IntStream.rangeClosed(from, to).toArray();
  }

  private static int[] concat(int[]... parts) {
    return Arrays.stream(parts).flatMapToInt(Arrays::stream).toArray();
  }

  /**
   * 패턴 인자의 {@code UNISTR('...')} 에서 {@code \XXXX} 를 코드점으로 풀어 집합을 만든다.
   *
   * <p>주석 줄({@code --})은 빼고 {@code REGEXP_REPLACE(} 부터의 문장만 본다 — 주석의 UNISTR 예시가 섞이지 않게 한다.
   */
  private static Set<Integer> unistrCodePoints(String sql) {
    String code = codeLines(sql).collect(Collectors.joining("\n"));
    int from = code.indexOf("REGEXP_REPLACE(");
    Set<Integer> out = new HashSet<>();
    if (from < 0) {
      return out;
    }
    Matcher u = UNISTR_ARG.matcher(code.substring(from));
    while (u.find()) {
      Matcher h = HEX_ESCAPE.matcher(u.group(1));
      while (h.find()) {
        out.add(Integer.parseInt(h.group(1), 16));
      }
    }
    return out;
  }

  @Test
  @DisplayName("V171 문자 클래스는 UNISTR 로 NBSP·전각 공백·Cf·채움 문자를 모두 들고, `-` 와 줄바꿈은 넣지 않는다")
  void 문자_클래스에_필요한_코드점이_들고_줄바꿈은_없다() throws IOException {
    String sql = read();
    assertThat(sql).as("[[:space:]] 는 패턴에 남아 있어야 한다").contains("[[:space:]");
    Set<Integer> cps = unistrCodePoints(sql);
    assertThat(cps).as("UNISTR 인자를 찾을 수 없다").isNotEmpty();

    for (int cp : concat(REQUIRED, ADDED)) {
      assertThat(cps.contains(cp)).as("U+%04X 이 UNISTR 목록에 없다 — TextRules 와 어긋난다", cp).isTrue();
    }
    assertThat(cps.contains((int) '-')).as("`-` 를 문자 클래스에 두면 범위로 새어 든다").isFalse();
    for (int cp : LINE_SEPARATORS) {
      assertThat(cps.contains(cp)).as("U+%04X 은 문자 클래스에 넣지 않는다", cp).isFalse();
      // 주석 포함 파일 전체에 실자가 없어야 한다(\R split 이 그 글자를 삼켜 줄 검사로는 못 본다)
      assertThat(sql.indexOf(cp)).as("U+%04X 이 파일에 있다", cp).isLessThan(0);
    }
    assertThat(sql)
        .as("패턴이 DB 문자셋으로 바뀌지 않도록 원본을 TO_NCHAR 로 비교한다")
        .contains("REGEXP_REPLACE(TO_NCHAR(folder),");
  }

  @Test
  @DisplayName("V171 의 주석이 아닌 줄에는 ASCII 밖 글자가 없다 — 실자는 문자셋 변환에서 '?' 로 바뀐다(WD-1144)")
  void nonAsciiOutsideComments_none() throws IOException {
    codeLines(read())
        .forEach(
            line ->
                line.codePoints()
                    .filter(c -> c > 0x7F)
                    .findFirst()
                    .ifPresent(c -> Assertions.fail("주석 아닌 줄에 U+%04X: %s".formatted(c, line))));
  }

  private static String read() throws IOException {
    assertThat(Files.exists(TIBERO)).as("%s 가 없다", TIBERO).isTrue();
    return Files.readString(TIBERO);
  }
}
