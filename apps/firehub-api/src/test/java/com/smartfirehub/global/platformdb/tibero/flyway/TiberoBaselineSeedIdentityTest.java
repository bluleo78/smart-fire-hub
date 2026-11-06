package com.smartfirehub.global.platformdb.tibero.flyway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Tibero 기준선 시드가 ID 를 명시로 넣는 모든 테이블에서 IDENTITY 시작값이 시드 최대 ID 보다 큰지 파일만 읽어 본다(WD-490).
 *
 * <p>왜: 시드는 PG 와 같은 ID 를 명시로 넣는데 IDENTITY 가 1 부터 시작하면 앱의 첫 INSERT(ID 생략)가 시드 행과 부딪혀 유일 위반(-10007)이
 * 난다. 실서버 시험({@link TiberoBaselineSchemaLiveTest})은 tenant·permission 두 테이블만 실제로 넣어 보므로, 나머지 시드
 * 테이블은 이 정적 검사가 매 PR 의 PG 회귀에서 지킨다.
 */
class TiberoBaselineSeedIdentityTest {

  private static final Path DIR = Path.of("src/main/resources/db/migration-tibero");

  /** CREATE TABLE 이름 ( … id … IDENTITY [START WITH n] … */
  private static final Pattern TABLE =
      Pattern.compile("CREATE TABLE (\"?\\w+\"?) \\(\\n  id [^\\n]*?IDENTITY( START WITH (\\d+))?");

  /** INSERT INTO 이름 (id, …) VALUES (숫자, */
  private static final Pattern SEED =
      Pattern.compile("INSERT INTO (\"?\\w+\"?) \\(id, [^)]*\\) VALUES \\((\\d+),");

  @Test
  void identityStart_isAfterMaxSeedId_forEverySeededTable() throws IOException {
    String schema = Files.readString(DIR.resolve("V155__tibero_baseline_schema.sql"));
    String seed = Files.readString(DIR.resolve("V155.1__tibero_baseline_seed.sql"));

    Map<String, Long> start = new HashMap<>();
    Matcher t = TABLE.matcher(schema);
    while (t.find()) start.put(t.group(1), t.group(3) == null ? 1L : Long.parseLong(t.group(3)));

    Map<String, Long> maxSeedId = new HashMap<>();
    Matcher s = SEED.matcher(seed);
    while (s.find()) maxSeedId.merge(s.group(1), Long.parseLong(s.group(2)), Math::max);

    assertThat(maxSeedId).as("시드가 ID 를 넣는 테이블을 하나도 못 찾으면 정규식이 헛돈다").isNotEmpty();
    List<String> wrong = new ArrayList<>();
    maxSeedId.forEach(
        (table, max) -> {
          Long first = start.get(table);
          if (first == null || first <= max) {
            wrong.add(table + "(시드 최대 " + max + ", IDENTITY 시작 " + first + ")");
          }
        });
    assertThat(wrong).as("IDENTITY 를 시드 최대 ID 다음부터 시작하지 않는 테이블").isEmpty();
  }
}
