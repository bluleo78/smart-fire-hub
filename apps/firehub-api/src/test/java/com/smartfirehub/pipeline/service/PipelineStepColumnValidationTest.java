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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * WD-154 — 스텝 출력 컬럼(API_CALL 응답 필드 매핑·AI_CLASSIFY 출력 컬럼) 저장 시점 검증.
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

  private static Map<String, Object> aiColumn(String name) {
    return Map.of("name", name, "type", "TEXT");
  }

  /** 거부돼야 하는 스텝 + 기대 문구. 출력 지정 여부는 OUT 표식(실제 id 는 setUp 이후에만 알 수 있다)으로 넘긴다. */
  private static final Long OUT = -1L;

  static Stream<Arguments> rejectedSteps() {
    return Stream.of(
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
            "스텝 'API 호출': 컬럼명 'id'은 시스템 예약어입니다. (예약어: id, import_id, created_at, _updated_at)"),
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
            aiStep(List.of(aiColumn("label"), aiColumn(""))),
            "스텝 'AI 분류': 출력 컬럼 이름을 입력하세요"),
        Arguments.of(
            "AI 출력 컬럼 이름 형식 위반",
            aiStep(List.of(aiColumn("Label"))),
            "스텝 'AI 분류': 컬럼 이름은 영문 소문자로 시작하고 영문 소문자·숫자·밑줄만 쓸 수 있습니다: Label"),
        Arguments.of(
            "AI 출력 컬럼 이름 중복",
            aiStep(List.of(aiColumn("label"), aiColumn("label"))),
            "스텝 'AI 분류': 출력 컬럼 이름이 중복됩니다: label"));
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
}
