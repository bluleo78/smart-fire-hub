package com.smartfirehub.global.platformdb.tibero.flyway;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.jooq.Public;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.TableOptions;
import org.junit.jupiter.api.Test;

/**
 * Tibero 테넌트 격리(WD-492) 정책·DEFAULT 목록의 짝 검사 — Tibero 실서버 없이 PG 회귀에서 돈다.
 *
 * <p>왜: PG 마이그레이션이 tenant_id 테이블을 더하고 Tibero 마이그레이션에 정책을 빠뜨리면 Tibero 설치본에서 그 테이블이 테넌트를 넘어 읽힌다. 카탈로그
 * 실서버 시험({@link TiberoTenantPolicyCatalogLiveTest})은 Tibero 가 있어야 돌므로, 여기서 PG 쪽(jOOQ 생성 코드)과 {@code
 * db/migration-tibero/*.sql} 의 {@code add_policy(...)}·{@code set_default(...)} 호출 목록을 매 PR 에서
 * 대조한다. SQL 은 정규식으로 읽는다 — 줄 주석은 먼저 지운다.
 */
class TiberoTenantPolicyParityTest {

  /** tenant_id 가 있지만 정책을 걸지 않는 테이블(PG 와 같음) — 로그인·테넌트 해석이 테넌트를 넘어 읽는다(설계 §4). */
  private static final Set<String> NO_POLICY =
      Set.of("MEMBERSHIP", "OAUTH_STATE", "TENANT_DATA_STORE", "TENANT_DATA_STORE_ACCOUNT");

  /** Tibero 기준선에 없는 PG 벡터 테이블(WD-495) — PG 쪽 집합에서 뺀다. */
  private static final Set<String> VECTOR_TABLES =
      Set.of(
          "DATASET_EMBEDDING_VEC_1024",
          "DATASET_EMBEDDING_VEC_1536",
          "DOCUMENT_CHUNK_VEC_1024",
          "DOCUMENT_CHUNK_VEC_1536");

  /**
   * Tibero 기준선에 없는 PG 전용 테이블 — PG 쪽 집합에서 뺀다. PYTHON 읽기 미확인 표식(WD-701)은 PG 데이터 저장소의
   * pg_stat_activity.backend_start 와 같은 시계로 비교하려고 PG 데이터 저장소에만 두고, Tibero V159 는 주석뿐이다.
   */
  private static final Set<String> PG_ONLY_TABLES = Set.of("PYTHON_READ_UNCONFIRMED_SLOT");

  private static final Path TIBERO = Path.of("src/main/resources/db/migration-tibero");

  /** add_policy('<테이블>', ...) 의 첫 인자. 선언부 add_policy(p_table ...) 는 따옴표가 없어 걸리지 않는다. */
  private static final Pattern ADD_POLICY =
      Pattern.compile("\\badd_policy\\s*\\(\\s*'([A-Za-z0-9_$#]+)'", Pattern.CASE_INSENSITIVE);

  /** set_default('<테이블>') 의 인자. */
  private static final Pattern SET_DEFAULT =
      Pattern.compile("\\bset_default\\s*\\(\\s*'([A-Za-z0-9_$#]+)'", Pattern.CASE_INSENSITIVE);

  @Test
  void TENANT_ID_가_있는_PG_테이블은_Tibero_마이그레이션에_정책이_있다() throws IOException {
    Set<String> expected = new TreeSet<>();
    for (Table<?> t : baseTables()) {
      if (t.field("tenant_id") != null) expected.add(t.getName().toUpperCase(Locale.ROOT));
    }
    expected.removeAll(NO_POLICY);
    expected.removeAll(VECTOR_TABLES);
    expected.removeAll(PG_ONLY_TABLES);

    Set<String> actual = namesCalled(ADD_POLICY);

    assertThat(expected).as("PG 쪽 집합이 비면 헛통과한다").isNotEmpty();
    assertThat(diff(expected, actual))
        .as(
            "TENANT_ID 가 있는 PG 테이블인데 Tibero 정책이 없다 — db/migration-tibero 마이그레이션 파일에 add_policy(...) 로"
                + " Tibero 테넌트 격리 정책을 추가할 것(정책 없이 두려면 NO_POLICY 와 설계 §4 를 함께 고친다)")
        .isEmpty();
    assertThat(diff(actual, expected))
        .as("Tibero add_policy(...) 대상인데 PG 에 TENANT_ID 테이블이 없다 — 정책 목록이 낡았는지 확인할 것")
        .isEmpty();
  }

  @Test
  void PG_컨텍스트_DEFAULT_가_있는_TENANT_ID_는_Tibero_마이그레이션에_DEFAULT_가_있다() throws IOException {
    Set<String> expected = new TreeSet<>();
    for (Table<?> t : baseTables()) {
      Field<?> f = t.field("tenant_id");
      if (f == null || !f.getDataType().defaulted()) continue;
      if (String.valueOf(f.getDataType().defaultValue()).contains("app.tenant_id")) {
        expected.add(t.getName().toUpperCase(Locale.ROOT));
      }
    }
    expected.removeAll(VECTOR_TABLES);
    expected.removeAll(PG_ONLY_TABLES);

    Set<String> actual = namesCalled(SET_DEFAULT);

    assertThat(expected).as("PG 쪽 집합이 비면 헛통과한다").isNotEmpty();
    assertThat(diff(expected, actual))
        .as(
            "PG 에서 tenant_id DEFAULT 가 app.tenant_id 인데 Tibero DEFAULT 가 없다 — db/migration-tibero 마이그레이션"
                + " 파일에 set_default(...) 로 Tibero tenant_id DEFAULT 를 추가할 것")
        .isEmpty();
    assertThat(diff(actual, expected))
        .as("Tibero set_default(...) 대상인데 PG 에 app.tenant_id DEFAULT 가 없다 — DEFAULT 목록이 낡았는지 확인할 것")
        .isEmpty();
  }

  /** Tibero 마이그레이션 전체에서 패턴의 첫 인자(테이블 이름, 대문자)를 모은다. 줄 주석은 지우고 본다. */
  private static Set<String> namesCalled(Pattern pattern) throws IOException {
    Set<String> names = new TreeSet<>();
    List<Path> files;
    try (Stream<Path> s = Files.list(TIBERO)) {
      files = s.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
    }
    assertThat(files).as("db/migration-tibero 를 찾지 못했다(작업 디렉터리 확인)").isNotEmpty();
    for (Path p : files) {
      String sql = Files.readString(p, StandardCharsets.UTF_8).replaceAll("--[^\\n]*", "");
      Matcher m = pattern.matcher(sql);
      while (m.find()) names.add(m.group(1).toUpperCase(Locale.ROOT));
    }
    return names;
  }

  /**
   * jOOQ 가 생성한 public 스키마의 일반 테이블만. 테이블 반환 함수(resolve_trigger_tenant_by_token_hash 등)도 {@code
   * getTables()} 에 섞여 tenant_id 열을 가지므로 걸러 낸다 — RLS 정책은 테이블에만 건다.
   */
  private static List<Table<?>> baseTables() {
    return Public.PUBLIC.getTables().stream()
        .filter(t -> t.getTableType() == TableOptions.TableType.TABLE)
        .toList();
  }

  private static Set<String> diff(Set<String> a, Set<String> b) {
    Set<String> d = new TreeSet<>(a);
    d.removeAll(b);
    return d;
  }
}
