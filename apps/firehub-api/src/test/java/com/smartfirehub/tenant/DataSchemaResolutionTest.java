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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * <p><b>규약 가드는 세 규칙이다.</b> {@code data."}(한정 이름), 맨몸 {@code "data"}(리터럴 단독),
 * 손 조립({@code current() + ".."}). Task 2 시점에 첫 규칙만 있었고 Task 3~4 가 ~90곳을
 * {@link DataSchema} 로 옮겨 초록이 됐는데, <b>그 초록이 "완료" 를 뜻하지 않았다</b> — 첫 규칙이
 * 구조적으로 못 보는 형태로 실제 리터럴이 남아 있었다. Task 5 가 나머지 두 규칙을 더해 초록의
 * 의미를 맞췄다. 예외는 파일 단위가 아니라 {@link PinnedSite} 로 <b>개별 사이트</b>만 못박고,
 * 핀이 낡으면 {@link #pinnedSitesAreNotStale()} 이 빨개진다(허용목록을 뒤집은 역방향 단언).
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

  /**
   * 맨몸 스키마 리터럴. {@link #FORBIDDEN_LITERAL}({@code data."})은 <b>한정 이름</b> 형태만 잡으므로
   * {@code "data"} 처럼 스키마명 단독으로 적힌 곳은 <b>구조적으로 보이지 않는다</b> — 그래서 Task 3~4
   * 가 끝나고 가드가 초록이 된 뒤에도 실제 리터럴이 남아 있었다. 이 규칙이 그 구멍을 막는다.
   *
   * <p><b>{@code 'data'}(단일 인용)는 일부러 넣지 않는다.</b> 주석·Javadoc 의 산문이 스키마명을
   * {@code 'data'} 로 지칭하는 곳이 아직 여러 곳 있고(예: {@code AnalyticsQueryExecutionService},
   * {@code SqlValidator}), 산문은 개명 뒤 "틀린 문장" 이 될 뿐 동작 결함이 아니다. 산문 때문에
   * 빌드가 깨지면 가드가 소음으로 취급돼 결국 꺼진다.
   *
   * <p>스캔은 <b>원문</b>에 대해 한다 — {@code \"} 를 먼저 풀면 메시지 안의 {@code \"data\"} 가
   * 거짓 양성이 된다. 또한 <b>주석·Javadoc 은 제외</b>한다({@link #stripComments}): 산문이
   * {@code allowedSchema="data"} 처럼 스키마명을 <i>인용</i>하는 곳이 5곳 있고, 산문은 개명 뒤
   * 틀린 문장이 될 뿐 동작 결함이 아니다.
   */
  private static final String FORBIDDEN_BARE_LITERAL = "\"data\"";

  /**
   * 스키마명을 <b>손으로 조립</b>하는 형태를 잡는 정규식들.
   *
   * <p>왜 필요한가: 가드는 소스 텍스트를 훑으므로 {@code DataSchema.current() + "." + tbl} 은
   * {@code data."} 도 {@code "data"} 도 남기지 않으면서 {@link DataSchema#qualify} 의 인용·이중화를
   * 건너뛴다 — 즉 두 리터럴 규칙을 <b>모두 통과하면서 인젝션 표면을 다시 여는</b> 우회로다.
   * 조립은 {@code qualify()} 안에서만 일어나야 한다.
   *
   * <ul>
   *   <li>{@code current()} 가 문자열 연결에 직접 참여하는 형태(양쪽 모두)
   *   <li>{@code String.format("%s.\"%s\"", current(), tbl)} 같은 포맷 기반 조립 — 오늘 0건이므로
   *       핀도 필요 없다
   * </ul>
   *
   * <p><b>남아 있는 우회로(알려진 잔여물)</b>: {@code String s = DataSchema.current();} 로 변수에
   * 담은 뒤 {@code s + "." + tbl} 로 조립하면 텍스트 스캔으로는 보이지 않는다. 이를 잡으려면
   * {@code *schema} 류 식별자를 휴리스틱으로 훑어야 하는데, 그러면 {@code SqlValidator} 의 <b>산문
   * 메시지 조립</b>까지 걸려 산문을 가드에 넣는 셈이 된다(위 {@link #FORBIDDEN_BARE_LITERAL} 주석과
   * 같은 이유로 거부). 오늘 실측 3곳의 변수 경유 연결은 모두 {@code search_path} 목록·오류 메시지라
   * 위험 형태가 아니며, 위험 형태가 되려면 리터럴이 {@code .} 로 시작해야 한다 — 그때는 리뷰에서
   * 보인다. 휴리스틱을 넣는 대신 이 잔여물을 여기 적어 둔다.
   */
  private static final List<Pattern> HAND_ASSEMBLY_PATTERNS =
      List.of(
          Pattern.compile("current\\(\\)\\s*\\+\\s*\""),
          Pattern.compile("\"\\s*\\+\\s*(?:DataSchema\\.)?current\\(\\)"),
          Pattern.compile("%s\\.\""));

  /**
   * 규칙을 위반해도 되는 <b>개별 사이트</b>. 파일 단위 면제가 아니라 <b>정확한 코드 조각</b>을
   * 못박는다 — 파일로 면제하면 {@code SqlValidator} 에 새로 생기는 리터럴이 영구히 숨는다.
   *
   * @param file 스캔 루트 기준 상대 경로(끝부분 일치)
   * @param snippet 그 파일에 정확히 {@code expectedCount} 번 나타나야 하는 코드 조각
   * @param expectedCount 기대 출현 횟수 — 존재 여부가 아니라 <b>개수</b>를 못박는다. 존재만 보면
   *     같은 조각이 한 벌 더 복사돼도 조용히 면제된다.
   * @param reason 왜 남아 있는지 — 리뷰어가 핀을 지울 때 판단 근거가 된다
   */
  private record PinnedSite(String file, String snippet, int expectedCount, String reason) {}

  /**
   * {@link #FORBIDDEN_BARE_LITERAL} 규칙의 핀 목록.
   *
   * <p>{@code SqlValidator} 두 곳은 <b>다음 밴드로 의도적으로 이연</b>했다 — 검증기는 생성 시점에
   * 허용 스키마명을 인자로 받는 구조라 {@link DataSchema} 로 옮기려면 시그니처와 호출부까지 함께
   * 손대야 하고, 그 표면은 이 밴드의 범위가 아니다.
   *
   * <p>{@link DataSchema} 는 조립 지점 그 자체이므로 파일이 아니라 <b>그 한 줄</b>을 핀으로 둔다.
   * 파일 통째로 면제하면 조립 지점 안에서 늘어나는 리터럴이 보이지 않게 된다.
   *
   * <p><b>뒤쪽 4개는 동명이의(homonym)다</b> — JSON 응답·차트 스펙의 필드 이름이 우연히
   * {@code data} 인 것이고 물리 스키마와 아무 관계가 없다. 규칙을 "스키마 문맥" 휴리스틱으로
   * 좁히는 대신(그러면 {@code this("data", false)} 처럼 문맥 단어가 없는 진짜 위반을 놓친다)
   * 개별 사이트로 못박는다. 개수까지 못박으므로 그 파일에 새 {@code "data"} 가 생기면 빨개진다.
   */
  private static final List<PinnedSite> BARE_LITERAL_PINS =
      List.of(
          new PinnedSite(
              ALLOWED_FILE,
              "PHYSICAL_SCHEMA = \"data\"",
              1,
              "물리 스키마명의 유일한 선언 지점 — P3-b 가 바꿀 그 한 줄"),
          new PinnedSite(
              "com/smartfirehub/pipeline/service/validator/SqlValidator.java",
              "this(\"data\", false)",
              1,
              "검증기 기본 생성자 — 허용 스키마명 파라미터화는 다음 밴드로 이연"),
          new PinnedSite(
              "com/smartfirehub/pipeline/service/validator/SqlValidator.java",
              "new SqlValidator(\"data\", true)",
              1,
              "검증기 정적 팩토리 — 위와 같은 이유로 이연"),
          new PinnedSite(
              "com/smartfirehub/embedding/OpenAiEmbeddingProvider.java",
              "resp.get(\"data\")",
              1,
              "OpenAI 임베딩 응답의 JSON 필드명 — 스키마와 무관한 동명이의"),
          new PinnedSite(
              "com/smartfirehub/proactive/service/ProactiveAiClient.java",
              "s.get(\"data\")",
              1,
              "AI 리포트 섹션 JSON 의 필드명 — 동명이의"),
          new PinnedSite(
              "com/smartfirehub/proactive/service/ReportRenderUtils.java",
              "\"data\",",
              2,
              "차트 스펙(Chart.js 계열) 의 필드명 2곳 — 동명이의"));

  /**
   * {@link #HAND_ASSEMBLY_PATTERNS} 규칙의 핀 목록 — 조립이 <b>정당한</b> 두 곳뿐이다.
   *
   * <p>{@code AnalyticsQueryExecutionService} 는 {@code search_path} 를 세운다. 이건 한정 이름이
   * 아니라 <b>스키마 식별자 목록</b>({@code '<schema>', 'public'})이므로 {@code qualify()} 를 쓰면
   * 오히려 틀린 SQL 이 된다 — 그래서 조립이 맞다.
   */
  private static final List<PinnedSite> HAND_ASSEMBLY_PINS =
      List.of(
          new PinnedSite(
              ALLOWED_FILE,
              "current() + \".\\\"\" + tableName.replace",
              1,
              "qualify() 본문 — 조립이 일어나야 하는 유일한 지점"),
          new PinnedSite(
              "com/smartfirehub/analytics/service/AnalyticsQueryExecutionService.java",
              "\"SET LOCAL search_path = '\" + DataSchema.current() + \"', 'public'\"",
              1,
              "search_path 식별자 목록 — 한정 이름이 아니라 qualify() 가 부적절한 자리"));

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
            .filter(p -> unescape(decodeUnicodeEscapes(read(p))).contains(FORBIDDEN_LITERAL))
            .map(DataSchemaResolutionTest::relativePath)
            .sorted()
            .toList();

    assertThat(offenders)
        .as(
            "물리 스키마명을 직접 적은 프로덕션 소스 — DataSchema.qualify(..) 로 옮겨라 (유일한 예외: %s)",
            ALLOWED_FILE)
        .isEmpty();
  }

  @Test
  @DisplayName("규약 가드 — 핀으로 못박은 두 곳 밖에는 맨몸 \"data\" 리터럴이 없다")
  void noProductionSourceHoldsBareSchemaLiteralOutsidePinnedSites() {
    // data." 규칙이 잡지 못하는 형태를 덮는 두 번째 규칙이다. 원문(이스케이프 해제 전)에 대해
    // 훑는 이유는 FORBIDDEN_BARE_LITERAL 주석 참조.
    List<String> offenders =
        findOffendingLines(BARE_LITERAL_PINS, line -> line.contains(FORBIDDEN_BARE_LITERAL));

    assertThat(offenders)
        .as("맨몸 물리 스키마명을 적은 프로덕션 소스 — DataSchema 를 거쳐라 (예외는 핀 목록에만)")
        .isEmpty();
  }

  @Test
  @DisplayName("규약 가드 — 스키마명을 손으로 조립하는 곳이 핀 밖에 없다 (qualify() 우회 방지)")
  void noProductionSourceAssemblesQualifiedNameByHand() {
    List<String> offenders =
        findOffendingLines(
            HAND_ASSEMBLY_PINS,
            line -> {
              // 조립 탐지는 \" 를 푼 뒤에 한다 — 원문의 %s.\" 를 %s." 와 같게 보기 위해서다.
              String normalized = unescape(line);
              return HAND_ASSEMBLY_PATTERNS.stream().anyMatch(p -> p.matcher(normalized).find());
            });

    assertThat(offenders)
        .as("스키마명을 손으로 이어 붙인 프로덕션 소스 — DataSchema.qualify(..) 를 쓰라 (예외는 핀 목록에만)")
        .isEmpty();
  }

  @Test
  @DisplayName("핀 목록이 낡지 않았다 — 핀이 가리키는 코드가 사라지면 목록을 지우도록 빨개진다")
  void pinnedSitesAreNotStale() {
    // 역방향 단언(TenantSchemaConformanceTest 의 staleness 가드와 같은 장치). 다음 밴드가
    // SqlValidator 를 전환하면 핀은 아무것도 면제하지 않는 죽은 목록이 되는데, 그 상태를 조용히
    // 통과시키면 "핀 목록 = 남은 부채" 라는 문서로서의 값이 사라진다. 그래서 개수까지 못박는다.
    Stream.concat(BARE_LITERAL_PINS.stream(), HAND_ASSEMBLY_PINS.stream())
        .forEach(
            pin -> {
              List<Path> matched =
                  productionJavaFiles().stream()
                      .filter(p -> relativePath(p).endsWith(pin.file()))
                      .toList();
              assertThat(matched).as("핀이 가리키는 파일 %s", pin.file()).hasSize(1);
              // 개수도 코드 줄에서만 센다 — 주석의 인용(예: SqlValidator Javadoc 이 생성자 호출을
              // {@code ..} 로 인용한 곳)까지 세면 산문 수정이 이 역방향 단언을 깨뜨린다.
              assertThat(occurrences(stripComments(decodeUnicodeEscapes(read(matched.get(0)))), pin.snippet()))
                  .as(
                      "핀 %s → \"%s\" 의 출현 횟수가 달라졌다. 전환이 끝났으면 핀을 지워라 (근거: %s)",
                      pin.file(), pin.snippet(), pin.reason())
                  .isEqualTo(pin.expectedCount());
            });
  }

  // ── 스캔 유틸 ──────────────────────────────────────────────────────────

  /**
   * 프로덕션 소스를 줄 단위로 훑어 {@code violates} 를 만족하는 줄을 모으되, 핀으로 못박은 코드
   * 조각을 담은 줄은 뺀다. 핀을 <b>줄</b> 단위로 적용하는 이유: 파일 단위 면제는 그 파일에 새로
   * 생기는 위반까지 영구히 숨긴다.
   */
  private static List<String> findOffendingLines(
      List<PinnedSite> pins, java.util.function.Predicate<String> violates) {
    List<String> offenders = new java.util.ArrayList<>();
    for (Path path : productionJavaFiles()) {
      String relative = relativePath(path);
      List<String> pinnedSnippets =
          pins.stream().filter(pin -> relative.endsWith(pin.file())).map(PinnedSite::snippet).toList();
      // 주석·Javadoc 은 두 새 규칙의 대상이 아니다(stripComments 주석 참조). 유니코드 이스케이프를
      // 먼저 푸는 순서도 의도다 — 자바는 토큰화 전에 풀므로 // 는 실제로 주석이 된다.
      String[] lines = stripComments(decodeUnicodeEscapes(read(path))).split("\n", -1);
      for (int i = 0; i < lines.length; i++) {
        String line = lines[i];
        if (!violates.test(line)) {
          continue;
        }
        if (pinnedSnippets.stream().anyMatch(line::contains)) {
          continue;
        }
        offenders.add(relative + ":" + (i + 1) + " → " + line.strip());
      }
    }
    return offenders.stream().sorted().toList();
  }

  /**
   * 주석·Javadoc 을 <b>같은 길이의 공백으로</b> 지운 소스를 돌려준다 — 줄 번호와 열 위치가 그대로
   * 남아야 위반 보고가 실제 위치를 가리킨다.
   *
   * <p>왜 주석을 지우는가: 산문은 개명 뒤 "틀린 문장" 이 될 뿐 동작 결함이 아니므로 빌드를 깨면
   * 안 된다(브리프의 명시적 요구). 반면 {@code data."} 규칙은 예전부터 <b>일부러</b> 주석까지 잡는다
   * — 그쪽은 밴드가 산문까지 정리하기로 결정한 범위다. 두 규칙의 대상이 다른 것은 의도다.
   *
   * <p>문자열·문자 리터럴 안의 {@code //}, {@code /*} 는 주석이 아니므로 상태를 따라가며 판단한다.
   * 단순 정규식으로 {@code //} 뒤를 자르면 {@code "http://..."} 안의 뒷부분이 사라져 그 뒤에 적힌
   * 진짜 위반이 숨는다.
   */
  private static String stripComments(String source) {
    StringBuilder out = new StringBuilder(source.length());
    boolean inString = false;
    boolean inChar = false;
    boolean inLineComment = false;
    boolean inBlockComment = false;
    for (int i = 0; i < source.length(); i++) {
      char c = source.charAt(i);
      char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
      if (inLineComment) {
        // 줄 주석은 개행에서 끝난다. 개행 자체는 보존해야 줄 번호가 유지된다.
        if (c == '\n') {
          inLineComment = false;
          out.append(c);
        } else {
          out.append(' ');
        }
        continue;
      }
      if (inBlockComment) {
        if (c == '*' && next == '/') {
          inBlockComment = false;
          out.append("  ");
          i++;
        } else {
          out.append(c == '\n' ? '\n' : ' ');
        }
        continue;
      }
      if (inString || inChar) {
        out.append(c);
        if (c == '\\' && next != '\0') {
          // 이스케이프된 다음 문자는 그대로 통과시킨다 — \" 를 리터럴 종료로 오인하지 않기 위해.
          out.append(next);
          i++;
        } else if ((inString && c == '"') || (inChar && c == '\'')) {
          inString = false;
          inChar = false;
        }
        continue;
      }
      if (c == '/' && next == '/') {
        inLineComment = true;
        out.append("  ");
        i++;
      } else if (c == '/' && next == '*') {
        inBlockComment = true;
        out.append("  ");
        i++;
      } else {
        if (c == '"') {
          inString = true;
        } else if (c == '\'') {
          inChar = true;
        }
        out.append(c);
      }
    }
    return out.toString();
  }

  /** {@code needle} 의 출현 횟수. 핀의 "존재" 가 아니라 "개수" 를 못박기 위한 계수다. */
  private static int occurrences(String haystack, String needle) {
    int count = 0;
    for (int from = haystack.indexOf(needle); from >= 0; from = haystack.indexOf(needle, from + 1)) {
      count++;
    }
    return count;
  }

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

  /** {@link #decodeUnicodeEscapes} 가 찾는 유니코드 이스케이프. JLS 3.3 대로 {@code u} 중복을 허용한다. */
  private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u+([0-9A-Fa-f]{4})");

  /**
   * 자바 소스의 {@code \\uXXXX} 를 실제 문자로 되돌린다.
   *
   * <p>왜 필요한가: 자바는 <b>토큰화 이전</b>에 유니코드 이스케이프를 푼다(JLS 3.3). 그래서
   * {@code // data.\\u0022} 같은 줄은 컴파일 시점에 {@code data."} 가 되지만, 원문만 훑는 스캔에는
   * 절대 보이지 않는다. 이 프로젝트에는 선례가 있다 — SQL 가드 밴드에서 {@code U&"..."} 유니코드
   * 식별자가 이름 대조를 기계적으로 무력화했고, 그 때문에 거부목록을 허용목록으로 뒤집었다.
   * 같은 부류의 구멍을 <b>문서화로 남기지 않고 닫는다</b>: 디코딩 한 단계가 전부이고, 변이 테스트로
   * 닫혔음을 증명할 수 있기 때문이다.
   *
   * <p><b>정확히 무엇이 열려 있었나</b>: 문자열 <i>리터럴</i> 경로({@code "data.\\u005C\\u0022"})는
   * 이미 닫혀 있었다 — {@code \\u005C\\u0022} 는 {@code \"} 로 풀리고 {@link #unescape} 가 그것을
   * 정규화한다. 남아 있던 구멍은 <b>주석</b> 경로뿐이다({@code "data.\\u0022"} 는 애초에 컴파일되지
   * 않는다 — 리터럴이 조기 종료된다). 즉 이 디코더가 새로 막는 것은 주석·Javadoc 경로다.
   *
   * <p><b>근사(approximation)</b>: 앞선 역슬래시의 개수(짝/홀)를 따지지 않으므로, 리터럴 안의
   * {@code \\\\u0022}(문자 그대로의 백슬래시 + u0022)도 디코딩해 이론상 거짓 양성이 될 수 있다.
   * 오늘 {@code src/main/java} 에 {@code \\u} 는 0건이므로 실측 거짓 양성도 0건이며, 거짓 양성은
   * 시끄럽게 실패해 사람이 보게 되는 쪽이라 fail-closed 로 둔다.
   */
  private static String decodeUnicodeEscapes(String source) {
    if (!source.contains("\\u")) {
      return source;
    }
    Matcher matcher = UNICODE_ESCAPE.matcher(source);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      matcher.appendReplacement(
          out, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(matcher.group(1), 16))));
    }
    matcher.appendTail(out);
    return out.toString();
  }
}
