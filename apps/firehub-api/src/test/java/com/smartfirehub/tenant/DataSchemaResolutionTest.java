package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.MissingTenantScopeException;
import com.smartfirehub.global.tenant.TenantContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DataSchema} 의 계약과, 그 계약을 <b>호출처에서 실제로 지키게 만드는 규약 가드</b>.
 *
 * <p>스프링 컨텍스트를 띄우지 않는 순수 단위 테스트다({@code TenantContextRequireTest} 와 같은 형태).
 * 검사 대상이 ThreadLocal 과 디스크의 소스 파일뿐이라 DB 도 빈도 필요 없다.
 *
 * <p><b>이 클래스의 마지막 테스트({@link #noProductionSourceOutsideDataSchemaHoldsTheLiteral()})는
 * P3-a Task 2 시점에 의도적으로 빨간 상태다.</b> Task 3~4 가 ~74곳의 하드코딩을 {@link DataSchema}
 * 로 옮기면 초록으로 바뀐다 — 즉 밴드의 진행률 지표다. 허용목록을 붙여 초록으로 만들거나
 * {@code @Disabled} 로 덮으면 밴드의 유일한 강제 장치가 사라진다.
 */
class DataSchemaResolutionTest {

  /** 규약 가드의 스캔 대상 루트. 스캔 대상은 <b>프로덕션 소스뿐</b>이다 — 아래 주석 참조. */
  private static final String MAIN_JAVA = "src/main/java";

  /**
   * {@code src/main/java} 안의 {@code .java} 파일 개수 하한. 2026-08-17 실측 530개.
   *
   * <p>이 하한이 없으면 규약 가드는 <b>스캔이 망가졌을 때도 빨갛다</b>. 그런데 이 밴드에서 빨간
   * 것은 정상 상태이므로, 아무도 "루트를 못 찾아 0개를 훑었다"는 사실을 눈치채지 못한다. 그래서
   * 스캔의 건전성만 따로 떼어 <b>초록 테스트</b>로 둔다. {@code TenantSchemaConformanceTest} 의
   * {@code MIN_RLS_TABLE_COUNT} 와 같은 장치이며, 같은 이유로 정확한 수가 아니라 하한이다.
   */
  private static final int MIN_PRODUCTION_JAVA_FILES = 400;

  /**
   * 규약 가드가 찾는 문자열. 자바 소스에서 이 형태는 <b>세 가지 모습</b>으로 나타나므로 원문을
   * 그대로 찾지 않고 이스케이프를 먼저 푼다({@link #unescape}).
   *
   * <ul>
   *   <li>문자열 리터럴 안: 원문 바이트가 {@code data.\"} (예: {@code PipelineAsyncRunner})
   *   <li>Javadoc·주석 안: 원문 바이트가 {@code data."} (예: {@code DataTableService} 의 스테이징
   *       테이블 설명)
   *   <li>{@code "... data."} 처럼 문자열이 {@code data.} 로 <b>끝나는</b> 경우 — 뒤따르는 {@code "}
   *       는 리터럴의 닫는 따옴표다 (예: {@code AnalyticsQueryExecutionService} 의 거부 메시지)
   * </ul>
   *
   * <p><b>세 가지를 모두 위반으로 본다 — 정규식의 사고가 아니라 결정이다.</b> 주석과 사용자 노출
   * 메시지도 P3-b 의 {@code data_t{id}} 개명 뒤에는 그냥 <b>틀린 문장</b>이 된다. 물리 스키마명을
   * 소스 어디에도 손으로 적지 않는 것이 이 밴드의 목표이므로, 세 번째 모습까지 잡히는 것이 맞다.
   */
  private static final String FORBIDDEN_LITERAL = "data.\"";

  /** {@link #FORBIDDEN_LITERAL} 을 담아도 되는 유일한 파일 — 조립 지점 그 자체다. */
  private static final String ALLOWED_FILE = "com/smartfirehub/global/tenant/DataSchema.java";

  /** 순수 단위 테스트라도 ThreadLocal 은 포크를 공유한다 — 뒤따르는 테스트로 새지 않게 지운다. */
  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  @DisplayName("current() 는 테넌트 컨텍스트를 요구한다 — 없으면 조용히 기본값을 쓰지 않는다")
  void current_requiresTenantContext() {
    TenantContext.clear();
    assertThatThrownBy(DataSchema::current)
        .as("스키마명은 TenantContext 에서만 파생된다 — 컨텍스트가 없으면 조용히 기본값을 쓰지 않는다")
        .isInstanceOf(MissingTenantScopeException.class);
  }

  @Test
  @DisplayName("current() 는 컨텍스트가 있으면 오늘의 물리 스키마 'data' 를 돌려준다")
  void current_returnsPhysicalSchemaWhenScoped() {
    // 위 테스트만 있으면 "무조건 던지는" 구현도 통과한다. 긍정 단언으로 계약의 반쪽을 못박는다.
    String schema = TenantContext.runScopedGet(4242L, DataSchema::current);
    assertThat(schema).as("P3-a 시점의 물리 스키마는 아직 테넌트별로 나뉘지 않았다").isEqualTo("data");
  }

  @Test
  @DisplayName("qualify() 도 컨텍스트를 요구한다 — current() 를 거치는 계약을 못박는다")
  void qualify_requiresTenantContext() {
    TenantContext.clear();
    assertThatThrownBy(() -> DataSchema.qualify("sensor_reading"))
        .as("qualify 가 스키마를 따로 하드코딩하면 이 단언이 통과하지 못한다")
        .isInstanceOf(MissingTenantScopeException.class);
  }

  @Test
  @DisplayName("qualify() 는 테이블명을 큰따옴표로 감싸고, 내장된 큰따옴표를 이중화한다")
  void qualify_escapesEmbeddedDoubleQuote() {
    // data."a""b" — 인용부호가 든 테이블명이 SQL 을 탈출하지 못한다.
    // PostgreSQL 의 인용 식별자 규칙상 안쪽 " 는 "" 로 이중화해야 하며, 그러지 않으면 식별자가
    // 조기 종료돼 뒤따르는 문자열이 SQL 문법으로 해석된다.
    assertThat(TenantContext.runScopedGet(4242L, () -> DataSchema.qualify("a\"b")))
        .isEqualTo("data.\"a\"\"b\"");
    assertThat(TenantContext.runScopedGet(4242L, () -> DataSchema.qualify("sensor_reading")))
        .as("따옴표가 없는 평범한 이름도 항상 인용된다 — 예약어 테이블명을 위해")
        .isEqualTo("data.\"sensor_reading\"");
  }

  @Test
  @DisplayName("규약 가드의 스캔이 건전하다 — 프로덕션 소스를 실제로 훑었고 예외 파일이 존재한다")
  void conventionGuardScanIsSound() {
    List<Path> sources = productionJavaFiles();

    // (1) 스캔 자체의 비공허성. 이게 없으면 아래 가드가 "루트를 못 찾아 0개를 훑고" 도 빨간지
    //     초록인지 구분되지 않는다 — 그리고 이 밴드에서 빨강은 정상 상태라 아무도 눈치채지 못한다.
    assertThat(sources)
        .as("%s 에서 발견한 프로덕션 자바 파일", MAIN_JAVA)
        .hasSizeGreaterThanOrEqualTo(MIN_PRODUCTION_JAVA_FILES);

    // (2) 예외 경로가 실제로 가리키는 파일이 있고, 그 파일이 예외를 받을 자격이 있다.
    //     예외가 오타난 경로를 가리켜도 가드는 조용히 통과하므로, 예외의 유효성을 못박는다.
    //     (TenantSchemaConformanceTest 의 containsAll staleness 가드와 같은 장치)
    //
    //     자격의 근거로 **PHYSICAL_SCHEMA 상수 선언**을 본다. 금지 리터럴(data.")을 근거로
    //     삼으면 그 조건을 만족시키는 것은 qualify() 의 Javadoc 예시뿐인데, P3-b 가 그 예시를
    //     정당하게 고쳐 쓰는 순간 이 초록 테스트가 가드와 무관한 이유로 빨개진다.
    //     조립에 반드시 있어야 하는 것은 물리 스키마명 그 자체다.
    List<Path> allowed = sources.stream().filter(DataSchemaResolutionTest::isAllowedFile).toList();
    assertThat(allowed).as("예외 대상 %s 가 스캔 결과에 정확히 하나 있어야 한다", ALLOWED_FILE).hasSize(1);
    assertThat(read(allowed.get(0)))
        .as("예외 파일이 물리 스키마명을 담고 있지 않다 — 조립 지점이 여기가 아니거나 경로가 낡았다")
        .contains("PHYSICAL_SCHEMA = \"data\"");
  }

  @Test
  @DisplayName("규약 가드 — DataSchema.java 밖의 프로덕션 소스에 data.\" 리터럴이 없다")
  void noProductionSourceOutsideDataSchemaHoldsTheLiteral() {
    // 이 밴드의 핵심 가드다. 리플렉션으로는 문자열 리터럴을 볼 수 없으므로 디스크의 소스를 읽는다.
    //
    // 테스트 소스(src/test/java)는 대상에서 뺀다 — 스키마 가드·프로브 테스트는 물리 스키마명을
    // 직접 불러야 검사가 성립한다(DataSchemaProbeSupport, DataSchemaGrantTest). 이 면제를 주석이
    // 아니라 여기 명시적으로 적어 둔다: 대상 루트가 MAIN_JAVA 하나인 것이 곧 그 면제다.
    //
    // Task 3~4 가 끝날 때까지 이 단언은 빨갛다(의도된 진행률 지표).
    List<String> offenders =
        productionJavaFiles().stream()
            .filter(p -> !isAllowedFile(p))
            .filter(p -> unescape(read(p)).contains(FORBIDDEN_LITERAL))
            .map(DataSchemaResolutionTest::relativePath)
            .sorted()
            .toList();

    assertThat(offenders)
        .as(
            "물리 스키마명을 직접 적은 프로덕션 소스 — DataSchema.qualify(..) 로 옮겨라 (유일한 예외: %s)",
            ALLOWED_FILE)
        .isEmpty();
  }

  // ── 스캔 유틸 ──────────────────────────────────────────────────────────

  /** 스캔 루트. Gradle 테스트의 작업 디렉터리는 {@code apps/firehub-api} 이므로 상대 경로로 잡힌다. */
  private static Path mainJavaRoot() {
    Path fromWorkingDir = Paths.get(MAIN_JAVA).toAbsolutePath().normalize();
    if (Files.isDirectory(fromWorkingDir)) {
      return fromWorkingDir;
    }
    // 작업 디렉터리가 저장소 루트인 실행기(IDE 등)를 위한 보정. 찾지 못하면 그대로 반환해
    // 위쪽 비공허성 단언이 시끄럽게 실패하도록 둔다 — 조용히 0개를 훑는 것보다 낫다.
    Path fromRepoRoot = Paths.get("apps/firehub-api", MAIN_JAVA).toAbsolutePath().normalize();
    return Files.isDirectory(fromRepoRoot) ? fromRepoRoot : fromWorkingDir;
  }

  /** {@code src/main/java} 아래 모든 {@code .java}. jOOQ 생성물({@code src/main/generated})은 밖이다. */
  private static List<Path> productionJavaFiles() {
    Path root = mainJavaRoot();
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".java"))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("프로덕션 소스 스캔 실패: " + root, e);
    }
  }

  private static boolean isAllowedFile(Path path) {
    return relativePath(path).endsWith(ALLOWED_FILE);
  }

  /** 단언 메시지에 절대 경로가 아니라 패키지 경로가 보이도록 루트 기준 상대 경로로 바꾼다. */
  private static String relativePath(Path path) {
    return mainJavaRoot().relativize(path).toString().replace('\\', '/');
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("소스 읽기 실패: " + path, e);
    }
  }

  /**
   * 자바 소스의 {@code \"} 이스케이프를 실제 {@code "} 로 되돌린다. 문자열 리터럴 안에 적힌
   * {@code data.\"} 와 주석에 적힌 {@code data."} 를 <b>같은 위반</b>으로 취급하기 위한 정규화다.
   */
  private static String unescape(String source) {
    return source.replace("\\\"", "\"");
  }
}
