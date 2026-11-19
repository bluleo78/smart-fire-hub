package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.PipelineDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.UpdatePipelineRequest;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * WD-154 — 스텝 출력 컬럼(API_CALL 응답 필드 매핑·AI_CLASSIFY 출력 컬럼) 저장 시점 검증. WD-352 에서 PYTHON 출력 컬럼(자동 생성
 * 출력)도 더했다.
 *
 * <p>예전에는 빈 매핑 행·빈 출력 컬럼 이름·출력 데이터셋에 없는 대상 컬럼이 모두 저장에 성공하고, 실행 시점에야 영어 원문 오류({@code Invalid name: .
 * Must match …}, {@code bad SQL grammar [INSERT INTO …]})로 실패했다. 각 규칙을 생성·수정 두 경로에서 같은 한국어 문구(스텝 이름
 * 포함)로 거부하는지, 그리고 매핑으로 출력을 만드는 흐름(출력 자동 생성)은 막지 않는지 확인한다.
 */
@Transactional
class PipelineStepColumnValidationTest extends IntegrationTestBase {

  @Autowired private PipelineService pipelineService;
  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;

  private Long userId;

  /** 지정 출력 데이터셋 — 사용자 컬럼은 col1 하나(시스템 컬럼 id 등은 물리 테이블에 있다). */
  private Long outputId;

  @BeforeEach
  void setUp() {
    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "wd154user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "WD154 User")
            .set(USER.EMAIL, "wd154@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    // 보안 등급(S2): 출력 지정 스텝 저장은 편집자가 그 데이터셋을 볼 수 있어야 한다 — 기본 '내부' 자격(ACTIVE 멤버십 + USER 역할).
    TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID);
    dsl.execute(
        "insert into user_role (user_id, role_id) select ?, id from role where name = 'USER'",
        userId);
    outputId =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    "API Out",
                    "api_out",
                    null,
                    null,
                    "TABLE",
                    "DERIVED",
                    List.of(
                        new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null)),
                    null),
                userId)
            .id();
  }

  // ---------------------------------------------------------------- 픽스처

  private static Map<String, Object> mapping(String source, String target) {
    return Map.of("sourceField", source, "targetColumn", target);
  }

  /** API_CALL 스텝 — outputId 가 null 이면 출력 자동 생성(TEMP). */
  private static PipelineStepRequest apiStep(Long outputId, List<Map<String, Object>> mappings) {
    return new PipelineStepRequest(
        "API 호출",
        null,
        "API_CALL",
        null,
        outputId,
        null,
        null,
        "REPLACE",
        Map.of(
            "customUrl",
            "https://example.com/items",
            "method",
            "GET",
            "dataPath",
            "$",
            "fieldMappings",
            mappings),
        null,
        null,
        null);
  }

  /** API_CALL 스텝 + OFFSET 페이지네이션(WD-362) — pageSize 가 null 이면 키를 넣지 않는다(실행기 기본값). */
  private static PipelineStepRequest apiStepWithPageSize(Object pageSize) {
    Map<String, Object> pagination = new HashMap<>();
    pagination.put("type", "OFFSET");
    if (pageSize != null) {
      pagination.put("pageSize", pageSize);
    }
    return new PipelineStepRequest(
        "API 호출",
        null,
        "API_CALL",
        null,
        null,
        null,
        null,
        "REPLACE",
        Map.of("customUrl", "https://example.com/items", "method", "GET", "pagination", pagination),
        null,
        null,
        null);
  }

  /** API_CALL 스텝 + 응답 크기 한도(WD-810) — maxResponseSizeMb 가 null 이면 키를 넣지 않는다(실행기 기본값 10MB). */
  private static PipelineStepRequest apiStepWithMaxResponseSize(Object maxResponseSizeMb) {
    Map<String, Object> apiConfig = new HashMap<>();
    apiConfig.put("customUrl", "https://example.com/items");
    apiConfig.put("method", "GET");
    if (maxResponseSizeMb != null) {
      apiConfig.put("maxResponseSizeMb", maxResponseSizeMb);
    }
    return new PipelineStepRequest(
        "API 호출", null, "API_CALL", null, null, null, null, "REPLACE", apiConfig, null, null, null);
  }

  /** AI_CLASSIFY 스텝 — 출력 자동 생성(TEMP), 입력 없음(입력 판정과 무관하게 출력 컬럼 검증만 본다). */
  private static PipelineStepRequest aiStep(List<Map<String, Object>> outputColumns) {
    return new PipelineStepRequest(
        "AI 분류",
        null,
        "AI_CLASSIFY",
        null,
        null,
        null,
        null,
        "REPLACE",
        null,
        Map.of("prompt", "감성을 분류하세요", "outputColumns", outputColumns),
        null,
        null);
  }

  private static Map<String, Object> outputColumn(String name) {
    return Map.of("name", name, "type", "TEXT");
  }

  /**
   * PYTHON 스텝(WD-352) — outputId 가 null 이면 출력 컬럼 정의로 TEMP 를 자동 생성한다. 출력 컬럼 모양은 AI 분류와
   * 같다(name·type).
   */
  private static PipelineStepRequest pythonStep(
      Long outputId, List<Map<String, Object>> outputColumns) {
    return new PipelineStepRequest(
        "파이썬",
        null,
        "PYTHON",
        "import json\nprint(json.dumps([{\"val\": 1}]))",
        outputId,
        null,
        null,
        "REPLACE",
        null,
        null,
        Map.of("outputColumns", outputColumns),
        null);
  }

  /** 거부돼야 하는 스텝 + 기대 문구. 출력 지정 여부는 OUT 표식(실제 id 는 setUp 이후에만 알 수 있다)으로 넘긴다. */
  private static final Long OUT = -1L;

  /** WD-362 페이지 크기 범위 오류 문구(API 스텝 이름 'API 호출'). */
  private static final String PAGE_SIZE_ERROR = "스텝 'API 호출': 페이지 크기는 1~10000 사이의 정수여야 합니다";

  private static final String MAX_RESPONSE_SIZE_ERROR =
      "스텝 'API 호출': 응답 크기 한도는 1~100MB 사이의 정수여야 합니다";

  static Stream<Arguments> rejectedSteps() {
    return Stream.of(
        // WD-362 — 0 이하 페이지 크기는 실행기가 끝없이 다음 페이지를 요청한다
        Arguments.of("페이지 크기 음수", apiStepWithPageSize(-5), PAGE_SIZE_ERROR),
        Arguments.of("페이지 크기 0", apiStepWithPageSize(0), PAGE_SIZE_ERROR),
        Arguments.of("페이지 크기 상한 초과", apiStepWithPageSize(10_001), PAGE_SIZE_ERROR),
        Arguments.of("페이지 크기 소수", apiStepWithPageSize(2.5), PAGE_SIZE_ERROR),
        Arguments.of("페이지 크기 문자열", apiStepWithPageSize("abc"), PAGE_SIZE_ERROR),
        Arguments.of("응답 크기 0", apiStepWithMaxResponseSize(0), MAX_RESPONSE_SIZE_ERROR),
        Arguments.of("응답 크기 음수", apiStepWithMaxResponseSize(-1), MAX_RESPONSE_SIZE_ERROR),
        Arguments.of("응답 크기 상한 초과", apiStepWithMaxResponseSize(101), MAX_RESPONSE_SIZE_ERROR),
        Arguments.of("응답 크기 2048", apiStepWithMaxResponseSize(2048), MAX_RESPONSE_SIZE_ERROR),
        Arguments.of("응답 크기 4096", apiStepWithMaxResponseSize(4096), MAX_RESPONSE_SIZE_ERROR),
        Arguments.of("응답 크기 소수", apiStepWithMaxResponseSize(1.5), MAX_RESPONSE_SIZE_ERROR),
        Arguments.of("응답 크기 문자열", apiStepWithMaxResponseSize("abc"), MAX_RESPONSE_SIZE_ERROR),
        Arguments.of(
            "빈 매핑 행",
            apiStep(null, List.of(mapping("a", "a"), mapping("", ""))),
            "스텝 'API 호출': 비어 있는 매핑 행이 있습니다 — 채우거나 삭제하세요"),
        Arguments.of(
            "대상 컬럼 빈칸", apiStep(null, List.of(mapping("a", "  "))), "스텝 'API 호출': 대상 컬럼 이름을 입력하세요"),
        Arguments.of(
            "응답 필드 빈칸", apiStep(null, List.of(mapping("", "a"))), "스텝 'API 호출': 응답 필드를 입력하세요"),
        Arguments.of(
            "대상 컬럼 형식 위반",
            apiStep(null, List.of(mapping("a", "Bad Col"))),
            "스텝 'API 호출': 컬럼 이름은 영문 소문자로 시작하고 영문 소문자·숫자·밑줄만 쓸 수 있습니다: Bad Col"),
        Arguments.of(
            "자동 생성 출력의 예약어 대상 컬럼",
            apiStep(null, List.of(mapping("id", "id"))),
            "스텝 'API 호출': 컬럼명 'id'은 시스템 예약어입니다. (예약어: id, import_id, created_at, _updated_at, xmin, xmax, cmin, cmax, ctid, tableoid)"),
        Arguments.of(
            "대상 컬럼 중복",
            apiStep(null, List.of(mapping("a", "dup"), mapping("b", "dup"))),
            "스텝 'API 호출': 대상 컬럼이 중복됩니다: dup"),
        Arguments.of(
            "출력 데이터셋에 없는 대상 컬럼",
            apiStep(OUT, List.of(mapping("a", "col1"), mapping("b", "no_such_col"))),
            "스텝 'API 호출': 출력 데이터셋에 없는 컬럼입니다: no_such_col"),
        Arguments.of(
            "AI 출력 컬럼 이름 빈칸",
            aiStep(List.of(outputColumn("label"), outputColumn(""))),
            "스텝 'AI 분류': 출력 컬럼 이름을 입력하세요"),
        Arguments.of(
            "AI 출력 컬럼 이름 형식 위반",
            aiStep(List.of(outputColumn("Label"))),
            "스텝 'AI 분류': 컬럼 이름은 영문 소문자로 시작하고 영문 소문자·숫자·밑줄만 쓸 수 있습니다: Label"),
        Arguments.of(
            "AI 출력 컬럼 이름 중복",
            aiStep(List.of(outputColumn("label"), outputColumn("label"))),
            "스텝 'AI 분류': 출력 컬럼 이름이 중복됩니다: label"),
        Arguments.of(
            "Python 출력 컬럼 이름 빈칸",
            pythonStep(null, List.of(outputColumn("val"), outputColumn(" "))),
            "스텝 '파이썬': 출력 컬럼 이름을 입력하세요"),
        Arguments.of(
            "Python 출력 컬럼 이름 형식 위반",
            pythonStep(null, List.of(outputColumn("val"), outputColumn("my col"))),
            "스텝 '파이썬': 컬럼 이름은 영문 소문자로 시작하고 영문 소문자·숫자·밑줄만 쓸 수 있습니다: my col"),
        Arguments.of(
            "Python 출력 컬럼 이름 중복",
            pythonStep(null, List.of(outputColumn("val"), outputColumn("val"))),
            "스텝 '파이썬': 출력 컬럼 이름이 중복됩니다: val"),
        Arguments.of(
            "Python 출력 컬럼 이름 64자",
            pythonStep(null, List.of(outputColumn("a".repeat(64)))),
            "스텝 '파이썬': 칼럼명은 63자 이하여야 합니다"),
        Arguments.of(
            "Python 출력 컬럼 예약어",
            pythonStep(null, List.of(outputColumn("created_at"))),
            "스텝 '파이썬': 컬럼명 'created_at'은 시스템 예약어입니다. (예약어: id, import_id, created_at, _updated_at, xmin, xmax, cmin, cmax, ctid, tableoid)"),
        // PG 시스템 컬럼명도 TEMP 생성이 거부하므로 저장 시점에 같은 문구로 막는다(WD-564).
        Arguments.of(
            "Python 출력 컬럼 PG 시스템 컬럼명",
            pythonStep(null, List.of(outputColumn("xmin"))),
            "스텝 '파이썬': 컬럼명 'xmin'은 시스템 예약어입니다. (예약어: id, import_id, created_at, _updated_at, xmin, xmax, cmin, cmax, ctid, tableoid)"),
        // WD-635 — 형식 검사를 예약어보다 먼저 하도록 순서를 바꿨어도, 형식에 걸리는 예약어 변형(_updated_at·ID·XMIN)은 자동 생성 출력에서
        // 예전처럼 「예약어」 안내를 받는다(PG 테넌트라 PG 시스템 컬럼 변형도 예약어).
        Arguments.of(
            "자동 생성 출력의 예약어 변형 _updated_at",
            apiStep(null, List.of(mapping("a", "_updated_at"))),
            "스텝 'API 호출': 컬럼명 '_updated_at'은 시스템 예약어입니다. (예약어: id, import_id, created_at, _updated_at, xmin, xmax, cmin, cmax, ctid, tableoid)"),
        Arguments.of(
            "자동 생성 출력의 예약어 변형 ID",
            apiStep(null, List.of(mapping("a", "ID"))),
            "스텝 'API 호출': 컬럼명 'ID'은 시스템 예약어입니다. (예약어: id, import_id, created_at, _updated_at, xmin, xmax, cmin, cmax, ctid, tableoid)"),
        Arguments.of(
            "Python 출력 컬럼 PG 시스템 컬럼명 변형 XMIN",
            pythonStep(null, List.of(outputColumn("XMIN"))),
            "스텝 '파이썬': 컬럼명 'XMIN'은 시스템 예약어입니다. (예약어: id, import_id, created_at, _updated_at, xmin, xmax, cmin, cmax, ctid, tableoid)"),
        // 지정 출력 데이터셋은 예약어를 보지 않으므로 같은 이름이 예전처럼 형식 안내를 받는다.
        Arguments.of(
            "지정 출력의 형식 위반 _updated_at",
            apiStep(OUT, List.of(mapping("a", "_updated_at"))),
            "스텝 'API 호출': 컬럼 이름은 영문 소문자로 시작하고 영문 소문자·숫자·밑줄만 쓸 수 있습니다: _updated_at"));
  }

  /** OUT 표식을 실제 출력 데이터셋 id 로 바꾼다. */
  private PipelineStepRequest resolve(PipelineStepRequest step) {
    if (!OUT.equals(step.outputDatasetId())) {
      return step;
    }
    return new PipelineStepRequest(
        step.name(),
        step.description(),
        step.scriptType(),
        step.scriptContent(),
        outputId,
        step.inputDatasetIds(),
        step.dependsOnStepNames(),
        step.loadStrategy(),
        step.apiConfig(),
        step.aiConfig(),
        step.pythonConfig(),
        step.apiConnectionId());
  }

  // ---------------------------------------------------------------- 거부 — 생성·수정

  @ParameterizedTest(name = "생성: {0}")
  @MethodSource("rejectedSteps")
  void create_rejectsWithKoreanStepMessage(
      String label, PipelineStepRequest step, String expected) {
    PipelineStepRequest resolved = resolve(step);
    assertThatThrownBy(
            () ->
                pipelineService.createPipeline(
                    new CreatePipelineRequest("WD154 생성", null, List.of(resolved)), userId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(expected);
  }

  @ParameterizedTest(name = "수정: {0}")
  @MethodSource("rejectedSteps")
  void update_rejectsWithKoreanStepMessage(
      String label, PipelineStepRequest step, String expected) {
    // 먼저 올바른 파이프라인을 만들어 두고, 같은 이름의 스텝을 잘못된 설정으로 고쳐 저장한다.
    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "WD154 수정", null, List.of(apiStep(outputId, List.of(mapping("a", "col1"))))),
            userId);
    PipelineStepRequest resolved = resolve(step);
    assertThatThrownBy(
            () ->
                pipelineService.updatePipeline(
                    created.id(),
                    new UpdatePipelineRequest("WD154 수정", null, true, List.of(resolved)),
                    userId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(expected);
  }

  // ---------------------------------------------------------------- 통과 — 막으면 안 되는 흐름

  /** 출력 자동 생성 스텝은 매핑이 곧 TEMP 스키마다 — 새 대상 컬럼을 막지 않는다. */
  @Test
  void create_autoOutputWithNewTargetColumns_succeeds() {
    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "WD154 자동",
                null,
                List.of(apiStep(null, List.of(mapping("name", "name"), mapping("v", "amount"))))),
            userId);
    assertThat(created.steps()).hasSize(1);
  }

  /** 지정 출력에 있는 컬럼, 그리고 물리 테이블에 있는 시스템 컬럼(id)은 있는 것으로 본다. */
  @Test
  void create_explicitOutputWithExistingAndSystemColumns_succeeds() {
    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "WD154 지정",
                null,
                List.of(apiStep(outputId, List.of(mapping("a", "col1"), mapping("id", "id"))))),
            userId);
    assertThat(created.steps()).hasSize(1);
  }

  /** 수정 경로도 같은 규칙 — 올바른 매핑으로 바꾸면 저장된다. */
  @Test
  void update_validMappings_succeeds() {
    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "WD154 수정 통과", null, List.of(apiStep(null, List.of(mapping("a", "a"))))),
            userId);
    pipelineService.updatePipeline(
        created.id(),
        new UpdatePipelineRequest(
            "WD154 수정 통과", null, true, List.of(apiStep(outputId, List.of(mapping("b", "col1"))))),
        userId);
    assertThat(pipelineService.getPipelineById(created.id()).steps().get(0).outputDatasetId())
        .isEqualTo(outputId);
  }

  /** 매핑이 없는 API_CALL(응답 필드 그대로 적재)은 이번 검증 대상이 아니다. */
  @Test
  void create_noMappings_succeeds() {
    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest("WD154 매핑 없음", null, List.of(apiStep(outputId, List.of()))),
            userId);
    assertThat(created.steps()).hasSize(1);
  }

  /** PYTHON 자동 생성 출력의 올바른 컬럼 정의는 저장된다(WD-352). */
  @Test
  void create_pythonAutoOutputWithValidColumns_succeeds() {
    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "WD352 파이썬",
                null,
                List.of(pythonStep(null, List.of(outputColumn("val"), outputColumn("score_1"))))),
            userId);
    assertThat(created.steps()).hasSize(1);
  }

  /**
   * 소문자로 쓴 출력 컬럼 타입도 저장된다(대소문자 무시). 터키어 기본 로캘 사례는 Spring 없이 {@code CaseConversionLocaleTest} 가
   * 본다(WD-653).
   */
  @Test
  void create_pythonLowerCaseOutputType_succeeds() {
    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "WD635 소문자 타입",
                null,
                List.of(pythonStep(null, List.of(Map.of("name", "score", "type", "integer"))))),
            userId);
    assertThat(created.steps()).hasSize(1);
  }

  /** 페이지 크기 경계값(1·10000)은 저장된다(WD-362). */
  @ParameterizedTest(name = "페이지 크기 {0} 저장")
  @ValueSource(ints = {1, 10_000})
  void create_pageSizeBoundary_succeeds(int pageSize) {
    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest("WD362 페이지 크기", null, List.of(apiStepWithPageSize(pageSize))),
            userId);
    assertThat(created.steps()).hasSize(1);
  }

  /**
   * 출력을 지정한 PYTHON 스텝은 컬럼 정의를 쓰지 않는다(편집기도 숨긴다) — 남아 있는 정의의 이름은 막지 않는다(WD-352). 막으면 화면에서 고칠 수 없는 오류로
   * 저장이 막힌다.
   */
  @Test
  void create_pythonExplicitOutputIgnoresColumnNames_succeeds() {
    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "WD352 파이썬 지정",
                null,
                List.of(
                    pythonStep(outputId, List.of(outputColumn("my col"), outputColumn("my col"))))),
            userId);
    assertThat(created.steps()).hasSize(1);
  }

  /** 페이지 크기를 비우면 실행기 기본값(100)을 쓰므로 저장된다(WD-362). */
  @Test
  void create_pageSizeEmpty_succeeds() {
    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest("WD362 페이지 크기 비움", null, List.of(apiStepWithPageSize(null))),
            userId);
    assertThat(created.steps()).hasSize(1);
  }

  /** 저장되는 응답 크기 한도: 경계값(1·100), 숫자 문자열("50", 트리거 설정과 같은 해석), 비움(실행기 기본값 10MB)(WD-810). */
  static Stream<Arguments> acceptedMaxResponseSizes() {
    return Stream.of(
        Arguments.of("경계 1", 1),
        Arguments.of("경계 100", 100),
        Arguments.of("숫자 문자열", "50"),
        Arguments.of("비움", null));
  }

  @ParameterizedTest(name = "응답 크기 저장: {0}")
  @MethodSource("acceptedMaxResponseSizes")
  void create_maxResponseSizeAccepted_succeeds(String label, Object maxResponseSizeMb) {
    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest(
                "WD810 응답 크기", null, List.of(apiStepWithMaxResponseSize(maxResponseSizeMb))),
            userId);
    assertThat(created.steps()).hasSize(1);
  }

  /** 옛 스텝(저장 상한 이전 값)을 만든다 — 저장 검증을 우회해 DB 에 직접 심는다(WD-810). json 은 JSON 리터럴이다(예: 500, "150"). */
  private PipelineDetailResponse createLegacyStep(String name, String jsonLiteral) {
    PipelineDetailResponse created =
        pipelineService.createPipeline(
            new CreatePipelineRequest(name, null, List.of(apiStepWithMaxResponseSize(10))), userId);
    dsl.execute(
        "UPDATE pipeline_step SET api_config = jsonb_set(api_config, '{maxResponseSizeMb}', ?::jsonb) WHERE pipeline_id = ?",
        jsonLiteral,
        created.id());
    return created;
  }

  private void assertUpdateRejected(PipelineDetailResponse created, Object sent) {
    assertThatThrownBy(
            () ->
                pipelineService.updatePipeline(
                    created.id(),
                    new UpdatePipelineRequest(
                        "WD810 옛 스텝", null, true, List.of(apiStepWithMaxResponseSize(sent))),
                    userId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(MAX_RESPONSE_SIZE_ERROR);
  }

  /** 저장값이 문자열("150")이어도 같은 값(150 또는 "150")을 되돌려 보내면 통과한다(WD-810). */
  @Test
  void update_unchangedLegacyStringMaxResponseSize_succeeds() {
    PipelineDetailResponse created = createLegacyStep("WD810 문자열 옛 스텝", "\"150\"");
    pipelineService.updatePipeline(
        created.id(),
        new UpdatePipelineRequest(
            "WD810 문자열 옛 스텝", "설명", true, List.of(apiStepWithMaxResponseSize("150"))),
        userId);
    pipelineService.updatePipeline(
        created.id(),
        new UpdatePipelineRequest(
            "WD810 문자열 옛 스텝", "설명2", true, List.of(apiStepWithMaxResponseSize(150))),
        userId);
    assertThat(pipelineService.getPipelineById(created.id()).description()).isEqualTo("설명2");
  }

  /** 돌지 않는 저장값(5000·-1)은 그대로 되돌려 보내도 거부한다 — 사용자가 고쳐야 한다(WD-810). */
  @ParameterizedTest(name = "저장 {0} 그대로 되돌림 거부")
  @ValueSource(ints = {5000, -1, 0})
  void update_unchangedNonRunnableMaxResponseSize_rejected(int stored) {
    PipelineDetailResponse created = createLegacyStep("WD810 불가 옛 스텝", Integer.toString(stored));
    assertUpdateRejected(created, stored);
  }

  /** 저장본 값 그대로(500) 되돌려 보내는 편집기 저장은 막지 않는다(WD-810) — 막으면 옛 스텝의 설명도 못 고친다. */
  @Test
  void update_unchangedLegacyMaxResponseSize_succeeds() {
    PipelineDetailResponse created = createLegacyStep("WD810 옛 스텝", "500");
    pipelineService.updatePipeline(
        created.id(),
        new UpdatePipelineRequest(
            "WD810 옛 스텝", "설명 수정", true, List.of(apiStepWithMaxResponseSize(500))),
        userId);
    assertThat(pipelineService.getPipelineById(created.id()).description()).isEqualTo("설명 수정");
  }

  /** 저장본과 다른 범위 밖 값(500 -> 600)은 여전히 거부한다(WD-810). */
  @Test
  void update_changedOutOfRangeMaxResponseSize_rejected() {
    assertUpdateRejected(createLegacyStep("WD810 옛 스텝", "500"), 600);
  }

  /** 옛 스텝이 소수(10.5)로 저장돼 있어도(Jackson 이 10 으로 줄여 돌던 값) 같은 값을 되돌려 보내는 저장은 통과한다(WD-810). */
  @Test
  void update_unchangedLegacyDecimalMaxResponseSize_succeeds() {
    PipelineDetailResponse created = createLegacyStep("WD810 소수 옛 스텝", "10.5");
    pipelineService.updatePipeline(
        created.id(),
        new UpdatePipelineRequest(
            "WD810 소수 옛 스텝", "설명 수정", true, List.of(apiStepWithMaxResponseSize(10.5))),
        userId);
    assertThat(pipelineService.getPipelineById(created.id()).description()).isEqualTo("설명 수정");
  }

  /** 새 소수 값은 저장본이 없어도 여전히 400 이다(WD-810). */
  @Test
  void create_newDecimalMaxResponseSize_rejected() {
    assertThatThrownBy(
            () ->
                pipelineService.createPipeline(
                    new CreatePipelineRequest(
                        "WD810 새 소수", null, List.of(apiStepWithMaxResponseSize(10.5))),
                    userId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(MAX_RESPONSE_SIZE_ERROR);
  }

  /**
   * 저장본 소수(10.5)를 다른 소수(20.5)·같은 정수부의 다른 소수(10.9)로 바꾸는 저장은 바뀐 값이라 새 값 규칙(정수 1~100)에 막힌다(WD-810).
   */
  @ParameterizedTest(name = "저장 10.5 -> {0} 거부")
  @ValueSource(doubles = {20.5, 10.9})
  void update_changedDecimalMaxResponseSize_rejected(double sent) {
    assertUpdateRejected(createLegacyStep("WD810 소수 옛 스텝", "10.5"), sent);
  }

  /** 저장본 소수(10.5)를 정수 10 으로 바꾸는 저장은 바뀐 값이지만 새 값 규칙(정수 1~100)을 지키므로 통과한다(WD-810). */
  @Test
  void update_decimalChangedToValidInteger_succeeds() {
    PipelineDetailResponse created = createLegacyStep("WD810 소수 정수화", "10.5");
    pipelineService.updatePipeline(
        created.id(),
        new UpdatePipelineRequest(
            "WD810 소수 정수화", "정수로 고침", true, List.of(apiStepWithMaxResponseSize(10))),
        userId);
    assertThat(pipelineService.getPipelineById(created.id()).description()).isEqualTo("정수로 고침");
  }

  /** 저장본 150.7(소수부를 버리면 150, 상한 100 초과)을 150 이나 "150" 으로 바꾸는 저장은 바뀐 값이라 거부한다(WD-810). */
  @Test
  void update_changedLargeDecimalToInteger_rejected() {
    PipelineDetailResponse created = createLegacyStep("WD810 큰 소수", "150.7");
    assertUpdateRejected(created, "150");
    assertUpdateRejected(created, 150);
  }

  /** 옛 스텝이 빈 문자열("")로 저장돼 기본 10MB 로 돌았다면 같은 값을 되돌려 보내는 저장은 통과한다(WD-810). */
  @Test
  void update_unchangedLegacyBlankMaxResponseSize_succeeds() {
    PipelineDetailResponse created = createLegacyStep("WD810 빈 문자열", "\"\"");
    pipelineService.updatePipeline(
        created.id(),
        new UpdatePipelineRequest(
            "WD810 빈 문자열", "설명 수정", true, List.of(apiStepWithMaxResponseSize(""))),
        userId);
    assertThat(pipelineService.getPipelineById(created.id()).description()).isEqualTo("설명 수정");
  }

  /** 새로 보내는 빈 문자열은 저장본이 정수여도 예전처럼 400 이다(WD-810). */
  @Test
  void update_newBlankMaxResponseSize_rejected() {
    assertUpdateRejected(createLegacyStep("WD810 빈 문자열 새 값", "50"), "");
  }

  /**
   * 저장본 150.0 을 150 으로 되돌려 보내는 저장은 숫자로 같고 150 도 실행에서 도는 값이라 통과한다 — 설명만 고쳐도 저장되며 요청 값 그대로
   * 저장된다(WD-810).
   */
  @Test
  void update_numericallyEqualMaxResponseSizeForms_succeeds() {
    PipelineDetailResponse created = createLegacyStep("WD810 150.0", "150.0");
    pipelineService.updatePipeline(
        created.id(),
        new UpdatePipelineRequest(
            "WD810 150.0", "설명 수정", true, List.of(apiStepWithMaxResponseSize(150))),
        userId);
    PipelineDetailResponse saved = pipelineService.getPipelineById(created.id());
    assertThat(saved.description()).isEqualTo("설명 수정");
    assertThat(saved.steps().get(0).apiConfig().get("maxResponseSizeMb")).isEqualTo(150);
  }

  /**
   * 저장본 150 을 "150.0" 으로 보내는 저장은 숫자로는 같지만 요청 값(문자열 "150.0")은 실행기 변환(Integer)이 못 읽어 매 실행이 실패하므로
   * 거부한다(WD-810).
   */
  @Test
  void update_numericallyEqualButUnrunnableRequested_rejected() {
    PipelineDetailResponse created = createLegacyStep("WD810 150 문자열", "150");
    assertUpdateRejected(created, "150.0");
    assertUpdateRejected(created, "1.5e2");
  }

  /** 저장본 150.0 을 151 로, 150.5 를 150 으로 바꾸는 저장은 숫자로 다른 값이라 바뀐 값으로 보고 거부한다(WD-810). */
  @Test
  void update_numericallyDifferentMaxResponseSize_rejected() {
    assertUpdateRejected(createLegacyStep("WD810 옛 스텝", "150.0"), 151);
  }

  /** 저장본 150.5 를 150 으로 바꾸는 저장은 소수부가 달라 바뀐 값이고 150 은 상한 초과라 거부한다(WD-810). */
  @Test
  void update_fractionalDifferenceMaxResponseSize_rejected() {
    assertUpdateRejected(createLegacyStep("WD810 옛 스텝", "150.5"), 150);
  }

  /** 저장본이 0.5 처럼 소수부를 버리면 돌 수 없는 값(0)은 그대로 되돌려 보내도 거부한다(WD-810). */
  @Test
  void update_unchangedNonRunnableDecimalMaxResponseSize_rejected() {
    assertUpdateRejected(createLegacyStep("WD810 소수 불가 옛 스텝", "0.5"), 0.5);
  }
}
