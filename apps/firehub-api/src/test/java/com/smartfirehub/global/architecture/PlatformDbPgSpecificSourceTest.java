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
import java.util.regex.Pattern;
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
 * <p>매퍼 XML({@code src/main/resources/mybatis/**}, WD-706)에도 같은 검사를 건다 — PG 전용 문법은 {@code
 * databaseId="postgresql"} 문장 안에서만, {@code FH_TENANT.set_tenant}·{@code clear_tenant} 는 XML 어디서도
 * 금지다.
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

  /** 플랫폼 매퍼 XML 위치(WD-706). */
  private static final Path MYBATIS_XML = Paths.get("src/main/resources/mybatis");

  /**
   * XML 에서 PG 전용으로 보는 토큰(소문자). 자바용 {@link #TOKENS} 에 PG 문법 흔적({@code ::} 캐스트·ON
   * CONFLICT·ILIKE·RETURNING)을 더한다.
   */
  private static final List<String> XML_PG_TOKENS =
      List.of(
          "pg_advisory",
          "set_config(",
          "listen ",
          "notify ",
          "hashtextextended",
          "::",
          "on conflict",
          "ilike",
          "returning ");

  private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

  /** {@code databaseId="postgresql"} 문장·조각 통째(같은 태그로 닫힐 때까지). */
  private static final Pattern PG_ONLY_ELEMENT =
      Pattern.compile(
          "<(select|insert|update|delete|sql)\\b[^>]*databaseId=\"postgresql\"[^>]*>.*?</\\1>",
          Pattern.DOTALL);

  /**
   * SQL/JSON 함수의 반환형 절({@code JSON_VALUE(col, '$.x' RETURNING CLOB)}) — 경로 문자열 바로 뒤의 RETURNING 은 PG
   * 의 {@code INSERT … RETURNING} 이 아니라 표준 SQL/JSON 문법이라 검사 전에 걷어낸다(WD-709 Tibero 리포트 판정). JSON 경로
   * 리터럴({@code '$…'})과 그 뒤의 RETURNING 만 걷어낸다 — 다른 문자열 리터럴 뒤의 DML RETURNING({@code SET s = 'x'
   * RETURNING id})은 그대로 위반으로 남는다.
   */
  private static final Pattern SQL_JSON_RETURNING =
      Pattern.compile("'\\$[^']*'\\s+returning\\s+", Pattern.CASE_INSENSITIVE);

  /** Tibero 테넌트 컨텍스트 직접 조작({@code set_tenant}·{@code clear_tenant}) — XML 어디서도 금지. */
  private static final Pattern FH_TENANT_CONTEXT_CALL =
      Pattern.compile("fh_tenant\\s*\\.\\s*(set_tenant|clear_tenant)", Pattern.CASE_INSENSITIVE);

  /**
   * 매퍼 XML 한 개의 위반 목록. 주석은 지우고, PG 전용 토큰은 {@code databaseId="postgresql"} 요소 밖에서만 위반으로 본다. {@code
   * FH_TENANT.set_tenant}·{@code clear_tenant} 는 어디서든 위반이다.
   */
  static List<String> xmlViolations(String xml) {
    String body = XML_COMMENT.matcher(xml).replaceAll("");
    List<String> out = new java.util.ArrayList<>();
    if (FH_TENANT_CONTEXT_CALL.matcher(body).find()) {
      out.add("FH_TENANT.set_tenant/clear_tenant 직접 호출");
    }
    String common =
        SQL_JSON_RETURNING
            .matcher(PG_ONLY_ELEMENT.matcher(body).replaceAll(""))
            .replaceAll("''")
            .toLowerCase(Locale.ROOT);
    for (String t : XML_PG_TOKENS) {
      if (common.contains(t)) {
        out.add("databaseId=\"postgresql\" 밖의 PG 전용 문법 '" + t + "'");
      }
    }
    return out;
  }

  /** 매퍼 XML 은 PG 전용 문법을 databaseId="postgresql" 문장 안에서만 쓰고, 테넌트 컨텍스트를 직접 만지지 않는다. */
  @Test
  void mybatisXmlHasNoPgSpecificSyntaxOutsidePostgresStatements() throws IOException {
    TreeSet<String> hits = new TreeSet<>();
    try (Stream<Path> files = Files.walk(MYBATIS_XML)) {
      for (Path p : files.filter(f -> f.toString().endsWith(".xml")).toList()) {
        List<String> v = xmlViolations(Files.readString(p));
        if (!v.isEmpty()) {
          hits.add(MYBATIS_XML.relativize(p) + " " + v);
        }
      }
    }
    assertThat(hits)
        .as("매퍼 XML 의 PG 전용 문법은 databaseId=\"postgresql\" 문장에만, set_tenant/clear_tenant 는 금지")
        .isEmpty();
  }

  /** 검사 규칙 자체 시험 — 위반 XML 은 잡고 허용 XML 은 통과시킨다. */
  @Test
  void xmlRuleDetectsViolationsAndAllowsPostgresOnlyStatements() {
    String pgOnly =
        "<mapper><!-- ::text 는 주석 --><select id=\"a\" databaseId=\"postgresql\">select x::text from t</select>"
            + "<select id=\"a\" databaseId=\"tibero\">select x from t</select></mapper>";
    assertThat(xmlViolations(pgOnly)).isEmpty();
    assertThat(xmlViolations("<mapper><select id=\"b\">select x::text from t</select></mapper>"))
        .isNotEmpty();
    assertThat(
            xmlViolations(
                "<mapper><insert id=\"c\" databaseId=\"postgresql\">insert into t values (1) on conflict do nothing</insert></mapper>"))
        .isEmpty();
    assertThat(
            xmlViolations(
                "<mapper><select id=\"d\" databaseId=\"tibero\">call FH_TENANT.set_tenant(1)</select></mapper>"))
        .isNotEmpty();
    // SQL/JSON 반환형 절은 허용, DML RETURNING 은 여전히 위반.
    assertThat(
            xmlViolations(
                "<mapper><select id=\"f\" databaseId=\"tibero\">select JSON_VALUE(r, '$.x' RETURNING CLOB) from t</select></mapper>"))
        .isEmpty();
    assertThat(
            xmlViolations(
                "<mapper><insert id=\"g\">insert into t (a) values (1) returning id</insert></mapper>"))
        .isNotEmpty();
    // 문자열 리터럴 바로 뒤의 DML RETURNING 도 위반(JSON 경로 리터럴이 아니므로 걷어내지 않는다).
    assertThat(
            xmlViolations(
                "<mapper><update id=\"h\">update t set s = 'x' returning id</update></mapper>"))
        .isNotEmpty();
    assertThat(
            xmlViolations(
                "<mapper><select id=\"e\" databaseId=\"postgresql\">select fh_tenant.clear_tenant()</select></mapper>"))
        .isNotEmpty();
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
