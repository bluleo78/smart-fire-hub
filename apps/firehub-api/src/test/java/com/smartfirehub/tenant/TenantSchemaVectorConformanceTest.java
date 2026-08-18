package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link DataSchema#current()} 가 Python executor 의 {@code resolve_schema} 와 <b>같은 값</b>을
 * 내는지 고정벡터로 대조한다(P3-b2 T3).
 *
 * <p><b>이 표는 {@code apps/firehub-executor/tests/test_tenant_schema_vectors.py} 와 짝이다.
 * 한쪽만 고치지 말 것.</b> 스키마 파생 규약이 바뀌면 두 표를 함께 갱신하고, 둘 다 테스트를 다시
 * 통과시켜야 한다.
 *
 * <p><b>왜 Python 을 호출하지 않고 값을 복제하는가</b> — executor 는 별도 프로세스이고 Java
 * 코드를 부를 수 없다({@code app/tenant.py} 모듈 docstring 참조). 두 언어가 스키마명을 요청
 * 페이로드로 주고받지 않고 각자 독립적으로 파생하는 이유는 그쪽이 이미 상세히 남겨 뒀다 —
 * 요약하면 클라이언트(호출자) 제공 식별자를 신뢰하게 되는 보안 후퇴이기 때문이다. 드리프트
 * 방어는 이 고정벡터 표를 양 언어에 같은 값으로 두고 대조하는 것뿐이다.
 *
 * <p><b>라운드 1 리뷰 R17 — 드리프트 방어의 Python 절반이 어떤 게이트에도 없었다.</b> 이 저장소는
 * CI 가 없고, pre-commit/pre-push 훅은 {@code gradle test} + e2e 만 돈다(pytest 배선 없음).
 * {@code pnpm test}(turbo)에 executor 의 {@code "test": "python3 -m pytest tests/ -q"} 가 걸려
 * 있지만 <b>어떤 훅도 그것을 부르지 않고</b>, 이 개발 환경의 시스템 {@code python3} 에는 pytest 가
 * 아예 없다(문서화된 경로조차 실행 불가). 즉 Java 파생 규약이 바뀌고 Java 고정벡터도 같이
 * 갱신되면 {@code gradle test} 는 초록이고 pre-push 도 통과하는데, Python 은 옛 규약 그대로
 * 남을 수 있다 — 잡을 수 있는 유일한 테스트({@code test_tenant_schema_vectors.py})는 아무도
 * 돌리지 않는 게이트 밖에 있다.
 *
 * <p>그래서 {@link #pythonDerivationMatchesJavaSource()} 가 <b>이 테스트(게이트 안, 매번
 * 돈다)에서 Python 소스 파일을 직접 읽어</b> 파생 상수를 추출하고 Java 쪽과 대조한다 — pre-push
 * 에 pytest 를 추가하는 방식은 택하지 않았다: 이 환경에서 시스템 python3 에 pytest 가 없어
 * 모두의 게이트가 깨지고, 이 저장소는 훅이 플레이크로 자주 막혀 {@code --no-verify} 가
 * 관행이라(#360) 우회되는 게이트에 방어를 얹는 것은 방어가 아니기 때문이다.
 */
class TenantSchemaVectorConformanceTest {

  /** 순수 단위 테스트라도 ThreadLocal 은 포크를 공유한다 — 뒤따르는 테스트로 새지 않게 지운다. */
  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  /**
   * Python {@code SCHEMA_VECTORS}(tests/test_tenant_schema_vectors.py)와 완전히 같은 값이다:
   * {@code (1, "data"), (2, "data_t2"), (7, "data_t7"), (43259, "data_t43259")}.
   */
  @ParameterizedTest
  @CsvSource({
    "1, data",
    "2, data_t2",
    "7, data_t7",
    "43259, data_t43259",
  })
  @DisplayName("DataSchema.current() 가 고정벡터와 일치한다 — Python resolve_schema 와 짝")
  void currentMatchesFixedVector(long tenantId, String expectedSchema) {
    assertThat(TenantContext.runScopedGet(tenantId, DataSchema::current)).isEqualTo(expectedSchema);
  }

  // ── R17: Python 소스를 직접 읽어 대조 ────────────────────────────────────

  /**
   * {@code apps/firehub-executor/app/tenant.py} 의 스캔 대상 상대 경로 — Gradle 테스트의 작업
   * 디렉터리는 {@code apps/firehub-api} 이므로 형제 앱 디렉터리로 한 단계 올라간다({@code
   * mainJavaRoot()} 류의 다른 규약 가드와 같은 이유로 이 경로를 실제로 읽어서 확인했다).
   */
  private static final String PYTHON_TENANT_MODULE_FROM_GRADLE_CWD =
      "../firehub-executor/app/tenant.py";

  /** 작업 디렉터리가 저장소 루트인 실행기(IDE 등)를 위한 보정. */
  private static final String PYTHON_TENANT_MODULE_FROM_REPO_ROOT =
      "apps/firehub-executor/app/tenant.py";

  private static final Pattern PYTHON_PREFIX_DECLARATION =
      Pattern.compile("_TENANT_SCHEMA_PREFIX\\s*=\\s*\"([^\"]*)\"");

  private static final Pattern PYTHON_LEGACY_MAP_DECLARATION =
      Pattern.compile("_LEGACY_SCHEMA_BY_TENANT\\s*=\\s*\\{([^}]*)\\}");

  private static final Pattern PYTHON_LEGACY_MAP_ENTRY =
      Pattern.compile("(\\d+)\\s*:\\s*\"([^\"]*)\"");

  /**
   * Python 소스에서 직접 추출한 파생 상수로 기대값을 계산해, {@link DataSchema#current()} 의
   * 실제 반환값과 대조한다 — 클래스 Javadoc 의 R17 참조.
   *
   * <p>Python 을 실행하지 않는다(그 자체가 pytest 의존이라 게이트 밖 문제를 재현하게 된다).
   * 대신 소스 텍스트에서 {@code _TENANT_SCHEMA_PREFIX} 리터럴과 {@code
   * _LEGACY_SCHEMA_BY_TENANT} 딕셔너리 리터럴을 정규식으로 뽑아, 그 값으로 이 테스트 자신의
   * 고정벡터(1, 2, 7, 43259)에 대한 기대 스키마를 계산한다. Python 쪽이
   * {@code _TENANT_SCHEMA_PREFIX} 를 {@code "data_x"} 로 바꾸면(변이 테스트로 확인) 여기서
   * 계산한 기대값도 함께 {@code data_x2} 로 바뀌어, {@link DataSchema#current()} 가 여전히
   * {@code data_t2} 를 내는 순간 대조가 어긋난다.
   */
  @Test
  @DisplayName("Python tenant.py 를 직접 읽어 대조한다 — 게이트 밖 드리프트 방어(R17)")
  void pythonDerivationMatchesJavaSource() {
    String source = readPythonTenantModule();

    Matcher prefixMatcher = PYTHON_PREFIX_DECLARATION.matcher(source);
    assertThat(prefixMatcher.find())
        .as(
            "app/tenant.py 에서 _TENANT_SCHEMA_PREFIX 선언을 찾지 못했다 — 변수명이나 형태가"
                + " 바뀌었다면 이 테스트를 함께 고쳐라. 조용히 통과시키지 않는다(찾지 못하면 실패).")
        .isTrue();
    String pythonPrefix = prefixMatcher.group(1);

    Matcher legacyMapMatcher = PYTHON_LEGACY_MAP_DECLARATION.matcher(source);
    assertThat(legacyMapMatcher.find())
        .as(
            "app/tenant.py 에서 _LEGACY_SCHEMA_BY_TENANT 선언을 찾지 못했다 — 변수명이나 형태가"
                + " 바뀌었다면 이 테스트를 함께 고쳐라. 조용히 통과시키지 않는다(찾지 못하면 실패).")
        .isTrue();
    Map<Long, String> pythonLegacyMap = new LinkedHashMap<>();
    Matcher entryMatcher = PYTHON_LEGACY_MAP_ENTRY.matcher(legacyMapMatcher.group(1));
    while (entryMatcher.find()) {
      pythonLegacyMap.put(Long.parseLong(entryMatcher.group(1)), entryMatcher.group(2));
    }
    // _LEGACY_SCHEMA_BY_TENANT 는 최소 테넌트 1 항목을 가져야 한다 — 파싱이 0건을 조용히
    // 통과시키는 것을 막는다(정규식이 깨졌는데 "빈 맵이라 우연히 일치"하는 경우를 배제).
    assertThat(pythonLegacyMap)
        .as("_LEGACY_SCHEMA_BY_TENANT 파싱 결과가 비어 있다 — 파싱 정규식이 깨졌을 수 있다")
        .isNotEmpty();

    for (long tenantId : new long[] {1L, 2L, 7L, 43259L}) {
      String expected = pythonLegacyMap.getOrDefault(tenantId, pythonPrefix + tenantId);
      String actual = TenantContext.runScopedGet(tenantId, DataSchema::current);
      assertThat(actual)
          .as(
              "테넌트 %d — Python 소스(app/tenant.py)에서 읽은 파생값(%s)과 DataSchema.current()"
                  + " 가 어긋난다",
              tenantId, expected)
          .isEqualTo(expected);
    }
  }

  /**
   * Python 소스를 읽는다. 파일을 찾지 못하면 <b>스킵하지 않고 실패</b>시킨다 — 경로가 바뀌었는데
   * 조용히 초록이 되는 것이 이 밴드가 반복해 데인 실패 양식이다({@code DataSchemaResolutionTest}
   * 의 {@code MIN_PRODUCTION_JAVA_FILES} 하한과 같은 이유).
   */
  private static String readPythonTenantModule() {
    Path fromGradleCwd = Paths.get(PYTHON_TENANT_MODULE_FROM_GRADLE_CWD).toAbsolutePath().normalize();
    Path fromRepoRoot = Paths.get(PYTHON_TENANT_MODULE_FROM_REPO_ROOT).toAbsolutePath().normalize();
    Path resolved = Files.isRegularFile(fromGradleCwd) ? fromGradleCwd : fromRepoRoot;

    assertThat(Files.isRegularFile(resolved))
        .as(
            "Python 소스를 찾지 못했다(시도한 경로: %s, %s) — 경로가 바뀌었다면 이 테스트를"
                + " 고쳐라. 스킵하지 않고 실패시킨다.",
            fromGradleCwd, fromRepoRoot)
        .isTrue();

    try {
      return Files.readString(resolved, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Python 소스 읽기 실패: " + resolved, e);
    }
  }
}
