package com.smartfirehub.global.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * PG 전용 SQL 문자열이 허용된 파일 밖에 새로 생기지 않도록 소스를 훑어 고정한다(WD-486).
 *
 * <p>무엇을: {@code src/main/java} 의 모든 .java 를 줄 단위로 읽어(주석 줄 제외) {@code pg_advisory}, {@code
 * set_config(}, {@code "LISTEN }, {@code "NOTIFY }, {@code hashtextextended} 가 나오는 파일을 모아, 허용 집합
 * 밖이면 실패한다.
 *
 * <p>왜: ArchUnit 은 클래스 의존만 보므로 문자열 안의 PG 전용 SQL 은 못 잡는다. 플랫폼 DB 를 Tibero 로 옮길 때 서비스·리포지토리에 이런 문장이
 * 남아 있으면 조용히 깨진다. 잠금·세션·LISTEN 문장은 {@code global/platformdb/postgres/} 에만 둔다.
 *
 * <p>WD-486 완료 — 허용 집합의 사유는 {@link #ALLOWED} 각 항목에 적었다.
 */
class PlatformDbPgSpecificSourceTest {

  private static final Path MAIN_JAVA = Paths.get("src/main/java");

  /** 검사할 PG 전용 토큰. */
  private static final List<String> TOKENS =
      List.of("pg_advisory", "set_config(", "\"LISTEN ", "\"NOTIFY ", "hashtextextended");

  /** 허용 경로(상대경로에 포함되는 부분 문자열) → 사유. */
  private static final Map<String, String> ALLOWED =
      Map.of(
          "global/platformdb/postgres/",
          "PG 구현 패키지 — PG 전용 SQL 이 있는 것이 존재 이유",
          "securitylevel/pythonread/PythonReadGrantSync.java",
          "lockTenantSync(public static)는 PG 데이터 저장소 경로(동기화·REPLACE 맞바꿈)가 데이터 저장소 연결에서 쓴다 — 한 직렬화 = 한 저장소(WD-493)",
          "global/tenant/TenantPipelineRoleProvisioner.java",
          "R2/R5: 롤 프로비저너의 hashtext(...)::bigint inline 잠금은 PG 데이터 저장소 인프라 안에 둔다",
          "pipeline/service/SqlScriptExecutor.java",
          "OOS(§0.2): PG 데이터 경로의 출력 테이블 잠금 키 식(OutputSwapLock 과 같은 해시)",
          "dataset/service/PostgresDataTableStore.java",
          "OOS(§0.2): PG 데이터 저장소 구현 — 필드 인덱스 직렬화 잠금",
          "dataset/storage/DatasetStructureLock.java",
          "OOS(§0.2): PG 데이터 경로의 데이터셋 구조 공유 잠금 SQL — SQL 스텝이 PG 데이터 저장소 트랜잭션 첫 문장으로 쓴다(WD-670, Tibero 는 후속)",
          "pipeline/service/validator/SqlValidator.java",
          "OOS(§0.2): 사용자 SQL 검증",
          "global/util/SqlValidationUtils.java",
          "OOS(§0.2): 사용자 SQL 검증");

  /** 주석 줄(trim 후 {@code *}, {@code //}, {@code /*} 로 시작)인가. */
  private static boolean isComment(String line) {
    String t = line.trim();
    return t.startsWith("*") || t.startsWith("//") || t.startsWith("/*");
  }

  private static boolean allowed(String rel) {
    return ALLOWED.keySet().stream().anyMatch(rel::contains);
  }

  /** {@code src/main/java} 아래 모든 파일의 상대경로(구분자 {@code /}). 두 테스트가 같은 걷기를 공유한다. */
  private static List<String> listRelativePaths() throws IOException {
    try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
      return files.map(p -> MAIN_JAVA.relativize(p).toString().replace('\\', '/')).toList();
    }
  }

  /** 주석 줄을 뺀 본문에 PG 전용 토큰이 있는 파일인가. */
  private static boolean hasToken(String rel) throws IOException {
    for (String line : Files.readAllLines(MAIN_JAVA.resolve(rel))) {
      if (!isComment(line) && TOKENS.stream().anyMatch(line::contains)) {
        return true;
      }
    }
    return false;
  }

  /** 허용 집합 밖 파일에는 PG 전용 토큰이 없어야 한다. */
  @Test
  void pgSpecificSqlOnlyInAllowedFiles() throws IOException {
    TreeSet<String> hits = new TreeSet<>();
    for (String rel : listRelativePaths()) {
      if (rel.endsWith(".java") && hasToken(rel)) {
        hits.add(rel);
      }
    }
    // 허용된 파일을 빼고 남은 것이 위반이다.
    hits.removeIf(PlatformDbPgSpecificSourceTest::allowed);
    assertThat(hits)
        .as(
            "PG 전용 SQL 토큰 %s 은 허용 집합 밖에 둘 수 없다 — 플랫폼 인터페이스(global.platformdb)를 쓰거나 사유와 함께 허용 집합에 추가",
            TOKENS)
        .isEmpty();
  }

  /** Tibero 테넌트 컨텍스트 심기 호출 — 대소문자를 가리지 않는다(Tibero 식별자는 대소문자 무시). */
  private static final String TIBERO_SET_TENANT = "fh_tenant.set_tenant";

  /** 그 호출을 둘 수 있는 유일한 파일. */
  private static final String TIBERO_SET_TENANT_OWNER =
      "com/smartfirehub/global/platformdb/tibero/TiberoPlatformTenantSession.java";

  /** 주석 줄을 뺀 본문에 Tibero 테넌트 심기 호출이 있는 파일인가. */
  private static boolean hasTiberoSetTenant(String rel) throws IOException {
    for (String line : Files.readAllLines(MAIN_JAVA.resolve(rel))) {
      if (!isComment(line) && line.toLowerCase(Locale.ROOT).contains(TIBERO_SET_TENANT)) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@code FH_TENANT.set_tenant} 는 {@code TiberoPlatformTenantSession} 한 파일에서만 부른다(WD-492 스펙 「남는
   * 위험」).
   *
   * <p>왜: Tibero 컨텍스트는 세션 범위다. 트랜잭션 매니저 밖에서 커넥션을 빌려 심으면 아무도 지우지 않은 채 풀로 돌아가 다음 요청이 이전 테넌트를 물려받는다.
   * 지금 심는 길은 {@code PlatformTenantSession} 두 메서드뿐이고 둘 다 트랜잭션 매니저가 지운다. 허용 파일에 호출이 실제로 있는지도 본다 — 이름이
   * 바뀌어 규칙이 헛돌지 않게.
   */
  @Test
  void tiberoSetTenantOnlyInTenantSession() throws IOException {
    TreeSet<String> hits = new TreeSet<>();
    for (String rel : listRelativePaths()) {
      if (rel.endsWith(".java") && hasTiberoSetTenant(rel)) {
        hits.add(rel);
      }
    }
    assertThat(hits)
        .as(
            "FH_TENANT.set_tenant 는 %s 밖에서 부를 수 없다 — PlatformTenantSession 을 쓴다(세션 범위 컨텍스트가 풀에 남는다)",
            TIBERO_SET_TENANT_OWNER)
        .containsExactly(TIBERO_SET_TENANT_OWNER);
  }

  /** 허용 경로가 실제로 존재해야 한다 — 이름이 바뀌어 허용이 조용히 무의미해지는 것을 막는다. */
  @Test
  void allowedPathsExist() throws IOException {
    List<String> all = listRelativePaths();
    for (String a : ALLOWED.keySet()) {
      assertThat(all.stream().anyMatch(f -> f.contains(a))).as("허용 경로가 존재해야 한다: %s", a).isTrue();
    }
  }
}
