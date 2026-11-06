package com.smartfirehub.global.platformdb.tibero.flyway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * PG 마이그레이션과 Tibero 마이그레이션의 버전 짝 검사(WD-490, ADR 0001 결정 2).
 *
 * <p>규칙: Tibero 폴더({@code db/migration-tibero})는 기준선 V155(스키마)·V155.1(시드)로 시작하고, 그 뒤 PG 에 추가하는 Vn
 * 마다 같은 번호 Vn 을 둔다(할 일이 없으면 주석만 있는 파일). 이 검사가 PG 에만 새 버전을 넣은 PR 을 매 PR 의 PG 회귀에서 잡는다 — Tibero 실서버
 * 없이 돈다.
 *
 * <p>PG 쪽은 SQL 폴더와 Java 마이그레이션 폴더({@code db.migration} 패키지)를 함께 센다. 버전 비교는 Flyway 와 같은 {@link
 * MigrationVersion} 으로 한다(155.1 과 155.10 은 다른 버전, 1.2.3 같은 여러 마디 버전). 기준선을 올려 이 검사를 "고치지" 말 것 —
 * 기준선은 Tibero 설치본이 이미 적용한 스키마라 바꿀 수 없다.
 */
class MigrationParityTest {

  /** Tibero 기준선 — PG V1~V155 가 V155 한 파일에 담겼다. */
  private static final int BASELINE = 155;

  /** 기준선이 차지하는 Tibero 버전(스키마·시드). */
  private static final Set<MigrationVersion> BASELINE_VERSIONS =
      Set.of(MigrationVersion.fromVersion("155"), MigrationVersion.fromVersion("155.1"));

  private static final Pattern VERSIONED = Pattern.compile("^V([0-9._]+?)__.+\\.(sql|java)$");

  private static final Path PG_SQL = Path.of("src/main/resources/db/migration");
  private static final Path PG_JAVA = Path.of("src/main/java/db/migration");
  private static final Path TIBERO = Path.of("src/main/resources/db/migration-tibero");

  /** 기준선 뒤 PG 버전마다 같은 Tibero 버전이 있고, Tibero 에만 있는 버전은 없다. */
  @Test
  void tiberoVersions_equalBaselinePlusPgVersionsAfterBaseline() throws IOException {
    MigrationVersion baseline = MigrationVersion.fromVersion(String.valueOf(BASELINE));
    Set<MigrationVersion> expected = new TreeSet<>(BASELINE_VERSIONS);
    for (MigrationVersion v : pgVersions()) {
      if (v.isNewerThan(baseline)) expected.add(v);
    }
    Set<MigrationVersion> tibero = versions(TIBERO);
    Set<MigrationVersion> missing = new TreeSet<>(expected);
    missing.removeAll(tibero);
    Set<MigrationVersion> extra = new TreeSet<>(tibero);
    extra.removeAll(expected);

    assertThat(missing)
        .as("PG 에 있는데 db/migration-tibero 에 없는 버전 — 같은 번호의 Tibero 파일(할 일이 없으면 주석만)을 추가할 것")
        .isEmpty();
    assertThat(extra)
        .as("PG 에 없는 Tibero 버전 — Tibero 에만 스키마를 바꾸지 말 것(기준선 V155·V155.1 제외)")
        .isEmpty();
  }

  /**
   * 기준선 이하의 PG 버전은 V1~V155 정수 그대로 얼어 있다. 기준선 뒤에 V154_1 같은 끼워 넣기 버전이나 기준선과 겹치는 V155_1 을 PG 에 추가하면, 위
   * 검사는 "기준선 안"으로 보고 놓치므로 여기서 막는다 — 그런 변경은 기준선보다 큰 새 버전으로 낸다.
   */
  @Test
  void pgVersionsUpToBaseline_areFrozen() throws IOException {
    // 첫 마디가 155 이하인 버전 전부(155.1 처럼 기준선과 첫 마디가 같은 버전도 포함)
    Set<MigrationVersion> upToBaseline = new TreeSet<>();
    for (MigrationVersion v : pgVersions()) {
      if (v.getMajor().intValue() <= BASELINE) upToBaseline.add(v);
    }
    Set<MigrationVersion> frozen = new TreeSet<>();
    IntStream.rangeClosed(1, BASELINE)
        .forEach(i -> frozen.add(MigrationVersion.fromVersion(String.valueOf(i))));
    assertThat(upToBaseline).as("기준선(V155) 이하 PG 버전은 V1~V155 그대로여야 한다").isEqualTo(frozen);
  }

  /** PG SQL 폴더 + Java 마이그레이션 폴더의 버전. */
  private static Set<MigrationVersion> pgVersions() throws IOException {
    Set<MigrationVersion> pg = versions(PG_SQL);
    pg.addAll(versions(PG_JAVA));
    return pg;
  }

  /** 폴더의 Flyway 버전 마이그레이션(V{버전}__{설명}) 번호들. 폴더가 없으면 예외 — 0개끼리 같다고 헛통과하지 않는다. */
  private static Set<MigrationVersion> versions(Path dir) throws IOException {
    Set<MigrationVersion> out = new TreeSet<>();
    try (Stream<Path> files = Files.list(dir)) {
      for (Path f : (Iterable<Path>) files::iterator) {
        Matcher m = VERSIONED.matcher(f.getFileName().toString());
        if (m.matches()) out.add(MigrationVersion.fromVersion(m.group(1)));
      }
    }
    return out;
  }
}
