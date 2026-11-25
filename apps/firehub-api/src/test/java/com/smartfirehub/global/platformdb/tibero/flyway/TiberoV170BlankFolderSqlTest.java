package com.smartfirehub.global.platformdb.tibero.flyway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tibero V170 파일({@code db/migration-tibero/V170__blank_saved_query_folder_to_null.sql})의 문장 자체를
 * Tibero 실서버 없이 읽어 검사한다(WD-1103 리뷰).
 *
 * <p><b>왜 문장을 검사하나.</b> 이 마이그레이션은 Tibero 전용이라 Pod 에서 실행할 수 없고, 실서버에 올라간 뒤에야 틀린 문장이 드러난다. 리뷰에서 지적한 세
 * 가지 — {@code = ''} 는 Tibero 에서 한 행도 고르지 못하고, {@code [[:space:]]} 만 쓰면 NBSP·전각 공백이 새고, 문자 클래스에
 * {@code -} 를 넣으면 다른 문자가 범위로 통째로 새어 들어온다 — 를 실행 없이 잡는다.
 *
 * <p>실행으로 증명되는 부분은 PG 쪽 {@link com.smartfirehub.analytics.SavedQueryFolderBlankMigrationTest} 가
 * 맡는다(같은 일이 Java 마이그레이션으로 간다). 여기는 "Tibero 에만 존재하는 SQL 텍스트"를 본다.
 */
class TiberoV170BlankFolderSqlTest {

  private static final Path TIBERO =
      Path.of("src/main/resources/db/migration-tibero/V170__blank_saved_query_folder_to_null.sql");

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
  @DisplayName("V170 은 잘라 낸 결과를 IS NULL 로 본다 — `= ''` 비교는 Tibero 에서 한 행도 못 고른다")
  void 빈_값_비교는_IS_NULL_이다() throws IOException {
    String sql = read();

    assertThat(Pattern.compile("folder\\s*=\\s*''").matcher(sql).find())
        .as("Tibero 는 `''` 를 NULL 로 보므로 `= ''` 은 비교 결과가 NULL 이 되어 0행이다")
        .isFalse();
    assertThat(sql)
        .as("WHERE 에서는 `folder IS NOT NULL`, 잘라 낸 결과 비교는 `IS NULL` 로 본다")
        .contains("WHERE folder IS NOT NULL")
        .contains("IS NULL");
  }

  @Test
  @DisplayName("V170 문자 클래스는 NBSP·전각 공백·Cf 실자를 모두 들고, `-` 와 줄바꿈은 넣지 않는다")
  void 문자_클래스에_필요한_코드점이_들고_줄바꿈은_없다() throws IOException {
    String sql = read();
    Matcher m = Pattern.compile("REGEXP_REPLACE\\(folder,\\s*'([^']*)'").matcher(sql);
    assertThat(m.find()).as("REGEXP_REPLACE 의 문자 클래스를 찾을 수 없다").isTrue();
    String cls = m.group(1);

    for (int cp : REQUIRED) {
      assertThat(cls.indexOf(cp))
          .as("U+%04X 이 문자 클래스에 없다 — `[[:space:]]` 만 믿으면 NBSP·전각 공백이 TextRules 와 어긋난다", cp)
          .isNotNegative();
    }
    assertThat(cls.indexOf('-')).as("`-` 를 문자 클래스에 두면 다른 문자가 범위로 통째로 새어 들어간다").isNegative();
    for (int cp : LINE_SEPARATORS) {
      assertThat(cls.indexOf(cp)).as("U+%04X 은 새는 위험이 있어 문자 클래스에 넣지 않는다", cp).isNegative();
      assertThat(sql.indexOf(cp)).as("U+%04X 이 파일 어디엔가 있다 — SQL 파일이 새어 깨진다", cp).isNegative();
    }
  }

  private static String read() throws IOException {
    assertThat(Files.exists(TIBERO)).as("%s 가 없다", TIBERO).isTrue();
    return Files.readString(TIBERO);
  }
}
