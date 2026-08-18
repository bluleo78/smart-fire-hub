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
 * <p><b>규약 가드는 세 규칙이다(P3-a 시점).</b> {@code data."}(한정 이름), 맨몸 {@code "data"}·
 * {@code 'data'}(리터럴 단독), 손 조립({@code current() + ".."}). Task 2 시점에 첫 규칙만
 * 있었고 Task 3~4 가 ~90곳을 {@link DataSchema} 로 옮겨 초록이 됐는데, <b>그 초록이 "완료" 를
 * 뜻하지 않았다</b> — 첫 규칙이 구조적으로 못 보는 형태로 실제 리터럴이 남아 있었다. Task 5 가
 * 나머지 두 규칙을 더해 초록의 의미를 맞췄다. 예외는 파일 단위가 아니라 {@link PinnedSite} 로
 * <b>개별 사이트</b>만 못박고, 핀이 낡으면 {@link #pinnedSitesAreNotStale()} 이 빨개진다
 * (허용목록을 뒤집은 역방향 단언).
 *
 * <p><b>P3-b2 T2 가 네 번째 규칙을 더했다 — 카탈로그 기반 열거 금지.</b> 테넌트별 스키마 파생
 * (P3-b2 T1)이 생기면서 "손 조립" 규칙도 {@code data_t} 접두사 우회로 확장됐고(위 손 조립
 * 규칙과 같은 목록), 별도로 {@code information_schema.schemata}/{@code pg_namespace} 를
 * {@code data_t} 패턴과 함께 써서 테넌트 스키마를 열거하려는 코드를 잡는 새 규칙
 * ({@link #noProductionSourceEnumeratesTenantSchemasViaCatalog()})이 생겼다 — 그런 코드는
 * 테넌트 1 의 {@code data} 를 구조적으로 못 찾는다({@link #CATALOG_ENUMERATION_TOKENS} 참조).
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
   * <p>SQL 문자열 안의 {@code 'data'}(단일 인용)도 같은 규칙으로 잡는다 — {@code where table_schema
   * = 'data'} 나 {@code search_path = 'data'} 는 진짜 스키마 리터럴인데 위 두 형태 어디에도 걸리지
   * 않는다. 2026-08-17 실측: {@code src/main/java} 의 {@code 'data'} 11건은 <b>전부 주석·Javadoc</b>
   * 이고(즉 코드 줄 0건) 그래서 이 규칙은 핀 없이 초록이다. 산문은 개명 뒤 "틀린 문장" 이 될 뿐
   * 동작 결함이 아니므로 대상 밖이다 — 산문 때문에 빌드가 깨지면 가드는 소음이 되고 결국 꺼진다.
   *
   * <p>스캔은 <b>원문</b>에 대해 한다 — {@code \"} 를 먼저 풀면 메시지 안의 {@code \"data\"} 가
   * 거짓 양성이 된다. 또한 <b>주석·Javadoc 은 제외</b>한다({@link #stripComments}): 산문이
   * {@code allowedSchema="data"} 처럼 스키마명을 <i>인용</i>하는 곳이 5곳 있고, 산문은 개명 뒤
   * 틀린 문장이 될 뿐 동작 결함이 아니다.
   *
   * <p><b>P3-b2 T2 라운드 1 리뷰 NIT — {@code "data_t"} 를 추가했다.</b> 손조립 규칙 넷(
   * {@link #HAND_ASSEMBLY_PATTERNS})은 전부 {@code + tenantId} 류의 <i>사용</i> 형태만 잡는다.
   * 이름만 다른 상수 선언({@code private static final String SCHEMA_PREFIX = "data_t";})은
   * 그 자체로는 아무 데도 안 걸린다 — 그 상수를 실제로 이어 붙이는 줄에서만 걸린다. 접두사
   * <i>선언</i> 자체를 클론하는 것도 막으려면 맨몸 리터럴 목록에 {@code "data_t"} 를 추가해야
   * 한다. 2026-08-18 실측: {@code src/main/java} 전체에서 {@code "data_t"} 리터럴은
   * {@link DataSchema}{@code .java:37}(원본 선언) 단 한 곳뿐이라 핀 하나로 오탐 없이 막힌다.
   */
  private static final List<String> FORBIDDEN_BARE_LITERALS = List.of("\"data\"", "'data'", "\"data_t\"");

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
   * 메시지 조립</b>까지 걸려 산문을 가드에 넣는 셈이 된다(위 {@link #FORBIDDEN_BARE_LITERALS} 주석과
   * 같은 이유로 거부). 오늘 실측 3곳의 변수 경유 연결은 모두 {@code search_path} 목록·오류 메시지라
   * 위험 형태가 아니며, 위험 형태가 되려면 리터럴이 {@code .} 로 시작해야 한다 — 그때는 리뷰에서
   * 보인다. {@code String.format("%s.%s", schema, tbl)} 도 같은 부류로 남는다 — 여기에 맞추려면
   * 패턴이 {@code %s\.} 가 되어야 하고 그러면 로깅 포맷 전반이 거짓 양성이 된다. 휴리스틱을 넣는
   * 대신 이 잔여물들을 여기 적어 둔다.
   *
   * <p><b>P3-b2 T2 확장 — 스키마 접두사({@code data_t})의 손조립도 같은 규칙으로 잡는다.</b>
   * {@code current()} 가 물리 스키마 상수 하나였을 때는 조립 우회로가 {@code qualify()} 쪽 하나뿐
   * 이었지만, 테넌트별 파생이 생긴 뒤로는 {@code "data_t" + tenantId} 형태로 파생 로직 자체를
   * 손으로 복제하는 두 번째 우회로가 생긴다. 세 패턴을 추가한다:
   *
   * <ul>
   *   <li>{@code "data_t" + }, {@code 'data_t' + } — 접두사 리터럴을 직접 이어 붙이는 형태
   *   <li>{@code "_t" + tenantId} — 접미사만 따로 이어 붙이는 형태(예: {@code "data" + "_t" +
   *       tenantId} 처럼 여러 조각으로 쪼개 만들어도 이 조각 하나로 걸린다)
   *   <li>{@code TENANT_SCHEMA_PREFIX + } — {@link DataSchema#current()} 자신의 파생 로직이
   *       바로 이 형태다. 이 패턴이 없으면 그 한 줄이 규약 가드에 구조적으로 보이지 않아, "조립
   *       지점은 DataSchema 하나"라는 규약이 코드로 강제되지 않고 문서로만 남는다 — {@code
   *       qualify()} 본문({@code current() + "."})을 이미 같은 이유로 핀 처리하고 있는 것과
   *       대칭이다.
   * </ul>
   */
  private static final List<Pattern> HAND_ASSEMBLY_PATTERNS =
      List.of(
          Pattern.compile("current\\(\\)\\s*\\+\\s*\""),
          Pattern.compile("\"\\s*\\+\\s*(?:DataSchema\\.)?current\\(\\)"),
          Pattern.compile("%s\\.\""),
          Pattern.compile("\"data_t\"\\s*\\+"),
          Pattern.compile("'data_t'\\s*\\+"),
          Pattern.compile("\"_t\"\\s*\\+\\s*tenantId"),
          Pattern.compile("TENANT_SCHEMA_PREFIX\\s*\\+"));

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
   * {@link #FORBIDDEN_BARE_LITERALS} 규칙의 핀 목록.
   *
   * <p>{@code SqlValidator} 두 곳(무인자 생성자·정적 팩토리)은 <b>P3-b1 Task 3 에서 전환 완료</b>되어
   * 핀이 사라졌다 — 검증기는 이제 허용 스키마를 {@code Supplier<String>}({@code DataSchema::current})
   * 로 들고 검증 시점에 해석한다(싱글턴 생성 시점에는 테넌트 컨텍스트가 없어 값으로 받을 수 없다).
   * 리터럴 2-인자 생성자는 <b>단위 테스트 전용</b>으로 남아 있으므로 프로덕션 소스에는 리터럴이 없다.
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
              "Map.of(1L, \"data\")",
              1,
              "물리 스키마명의 유일한 선언 지점 — P3-b2 가 테넌트 1(레거시)에 고정한 그 한 줄"),
          new PinnedSite(
              ALLOWED_FILE,
              "TENANT_SCHEMA_PREFIX = \"data_t\"",
              1,
              "신규 테넌트 스키마 접두사의 유일한 선언 지점(P3-b2 T2 라운드 1 NIT) — 이름만 바꾼"
                  + " 클론 상수 선언을 이 핀 밖에서 잡는다"),
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
   * {@link #HAND_ASSEMBLY_PATTERNS} 규칙의 핀 목록 — 조립이 <b>정당한</b> 곳들뿐이다.
   *
   * <p>{@code AnalyticsQueryExecutionService} 는 {@code search_path} 를 세운다. 이건 한정 이름이
   * 아니라 <b>스키마 식별자 목록</b>({@code '<schema>', 'public'})이므로 {@code qualify()} 를 쓰면
   * 오히려 틀린 SQL 이 된다 — 그래서 조립이 맞다.
   *
   * <p>{@code TENANT_SCHEMA_PREFIX + tenantId}({@code DataSchema.current()} 본문)도 핀이다 —
   * 접두사에서 파생 스키마명을 만드는 유일한 합법 조립 지점이 바로 이 한 줄이다(P3-b2 T2).
   */
  private static final List<PinnedSite> HAND_ASSEMBLY_PINS =
      List.of(
          new PinnedSite(
              ALLOWED_FILE,
              // qualify() 의 파라미터명은 tableName → identifier 로 바뀌었다(테이블 전용이 아니라
              // 인덱스·시퀀스도 한정하므로). 핀 문자열도 함께 따라가야 가드가 계속 그 한 줄을 본다.
              "current() + \".\\\"\" + identifier.replace",
              1,
              "qualify() 본문 — 조립이 일어나야 하는 유일한 지점"),
          new PinnedSite(
              ALLOWED_FILE,
              "TENANT_SCHEMA_PREFIX + tenantId",
              1,
              "current() 본문 — data_t{id} 를 만드는 유일한 합법 조립 지점(P3-b2 T2)"),
          new PinnedSite(
              "com/smartfirehub/analytics/service/AnalyticsQueryExecutionService.java",
              "\"SET LOCAL search_path = '\" + DataSchema.current() + \"', 'public'\"",
              1,
              "search_path 식별자 목록 — 한정 이름이 아니라 qualify() 가 부적절한 자리"),
          new PinnedSite(
              "com/smartfirehub/pipeline/service/SqlScriptExecutor.java",
              "\"SET LOCAL search_path = '\" + DataSchema.current() + \"'\"",
              1,
              "파이프라인 SQL 실행 직전 search_path — 위와 같은 이유(식별자 목록)로 조립이 맞다"));

  /**
   * 카탈로그로 테넌트 스키마를 <b>열거</b>하는 코드를 잡는다(P3-b2 T2, 신설. 라운드 1 리뷰로
   * 토큰 경계·스캔 범위 개정).
   *
   * <p><b>왜 nit 이 아니라 진짜 결함 방지인가.</b> 테넌트 1 의 물리 스키마는 {@code data} 라서
   * {@code data_t[0-9]+} 어떤 패턴에도 안 걸린다. "전 테넌트 스키마 순회"를 의도한 코드가
   * {@code information_schema.schemata} 나 {@code pg_namespace} 를 {@code data_t} 패턴으로
   * 필터링하면, 이 필터는 <b>구조적으로 테넌트 1 을 절대 찾지 못한다</b> — prod 에서 86개
   * 테이블이 전부 들어 있는 바로 그 스키마를 조용히 빼먹는다. 정상적으로 만들어진 목록에서
   * 우연히 하나가 빠지는 게 아니라, 필터 자체가 처음부터 그 스키마를 배제하도록 짜여 있다.
   *
   * <p><b>이 가드가 실제로 잡는 것 — 과장하지도 축소하지도 않는다(라운드 1·2 리뷰 지적).</b>
   * 잡는 것: {@code information_schema.schemata}/{@code pg_namespace} <b>리터럴</b>과
   * {@code data_t} 패턴(이스케이프된 밑줄 {@code data\_t} 포함, {@code data_type} 같은 무관한
   * 식별자는 제외 — 아래 {@link #countCatalogEnumerations} 참조)이 <b>같은 문장(세미콜론으로
   * 구분한 단위) 안</b>에 함께 있는 코드. <b>알려진 잔여물(못 잡는 것)</b>:
   *
   * <ul>
   *   <li>{@code LIKE 'data%'} 처럼 {@code data_t} 라고 철자하지 않은 다른 형태의 열거(여전히
   *       테넌트 1 을 못 찾는 것은 같지만, 이 가드는 {@code data_t} 패턴 <i>철자</i>만 검사한다)
   *   <li>{@code nspname <> 'public'} 같은 배제 조건
   *   <li>세미콜론이 문자열 리터럴 안에 있어 문장 경계 분리 자체가 어긋나는 극히 드문 경우(이
   *       경우는 <b>놓치는</b> 방향으로만 실패한다 — 오탐이 아니라 미탐이 늘어난다)
   *   <li>카탈로그 토큰을 상수·변수로 뽑아 쓰는 경우(예: {@code String sql = "select nspname from
   *       " + CATALOG + " where nspname like 'data_t%'";}) — 문장에 {@code pg_namespace}
   *       <b>리터럴</b>이 없으므로 {@code data_t} 를 철자하고도 빠져나간다(2026-08-18 실측,
   *       라운드 2 리뷰). jOOQ DSL 체이닝({@code table(name("pg_namespace"))})과
   *       {@code String.format} 은 문장 안에 {@code pg_namespace} 리터럴이 그대로 있으므로
   *       <b>잡힌다</b>
   * </ul>
   *
   * <p><b>덤 — {@code LIKE} 의 {@code _} 는 단일 문자 와일드카드다.</b> {@code LIKE 'data_t%'}
   * 는 {@code dataXt...} 도 잡는다. 진짜로 카탈로그를 훑어야 한다면 이스케이프({@code LIKE
   * 'data\_t%'})하거나 정규식({@code ~ '^data_t[0-9]+$'})을 써야 한다. 이 가드는 두 철자
   * 형태(이스케이프된 밑줄, 자바/SQL 정규식 이스케이프 {@code \d} 류) 모두에서 카탈로그+
   * {@code data_t} 조합을 잡는다({@link #countCatalogEnumerations} 가 밑줄 앞 백슬래시만
   * 정규화해서 본다, 라운드 2 리뷰로 사거리 정정) — 단, 바로 위 잔여물 목록에 없는 형태에
   * 한해서다. "철자 형태와 무관하게 전부 잡는다" 는 절대 단언은 아니다.
   *
   * <p><b>스캔 범위 — 같은 줄이 아니라 같은 문장(라운드 1 리뷰로 개정).</b> 이 리포의 지배적
   * SQL 조립 스타일은 줄머리 {@code + "} 다줄 연결이다(2026-08-18 리뷰 실측: {@code src/main/java}
   * 35개 파일, 텍스트 블록은 프로덕션에 0개). "같은 줄" 기준이면 이 스타일에 정면으로 무력화되고,
   * 하필 이 밴드가 방금 쓴 {@code TenantSchemaProvisioner.hasCompleteDefaultPrivileges} 자신이
   * {@code pg_namespace} 를 그 스타일로 쓴다 — 20줄 옆에서. 그래서 스캔 단위를 세미콜론으로 나눈
   * <b>문장</b>으로 넓히고, 문장 안에서 인접한 문자열 리터럴 연결({@code "..." + "..."})을
   * {@link #foldStringConcatenation} 으로 접어 한 덩어리로 본다 — 다줄로 쪼개 적어도 조립된
   * 결과 텍스트는 하나로 붙어 있다고 보는 것이다. 다른 두 규칙(맨몸 리터럴·손조립)은 건드리지
   * 않는다 — 그쪽은 이미 줄 단위로도 실제 우회를 잡고 있고, 예산 인프라({@link
   * #findExcessViolations})를 이 규칙 때문에 문장 단위로 바꾸면 두 규칙의 리포팅(정확한 줄
   * 번호)이 부정확해진다. 이 규칙은 핀 목록이 비어 있어 그 인프라를 아예 안 쓰므로 독립적으로
   * 넓힐 수 있었다.
   *
   * <p>오늘 프로덕션 소스의 {@code pg_namespace} 사용(예: {@code TenantSchemaProvisioner.
   * schemaExists}, {@code hasCompleteDefaultPrivileges}, {@code AnalyticsQueryExecutionService}
   * 의 {@code pg_class}+{@code pg_namespace} 인트로스펙션)은 전부 {@code data_t} 패턴을 참조하지
   * 않으므로 이 규칙에 걸리지 않는다(2026-08-18 재실측 0건, 문장 단위 스캔으로도 동일) — 핀이
   * 필요 없다. {@link DataSchema} 도 이 두 카탈로그 토큰을 전혀 쓰지 않으므로 핀 목록이 비어
   * 있다(빈 리스트 자체가 "오늘은 예외가 없다"는 정확한 상태다 — 억지로 자리만 차지하는 핀을
   * 만들지 않는다).
   */
  private static final List<String> CATALOG_ENUMERATION_TOKENS =
      List.of("information_schema.schemata", "pg_namespace");

  /**
   * {@link #CATALOG_ENUMERATION_TOKENS} 규칙의 핀 목록 — 오늘은 예외가 없다(실측 0건).
   *
   * <p><b>이 목록은 죽어 있다(라운드 2 리뷰 BLOCKER) — 채워도 아무 효과가 없다.</b> 이 규칙의
   * 본문({@link #noProductionSourceEnumeratesTenantSchemasViaCatalog()})은 다른 두 규칙과
   * 달리 {@link #findExcessViolations}(줄 단위 예산 인프라)를 부르지 않고 문장 단위로 직접
   * 순회한다 — 그래서 핀 목록을 <b>아예 읽지 않는다</b>. 누군가 정당한 예외를 만나 관례대로 여기
   * 핀을 추가해도 규칙은 계속 빨갛다. 핀을 실제로 존중하게 하려면 예산 인프라를 문장 단위로
   * 다시 끌어와야 하는데, 이 라운드에서는 택하지 않는다(범위 밖) — 대신 아래 단언으로 "핀 목록이
   * 비어 있어야 한다"는 가정을 강제해, 누가 핀을 추가하는 순간 "이 규칙은 핀을 지원하지 않는다"
   * 를 즉시 알게 한다.
   */
  private static final List<PinnedSite> CATALOG_ENUMERATION_PINS = List.of();

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
  @DisplayName("current() 는 컨텍스트가 있으면 기본 테넌트(1)의 물리 스키마 'data' 를 돌려준다")
  void current_returnsPhysicalSchemaWhenScoped() {
    // 위 테스트만 있으면 "무조건 던지는" 구현도 통과한다. 긍정 단언으로 계약의 반쪽을 못박는다.
    // 테넌트별 파생(1→data, 그 외→data_t{id})의 상세 규약은 DataSchemaTenantResolutionTest 가
    // DB 통합 테스트로 고정한다 — 여기는 스프링 컨텍스트 없는 순수 단위 테스트라 기본 테넌트
    // 경로 하나만 회귀 가드로 남긴다.
    String schema = TenantContext.runScopedGet(1L, DataSchema::current);
    assertThat(schema).as("테넌트 1 은 리네임 없이 기존 data 스키마를 그대로 쓴다").isEqualTo("data");
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
    assertThat(TenantContext.runScopedGet(1L, () -> DataSchema.qualify("a\"b")))
        .isEqualTo("data.\"a\"\"b\"");
    assertThat(TenantContext.runScopedGet(1L, () -> DataSchema.qualify("sensor_reading")))
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
        .contains("Map.of(1L, \"data\")");
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
    // 훑는 이유는 FORBIDDEN_BARE_LITERALS 주석 참조.
    List<String> offenders =
        findExcessViolations(BARE_LITERAL_PINS, DataSchemaResolutionTest::countBareLiterals);

    assertThat(offenders)
        .as("맨몸 물리 스키마명을 적은 프로덕션 소스 — DataSchema 를 거쳐라 (예외는 핀 목록에만)")
        .isEmpty();
  }

  @Test
  @DisplayName("규약 가드 — 스키마명을 손으로 조립하는 곳이 핀 밖에 없다 (qualify() 우회 방지)")
  void noProductionSourceAssemblesQualifiedNameByHand() {
    List<String> offenders =
        findExcessViolations(HAND_ASSEMBLY_PINS, DataSchemaResolutionTest::countHandAssemblies);

    assertThat(offenders)
        .as("스키마명을 손으로 이어 붙인 프로덕션 소스 — DataSchema.qualify(..) 를 쓰라 (예외는 핀 목록에만)")
        .isEmpty();
  }

  @Test
  @DisplayName("규약 가드 — 카탈로그(pg_namespace/information_schema.schemata)로 data_t 패턴을 열거하는 코드가 없다")
  void noProductionSourceEnumeratesTenantSchemasViaCatalog() {
    // 이 규칙은 findExcessViolations(줄 단위 예산 인프라)를 쓰지 않는다 — 문장(세미콜론) 단위로
    // 스캔 범위를 넓히는 데 그 인프라를 건드릴 이유가 없어서다(클래스 Javadoc 참조). 그런데
    // 그 결과 아래 루프는 CATALOG_ENUMERATION_PINS 를 전혀 참조하지 않는다 — 핀 목록이 죽어
    // 있다는 뜻이다(라운드 2 리뷰 BLOCKER, 핀 필드의 Javadoc 참조). 누가 관례대로 핀을 추가해도
    // 조용히 무시되는 상태를 막기 위해, 핀 목록이 항상 비어 있어야 한다는 가정을 여기서 강제한다.
    assertThat(CATALOG_ENUMERATION_PINS)
        .as("이 규칙은 핀을 지원하지 않는다(findExcessViolations 미사용) — 핀을 추가하지 마라")
        .isEmpty();
    List<String> offenders = new java.util.ArrayList<>();
    for (Path path : productionJavaFiles()) {
      String relative = relativePath(path);
      String stripped = stripComments(decodeUnicodeEscapes(read(path)));
      // 문 단위로 나눠 각 문 안에서만 문자열 연결을 접는다 — 파일 전체를 하나로 접으면 서로
      // 무관한 두 문장(예: 한 곳의 pg_namespace 조회와 다른 곳의 data_type 언급)이 우연히 같은
      // 파일에 있다는 이유만으로 오탐이 난다. 세미콜론이 문자열 리터럴 안에 있으면 이 분리가
      // 어긋나지만, 그 실패 방향은 "일부를 놓친다" 쪽이라 안전하다(클래스 Javadoc 참조).
      for (String statement : stripped.split(";")) {
        if (countCatalogEnumerations(foldStringConcatenation(statement)) > 0) {
          offenders.add(relative + ": " + statement.strip().replaceAll("\\s+", " "));
          break; // 파일당 한 번만 보고하면 충분하다
        }
      }
    }

    assertThat(offenders)
        .as(
            "카탈로그로 테넌트 스키마를 열거하는 프로덕션 소스 — 테넌트 1(data)을 구조적으로"
                + " 놓친다. DataSchema 를 거치는 다른 방법을 쓰라 (예외는 핀 목록에만)")
        .isEmpty();
  }

  @Test
  @DisplayName("핀 목록이 낡지 않았다 — 핀이 가리키는 코드가 사라지면 목록을 지우도록 빨개진다")
  void pinnedSitesAreNotStale() {
    // 역방향 단언(TenantSchemaConformanceTest 의 staleness 가드와 같은 장치). 다음 밴드가
    // SqlValidator 를 전환하면 핀은 아무것도 면제하지 않는 죽은 목록이 되는데, 그 상태를 조용히
    // 통과시키면 "핀 목록 = 남은 부채" 라는 문서로서의 값이 사라진다. 그래서 개수까지 못박는다.
    //
    // 이 테스트가 다루는 방향은 **핀이 낡았다(조각이 사라졌거나 개수가 줄었다)** 뿐이다. 반대
    // 방향(새 위반 추가)은 규칙 자신이 예산 초과로 잡는다(findExcessViolations 주석 참조) —
    // 두 방향의 메시지가 섞이면 새 누출을 들고 온 사람이 핀을 지워 면제로 바꿔 버린다.
    Stream.concat(
            Stream.concat(BARE_LITERAL_PINS.stream(), HAND_ASSEMBLY_PINS.stream()),
            CATALOG_ENUMERATION_PINS.stream())
        .forEach(
            pin -> {
              List<Path> matched =
                  productionJavaFiles().stream()
                      .filter(p -> relativePath(p).endsWith(pin.file()))
                      .toList();
              assertThat(matched).as("핀이 가리키는 파일 %s", pin.file()).hasSize(1);
              // 개수도 코드 줄에서만 센다 — 주석의 인용(예: SqlValidator Javadoc 이 생성자 호출을
              // {@code ..} 로 인용한 곳)까지 세면 산문 수정이 이 역방향 단언을 깨뜨린다.
              String code = stripComments(decodeUnicodeEscapes(read(matched.get(0))));
              assertThat(occurrences(code, pin.snippet()))
                  .as(
                      "핀 %s → \"%s\" 가 사라졌거나 줄었다(핀이 낡았다). 전환이 끝났으면 핀을 지워라 (근거: %s)",
                      pin.file(), pin.snippet(), pin.reason())
                  // 하한만 본다 — 개수가 **늘어난** 경우(=새 위반)는 규칙 자신이 예산 초과로 잡고,
                  // 그쪽 메시지는 "고쳐라" 다. 여기서 상한까지 보면 새 위반에 대해 "핀을 지워라" 라는
                  // 틀린 처방이 함께 뜬다(리뷰에서 지적된 바로 그 혼선).
                  .isGreaterThanOrEqualTo(pin.expectedCount());
            });
  }

  // ── 스캔 유틸 ──────────────────────────────────────────────────────────

  /**
   * 프로덕션 소스를 파일 단위로 훑어, <b>위반 개수가 핀이 허용한 예산을 넘는</b> 파일을 모은다.
   *
   * <p><b>왜 "줄에 핀 조각이 있으면 면제" 가 아니라 개수 예산인가 — 리뷰에서 잡힌 실제 결함이다.</b>
   * 줄 단위 부분문자열 면제는 핀 조각을 <b>흡수기</b>로 만든다: {@code ReportRenderUtils} 의 핀 조각은
   * {@code "data",} 인데, 새로 추가된 진짜 위반 {@code Map.of("data", true)} 도 그 조각을 담고 있어
   * 조용히 면제됐다. 그러면 빨개지는 것은 이 규칙이 아니라 {@link #pinnedSitesAreNotStale()} 의 개수
   * 단언이고, 그 메시지는 "핀을 지워라" 라고 말한다 — <b>새 누출을 들고 온 사람이 핀을 지워 시끄러운
   * 실패를 영구 면제로 바꾸도록 유도한다.</b> 그래서 두 방향을 분리한다.
   *
   * <ul>
   *   <li>위반이 예산보다 <b>많다</b> → 이 규칙이 빨개진다: "새 위반이 생겼다, 핀을 지우지 말고 고쳐라"
   *   <li>핀 조각이 <b>사라졌다</b> → {@link #pinnedSitesAreNotStale()} 이 빨개진다: "핀을 지워라"
   * </ul>
   *
   * <p>예산은 핀 조각 자체를 같은 계수기로 세서 만든다({@code expectedCount × 조각의 위반 개수}) —
   * 규칙과 예산이 같은 정의를 쓰므로 한쪽만 바뀌어 어긋날 수 없다.
   *
   * @param countMatches 한 줄(또는 핀 조각) 안의 위반 개수를 세는 함수
   */
  private static List<String> findExcessViolations(
      List<PinnedSite> pins, java.util.function.ToIntFunction<String> countMatches) {
    List<String> offenders = new java.util.ArrayList<>();
    for (Path path : productionJavaFiles()) {
      String relative = relativePath(path);
      // 주석·Javadoc 은 두 새 규칙의 대상이 아니다(stripComments 주석 참조). 유니코드 이스케이프를
      // 먼저 푸는 순서도 의도다 — 자바는 토큰화 전에 풀므로 // 는 실제로 주석이 된다.
      String[] lines = stripComments(decodeUnicodeEscapes(read(path))).split("\n", -1);
      int observed = 0;
      List<String> hits = new java.util.ArrayList<>();
      for (int i = 0; i < lines.length; i++) {
        int found = countMatches.applyAsInt(lines[i]);
        if (found > 0) {
          observed += found;
          hits.add(relative + ":" + (i + 1) + " → " + lines[i].strip());
        }
      }
      int budget =
          pins.stream()
              .filter(pin -> relative.endsWith(pin.file()))
              .mapToInt(pin -> pin.expectedCount() * countMatches.applyAsInt(pin.snippet()))
              .sum();
      if (observed > budget) {
        offenders.add(
            relative
                + ": 위반 "
                + observed
                + "건 > 핀 허용 "
                + budget
                + "건 — 새 위반이 생겼다(핀을 지우지 말고 위반을 고쳐라). 해당 줄: "
                + hits);
      }
    }
    return offenders.stream().sorted().toList();
  }

  /** 한 줄 안의 맨몸 리터럴 개수. 규칙과 핀 예산이 <b>같은</b> 계수기를 쓰게 하려고 떼어 둔다. */
  private static int countBareLiterals(String line) {
    return FORBIDDEN_BARE_LITERALS.stream().mapToInt(token -> occurrences(line, token)).sum();
  }

  /**
   * 텍스트 조각(문장 또는 그 안의 접은 문자열) 안에 카탈로그 토큰({@link
   * #CATALOG_ENUMERATION_TOKENS})과 {@code data_t} 패턴이 <b>함께</b> 나타나면 1, 아니면 0.
   * 둘 다 있어야 "카탈로그로 data_t 패턴을 열거"하는 형태가 되므로 존재 개수가 아니라 동시
   * 출현 여부를 센다 — 카탈로그 토큰만 있는 정상적인 단건 조회({@code nspname = ?})는 이
   * 규칙의 대상이 아니다.
   *
   * <p><b>{@code data_t} 판정을 정규식으로 바꿨다(라운드 1 리뷰 BLOCKER).</b> 단순
   * {@code contains("data_t")} 는 양방향으로 어긋났다:
   *
   * <ul>
   *   <li><b>놓친다</b>: 이스케이프를 옳게 한 {@code LIKE 'data\_t%'} 는 소스에 {@code
   *       data\\_t}(백슬래시 포함)로 적혀 {@code data_t} 부분문자열이 없다 — 그런데 이스케이프를
   *       옳게 해도 열거는 여전히 구조적으로 틀리다. 그래서 백슬래시를 먼저 지워 정규화한다
   *       (raw 소스의 {@code \\} 든 {@code \}) 든 전부 지운다 — 이 규칙의 목적상 이스케이프
   *       여부는 무관하다).
   *   <li><b>과잉으로 잡는다</b>: {@code data_t} 는 {@code data_type} 의 부분문자열이다.
   *       프로덕션에 {@code data_type} 이 여럿 있다({@code DataTableRowService},
   *       {@code AnalyticsQueryExecutionService}, {@code OntologyRepository}). 그래서
   *       {@code data_t} 뒤에 알파벳이 오면(={@code data_type} 처럼 진짜 단어의 일부이면)
   *       제외한다({@code (?![a-zA-Z])}) — 뒤에 숫자나 SQL 와일드카드({@code %}, {@code '})가
   *       오는 진짜 테넌트 패턴({@code data_t2}, {@code data_t%})은 그대로 잡힌다.
   * </ul>
   *
   * <p><b>알려진 잔여물(NIT, 라운드 2 리뷰) — 오늘의 규약에 하드코딩돼 있다.</b> 이 패턴의
   * {@code "data_t"} 는 {@link DataSchema#TENANT_SCHEMA_PREFIX} 상수를 <b>읽지 않고</b> 그
   * 값을 리터럴로 다시 적은 것이다. 그래서 접두사 규약이 바뀌면(예: {@code data_t} →
   * {@code data_tenant}) 이 가드는 조용히 낡은 패턴을 계속 검사한다 — 고치라는 게 아니라
   * 기록만 남긴다. ({@link #HAND_ASSEMBLY_PATTERNS} 의 손조립 규칙도 같은 방식으로 상수 이름
   * {@code TENANT_SCHEMA_PREFIX} 자체에 묶여 있어 대칭이 안 맞는다 — 그쪽은 식별자를, 이쪽은
   * 값을 하드코딩한다.)
   */
  private static final Pattern TENANT_SCHEMA_PATTERN_TOKEN = Pattern.compile("data_t(?![a-zA-Z])");

  private static int countCatalogEnumerations(String text) {
    boolean hasCatalogToken = CATALOG_ENUMERATION_TOKENS.stream().anyMatch(text::contains);
    // 밑줄 앞의 백슬래시만 지운다(라운드 2 리뷰 — 사거리 회귀 수정). 이전엔 모든 백슬래시를
    // 지웠는데, 그러면 "data_t" 뒤에 알파벳으로 시작하는 자바 정규식 이스케이프(\d, \w, \s)가
    // 오는 신중한 철자(예: "^data_t\\d+$", 소스 상 문자 그대로는 data_t\\d)가 "data_td..." 로
    // 뭉개져 (?![a-zA-Z]) 배제 조건에 걸려 미탐이 된다. 밑줄 앞만 지우면 "data\_t"(SQL LIKE
    // 이스케이프)는 여전히 "data_t" 로 정규화되고, "data_t\\d" 는 손대지 않아 "data_t" 바로
    // 뒤에 오는 문자가 백슬래시 그대로 남는다 — 알파벳이 아니므로 배제 조건에 안 걸려 그대로
    // 잡힌다(두 형태 모두 캐치 유지, 변이 테스트로 검증).
    String withoutBackslashes = text.replaceAll("\\\\+(?=_)", "");
    boolean hasTenantSchemaPattern = TENANT_SCHEMA_PATTERN_TOKEN.matcher(withoutBackslashes).find();
    return (hasCatalogToken && hasTenantSchemaPattern) ? 1 : 0;
  }

  /**
   * 인접한 문자열 리터럴 연결({@code "..." + "..."})을 하나로 접는다 — 카탈로그 규칙 전용
   * 정규화다(라운드 1 리뷰). 이 리포의 지배적 SQL 스타일이 줄머리 {@code + "} 다줄 연결이라,
   * 문장을 통째로 넘겨도 리터럴이 여러 조각으로 쪼개져 있으면 {@code data_t} 패턴이 카탈로그
   * 토큰과 다른 리터럴 조각에 나뉘어 있을 수 있다. 닫는 따옴표–공백(개행 포함)–{@code +}–공백–
   * 여는 따옴표 형태를 통째로 지우면, 조립된 결과 텍스트가 실제로 실행될 SQL 과 같은 순서로
   * 하나로 이어진다.
   *
   * <p><b>알려진 잔여물(NIT, 라운드 2 리뷰) — 문자열 경계를 모른다.</b> 이 함수는 정규식으로
   * {@code " + "} 자리를 지울 뿐 리터럴의 시작·끝을 추적하지 않으므로, 리터럴 <b>안</b>에 우연히
   * {@code \" + \"} 와 같은 바이트 나열이 있어도 접힌다. 실해는 없다 — 방향이 미탐 쪽(잘못 접혀
   * 원래 잡혔을 위반이 더 안 보이게 될 수는 있어도, 없던 위반이 새로 생기지는 않는다)이고,
   * 세미콜론 근사({@link #noProductionSourceEnumeratesTenantSchemasViaCatalog} 참조)와 같은
   * 성질의 근사다.
   */
  private static String foldStringConcatenation(String statement) {
    return statement.replaceAll("\"\\s*\\+\\s*\"", "");
  }

  /** 한 줄 안의 손 조립 개수. {@code \"} 를 먼저 푸는 이유는 원문의 {@code %s.\"} 를 같게 보기 위해서다. */
  private static int countHandAssemblies(String line) {
    String normalized = unescape(line);
    int count = 0;
    for (Pattern pattern : HAND_ASSEMBLY_PATTERNS) {
      Matcher matcher = pattern.matcher(normalized);
      while (matcher.find()) {
        count++;
      }
    }
    return count;
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
    for (int from = haystack.indexOf(needle);
        from >= 0;
        from = haystack.indexOf(needle, from + 1)) {
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
   * <p><b>정확히 무엇이 열려 있었나 — 두 경로 모두다</b>(초판 주석은 "주석 경로뿐" 이라고 적었는데
   * 리뷰에서 실측으로 반증됐다. 낡은 근거는 낡은 처방과 같은 등급의 결함이므로 여기 정정해 둔다):
   *
   * <ul>
   *   <li><b>문자열 리터럴 경로</b>: {@code "data.\\u005C\\u0022"} 는 컴파일 전에 {@code "data.\""}
   *       가 되어 값이 {@code data."} 다. 그런데 원문에는 {@code \"} 도 {@code data."} 도 없으므로
   *       {@link #unescape} 만 하던 예전 스캔은 <b>이걸 놓친다</b>. 디코더가 새로 막는다.
   *       ({@code "data.\\u0022"} 는 리터럴이 조기 종료돼 애초에 컴파일되지 않는다 — 그쪽이 아니라
   *       {@code \\u005C\\u0022} 가 실제 우회로다.)
   *   <li><b>주석 경로</b>: {@code // data.\\u0022} 는 컴파일 시점에 {@code data."} 인 주석이 되고
   *       역시 원문 스캔에 보이지 않는다.
   * </ul>
   *
   * <p><b>근사(approximation)</b>: 앞선 역슬래시의 개수(짝/홀)를 따지지 않으므로, 리터럴 안의
   * {@code \\\\u0022}(문자 그대로의 백슬래시 + u0022)도 디코딩해 이론상 거짓 양성이 될 수 있다.
   * 오늘 {@code src/main/java} 의 {@code \\u} 는 1건뿐이고({@code ApiCallPreviewService} 의 Javadoc 이
   * U+FFFD 를 언급하는 곳) 그것은 스키마 리터럴을 만들지 않으므로 실측 거짓 양성 0건이다. 설령
   * 생겨도 거짓 양성은 시끄럽게 실패해 사람이 보게 되는 쪽이므로 fail-closed 로 둔다.
   */
  private static String decodeUnicodeEscapes(String source) {
    if (!source.contains("\\u")) {
      return source;
    }
    Matcher matcher = UNICODE_ESCAPE.matcher(source);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      char decoded = (char) Integer.parseInt(matcher.group(1), 16);
      matcher.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(decoded)));
    }
    matcher.appendTail(out);
    return out.toString();
  }
}
