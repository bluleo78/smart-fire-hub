package com.smartfirehub.pipeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.service.ApiConnectionReader;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataTableNames;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.exception.EditConflictException;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.exception.PipelineInactiveException;
import com.smartfirehub.pipeline.exception.PipelineNameConflictException;
import com.smartfirehub.pipeline.exception.PipelineNotFoundException;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import com.smartfirehub.pipeline.service.validator.PythonScriptValidator;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.DatasetAction;
import com.smartfirehub.securitylevel.access.SqlAccessMode;
import com.smartfirehub.securitylevel.access.SqlAccessResult;
import com.smartfirehub.securitylevel.ai.AiCall;
import com.smartfirehub.securitylevel.ai.AiCallContext;
import com.smartfirehub.securitylevel.ai.AiHostingResolver;
import com.smartfirehub.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineService {

  private static final Set<String> VALID_OUTPUT_COLUMN_TYPES =
      Set.of("TEXT", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "TIMESTAMP");
  private static final Set<String> VALID_ON_ERROR = Set.of("CONTINUE", "RETRY_BATCH", "FAIL_STEP");

  // 스텝 출력 컬럼 저장 검증 문구(WD-154) — 웹 편집기(usePipelineValidation)와 같은 문장을 쓴다. 서버는 앞에 「스텝 '<이름>': 」을 붙인다.
  static final String EMPTY_MAPPING_ROW_MESSAGE = "비어 있는 매핑 행이 있습니다 — 채우거나 삭제하세요";
  static final String TARGET_COLUMN_REQUIRED_MESSAGE = "대상 컬럼 이름을 입력하세요";
  static final String SOURCE_FIELD_REQUIRED_MESSAGE = "응답 필드를 입력하세요";
  static final String OUTPUT_COLUMN_NAME_REQUIRED_MESSAGE = "출력 컬럼 이름을 입력하세요";
  static final String INVALID_COLUMN_NAME_MESSAGE = "컬럼 이름은 영문 소문자로 시작하고 영문 소문자·숫자·밑줄만 쓸 수 있습니다: ";
  static final String MISSING_OUTPUT_COLUMN_MESSAGE = "출력 데이터셋에 없는 컬럼입니다: ";

  /**
   * 스텝이 참조한 API 연결이 현재 워크스페이스에서 보이지 않을 때의 저장 거절 문구(WD-176). 다른 워크스페이스의 연결과 없는 연결을 같은 문구·같은 400 으로
   * 거절해야 연결 번호의 존재 여부가 새지 않으므로, 연결 번호를 문구에 넣지 않는다.
   */
  static final String API_CONNECTION_NOT_FOUND_MESSAGE = "선택한 API 연결을 찾을 수 없습니다. 연결 목록에서 다시 선택하세요.";

  // "스텝 참조" 문법({{#N}}) — 실행 시점(PipelineAsyncRunner#resolveStepReferences)과 동일한 패턴을
  // 저장 시점 검증에도 적용해야 두 시점의 검증 대상이 일치한다 (#643).
  private static final Pattern STEP_REFERENCE_PATTERN = Pattern.compile("\\{\\{#(\\d+)\\}\\}");

  private final PipelineRepository pipelineRepository;
  private final PipelineStepRepository stepRepository;
  private final PipelineExecutionRepository executionRepository;
  private final PipelineExecutionService executionService;
  private final UserRepository userRepository;
  private final TriggerRepository triggerRepository;
  private final ObjectMapper objectMapper;

  /**
   * 테넌트 백엔드별 데이터 스키마·검증기 선택(WD-77, 스펙 §5.0). SQL 스텝 검증기와 {@code {{#N}}} 더미 한정 이름을 여기서 얻는다 — PG 테넌트는
   * 기존 검증기 빈·{@code DataSchema.qualify} 그대로라 러너와 같은 선택이 된다.
   */
  private final PipelineDataSchema pipelineDataSchema;

  private final PythonScriptValidator pythonScriptValidator;
  private final DatasetColumnRepository columnRepository;
  private final PipelineSecurityGate pipelineSecurityGate;

  /** 상세 응답의 출력 데이터셋 이름 가시성(보안 등급) — 볼 수 없는 출력은 이름만 null(id 는 유지). */
  private final DatasetAccessGuard datasetAccessGuard;

  /** 실행 기록 조회자 자격(WD-27) — 원문 오류·로그를 보여 줄지 판정한다. */
  private final ClearanceResolver clearanceResolver;

  /** AI_CLASSIFY 입력 판정의 분류 공급자 호스팅(S3 §4.3) — 채팅이 아니라 실제로 분류를 맡을 공급자 기준이다. */
  private final AiHostingResolver aiHostingResolver;

  /** AI 대행 요청 판별(S3 §4.3) — 실행 기록 원문이 LLM 으로 갈 때 관련 데이터셋의 AI(+SHARE) 정책까지 본다. */
  private final AiCallContext aiCallContext;

  /**
   * 스텝이 만든 자동 생성 임시 데이터셋 정리(WD-170) — 파이프라인 삭제·스텝 제거 때 메타·물리 테이블을 데이터셋 삭제와 같은 경로({@code
   * DatasetService.deleteDataset})로 지운다.
   */
  private final TempDatasetService tempDatasetService;

  /** 스텝이 참조하는 API 연결이 현재 워크스페이스에서 보이는지 판정(WD-176) — RLS 가 걸린 조회다. */
  private final ApiConnectionReader apiConnectionReader;

  /**
   * 원문 오류를 볼 수 없는 조회자에게 스텝·실행 오류 대신 보여 주는 고정 문구(WD-27). 원문(PG 오류)에는 숨김 테이블명·행 값이 실릴 수 있어 일부만 지우지 않고
   * 통째로 바꾼다. 판정 근거가 없어 가린 경우(숨김 데이터셋이 실제로 없을 수도 있음)에도 사실과 어긋나지 않도록 중립 문구로 둔다.
   */
  public static final String WITHHELD_STEP_ERROR_MESSAGE =
      "이 스텝의 상세 오류는 관련 데이터를 볼 수 있는 사용자에게만 표시됩니다.";

  /** 실행 단위 오류(스텝 밖 최상위 예외)용 가림 문구 — 특정 스텝 오류가 아니므로 "이 스텝의" 대신 "이 실행의" 로 둔다(WD-27). */
  public static final String WITHHELD_EXECUTION_ERROR_MESSAGE =
      "이 실행의 상세 오류는 관련 데이터를 볼 수 있는 사용자에게만 표시됩니다.";

  /**
   * 이름 중복 409 의 사용자 안내 문구(WD-112). 웹이 서버 message 를 그대로 토스트로 보여 주므로 한국어 안내 문장으로 쓴다 — 예전엔 "Pipeline
   * with name '…' already exists" 영어 원문이 그대로 노출됐다. 생성·수정이 같은 문구를 쓰도록 한곳에 둔다.
   */
  static String duplicateNameMessage(String name) {
    return "같은 이름의 파이프라인이 이미 있습니다: '" + name + "'. 다른 이름을 입력하세요.";
  }

  @Transactional
  public PipelineDetailResponse createPipeline(CreatePipelineRequest request, Long userId) {
    // 이름 중복 검사 — 동일 이름의 파이프라인이 존재하면 409 반환 (#181)
    if (pipelineRepository.existsByName(request.name())) {
      throw new PipelineNameConflictException(duplicateNameMessage(request.name()));
    }

    // Validate DAG (cycle detection)
    executionService.validateDAG(request.steps());

    // Save pipeline
    PipelineResponse pipeline =
        pipelineRepository.save(request.name(), request.description(), userId);

    // Save steps — 새 파이프라인이므로 요청에 스텝 id 가 와도 무시하고 전부 새로 만든다(existingSteps = 전부 null).
    saveSteps(pipeline.id(), request.steps(), userId, Set.of(), Set.of(), null);

    // Return full detail
    return getPipelineById(pipeline.id());
  }

  /**
   * 스텝 전체를 검증·저장한다.
   *
   * @param editorUserId 저장하는 편집자 — SQL 스텝이 참조하는 데이터셋을 이 사용자가 볼 수 있어야 한다(보안 등급 S2, 스펙 §4.2 5행)
   * @param previouslySavedOutputIds 이 파이프라인에 저장 전부터 있던(해석된) 출력 데이터셋 id — 그대로 되돌아온 id 는 출력 VIEW 판정을
   *     건너뛴다(왕복 보존, 아래 SQL·AI_CLASSIFY 출력 판정 참고). 새 파이프라인은 빈 집합
   * @param previouslySavedInputIds 이 파이프라인에 저장 전부터 있던 입력 데이터셋 id — SQL·PYTHON·API_CALL 입력의 같은 왕복 보존
   *     규칙(WD-21)
   * @param existingSteps 요청 순서대로, 제자리 갱신할 기존 스텝(새 스텝은 null) — WD-108. null 이면 전부 새 스텝(생성 경로). 저장 전
   *     값은 WD-426 자기 출력 판정이 "바뀐 스텝만" 거부하는 데도 쓴다
   */
  private void saveSteps(
      Long pipelineId,
      List<PipelineStepRequest> stepRequests,
      Long editorUserId,
      Set<Long> previouslySavedOutputIds,
      Set<Long> previouslySavedInputIds,
      List<PipelineStepResponse> existingSteps) {
    if (stepRequests == null || stepRequests.isEmpty()) {
      return;
    }
    // 스텝마다 같은 값을 다시 만들지 않게 루프 밖에서 한 번만 꺼낸다(WD-426 자기 출력 판정용). 백엔드 해석은 테넌트 컨텍스트를
    // 요구하므로 SQL 스텝이 있을 때만 한다 — SQL 스텝 검증은 어차피 같은 해석을 거치고, 다른 유형만 있는 저장은 예전처럼 건드리지 않는다.
    boolean anySqlStep = stepRequests.stream().anyMatch(r -> "SQL".equals(r.scriptType()));
    SqlValidator sqlValidator = anySqlStep ? pipelineDataSchema.validator() : null;
    boolean tiberoBackend = anySqlStep && pipelineDataSchema.backend() == DataStoreBackend.TIBERO;

    // Step 1: Save all steps and build name -> stepId map
    Map<String, Long> stepNameToId = new HashMap<>();
    // 이번 요청에 든 스텝 이름 — AI_CLASSIFY 의 의존 스텝이 실제로 이 파이프라인에 있는지 본다(WD-216). 러너도 같은 파이프라인의 스텝만
    // 이름으로 찾고 없는 이름은 건너뛰므로, 저장 대상 목록이 곧 실행 시점의 해석 범위다.
    Set<String> requestStepNames = new java.util.HashSet<>();
    for (PipelineStepRequest r : stepRequests) {
      requestStepNames.add(r.name());
    }
    // 요청 출력 중 소유 스텝이 있는 러너 TEMP → 소유 스텝 id(WD-224 출력 판정용, 한 번에 읽는다).
    Map<Long, Long> tempOwnerByDatasetId =
        stepRepository.findTempOwnerStepIdsByDatasetIds(
            stepRequests.stream()
                .map(PipelineStepRequest::outputDatasetId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet()));

    for (int i = 0; i < stepRequests.size(); i++) {
      PipelineStepRequest stepRequest = stepRequests.get(i);
      // 짝지은 기존 스텝(새 스텝이면 null) — 제자리 갱신 id·자기 TEMP 판정·WD-426 "바뀐 스텝만" 판정이 함께 쓴다.
      PipelineStepResponse storedStep = existingSteps != null ? existingSteps.get(i) : null;
      Long existingStepId = storedStep != null ? storedStep.id() : null;

      // WD-176: 참조할 API 연결이 현재 워크스페이스에서 보여야 한다. 외래키 검사는 RLS 를 우회해 다른 워크스페이스의 연결 번호도 통과시켜,
      // 남의 연결을 참조로 저장(그 워크스페이스는 참조 0건으로 보이는데 삭제가 영영 409)하거나 저장 성공/FK 409 차이로 번호 존재를 떠볼 수
      // 있었다. 스크립트 유형과 무관하게 본다 — 리포지토리는 유형을 가리지 않고 api_connection_id 를 기록하므로 SQL 스텝으로도 같은
      // 참조가 생긴다. 수정 경로에도 왕복 보존 예외를 두지 않는다: 같은 워크스페이스 안에서는 연결의 보임 여부가 사용자마다 갈리지 않으므로,
      // 다시 보낸 값이 안 보인다면 이미 저장된 교차 참조다(되살리지 않는다). null(인라인 인증)은 그대로 허용한다.
      requireVisibleApiConnection(stepRequest);

      // Validate API_CALL step requirements
      if ("API_CALL".equals(stepRequest.scriptType())) {
        if (stepRequest.apiConfig() == null || stepRequest.apiConfig().isEmpty()) {
          throw new IllegalArgumentException(
              stepError(stepRequest.name(), "API 호출 설정(apiConfig)이 필요합니다"));
        }
        // 응답 필드 매핑의 빈 행·형식·중복은 데이터를 보지 않는 검사라 보안 판정 전에 둔다(WD-154). 출력 데이터셋에 컬럼이 있는지는
        // 아래 출력 판정 뒤에 본다(존재 오라클 방지).
        validateApiCallFieldMappings(stepRequest);
        validateApiCallPagination(stepRequest);
      }

      // Validate AI_CLASSIFY step requirements
      if ("AI_CLASSIFY".equals(stepRequest.scriptType())) {
        validateAiClassifyStep(stepRequest, requestStepNames);
        // 보안 등급(최종 리뷰 C3): AI_CLASSIFY 는 명시 입력 데이터셋을 전부 읽어 LLM 으로 보낸다 — 편집자가 볼 수 없는 입력은 저장할 수
        // 없다(SQL 스텝의 저장 판정과 같은 의미). 의존 스텝 출력 자동 해석분은 실행 시점에 판정된다.
        if (stepRequest.inputDatasetIds() != null && !stepRequest.inputDatasetIds().isEmpty()) {
          pipelineSecurityGate.checkStepInputsForSave(editorUserId, stepRequest.inputDatasetIds());
          // S3 §4.3: AI_CLASSIFY 입력은 분류 공급자로 간다 — 편집자 기준 AI 판정(분류 호스팅).
          // PipelineSecurityGate(흐름 B 소유)는 고치지 않고 여기서 가드를 직접 부른다.
          // editorUserId 가 null 이면 바로 위 판정이 이미 거부했으므로 여기 오지 않는다.
          datasetAccessGuard.requireAiForDatasets(
              clearanceResolver.resolve(editorUserId),
              stepRequest.inputDatasetIds(),
              new AiCall(aiHostingResolver.classify(), false));
          // WD-215: 고른 입력 컬럼이 명시 입력 데이터셋에 실제로 있는지 — 입력 보안·AI 판정을 지난 뒤에만 본다(앞에 두면 볼 수 없는
          // 데이터셋으로 컬럼 이름을 떠보는 존재 오라클이 된다, WD-154 와 같은 이유).
          validateAiClassifyInputColumnsExist(stepRequest);
        }
      }

      // 보안 등급(코드리뷰 CR2): API_CALL·PYTHON 은 SQL 관문 없이 지정 출력을 비우고(REPLACE) 덮어쓴다 — 편집자가 볼 수 없는 데이터셋을
      // 출력으로 지정한 스텝은 저장할 수 없다. 러너 TEMP(편집 화면이 되돌려 보내는 출력 폴백)는 실행 시점에 판정된다.
      if ("API_CALL".equals(stepRequest.scriptType())
          || "PYTHON".equals(stepRequest.scriptType())) {
        pipelineSecurityGate.checkStepOutputForSave(
            editorUserId,
            stepRequest.outputDatasetId(),
            // Set.of() 는 contains(null) 에서 NPE — null 출력(자동 TEMP)은 헬퍼가 먼저 통과시킨다.
            stepRequest.outputDatasetId() != null
                && previouslySavedOutputIds.contains(stepRequest.outputDatasetId()));
      } else if (stepRequest.outputDatasetId() != null
          && !previouslySavedOutputIds.contains(stepRequest.outputDatasetId())) {
        // TEMP 예외 없이 판정한다(리뷰 M1) — 자기 TEMP 폴백은 previouslySavedOutputIds 가 이미 덮는다.
        // 보안 등급(이름 확인 경로 차단): SQL·AI_CLASSIFY 스텝의 출력 id 는 저장 시 판정이 없어, 임의 id 를 넣고 저장 성공(숨김)·FK
        // 오류(없음)·MERGE PK 안내 차이로 숨김 데이터셋의 존재를 확인할 수 있었다. 새로 지정한 출력은 편집자가 볼 수 있어야 한다 —
        // 숨김·없는 id 는 같은 403(DATASET_SQL_ACCESS_DENIED). MERGE PK 조회·saveStep(FK)보다 먼저 둬야 구분이 사라진다.
        // 이미 이 파이프라인에 있던 출력 id(편집자가 자격을 잃은 뒤 웹이 그대로 되돌려 보내는 값)는 막지 않는다 — 막으면 다른 필드만
        // 고쳐도 저장이 불가능해진다(왕복 보존). 실행 시점에는 실행 주체 기준으로 다시 판정된다(enforceOutputLevel).
        pipelineSecurityGate.checkNewStepReferencesForSave(
            editorUserId, List.of(stepRequest.outputDatasetId()));
      }

      // 보안 등급(WD-21): SQL·PYTHON·API_CALL 의 입력 id 는 메타데이터로만 저장돼 판정이 없었다 — 없는 id 는 FK 오류, 숨김 id 는 저장
      // 성공으로 갈려 존재를 확인할 수 있었다. 새로 추가한 입력만 편집자 VIEW 판정(숨김·없음·null 같은 403), 기존 입력 재전송은 통과(왕복
      // 보존). AI_CLASSIFY 는 위에서 입력 전부를 이미 판정한다(입력을 실제로 읽어 LLM 으로 보내므로 더 엄격 — 그대로 둔다).
      if (!"AI_CLASSIFY".equals(stepRequest.scriptType())
          && stepRequest.inputDatasetIds() != null) {
        List<Long> newInputs = new java.util.ArrayList<>();
        for (Long in : stepRequest.inputDatasetIds()) {
          if (in == null || !previouslySavedInputIds.contains(in)) {
            newInputs.add(in);
          }
        }
        pipelineSecurityGate.checkNewStepReferencesForSave(editorUserId, newInputs);
      }

      // WD-224: 다른 스텝이 소유한 러너 TEMP 는 출력으로 지정할 수 없다. 소유 스텝은 실행마다 그 TEMP 를 자기 결과로 비우고 다시
      // 채우므로(필요하면 다시 만든다, WD-222), 다른 스텝이 같은 TEMP 에 쓰면 두 스텝이 실행될 때마다 서로의 결과를 덮어쓴다.
      // 판정은 보안(가시성) 판정 뒤에 둔다 — 앞에 두면 숨김 데이터셋이 TEMP 인지 400/403 차이로 떠볼 수 있다. 왕복 보존 예외를 두지
      // 않는다: previouslySavedOutputIds 에는 같은 파이프라인 형제 스텝의 TEMP·이미 고정된 남의 TEMP 도 들어 있어, 예외를 두면
      // 그대로 통과한다. 자기 TEMP 는 짝지은 기존 스텝 id 와 소유 스텝 id 를 직접 비교해 허용한다(수정 경로는 앞서 null 로
      // 정규화되지만, 한 스텝에 TEMP 가 여럿인 경우까지 정규화에 기대지 않는다). 소유 스텝이 끊긴 고아 TEMP(같은 저장에서 뺀 스텝의
      // TEMP 를 넘겨받는 경우 등, WD-170)는 덮어쓸 소유자가 없으므로 허용한다.
      Long outputId = stepRequest.outputDatasetId();
      if (outputId != null && tempOwnerByDatasetId.containsKey(outputId)) {
        if (!tempOwnerByDatasetId.get(outputId).equals(existingStepId)) {
          throw new IllegalArgumentException(
              stepError(stepRequest.name(), FOREIGN_TEMP_OUTPUT_MESSAGE));
        }
      }

      // WD-154: API_CALL 매핑의 대상 컬럼이 지정 출력 데이터셋에 실제로 있는지 — 출력·입력 보안 판정을 모두 지난 뒤에만 본다. 앞에 두면 볼
      // 수 없는(또는 없는) 데이터셋 id 로 컬럼 이름을 떠보는 존재 오라클이 된다.
      if ("API_CALL".equals(stepRequest.scriptType())) {
        validateApiCallTargetsExist(stepRequest, editorUserId);
      }

      // PYTHON 스텝 escalation 코드(shell/동적실행 등) 차단 — pythonConfig 유무와 무관하게 항상 검증 (#270)
      if ("PYTHON".equals(stepRequest.scriptType())) {
        pythonScriptValidator.validate(stepRequest.scriptContent());
      }

      // Validate PYTHON step outputColumns
      if ("PYTHON".equals(stepRequest.scriptType()) && stepRequest.pythonConfig() != null) {
        validatePythonStep(stepRequest);
      }

      // SQL 스텝 안전 정책(단일 statement + DML + data 스키마 + 위험 함수 차단) 검증 (#136)과 조회/DML 판정(WD-93, 아래
      // MERGE 검증이 쓴다)을 한 번에 한다. {{#N}} 스텝 참조는 유효한 SQL 문법이 아니므로, 실행 시점과 동일하게 먼저 더미
      // 테이블 참조로 치환한 뒤 검증한다 — 그렇지 않으면 UI가 권장하는 표준 사용법이 저장 단계에서 항상 파싱 실패로 거부된다 (#643).
      boolean sqlStepIsQuery = false;
      // 사용자 SQL 만의 참조 판정(WD-426) — 아래 출력 방식이 정해진 뒤 REPLACE 자기 출력 판정에 쓴다. 판정한 문자열도 남겨 둔다.
      SqlAccessResult sqlAccess = null;
      String substituted =
          "SQL".equals(stepRequest.scriptType())
              ? substituteForValidation(stepRequest.scriptContent())
              : null;
      if ("SQL".equals(stepRequest.scriptType())) {
        sqlStepIsQuery = validateAndJudgeQuery(sqlValidator, substituted);
        // 보안 등급(S2): 편집자가 볼 수 없는 데이터셋을 참조하는 스텝은 저장할 수 없다(스펙 §4.2 5행). 검증한 바로 그
        // 문자열을 판정한다(가드 계약). {{#N}} 더미는 판정에서 빠지고 실행 시점에 실제 테이블로 판정된다(판단 사항 4).
        sqlAccess = pipelineSecurityGate.checkStepSqlForSave(editorUserId, substituted);
      }

      // 출력 방식 검증 — 문자열로만 저장되던 값을 enum 으로 고정한다(알 수 없는 값이 REPLACE 로 조용히 폴백되던 경로 차단).
      String strategy = stepRequest.loadStrategy() != null ? stepRequest.loadStrategy() : "REPLACE";
      LoadStrategy ls;
      try {
        ls = LoadStrategy.valueOf(strategy);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("알 수 없는 로드 전략입니다: " + strategy);
      }
      // 증분 스텝은 REPLACE 와 양립할 수 없다 — REPLACE 는 매 실행 출력을 비우므로, 변경분만 읽는
      // SELECT 와 합치면 출력에 "이번에 바뀐 행"만 남고 나머지가 전부 사라진다(조용한 데이터 손실).
      // MERGE(권장) 또는 APPEND 를 쓰게 한다.
      if (ls == LoadStrategy.REPLACE
          && LastRunAtPlaceholder.isIncrementalStep(
              stepRequest.scriptType(), stepRequest.scriptContent())) {
        throw new IllegalArgumentException(
            "{{last_run_at}} 은 REPLACE 와 함께 쓸 수 없습니다(매 실행 출력이 변경분만 남습니다). MERGE 를 사용하세요: "
                + stepRequest.name());
      }
      // REPLACE 가 비울 출력을 SQL 이 스스로 읽거나 고치는가(WD-426, 규칙은 OutputSelfReference — 실행 시점도 같은 판정으로 2차
      // 방어한다). 새 스텝이거나 SQL·출력 방식·출력이 바뀐 스텝만 본다 — 규칙 전에 저장된 스텝이 그대로 남았다고 다른 스텝만 고친 저장까지
      // 막으면 파이프라인을 고칠 수 없다(그대로 남은 스텝은 실행 시점 판정이 출력을 지우지 않고 실패시킨다).
      if (sqlAccess != null
          && ls == LoadStrategy.REPLACE
          && !sameSelfReferenceInputs(storedStep, stepRequest, ls)) {
        OutputSelfReference.Plan selfReference =
            OutputSelfReference.plan(
                sqlStepIsQuery,
                tiberoBackend,
                sqlAccess,
                () -> sqlValidator.isUpdateOrDelete(substituted),
                stepRequest.outputDatasetId());
        if (selfReference.rejected()) {
          throw new IllegalArgumentException(selfReference.message() + ": " + stepRequest.name());
        }
      }

      if (ls == LoadStrategy.MERGE) {
        if (!"SQL".equals(stepRequest.scriptType())) {
          throw new IllegalArgumentException(
              "MERGE 로드 전략은 SQL 스텝에서만 사용할 수 있습니다: " + stepRequest.name());
        }
        // Fix round 1, must 3 — 사용자가 직접 쓴 INSERT/UPDATE/DELETE 스텝에 MERGE 를 걸면 실행 시점의
        // 출력 비우기 판단과 SELECT 래핑 두 블록이 모두 스킵돼(둘 다 SELECT 자동 적재 경로 전용) MERGE 가
        // 조용히 아무 의미도 없는 채로 사용자 SQL 이 그대로 실행된다 — PK 없음과 같은 무동작 부류라 저장
        // 시점에 막는다.
        if (!sqlStepIsQuery) {
          throw new IllegalArgumentException(
              "MERGE 로드 전략은 SELECT 스텝에만 사용할 수 있습니다(INSERT/UPDATE/DELETE 는 지원하지 않습니다): "
                  + stepRequest.name());
        }
        if (stepRequest.outputDatasetId() == null) {
          throw new IllegalArgumentException("MERGE 로드 전략에는 출력 데이터셋이 필요합니다: " + stepRequest.name());
        }
        boolean hasPk =
            columnRepository.findByDatasetId(stepRequest.outputDatasetId()).stream()
                .anyMatch(DatasetColumnResponse::isPrimaryKey);
        if (!hasPk) {
          throw new IllegalArgumentException(
              "MERGE 로드 전략에는 출력 데이터셋의 PK 컬럼이 필요합니다: " + stepRequest.name());
        }
      }

      // Save step — 기존 스텝이면 id 를 유지한 채 제자리 갱신하고(과거 실행 기록·러너 TEMP 연결 유지, WD-108), 새 스텝만 INSERT 한다.
      // 검증은 위에서 두 경우 모두 똑같이 끝났다.
      Long stepId;
      if (existingStepId != null) {
        stepRepository.updateStep(pipelineId, existingStepId, stepRequest, i);
        stepId = existingStepId;
      } else {
        stepId = stepRepository.saveStep(pipelineId, stepRequest, i);
      }
      stepNameToId.put(stepRequest.name(), stepId);

      // Save input datasets
      if (stepRequest.inputDatasetIds() != null) {
        for (Long datasetId : stepRequest.inputDatasetIds()) {
          stepRepository.saveStepInput(stepId, datasetId);
        }
      }
    }

    // Step 2: Save dependencies (now that all steps exist)
    for (PipelineStepRequest stepRequest : stepRequests) {
      Long stepId = stepNameToId.get(stepRequest.name());

      if (stepRequest.dependsOnStepNames() != null) {
        for (String dependsOnStepName : stepRequest.dependsOnStepNames()) {
          Long dependsOnStepId = stepNameToId.get(dependsOnStepName);
          if (dependsOnStepId != null) {
            stepRepository.saveStepDependency(stepId, dependsOnStepId);
          }
        }
      }
    }
  }

  /**
   * 저장 시점 SQL 구조 검증 전, {{#N}} 스텝 참조를 유효한 더미 테이블 참조로 치환한다.
   *
   * <p>실행 시점({@code PipelineAsyncRunner#resolveStepReferences})은 {{#N}}을 {@link
   * DataSchema#qualify}가 만든 실제 테이블 FQN으로 치환한 뒤 {@link SqlValidator#validate}를 호출하므로 정상 통과한다. 반면 저장
   * 시점은 지금까지 원본(치환 전) SQL을 그대로 파싱했기 때문에, {{#N}}이 SQL 문법상 유효하지 않아(예: FROM절) 항상 파싱 실패로 저장이 거부됐다
   * (#643). 두 시점의 검증 대상을 구조적으로 맞추기 위해, 여기서도 실제 치환과 동일한 형태(현재 테넌트 데이터 스키마 + 식별자)의 더미로 바꿔 넣는다 — 스텝
   * 번호가 유효한지, 참조 대상이 실제로 존재하는지는 실행 시점에만 알 수 있으므로 (저장 시점엔 DAG의 나머지 스텝이 아직 없을 수도 있음) 검사하지 않고, 오직 "SQL
   * 구조가 유효한가"만 본다.
   *
   * <p>공개 정적 메서드인 이유(WD-143): 데이터셋 참조 집계({@code DatasetService#getReferences})도 SQL 스텝 본문을 파싱해 실제로
   * 읽는 테이블을 찾는다. 같은 치환을 거치지 않으면 {{#N}}·{{last_run_at}} 이 든 스텝은 파싱에 실패해 참조에서 조용히 빠진다 — 치환 규칙을 두 곳에
   * 복사하면 한쪽만 바뀌는 순간 다시 갈라지므로 이 한 곳을 같이 쓴다. 인스턴스 상태를 쓰지 않는다 — 더미는 PG 꼴({@link
   * DataSchema#qualify})이다. 파이프라인 저장 검증은 테넌트 백엔드에 맞춘 {@link #substituteForValidation} 을 쓴다(WD-77).
   */
  public static String substituteStepReferencesForValidation(String sql) {
    return substituteStepReferencesForValidation(
        sql, DataSchema::qualify, DataStoreBackend.POSTGRESQL);
  }

  /**
   * 파이프라인 저장 검증용 치환 — 더미 한정 이름을 러너와 같은 {@link PipelineDataSchema#qualify} 로 만든다(WD-77). Tibero 테넌트는
   * {@code "GENIA_T{id}"."STEP_REF_N"}, PG 테넌트는 {@link DataSchema#qualify} 그대로라 정적 변형과 결과가 같다.
   */
  private String substituteForValidation(String sql) {
    return substituteStepReferencesForValidation(
        sql, pipelineDataSchema::qualify, pipelineDataSchema.backend());
  }

  /** 두 치환 변형의 공통 본문. {@code qualifier} 는 {@code {{#N}}} 더미 테이블 이름을 한정하는 함수다. */
  private static String substituteStepReferencesForValidation(
      String sql, UnaryOperator<String> qualifier, DataStoreBackend backend) {
    // 증분 플레이스홀더도 여기서 유효한 타임스탬프 리터럴로 바꿔 둔다 — {{last_run_at}} 은 SQL 문법이
    // 아니므로 그대로 두면 저장 시점 SQL 가드(AST 파싱)가 항상 실패해 증분 스텝을 아예 저장할 수 없다.
    // 값 자체는 검증에 영향이 없으므로 고정된 epoch 를 쓴다(실행 시점 값은 러너가 따로 주입한다).
    // 리터럴은 테넌트 방언대로 만든다 — Tibero 검증기(어휘 검사·AST)에 PG 의 {@code ::timestamptz} 캐스트를 넘기지 않는다(WD-77).
    return LastRunAtPlaceholder.substitute(
        substituteStepReferences(sql, qualifier),
        java.time.OffsetDateTime.parse("1970-01-01T00:00:00Z"),
        backend);
  }

  /**
   * 저장된 스텝이 WD-426 자기 출력 판정의 입력(스크립트 유형·SQL·출력 방식·출력)을 이번 요청과 똑같이 갖고 있는가. 같으면 그 판정 규칙이 생기기 전에 저장된
   * 스텝이 그대로 남은 것이므로 저장에서 다시 막지 않는다(다른 스텝만 고친 저장이 막히지 않게) — 실행 시점 판정이 여전히 막는다.
   *
   * <p>출력은 저장값으로 비교한다: 자동 생성 스텝의 조회 응답은 러너 TEMP id 를 채워 주지만(coalesce) 요청은 같은 스텝의 소유 TEMP 를 null 로
   * 정규화해 오므로(normalizeOwnedTempOutputs), 자동 생성이면 저장값을 null 로 본다.
   *
   * <p>출력 방식은 러너와 같은 해석({@link LoadStrategy#parse} — 대소문자 무시, 해석 불가면 REPLACE)으로 비교한다. 저장값에는 레거시
   * 소문자({@code "replace"})가 남아 있을 수 있어 문자열로 비교하면 같은 방식을 "바뀜"으로 본다.
   *
   * @param stored 짝지은 기존 스텝(새 스텝이면 null — 늘 "바뀜")
   * @param strategy 요청의 출력 방식(이미 해석한 값)
   */
  private static boolean sameSelfReferenceInputs(
      PipelineStepResponse stored, PipelineStepRequest request, LoadStrategy strategy) {
    if (stored == null) {
      return false;
    }
    Long storedOutput = stored.outputAutoGenerated() ? null : stored.outputDatasetId();
    LoadStrategy storedStrategy =
        LoadStrategy.parse(stored.loadStrategy()).orElse(LoadStrategy.REPLACE);
    return java.util.Objects.equals(stored.scriptType(), request.scriptType())
        && java.util.Objects.equals(stored.scriptContent(), request.scriptContent())
        && storedStrategy == strategy
        && java.util.Objects.equals(storedOutput, request.outputDatasetId());
  }

  /**
   * SQL 을 검증하고, 실행 시점(PipelineAsyncRunner)이 내릴 것과 <b>똑같은</b> "조회인가" 판정을 돌려준다. 검증에 실패하면 {@link
   * UnsafeSqlException} 을 던진다(저장 거부) — 판정 불가를 DML 로 보고 넘어가는 경로가 없다.
   *
   * <p>판정은 러너와 같은 {@link SqlValidator#validate} 의 반환값(파싱한 AST 의 문장 종류)이다(WD-93). 두 시점의 판단이 갈리면 저장은
   * 조회로 통과했는데 실행은 DML 로 보고 다르게 도는(또는 상세 조회가 실제와 다른 전체 재생성 모드를 보여주는) 결함이 생긴다.
   *
   * @param validator {@code pipelineDataSchema.validator()} — 러너(PipelineAsyncRunner)와 같은 검증기라
   *     저장·실행 판정이 갈리지 않는다. 스텝 저장 루프는 한 번 꺼낸 것을 넘긴다(Tibero 는 꺼낼 때마다 새로 만든다)
   * @param substituted {@link #substituteStepReferencesForValidation} 을 거친 SQL — 러너도 치환한 문자열을 검증한다
   */
  private static boolean validateAndJudgeQuery(SqlValidator validator, String substituted) {
    return validator.validate(substituted) == SqlValidator.StatementKind.QUERY;
  }

  /**
   * 상세 조회용 {@link #validateAndJudgeQuery} — 검증에 실패하면 false. 조회 화면을 검증 실패로 깨면 안 되고, 지금 정책으로 검증을 통과하지
   * 못하는 저장본(정책 강화 이전 행 등)은 실행 시점에도 같은 검증으로 실패하므로 "출력 재생성"이 일어날 일이 없다.
   */
  private boolean judgeQueryOrFalse(String scriptContent) {
    try {
      return validateAndJudgeQuery(
          pipelineDataSchema.validator(), substituteForValidation(scriptContent));
    } catch (UnsafeSqlException e) {
      return false;
    }
  }

  /**
   * {@code {{#N}}} 스텝 참조만 더미 테이블 참조로 치환한다({@code {{last_run_at}}} 은 그대로 둔다).
   *
   * <p>{@link #substituteStepReferencesForValidation}과 분리한 이유(코드리뷰 MEDIUM): {@link
   * SqlValidator#incrementalWarnings}는 <b>{@code {{last_run_at}}} 이 원문에 남아 있어야</b> 동작한다 (없으면 증분 스텝이
   * 아니라고 보고 빈 목록을 돌려준다). 그런데 스텝 참조가 남아 있으면 JSqlParser 파싱이 실패하고, 그 예외는 조용히 삼켜져 역시 빈 목록이 된다 — 즉 두 치환을
   * 한 덩어리로 쓰든 아예 안 쓰든 경고가 영원히 안 나온다. 그래서 "스텝 참조만" 바꾸는 이 단계가 따로 필요하다.
   */
  private static String substituteStepReferences(String sql, UnaryOperator<String> qualifier) {
    Matcher matcher = STEP_REFERENCE_PATTERN.matcher(sql);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      // 러너의 실제 치환과 같은 꼴 — 파이프라인 검증은 PipelineDataSchema.qualify(Tibero 는
      // "GENIA_T{id}"."STEP_REF_N", PG 는 DataSchema.qualify 그대로), 데이터셋 참조 집계는 DataSchema.qualify.
      String dummyTableRef = qualifier.apply("step_ref_" + matcher.group(1));
      matcher.appendReplacement(result, Matcher.quoteReplacement(dummyTableRef));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  @Transactional(readOnly = true)
  public PageResponse<PipelineResponse> getPipelines(int page, int size) {
    List<PipelineResponse> content = pipelineRepository.findAll(page, size);
    long totalElements = pipelineRepository.count();
    int totalPages = (int) Math.ceil((double) totalElements / size);
    return new PageResponse<>(content, page, size, totalElements, totalPages);
  }

  @Transactional(readOnly = true)
  public PipelineDetailResponse getPipelineById(Long id) {
    PipelineResponse pipeline =
        pipelineRepository.findById(id).orElseThrow(PipelineNotFoundException::new);

    List<PipelineStepResponse> steps =
        stepRepository.findByPipelineId(id, datasetAccessGuard.visibleCondition()).stream()
            .map(this::attachIncrementalMeta)
            .toList();

    var updatedAt = pipelineRepository.findUpdatedAtById(id).orElse(null);

    String updatedByUsername =
        pipelineRepository
            .findUpdatedByById(id)
            .flatMap(userRepository::findById)
            .map(user -> user.name())
            .orElse(null);

    return new PipelineDetailResponse(
        pipeline.id(),
        pipeline.name(),
        pipeline.description(),
        pipeline.isActive(),
        pipeline.createdBy(),
        steps,
        pipeline.createdAt(),
        updatedAt,
        updatedByUsername);
  }

  /**
   * SQL 스텝에 증분 처리 관련 어드바이저리 경고와 "전체 재생성 예약이 실제로 무엇을 하는가"({@code fullRebuildMode})를 계산해 붙인다(Task 7).
   * 저장을 막지 않는다 — 상세 조회 시 매번 다시 계산해 사용자에게 보여줄 뿐이다.
   *
   * <p>경고:
   *
   * <ul>
   *   <li>{@code {{last_run_at}}} + GROUP BY/집계/윈도우/DISTINCT — {@link
   *       SqlValidator#incrementalWarnings}
   *   <li>{@code {{last_run_at}}} + APPEND — 매 실행 바뀐 행이 추가로 한 번 더 쌓여 중복된다(MERGE 권장)
   * </ul>
   *
   * <p>{@code fullRebuildMode} 는 문자열 경고가 아니라 별도 필드로 노출한다 — 웹 UI(Task 8)가 문구를 파싱하지 않고도 "전체 재생성"(출력
   * 재작성)과 "전체 재읽기"(출력은 그대로, 입력만 전체)를 정확히 갈라 라벨을 붙이도록 하기 위해서다. 판정은 저장 시점 MERGE 검증(#saveSteps)과 같은
   * 검증+판정을 실패 시 false 로 감싼 {@link #judgeQueryOrFalse}로 내린다.
   */
  private PipelineStepResponse attachIncrementalMeta(PipelineStepResponse step) {
    // 증분 스텝이 아니면 경고도 재생성 모드도 없다(둘 다 기본값 유지) — 판정은 실행기와 같은 헬퍼(#739).
    if (!LastRunAtPlaceholder.isIncrementalStep(step.scriptType(), step.scriptContent())) {
      return step;
    }
    // 스텝 참조({{#N}})를 먼저 치환하고 넘긴다 — 원문 그대로 넘기면 JSqlParser 가 파싱에 실패하고
    // incrementalWarnings 가 그 RuntimeException 을 삼켜 항상 빈 목록을 돌려준다(코드리뷰 MEDIUM).
    // 이전 스텝 출력을 읽는 형태가 증분 스텝의 가장 흔한 모양이라, 사실상 경고가 아예 안 나왔다.
    // {{last_run_at}} 은 남겨야 한다 — incrementalWarnings 가 그 존재로 증분 여부를 판단한다.
    List<String> warnings =
        new java.util.ArrayList<>(
            pipelineDataSchema
                .validator()
                .incrementalWarnings(
                    substituteStepReferences(step.scriptContent(), pipelineDataSchema::qualify)));
    // equalsIgnoreCase — 실행기(PipelineAsyncRunner)가 로드 전략을 대소문자 무시로 해석하므로
    // 여기만 대소문자를 가리면 소문자 레거시 행("append")이 APPEND 로 실행되면서 경고만 빠진다.
    if ("APPEND".equalsIgnoreCase(step.loadStrategy())) {
      warnings.add("APPEND 와 {{last_run_at}} 을 함께 쓰면 수정된 행이 한 줄 더 추가되어 중복됩니다. MERGE 를 권장합니다.");
    }
    String fullRebuildMode =
        judgeQueryOrFalse(step.scriptContent())
            ? PipelineStepResponse.FULL_REBUILD_MODE_REBUILD_OUTPUT
            : PipelineStepResponse.FULL_REBUILD_MODE_READ_ALL;
    return step.withIncrementalMeta(warnings, fullRebuildMode);
  }

  /**
   * 전체 재생성/재읽기 예약(해제). 이 시점에는 데이터를 지우지 않는다 — 다음 실행이 비우기(SELECT 자동 적재 스텝) 또는 전체 읽기(사용자 DML 스텝)를 실제로
   * 한 트랜잭션에서 수행한다.
   *
   * <p><b>주의(운영 문서화 대상) — 사용자가 직접 쓴 INSERT/UPDATE/DELETE 증분 스텝은 "예약"이 출력을 재생성하지 않는다.</b> {@code
   * {{last_run_at}}} 이 {@code -infinity} 로 바뀌어 전체 행을 다시 읽을 뿐이고, 그 다음에 무엇을 하는지는 사용자 SQL
   * 자체(INSERT/UPDATE/DELETE 로직)에 달려 있다. 반면 SELECT 자동 적재 스텝(MERGE/APPEND)은 실행기가 출력을 비우고 전체를 다시 채운다.
   * 두 경우를 뭉뚱그려 "전체 재생성"이라 안내하면 사용자 DML 스텝에서는 거짓 약속이 된다 — 호출부(웹 UI 등)는 스텝의 로드 전략을 보고 문구를 갈라 써야 한다.
   *
   * @throws PipelineNotFoundException 파이프라인에 그 stepId 가 없을 때(다른 파이프라인의 스텝 포함)
   * @throws IllegalArgumentException {@code pending=true} 인데 스텝 SQL 이 {@code {{last_run_at}}} 을 쓰지
   *     않을 때 — 그런 스텝은 실행기가 증분 경로를 타지 않아 예약 플래그를 영원히 해제하지 못한다.
   */
  @Transactional
  public void setFullRebuildPending(Long pipelineId, Long stepId, boolean pending) {
    PipelineStepResponse step =
        stepRepository.findByPipelineId(pipelineId).stream()
            .filter(s -> s.id().equals(stepId))
            .findFirst()
            .orElseThrow(PipelineNotFoundException::step);
    // 실행기와 같은 판정("SQL 스텝 + {{last_run_at}}")으로 거른다(#739). 플레이스홀더만 보면 PYTHON 스텝
    // 스크립트 주석 속 문자열에도 예약이 들어가, 실행기가 소비하지 않는(화면에도 안 보이는) 예약이 남았다가
    // 같은 이름·출력의 증분 SQL 로 바꿔 저장하는 순간 이월되어 확인 없이 출력을 지운다.
    // 해제(pending=false)는 스텝 종류와 무관하게 허용한다 — 이미 남은 예약을 치울 수 있어야 한다.
    if (pending
        && !LastRunAtPlaceholder.isIncrementalStep(step.scriptType(), step.scriptContent())) {
      throw new IllegalArgumentException("{{last_run_at}} 을 쓰는 SQL 스텝만 전체 재생성을 예약할 수 있습니다.");
    }
    stepRepository.setFullRebuildPending(stepId, pending);
  }

  /** 편집 충돌 안내 문구(WD-353) — 웹은 코드로 충돌 창을 띄우고, 코드를 모르는 호출부(AI 도구 등)는 이 문구를 그대로 보인다. */
  static final String EDIT_CONFLICT_MESSAGE =
      "이 파이프라인은 편집을 시작한 뒤 다른 곳에서 변경되었습니다. 다시 불러온 뒤 저장하세요.";

  /**
   * 파이프라인을 고친다.
   *
   * @return 새로 저장된 수정 시각 — 웹이 다음 저장의 기준 버전으로 쓴다(WD-353). 돌려주지 않으면 저장 직후 재조회가 오기 전에 다시 고쳐 저장할 때 자기
   *     저장과 충돌한다.
   */
  @Transactional
  public LocalDateTime updatePipeline(Long id, UpdatePipelineRequest request, Long userId) {
    // 존재 확인 + 낙관적 잠금(WD-353). 행 잠금을 잡고 수정 시각을 읽어 편집 기준 버전과 비교한다 — 이름 중복·DAG 검증보다
    // 먼저 하는 이유: 낡은 화면에서 저장하면 낡은 내용으로 계산한 검증 오류 대신 "다른 곳에서 바뀌었다"를 먼저 알려야 한다.
    // 잠금은 이 트랜잭션 끝까지 유지되어 동시에 들어온 두 저장이 같은 버전으로 둘 다 통과하지 못한다.
    LocalDateTime currentUpdatedAt =
        pipelineRepository
            .lockUpdatedAtById(id)
            .orElseThrow(PipelineNotFoundException::new)
            .value1();
    EditConflictException.check(
        request.expectedUpdatedAt(), currentUpdatedAt, EDIT_CONFLICT_MESSAGE);

    // 이름 변경 시 중복 검사 — 다른 파이프라인에 동일 이름이 있으면 409 반환 (#181)
    if (request.name() != null) {
      String currentName = pipelineRepository.findNameById(id).orElse(null);
      if (!request.name().equals(currentName) && pipelineRepository.existsByName(request.name())) {
        throw new PipelineNameConflictException(duplicateNameMessage(request.name()));
      }
    }

    // Validate DAG if steps provided
    if (request.steps() != null) {
      executionService.validateDAG(request.steps());
    }

    // Update pipeline metadata
    LocalDateTime savedUpdatedAt =
        pipelineRepository.update(
            id, request.name(), request.description(), request.isActive(), userId);

    // 스텝 전체 교체 계약(요청이 스텝 목록 전체를 담는다)은 그대로지만, 저장 방식은 제자리 갱신이다(WD-108).
    // 예전에는 스텝을 전부 지우고 새 id 로 다시 만들었다 — pipeline_step_execution.step_id 가 ON DELETE CASCADE(V3)라
    // 저장 한 번(설명만 바꿔도)에 과거 실행의 스텝 상태·오류·적재 행 수가 통째로 지워졌다. 이제 같은 스텝은 id 를 유지한 채
    // UPDATE, 새 스텝만 INSERT, 요청에서 빠진 스텝만 DELETE 한다(지워진 스텝의 기록은 V139 의 SET NULL + step_name 스냅샷으로
    // 남는다).
    if (request.steps() != null) {
      List<PipelineStepResponse> existingSteps = stepRepository.findByPipelineId(id);
      // 왕복 보존용 기존 출력·입력 id — 이름이 아니라 파이프라인 전체 집합으로 비교한다(스텝 이름을 바꿔도 같은 출력을
      // 되돌려 보내는 저장이 막히지 않게). 웹은 해석된 출력(TEMP 폴백 포함)을 되돌려 보내므로 해석된 값을 쓴다.
      Set<Long> previouslySavedOutputIds = new java.util.HashSet<>();
      Set<Long> previouslySavedInputIds = new java.util.HashSet<>();
      for (PipelineStepResponse existing : existingSteps) {
        if (existing.outputDatasetId() != null) {
          previouslySavedOutputIds.add(existing.outputDatasetId());
        }
        previouslySavedInputIds.addAll(existing.inputDatasetIds());
      }

      // 1) 요청 스텝 ↔ 기존 스텝 짝짓기(요청 순서대로 짝지은 기존 스텝, 새 스텝은 null).
      List<PipelineStepResponse> matchedSteps = matchExistingSteps(existingSteps, request.steps());
      // Stream.toList() 는 null 원소를 허용한다 — 새 스텝 자리는 null id 로 saveSteps 에 넘어간다.
      List<Long> matchedStepIds =
          matchedSteps.stream().map(m -> m == null ? null : m.id()).toList();
      // 1-1) 자동 생성 출력 정규화(WD-222) — 웹 편집기·AI 도구는 조회 응답의 "해석된" 출력(출력 미지정이면 이 스텝의 러너 TEMP id)을 그대로
      // 되돌려 보낸다. 그대로 저장하면 output_dataset_id 가 TEMP id 로 고정돼 자동 생성 스텝이 "명시 출력" 처럼 보인다. 같은 스텝의
      // 소유 TEMP id 가 오면 미지정(null)으로 저장한다 — 러너도 같은 규칙으로 판정하지만(PipelineAsyncRunner), 저장값을 바로잡아 두면
      // 이미 고정된 기존 스텝도 다음 저장에서 스스로 풀린다. 다른 스텝·다른 파이프라인의 TEMP 는 여기서 바꾸지 않고 saveSteps 가 거부한다(WD-224).
      List<PipelineStepRequest> steps =
          normalizeOwnedTempOutputs(
              request.steps(),
              matchedStepIds,
              stepRepository.findTempDatasetIdsByStepIds(
                  matchedStepIds.stream().filter(java.util.Objects::nonNull).toList()));

      // 2) 요청에서 빠진 스텝만 지운다 — 먼저 지워야 그 이름을 새·이름 바뀐 스텝이 쓸 수 있다(UNIQUE (pipeline_id, name)).
      Set<Long> keptIds = new java.util.HashSet<>(matchedStepIds);
      keptIds.remove(null);
      List<Long> removedIds =
          existingSteps.stream()
              .map(PipelineStepResponse::id)
              .filter(sid -> !keptIds.contains(sid))
              .toList();
      // 2-1) 지울 스텝이 만든 자동 생성 임시 데이터셋을 미리 모아 둔다(WD-170) — 스텝을 지우면 source_pipeline_step_id 가
      // SET NULL(V36)이 되어 더는 찾을 수 없다. 실제 삭제는 저장이 끝난 뒤(7) 출력 참조를 다시 보고 한다.
      List<Long> removedStepTempIds = stepRepository.findAutoTempDatasetIdsByStepIds(removedIds);
      stepRepository.deleteSteps(id, removedIds);

      // 3) 남는 스텝의 입력·의존을 비운다 — 요청이 전체를 다시 보내므로 saveSteps 가 새 이름→id 맵으로 다시 잇는다.
      stepRepository.clearInputsAndDependencies(keptIds);

      // 4) 이름이 바뀌는 스텝은 임시 이름으로 비켜 둔다 — 이름 맞바꾸기(A↔B)도 UNIQUE 에 걸리지 않게(리포지토리 Javadoc).
      for (int i = 0; i < matchedSteps.size(); i++) {
        PipelineStepResponse before = matchedSteps.get(i);
        if (before != null && !before.name().equals(steps.get(i).name())) {
          stepRepository.renameStepTemporarily(id, before.id());
        }
      }

      // 5) 검증 + 제자리 갱신/새로 저장 + 입력·의존 재연결.
      saveSteps(id, steps, userId, previouslySavedOutputIds, previouslySavedInputIds, matchedSteps);

      // 6) 남은 스텝의 증분 책갈피 이월 규칙. 스텝 행이 그대로라 책갈피도 그대로 남아 있으므로 "버려야 할 때"만 되돌린다.
      //
      // 출력 데이터셋이 바뀐 스텝은 책갈피를 버린다 — 새 출력은 과거 실행분을 받은 적이 없는데 책갈피만 이어받으면 그 구간이
      // 영원히 비는(=조용한 데이터 누락) 결과가 된다. 전체 읽기로 되돌리는 쪽이 안전하다. 비교는 "해석된" 출력(지정 출력, 없으면 이
      // 스텝의 러너 TEMP)으로 한다 — 스텝 id 가 유지되므로 출력 미지정 스텝의 러너 TEMP(source_pipeline_step_id = 스텝 id)도
      // 그대로 이어진다. 예전(삭제·재생성)에는 id 가 바뀌어 러너가 빈 TEMP 를 새로 만들었기 때문에 출력 미지정 스텝의 책갈피를 버렸지만,
      // 이제 같은 TEMP 에 계속 쌓이므로 버리면 오히려 전체를 다시 읽어 APPEND 가 중복을 만든다.
      // 스크립트(SQL)만 바뀐 경우는 예전처럼 이어받는다(이름 기준 이월 시절과 같은 의미).
      // 출력을 비워 보낸 스텝의 해석값(러너 TEMP)은 남는 스텝 전체를 한 번에 읽어 둔다(스텝마다 조회하지 않음).
      Map<Long, Long> tempByStepId = stepRepository.findTempDatasetIdsByStepIds(keptIds);
      for (int i = 0; i < matchedSteps.size(); i++) {
        PipelineStepResponse before = matchedSteps.get(i);
        if (before == null) {
          continue; // 새 스텝 — 책갈피 없음(기본값)
        }
        Long sid = before.id();
        PipelineStepRequest s = steps.get(i);
        Long newResolvedOutput =
            s.outputDatasetId() != null ? s.outputDatasetId() : tempByStepId.get(sid);
        boolean sameOutput =
            before.outputDatasetId() != null
                && java.util.Objects.equals(before.outputDatasetId(), newResolvedOutput);
        // 재생성 예약은 새 스텝이 증분 스텝일 때만 이월한다(#734). 실행기(PipelineAsyncRunner)는
        // "SQL 스텝 + {{last_run_at}} 사용"일 때만 증분 경로를 타며 그 경로에서만 예약을 소비·해제한다.
        // 그래서 예약 API(setFullRebuildPending)도 비증분 스텝의 예약을 거부한다 — 저장 경로로 같은
        // 상태를 만들면 화면에 안 보이고 취소도 못 하는 예약이 남았다가, 나중에 {{last_run_at}} 을 다시
        // 넣는 순간 확인 없이 출력 전체를 지운다. 비증분으로 저장하면 예약은 버린다(다시 증분으로
        // 되돌려도 되살아나지 않는다). 책갈피(lastRunAt) 이월 여부는 별개 사안이라 기존대로 둔다.
        boolean newStepIncremental =
            LastRunAtPlaceholder.isIncrementalStep(s.scriptType(), s.scriptContent());
        if (!sameOutput) {
          stepRepository.setCursor(sid, null, false);
        } else if (before.fullRebuildPending() && !newStepIncremental) {
          stepRepository.setCursor(sid, before.lastRunAt(), false);
        }
      }

      // 7) 뺀 스텝이 만든 자동 생성 임시 데이터셋을 지운다(WD-170). 그대로 두면 연결만 끊긴 채 남아, 같은 이름의 스텝을 다시 넣으면
      // 러너가 새 임시 데이터셋을 만들려다 이름(ptmp_<파이프라인 id>_<스텝>) 충돌로 매번 실패한다. 저장(5)이 끝난 뒤에 하는 이유: 같은 저장에서
      // 남는·새 스텝이 그 임시 데이터셋을 출력으로 넘겨받을 수 있다(왕복 보존 출력) — 저장 후 상태로 출력 참조를 봐야 한다.
      // 지울 임시 데이터셋을 편집자가 모두 볼 수 있어야 한다(WD-310) — 못 보면 403 으로 저장 전체가 되돌아간다(같은 트랜잭션).
      // 판정한 목록을 그대로 지운다(판정과 삭제가 다른 목록을 보지 않게).
      AutoTempDeletion temps = planAutoTempDeletion(removedStepTempIds, removedIds);
      pipelineSecurityGate.requireAutoTempsDeletable(userId, temps.deletable());
      deleteAutoTempDatasets(temps);
    }
    return savedUpdatedAt;
  }

  /**
   * 함께 지울 자동 생성 임시 데이터셋만 고른다(WD-170) — 삭제 경로와 삭제 영향 조회가 같은 판정을 쓰게 하는 단일 지점이다(화면의 N 과 실제로 지워지는 개수가
   * 어긋나지 않게).
   *
   * <p>{@code excludedStepIds} 밖의 스텝이 출력({@code output_dataset_id})으로 쓰는 임시 데이터셋은 남긴다 — 데이터셋
   * 삭제({@code DatasetService.deleteDataset})가 출력 참조 시 거부하는 것과 같은 규칙이다. 입력으로만 참조하는 스텝은 막지 않는다(데이터셋
   * 삭제와 같음 — {@code pipeline_step_input.dataset_id} 가 ON DELETE CASCADE 라 그 입력 연결만 함께 사라진다).
   *
   * @param tempIds 후보 임시 데이터셋 id(지울 스텝들이 만든 것)
   * @param excludedStepIds 곧 지워질(또는 이미 지운) 스텝 — 자기 임시 데이터셋을 출력으로 되돌려 저장해 둔 경우를 참조로 세지 않는다
   */
  private List<Long> selectDeletableAutoTemps(
      List<Long> tempIds, Collection<Long> excludedStepIds) {
    Set<Long> usedAsOutput =
        stepRepository.findDatasetIdsUsedAsOutputExcludingSteps(tempIds, excludedStepIds);
    return tempIds.stream().filter(tid -> !usedAsOutput.contains(tid)).toList();
  }

  /**
   * 자동 생성 임시 데이터셋을 데이터셋 삭제 경로로 지운다(WD-170) — 물리 테이블(DROP)·행 검색 색인·컬럼 메타·데이터셋 행(태그·즐겨찾기 등은 FK
   * CASCADE)·감사 로그까지 기존 삭제와 똑같다. 호출자 트랜잭션에 합류하므로(같은 커넥션, PostgreSQL DDL 은 트랜잭션 안) 중간에 실패하면 스텝·파이프라인
   * 삭제까지 함께 되돌아간다 — 반쯤 지워진 상태가 남지 않는다.
   *
   * <p>출력 참조 판정을 먼저 해서 걸러 낸다. 데이터셋 삭제의 출력 참조 거부(DatasetInUseException)는 합류한 트랜잭션을 롤백 전용으로 만들어 잡아도
   * 커밋이 실패하므로, 거부에 기대지 않고 미리 거른다(그 거부는 안전망으로만 남는다). 데이터셋 소유자·보안 등급 확인은 하지 않는다 — 파이프라인을 지울 수 있는 사람이면
   * 그 파이프라인이 만든 임시 데이터셋도 지워져야 한다. 단 호출자가 볼 수 없는 것이 있으면 사용자 요청 경로가 먼저 거부한다(WD-310 — {@link
   * PipelineSecurityGate#requireAutoTempsDeletable}).
   *
   * <p>{@link #planAutoTempDeletion} 이 고른(사용자 경로면 판정까지 마친) 목록을 그대로 지운다 — 여기서 다시 고르지 않는다(판정과 삭제가 다른
   * 목록을 보지 않게).
   */
  private void deleteAutoTempDatasets(AutoTempDeletion temps) {
    for (Long tempId : temps.deletable()) {
      tempDatasetService.deleteTempDataset(tempId);
    }
    if (temps.deletable().size() < temps.candidates().size()) {
      log.info(
          "Kept {} auto temp dataset(s) still used as output by other steps: {}",
          temps.candidates().size() - temps.deletable().size(),
          temps.candidates().stream().filter(t -> !temps.deletable().contains(t)).toList());
    }
  }

  /**
   * 지울 자동 생성 임시 데이터셋 계획(WD-310) — 후보 전체와 그중 실제로 지울 것(다른 스텝이 출력으로 쓰지 않는 것).
   *
   * @param candidates 스텝이 만든 임시 데이터셋 후보 전체(남긴 것을 기록하는 데 쓴다)
   * @param deletable 지울 것 — 사용자 경로는 이 목록을 판정한 뒤 그대로 지운다
   */
  private record AutoTempDeletion(List<Long> candidates, List<Long> deletable) {}

  /** 후보에서 지울 것을 골라 계획으로 묶는다 — 판정·삭제·삭제 영향 조회가 같은 선택을 쓴다. */
  private AutoTempDeletion planAutoTempDeletion(
      List<Long> tempIds, Collection<Long> excludedStepIds) {
    return new AutoTempDeletion(tempIds, selectDeletableAutoTemps(tempIds, excludedStepIds));
  }

  /**
   * 파이프라인을 지우면 함께 지워질 자동 생성 임시 데이터셋 개수(WD-170 — 삭제 확인 창 안내용). 실제 삭제({@link #deletePipeline})와 같은
   * 판정({@link #selectDeletableAutoTemps})을 쓴다.
   *
   * @throws PipelineNotFoundException 파이프라인이 없을 때
   */
  @Transactional(readOnly = true)
  public PipelineDeleteImpactResponse getDeleteImpact(Long id, Long userId) {
    List<Long> deletable = planPipelineDeletion(id).deletable();
    // 볼 수 없는 것이 있으면 삭제가 403 이다(WD-310) — 확인 창이 미리 막도록 알리고, 개수에는 볼 수 있는 것만 센다(숨긴 개수를 드러내지 않게).
    int hidden = pipelineSecurityGate.hiddenCount(userId, deletable);
    return new PipelineDeleteImpactResponse(deletable.size() - hidden, hidden > 0);
  }

  /**
   * 요청 스텝의 출력이 짝지은 기존 스텝 자신의 러너 TEMP id 면 출력 미지정(null)으로 바꾼 목록을 돌려준다(WD-222). 조회 응답이 출력 미지정 스텝에 그
   * 스텝의 TEMP id 를 채워 주므로(PipelineStepRepository#findByPipelineId 의 coalesce — 화면 표시·{{#N}} 참조용),
   * 클라이언트가 그 값을 되돌려 보내도 "자동 생성" 이 유지되게 한다. 새 스텝(짝 없음)·다른 스텝의 TEMP·사용자 데이터셋은 그대로 둔다.
   *
   * @param stepRequests 요청 스텝(요청 순서)
   * @param matchedStepIds 요청 순서대로 짝지은 기존 스텝 id(새 스텝은 null)
   * @param tempByStepId 기존 스텝 id → 그 스텝의 러너 TEMP id
   */
  static List<PipelineStepRequest> normalizeOwnedTempOutputs(
      List<PipelineStepRequest> stepRequests,
      List<Long> matchedStepIds,
      Map<Long, Long> tempByStepId) {
    List<PipelineStepRequest> result = new ArrayList<>(stepRequests.size());
    for (int i = 0; i < stepRequests.size(); i++) {
      PipelineStepRequest s = stepRequests.get(i);
      Long matchedId = matchedStepIds.get(i);
      Long ownTemp = matchedId != null ? tempByStepId.get(matchedId) : null;
      if (s.outputDatasetId() != null && s.outputDatasetId().equals(ownTemp)) {
        s =
            new PipelineStepRequest(
                s.name(),
                s.description(),
                s.scriptType(),
                s.scriptContent(),
                null,
                s.inputDatasetIds(),
                s.dependsOnStepNames(),
                s.loadStrategy(),
                s.apiConfig(),
                s.aiConfig(),
                s.pythonConfig(),
                s.apiConnectionId(),
                s.id());
      }
      result.add(s);
    }
    return result;
  }

  /**
   * 수정 요청의 각 스텝이 어느 기존 스텝과 "같은 스텝"인지 정한다(WD-108). 결과는 요청 순서대로 짝지은 기존 스텝(저장 전 상태)이며 새 스텝은 null 이다.
   *
   * <p>판정 순서:
   *
   * <ol>
   *   <li>요청에 스텝 id 가 있으면 그것으로 찾는다 — 웹 편집기는 상세 응답의 스텝 id 를 그대로 되돌려 보내므로 이름을 바꿔도 같은 스텝으로 남는다. 이
   *       파이프라인의 현재 스텝이 아닌 id(다른 파이프라인의 스텝, 이미 지워진 스텝)는 거부한다 — 그대로 쓰면 다른 파이프라인의 스텝 행을 고치게 되고, 조용히 새
   *       스텝으로 바꾸면 사용자가 모르는 사이 기록이 끊긴다. 한 요청에 같은 id 가 두 번 와도 거부한다.
   *   <li>id 가 없으면 이름으로 찾는다 — AI 에이전트 도구 등 id 를 모르는 클라이언트와 저장 직후 아직 재조회 전인 새 스텝을 위해서다. 이름은 파이프라인
   *       안에서 고유하다({@code UNIQUE (pipeline_id, name)}, V3:24 — 웹도 의존을 이름으로 잇는다). 이미 id 로 짝지어진 스텝은
   *       다시 짝짓지 않는다.
   * </ol>
   */
  private List<PipelineStepResponse> matchExistingSteps(
      List<PipelineStepResponse> existingSteps, List<PipelineStepRequest> stepRequests) {
    Map<Long, PipelineStepResponse> existingById = new HashMap<>();
    Map<String, PipelineStepResponse> existingByName = new HashMap<>();
    for (PipelineStepResponse existing : existingSteps) {
      existingById.put(existing.id(), existing);
      existingByName.put(existing.name(), existing);
    }

    List<PipelineStepResponse> matched =
        new java.util.ArrayList<>(java.util.Collections.nCopies(stepRequests.size(), null));
    Set<Long> claimed = new java.util.HashSet<>();
    // 1차: 명시 id
    for (int i = 0; i < stepRequests.size(); i++) {
      Long requestedId = stepRequests.get(i).id();
      if (requestedId == null) {
        continue;
      }
      if (!existingById.containsKey(requestedId)) {
        throw new IllegalArgumentException(
            "이 파이프라인에 없는 스텝입니다(다른 곳에서 먼저 수정됐을 수 있습니다. 새로고침 후 다시 저장하세요): "
                + stepRequests.get(i).name());
      }
      if (!claimed.add(requestedId)) {
        throw new IllegalArgumentException("같은 스텝이 요청에 두 번 들어 있습니다: " + stepRequests.get(i).name());
      }
      matched.set(i, existingById.get(requestedId));
    }
    // 2차: id 없는 스텝은 아직 짝지어지지 않은 같은 이름의 기존 스텝
    for (int i = 0; i < stepRequests.size(); i++) {
      if (matched.get(i) != null || stepRequests.get(i).id() != null) {
        continue;
      }
      PipelineStepResponse byName = existingByName.get(stepRequests.get(i).name());
      if (byName != null && claimed.add(byName.id())) {
        matched.set(i, byName);
      }
    }
    return matched;
  }

  /**
   * AI_CLASSIFY 스텝 설정을 저장 전에 검증한다 — 프롬프트·출력 컬럼(이름·타입)·배치 크기·오류 처리.
   *
   * <p>출력 컬럼 이름(WD-154): 빈 이름·형식 위반·중복은 실행 시점에야 영어 원문 오류(Invalid name: . Must match …)로 실패했다 — 저장에서
   * 막고 어느 스텝인지 한국어로 알린다. 출력 데이터셋을 자동 생성(TEMP)하는 스텝은 시스템 예약어도 막는다(TEMP 생성이 예약어를 거부한다).
   */
  private void validateAiClassifyStep(PipelineStepRequest step, Set<String> requestStepNames) {
    String stepName = step.name();

    if (step.aiConfig() == null || step.aiConfig().isEmpty()) {
      throw new IllegalArgumentException(stepError(stepName, "AI 분류 설정(aiConfig)이 필요합니다"));
    }

    AiClassifyConfig config = objectMapper.convertValue(step.aiConfig(), AiClassifyConfig.class);

    if (config.prompt() == null || config.prompt().isBlank()) {
      throw new IllegalArgumentException(stepError(stepName, "프롬프트를 입력하세요"));
    }
    if (config.outputColumns() == null || config.outputColumns().isEmpty()) {
      throw new IllegalArgumentException(stepError(stepName, "출력 컬럼을 최소 1개 정의하세요"));
    }
    validateOutputColumnNames(
        stepName,
        config.outputColumns().stream().map(AiClassifyConfig.OutputColumn::name).toList(),
        step.outputDatasetId() == null);
    for (AiClassifyConfig.OutputColumn col : config.outputColumns()) {
      if (col.type() == null || !VALID_OUTPUT_COLUMN_TYPES.contains(col.type())) {
        throw new IllegalArgumentException(
            stepError(stepName, "출력 컬럼 타입은 다음 중 하나여야 합니다: " + VALID_OUTPUT_COLUMN_TYPES));
      }
    }
    if (config.batchSize() != null && (config.batchSize() < 1 || config.batchSize() > 100)) {
      throw new IllegalArgumentException(stepError(stepName, "배치 크기는 1~100 사이여야 합니다"));
    }
    if (config.onError() != null && !VALID_ON_ERROR.contains(config.onError())) {
      throw new IllegalArgumentException(
          stepError(stepName, "오류 처리 방식은 다음 중 하나여야 합니다: " + VALID_ON_ERROR));
    }

    // WD-216: 분류할 입력이 정해져야 한다(설정 자체의 오류를 먼저 알리도록 마지막에 본다) — 명시 입력 데이터셋이 있거나, 같은 파이프라인의 앞 스텝에 연결돼
    // 있어야 한다(러너가 명시 입력이
    // 비었을 때 의존 스텝의 출력 데이터셋을 입력으로 해석한다, PipelineAsyncRunner 의 AI_CLASSIFY 분기). 예전엔 둘 다 없어도 저장되고
    // 실행하면 영어 「No input rows found」와 함께 0행 완료로 끝났다. 의존 스텝이 실제로 출력을 낼지(SQL 조회·AI 분류는 출력 미지정 시
    // 임시 데이터셋을 만든다)는 저장 시점에 단정할 수 없어 보지 않는다 — 실행 시 해석된 입력이 없으면 실행기가 한국어로 실패시킨다.
    // 데이터를 읽지 않는 검사라 보안 판정보다 앞에 두어도 존재 오라클이 되지 않는다.
    boolean hasExplicitInput =
        step.inputDatasetIds() != null
            && step.inputDatasetIds().stream().anyMatch(java.util.Objects::nonNull);
    boolean hasDependency =
        step.dependsOnStepNames() != null
            && step.dependsOnStepNames().stream()
                .anyMatch(n -> n != null && !n.equals(stepName) && requestStepNames.contains(n));
    if (!hasExplicitInput && !hasDependency) {
      throw new IllegalArgumentException(stepError(stepName, AI_CLASSIFY_NO_INPUT_MESSAGE));
    }
  }

  /**
   * AI_CLASSIFY 입력 컬럼({@code aiConfig.inputColumns})이 명시 입력 데이터셋마다 실제로 있는지 확인한다(WD-215).
   *
   * <p>예전에는 없는 칸 이름도 저장에 성공하고, 실행 시 조회 SQL 이 깨지면서 내부 SQL 원문(스키마·테이블 이름)이 스텝 오류에 그대로 보였다. 실행기는 같은 입력
   * 컬럼 목록으로 입력 데이터셋을 하나씩 조회해 이어 붙이므로, 어느 하나에라도 없으면 그 데이터셋 조회가 깨진다 — 그래서 모든 명시 입력에서 본다. 시스템 컬럼(id
   * 등)은 물리 테이블에 늘 있으므로 있는 것으로 본다.
   *
   * <p>명시 입력이 없으면(의존 스텝 출력을 실행 시점에 자동 해석) 저장 시점에는 입력이 정해지지 않았으므로 보지 않는다 — 실행기가 조회 전에 다시 본다. 호출부는 입력
   * 보안 판정을 이미 통과한 뒤에만 부른다.
   */
  private void validateAiClassifyInputColumnsExist(PipelineStepRequest step) {
    if (step.inputDatasetIds() == null || step.inputDatasetIds().isEmpty()) {
      return;
    }
    AiClassifyConfig config = objectMapper.convertValue(step.aiConfig(), AiClassifyConfig.class);
    if (config.inputColumns() == null || config.inputColumns().isEmpty()) {
      return;
    }
    for (Long datasetId : step.inputDatasetIds()) {
      if (datasetId == null) {
        continue;
      }
      Set<String> existing = new java.util.HashSet<>(DataTableService.SYSTEM_COLUMNS);
      for (DatasetColumnResponse col : columnRepository.findByDatasetId(datasetId)) {
        existing.add(col.columnName());
      }
      List<String> missing =
          config.inputColumns().stream().filter(c -> !existing.contains(c)).distinct().toList();
      if (!missing.isEmpty()) {
        throw new IllegalArgumentException(
            stepError(step.name(), missingInputColumnsMessage(missing)));
      }
    }
  }

  /** 입력 데이터셋도 의존 스텝도 없는 AI 분류 스텝의 저장 거부 문구(WD-216). */
  static final String AI_CLASSIFY_NO_INPUT_MESSAGE =
      "입력 데이터셋을 지정하거나 앞 스텝에 연결하세요(AI 분류는 입력 데이터셋이나 앞 스텝의 출력을 분류합니다)";

  /** 없는 입력 컬럼 안내 문구 — 저장 거부와 테스트가 같은 문구를 쓴다(WD-215). */
  static String missingInputColumnsMessage(List<String> missing) {
    return "입력 컬럼 "
        + missing.stream()
            .map(c -> "'" + c + "'")
            .collect(java.util.stream.Collectors.joining(", "))
        + "이(가) 입력 데이터셋에 없습니다. 입력 컬럼 필터에서 다시 고르세요";
  }

  /**
   * PYTHON 출력 컬럼 정의 검증. 타입은 늘, 이름은 출력을 자동 생성할 때만 본다(WD-352) — 자동 생성이면 이 이름으로 TEMP 를 만드는데,
   * 예전에는 이름을 보지 않아 형식이 틀리거나 겹친 이름이 저장되고 실행 시점에야 영어 원문({@code Invalid name: my col. Must match …})
   * 또는 「이미 있는 필드명입니다」로 실패했다. 편집기도 자동 생성일 때만 컬럼 정의를 보여 주므로, 출력을 지정한 스텝의 남은 정의는 막지 않는다.
   * 규칙·문구는 AI_CLASSIFY 출력 컬럼과 같다.
   */
  private void validatePythonStep(PipelineStepRequest step) {
    PythonStepConfig config =
        objectMapper.convertValue(step.pythonConfig(), PythonStepConfig.class);
    if (config.outputColumns() != null) {
      // AI_CLASSIFY 와 달리 출력 지정 시에는 정의를 쓰지 않으므로 이름 검사를 통째로 건너뛴다(자동 생성일 때만 — 예약어도 막는다)
      if (step.outputDatasetId() == null) {
        validateOutputColumnNames(
            step.name(),
            config.outputColumns().stream().map(PythonStepConfig.OutputColumn::name).toList(),
            true);
      }
      for (PythonStepConfig.OutputColumn col : config.outputColumns()) {
        if (col.type() == null || !VALID_OUTPUT_COLUMN_TYPES.contains(col.type().toUpperCase())) {
          throw new IllegalArgumentException(
              stepError(step.name(), "출력 컬럼 타입은 다음 중 하나여야 합니다: " + VALID_OUTPUT_COLUMN_TYPES));
        }
      }
    }
  }

  /**
   * 출력 컬럼 이름 검증 — 빈 이름·예약어(자동 생성 시)·형식·길이·중복. AI_CLASSIFY·PYTHON 이 같은 규칙·문구를 쓴다(WD-352 — 화면의
   * validateOutputColumns 와 같다).
   */
  private static void validateOutputColumnNames(
      String stepName, List<String> names, boolean autoOutput) {
    Set<String> seenNames = new java.util.HashSet<>();
    for (String raw : names) {
      String name = raw == null ? "" : raw;
      if (name.isBlank()) {
        throw new IllegalArgumentException(
            stepError(stepName, OUTPUT_COLUMN_NAME_REQUIRED_MESSAGE));
      }
      rejectInvalidColumnName(stepName, name, autoOutput);
      if (!seenNames.add(name)) {
        throw new IllegalArgumentException(stepError(stepName, "출력 컬럼 이름이 중복됩니다: " + name));
      }
    }
  }

  /**
   * 스텝의 apiConnectionId 가 현재 워크스페이스에서 보이는 연결인지 확인한다(WD-176). 없거나 다른 워크스페이스의 연결이면 같은 400 문구로 거절한다 —
   * 응답 차이로 번호 존재 여부가 새지 않게 한다.
   */
  private void requireVisibleApiConnection(PipelineStepRequest stepRequest) {
    Long connectionId = stepRequest.apiConnectionId();
    if (connectionId != null && !apiConnectionReader.isVisible(connectionId)) {
      throw new IllegalArgumentException(
          stepError(stepRequest.name(), API_CONNECTION_NOT_FOUND_MESSAGE));
    }
  }

  /** 스텝 검증 오류 문구 — 어느 스텝의 문제인지 앞에 붙인다(예: 「스텝 'API 호출': 응답 필드를 입력하세요」). */
  /**
   * 다른 스텝(같은 파이프라인 포함)이 소유한 러너 TEMP 를 출력으로 지정했을 때의 안내(WD-224). 데이터셋 이름은 넣지 않는다 — 이미 이 파이프라인에 있던
   * 출력(왕복 보존)은 편집자가 볼 수 없는 데이터셋일 수 있다. 스텝 이름은 {@link #stepError} 가 앞에 붙인다.
   */
  static final String FOREIGN_TEMP_OUTPUT_MESSAGE =
      "출력 데이터셋이 다른 스텝(이 파이프라인 또는 다른 파이프라인)이 자동으로 만든 임시 데이터셋입니다. 임시 데이터셋은 그 스텝이 실행할 때마다 다시"
          + " 채우므로 출력으로 지정할 수 없습니다. 이 스텝의 출력을 새 데이터셋으로 바꾸거나 「자동 생성」으로 두세요.";

  private static String stepError(String stepName, String message) {
    return "스텝 '" + stepName + "': " + message;
  }

  /** API_CALL 응답 필드 매핑 한 행(원문 그대로 — 공백 판정만 trim 으로 한다). */
  private record FieldMappingRow(String sourceField, String targetColumn) {}

  /**
   * apiConfig.fieldMappings 를 행 목록으로 읽는다. 저장 요청의 apiConfig 는 자유 형식 맵이라 레코드 변환 대신 원시 값을 읽는다 — 다른 키의
   * 형식이 어긋나도 매핑 검증이 엉뚱한 변환 오류로 바뀌지 않게. 행이 객체가 아니면 빈 행으로 본다.
   */
  private static List<FieldMappingRow> readFieldMappings(Map<String, Object> apiConfig) {
    Object raw = apiConfig.get("fieldMappings");
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    List<FieldMappingRow> rows = new java.util.ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> m) {
        Object source = m.get("sourceField");
        Object target = m.get("targetColumn");
        rows.add(
            new FieldMappingRow(
                source == null ? "" : source.toString(), target == null ? "" : target.toString()));
      } else {
        rows.add(new FieldMappingRow("", ""));
      }
    }
    return rows;
  }

  /**
   * API_CALL 응답 필드 매핑의 행 단위 검증(WD-154) — 빈 행, 한쪽만 채운 행, 대상 컬럼 이름 형식·예약어, 같은 대상 컬럼 중복.
   *
   * <p>왜 저장에서 막나: 빈 대상 컬럼은 실행 시점 적재가 {@code Invalid name: . Must match [a-z][a-z0-9_]*} 영어 원문으로, 같은
   * 대상 컬럼 둘은 INSERT 컬럼 중복(또는 TEMP 생성 실패)으로 실패했다. 빈 행을 조용히 버리지 않는 이유: 사용자가 채우려던 행일 수 있다 — 채우거나 지우게
   * 한다.
   */
  private static void validateApiCallFieldMappings(PipelineStepRequest step) {
    String stepName = step.name();
    boolean autoOutput = step.outputDatasetId() == null;
    Set<String> seenTargets = new java.util.HashSet<>();
    for (FieldMappingRow row : readFieldMappings(step.apiConfig())) {
      boolean sourceBlank = row.sourceField().isBlank();
      boolean targetBlank = row.targetColumn().isBlank();
      if (sourceBlank && targetBlank) {
        throw new IllegalArgumentException(stepError(stepName, EMPTY_MAPPING_ROW_MESSAGE));
      }
      if (targetBlank) {
        throw new IllegalArgumentException(stepError(stepName, TARGET_COLUMN_REQUIRED_MESSAGE));
      }
      if (sourceBlank) {
        throw new IllegalArgumentException(stepError(stepName, SOURCE_FIELD_REQUIRED_MESSAGE));
      }
      rejectInvalidColumnName(stepName, row.targetColumn(), autoOutput);
      if (!seenTargets.add(row.targetColumn())) {
        throw new IllegalArgumentException(
            stepError(stepName, "대상 컬럼이 중복됩니다: " + row.targetColumn()));
      }
    }
  }

  /** API_CALL OFFSET 페이지네이션 페이지 크기 허용 범위(WD-362) — 화면 PAGE_SIZE_RANGE 와 같다. */
  static final int MIN_PAGE_SIZE = 1;

  static final int MAX_PAGE_SIZE = 10_000;

  static final String PAGE_SIZE_RANGE_MESSAGE =
      "페이지 크기는 " + MIN_PAGE_SIZE + "~" + MAX_PAGE_SIZE + " 사이의 정수여야 합니다";

  /**
   * API_CALL OFFSET 페이지네이션의 페이지 크기 검증(WD-362) — 비어 있으면 실행기 기본값(100)을 쓰므로 허용하고, 값이 있으면 1~10000 의
   * 정수만 받는다.
   *
   * <p>왜 저장에서 막나: 0 이하 페이지 크기는 실행기의 「다음 페이지 없음」 판정(받은 행 수 &lt; 페이지 크기)이 결코 참이 되지 않아, 총 건수
   * 경로가 없으면 offset 을 0, -5, -10… 으로 줄이며 최대 1만 페이지·최대 실행 시간까지 외부 API 를 반복 호출했다.
   */
  private static void validateApiCallPagination(PipelineStepRequest step) {
    if (!(step.apiConfig().get("pagination") instanceof Map<?, ?> pagination)) {
      return;
    }
    if (!(pagination.get("type") instanceof String type) || !"OFFSET".equalsIgnoreCase(type)) {
      return;
    }
    // 정수 범위 검사는 트리거 설정과 같은 해석을 쓴다 — 비어 있으면 통과(실행기 기본값), 소수·숫자 아님·범위 밖은 400.
    DatasetChangeTriggerConfig.requireSecondsInRange(
        pagination.get("pageSize"),
        MIN_PAGE_SIZE,
        MIN_PAGE_SIZE,
        MAX_PAGE_SIZE,
        stepError(step.name(), PAGE_SIZE_RANGE_MESSAGE));
  }

  /**
   * 출력 컬럼 이름(API_CALL 대상 컬럼·AI_CLASSIFY 출력 컬럼)의 예약어·형식 검사.
   *
   * <p>예약어(id·import_id·created_at·_updated_at)는 출력 데이터셋을 자동 생성할 때만 막는다 — TEMP 생성이 예약어를 거부하기
   * 때문이다(문구는 데이터셋 생성과 같은 {@link DataTableService#reservedColumnNameMessage}). 지정 출력 데이터셋에는 이 컬럼들이
   * 물리적으로 있어 적재가 가능하므로 형식 검사만 한다. 형식 규칙은 {@link DataTableNames#isValid} — 공용 영어 문구({@link
   * DataTableNames#validate})는 다른 호출처와 공유하므로 바꾸지 않고 여기서 한국어 안내를 낸다(WD-111 과 같은 문장).
   */
  private static void rejectInvalidColumnName(String stepName, String name, boolean autoOutput) {
    if (autoOutput && DataTableService.SYSTEM_COLUMNS.contains(name.toLowerCase())) {
      throw new IllegalArgumentException(
          stepError(stepName, DataTableService.reservedColumnNameMessage(name)));
    }
    if (!DataTableNames.isValid(name)) {
      throw new IllegalArgumentException(stepError(stepName, INVALID_COLUMN_NAME_MESSAGE + name));
    }
    // 63자를 넘으면 TEMP 생성·적재가 실행 시점에 실패한다 — 저장 시점에 같은 상한으로 막는다(WD-352, 화면 문구와 같다)
    if (name.length() > DataTableService.MAX_COLUMN_NAME_LENGTH) {
      throw new IllegalArgumentException(
          stepError(
              stepName, "칼럼명은 " + DataTableService.MAX_COLUMN_NAME_LENGTH + "자 이하여야 합니다"));
    }
  }

  /**
   * API_CALL 매핑의 대상 컬럼이 지정 출력 데이터셋에 있는지 검증한다(WD-154). 없는 컬럼은 실행 시점에 {@code bad SQL grammar [INSERT
   * INTO …]} 원문으로 실패했다.
   *
   * <p>검사하지 않는 경우:
   *
   * <ul>
   *   <li>출력 미지정(자동 생성) — 매핑으로 TEMP 를 만들므로 매핑이 곧 스키마다.
   *   <li>편집자가 볼 수 없는 출력(이 파이프라인에 이미 있던 TEMP 의 왕복 보존은 출력 판정을 건너뛴다) — 컬럼 목록을 알려 주면 숨김 데이터셋의 스키마를 떠볼
   *       수 있다. 실행 시점 오류로 남는다.
   * </ul>
   *
   * <p>지정 출력이 러너 TEMP 여도 검사한다 — 두 번째 실행부터 러너는 TEMP 를 매핑에 맞춰 다시 만들지 않고 그대로 적재하므로(해석된 출력 id 가 그대로
   * 들어온다), 없는 컬럼은 실행 시점에 똑같이 실패한다. 시스템 컬럼(id 등)은 물리 테이블에 있으므로 있는 것으로 본다.
   */
  private void validateApiCallTargetsExist(PipelineStepRequest step, Long editorUserId) {
    Long outputId = step.outputDatasetId();
    if (outputId == null || editorUserId == null) {
      return;
    }
    List<FieldMappingRow> rows = readFieldMappings(step.apiConfig());
    if (rows.isEmpty()) {
      return;
    }
    if (!datasetAccessGuard
        .checkDatasetReads(clearanceResolver.resolve(editorUserId), List.of(outputId))
        .allowed()) {
      return;
    }
    Set<String> existing = new java.util.HashSet<>(DataTableService.SYSTEM_COLUMNS);
    for (DatasetColumnResponse col : columnRepository.findByDatasetId(outputId)) {
      existing.add(col.columnName());
    }
    for (FieldMappingRow row : rows) {
      if (!existing.contains(row.targetColumn())) {
        throw new IllegalArgumentException(
            stepError(step.name(), MISSING_OUTPUT_COLUMN_MESSAGE + row.targetColumn()));
      }
    }
  }

  /**
   * 사용자 요청의 파이프라인 삭제(WD-310) — 함께 지워질 자동 생성 임시 데이터셋을 호출자가 모두 볼 수 있을 때만 지운다. 하나라도 못 보면 아무것도 바꾸지 않고
   * 403 {@link PipelineSecurityGate#HIDDEN_TEMP_DATASET_CODE}.
   */
  @Transactional
  public void deletePipeline(Long id, Long userId) {
    AutoTempDeletion temps = planPipelineDeletion(id);
    // 볼 수 없는 임시 데이터셋이 있으면 무엇이든 바꾸기 전에 거부한다(WD-310). 판정한 목록을 그대로 지운다.
    pipelineSecurityGate.requireAutoTempsDeletable(userId, temps.deletable());
    deletePipeline(id, temps);
  }

  /**
   * 호출자 판정 없이 파이프라인과 그 자동 생성 임시 데이터셋을 지운다 — 테스트 정리처럼 사용자 요청이 아닌 경로 전용이다. 사용자 요청은 반드시 {@link
   * #deletePipeline(Long, Long)} 로 들어온다(WD-310).
   */
  @Transactional
  public void deletePipelineUnchecked(Long id) {
    deletePipeline(id, planPipelineDeletion(id));
  }

  /**
   * 파이프라인이 있는지 확인하고 함께 지울 자동 생성 임시 데이터셋을 고른다. 스텝을 지우기 전에 모아 둔다(WD-170) — 스텝을 지우면
   * source_pipeline_step_id 가 SET NULL(V36)이 되어 찾을 수 없다. 다른 파이프라인 스텝이 출력으로 쓰는 것은 남긴다(이 파이프라인 스텝은 곧
   * 지워지므로 참조로 세지 않는다).
   */
  private AutoTempDeletion planPipelineDeletion(Long id) {
    pipelineRepository.findById(id).orElseThrow(PipelineNotFoundException::new);
    List<Long> stepIds = stepRepository.findStepIdsByPipelineId(id);
    return planAutoTempDeletion(stepRepository.findAutoTempDatasetIdsByStepIds(stepIds), stepIds);
  }

  private void deletePipeline(Long id, AutoTempDeletion temps) {
    // Disable chain triggers that reference this pipeline as upstream
    // 꺼진 이유(선행 삭제)는 따로 저장하지 않는다 — 트리거 조회가 선행 존재를 계산해 upstreamMissing 으로 내려 주고, 다시 켜기·저장은
    // TriggerService 가 거부한다(WD-218).
    int disabled = triggerRepository.disableByUpstreamPipelineId(id);
    if (disabled > 0) {
      log.info("Disabled {} chain triggers referencing pipeline {}", disabled, id);
    }

    // Delete steps (cascade deletes inputs and dependencies)
    stepRepository.deleteByPipelineId(id);

    // 임시 데이터셋은 스텝 다음에 지운다(WD-170) — 스텝이 자기 임시 데이터셋을 출력(output_dataset_id, FK RESTRICT)으로 들고 있을
    // 수 있어 먼저 지우면 걸린다. 같은 트랜잭션이라 실패하면 전부 되돌아간다.
    deleteAutoTempDatasets(temps);

    // Delete pipeline
    pipelineRepository.deleteById(id);
  }

  public PipelineExecutionResponse executePipeline(Long pipelineId, Long userId) {
    return executePipeline(pipelineId, userId, "MANUAL", null);
  }

  public PipelineExecutionResponse executePipeline(
      Long pipelineId, Long userId, String triggeredBy, Long triggerId) {
    // 파이프라인 존재 여부 확인
    PipelineResponse pipeline =
        pipelineRepository.findById(pipelineId).orElseThrow(PipelineNotFoundException::new);

    // 비활성 파이프라인은 수동 실행 불가 — 활성화 후 재시도해야 함 (#187)
    // 이 문구는 화면 토스트에 그대로 보이므로 사용자 안내 문장으로 쓴다(WD-110).
    if (!pipeline.isActive()) {
      throw new PipelineInactiveException("비활성 파이프라인은 실행할 수 없습니다. 파이프라인 편집에서 활성화한 뒤 다시 실행하세요.");
    }

    // Get user display name
    String username =
        userRepository.findById(userId).map(user -> user.name()).orElse(String.valueOf(userId));

    // Start execution with trigger info
    Long executionId = executionService.executePipeline(pipelineId, userId, triggeredBy, triggerId);

    // Return execution response
    return new PipelineExecutionResponse(
        executionId,
        pipelineId,
        "PENDING",
        username,
        null,
        null,
        java.time.LocalDateTime.now(),
        triggeredBy,
        null);
  }

  /**
   * 파이프라인 실행 취소(WD-153). 파이프라인이 보이지 않으면(없음·다른 테넌트 — RLS) 404, 실행이 이 파이프라인 것이 아니면 404(#188 과 같은 이유로
   * 존재 여부를 숨긴다). 실제 취소 기록은 {@link PipelineExecutionService#cancelExecution} 이 한다.
   */
  public CancelExecutionResponse cancelExecution(Long pipelineId, Long executionId, Long userId) {
    pipelineRepository.findById(pipelineId).orElseThrow(PipelineNotFoundException::new);
    return executionService.cancelExecution(pipelineId, executionId, userId);
  }

  @Transactional(readOnly = true)
  public List<PipelineExecutionResponse> getExecutionsByPipelineId(Long pipelineId) {
    // Verify pipeline exists
    pipelineRepository.findById(pipelineId).orElseThrow(PipelineNotFoundException::new);

    return executionRepository.findExecutionsByPipelineId(pipelineId);
  }

  @Transactional(readOnly = true)
  public ExecutionDetailResponse getExecutionById(Long pipelineId, Long executionId) {
    // 파이프라인 존재 확인
    pipelineRepository.findById(pipelineId).orElseThrow(PipelineNotFoundException::new);

    // 실행 조회 후, 해당 실행이 요청한 파이프라인에 속하는지 소유권 검증 (#188).
    // pipelineId가 일치하지 않으면 다른 파이프라인의 실행 상세가 노출되므로
    // 존재 여부 자체를 숨기기 위해 404(PipelineNotFoundException) 반환.
    ExecutionDetailResponse execution =
        executionRepository
            .findExecutionById(executionId)
            .orElseThrow(PipelineNotFoundException::execution);

    if (!pipelineId.equals(execution.pipelineId())) {
      // 크로스-파이프라인 접근 차단. 정보 노출 방지를 위해 없는 실행과 같은 문구로 답한다(WD-226).
      throw PipelineNotFoundException.execution();
    }

    return withholdRawErrorsFromViewer(pipelineId, execution);
  }

  /**
   * 실행 기록의 원문 오류·로그를 조회자 기준으로 가린다(WD-27, 보안 등급 S2).
   *
   * <p>실행 기록은 {@code pipeline:read} 만 있으면 누구나 읽지만, 스텝 오류에는 PG 원문이 그대로 저장된다 — 실행 주체(숨김 데이터셋을 볼 수 있는
   * 사용자)가 돌린 스텝이 실행 중 실패하면 그 문구에 숨김 데이터셋의 테이블명은 물론 <b>행 값</b>까지 실린다(실측: {@code invalid input syntax
   * for type integer: "<숨김 행 값>"}). 이름만 지우는 마스킹으로는 값이 남으므로, 조회자가 그 스텝이 다루는 데이터셋을 전부 볼 수 있을 때만 원문을
   * 보여 주고 아니면 원문 전체를 고정 문구로 바꾼다. 저장 시점이 아니라 조회 시점에 가리는 이유: 저장본을 지우면 볼 자격이 있는 사용자(실행 주체 등)의 디버깅 정보까지
   * 사라진다.
   *
   * <p>판정이 모호하면 가린다(과잉 은닉은 허용, 과소 은닉은 불허). 상세 화면은 실행 중 3초마다 폴링하므로, 가릴 원문이 하나도 없으면 판정 자체를 건너뛰고 스텝
   * 판정은 요청당 스텝마다 한 번만 한다(메모).
   */
  private ExecutionDetailResponse withholdRawErrorsFromViewer(
      Long pipelineId, ExecutionDetailResponse execution) {
    boolean anyStepText =
        execution.stepExecutions().stream()
            .anyMatch(se -> se.errorMessage() != null || se.log() != null);
    if (!anyStepText && execution.errorMessage() == null) {
      return execution;
    }
    Clearance viewer = clearanceResolver.current();
    // 판정 근거가 없는 스텝의 원문은 실행 주체 본인과 테넌트 관리자(관리자 우회 개념 — DatasetAccessPolicy 와 같은 tenantAdmin)에게만 보인다.
    Long runAsUserId = executionRepository.findExecutedById(execution.id()).orElse(null);
    boolean undeterminedAllowed =
        viewer.tenantAdmin() || (runAsUserId != null && runAsUserId == viewer.userId());
    // 스텝 정의는 현재 상태로, step_order 순으로 읽는다 — {{#N}} 은 이 순서의 N 번째 스텝이다(러너 resolveStepReferences 와 같은
    // 해석).
    // 출력 id 는 러너 TEMP 폴백(coalesce)이 반영된 값이다. 이름 가시성 조건은 여기서 쓰지 않는다.
    List<PipelineStepResponse> orderedSteps =
        stepRepository.findByPipelineId(pipelineId, datasetAccessGuard.visibleCondition(viewer));
    Map<Long, PipelineStepResponse> stepsById = new HashMap<>();
    for (PipelineStepResponse s : orderedSteps) {
      stepsById.put(s.id(), s);
    }
    // stepId → 원문 공개 여부(요청당 한 번만 판정).
    Map<Long, Boolean> verdicts = new HashMap<>();
    java.util.function.Function<Long, Boolean> canSee =
        stepId ->
            verdicts.computeIfAbsent(
                stepId,
                id ->
                    canSeeRawStepText(
                        viewer, stepsById.get(id), orderedSteps, undeterminedAllowed));

    List<StepExecutionResponse> steps = new java.util.ArrayList<>();
    for (StepExecutionResponse se : execution.stepExecutions()) {
      boolean hasText = se.errorMessage() != null || se.log() != null;
      steps.add(
          hasText && !canSee.apply(se.stepId())
              ? new StepExecutionResponse(
                  se.id(),
                  se.stepId(),
                  se.stepName(),
                  se.status(),
                  se.outputRows(),
                  null,
                  se.errorMessage() != null ? WITHHELD_STEP_ERROR_MESSAGE : null,
                  se.startedAt(),
                  se.completedAt(),
                  se.errorMessage() != null)
              : se);
    }
    // 실행 단위 오류(스텝 밖 최상위 예외)는 어느 스텝에서 왔는지 알 수 없으므로, 이 실행의 스텝과 현재 파이프라인 스텝 중 하나라도 조회자가 못 보면 가린다.
    String executionError = execution.errorMessage();
    boolean executionErrorMasked = false;
    if (executionError != null) {
      boolean anyHidden =
          execution.stepExecutions().stream().anyMatch(se -> !canSee.apply(se.stepId()))
              || orderedSteps.stream().anyMatch(s -> !canSee.apply(s.id()));
      if (anyHidden) {
        executionError = WITHHELD_EXECUTION_ERROR_MESSAGE;
        executionErrorMasked = true;
      }
    }
    return new ExecutionDetailResponse(
        execution.id(),
        execution.pipelineId(),
        execution.pipelineName(),
        execution.status(),
        execution.executedBy(),
        steps,
        execution.startedAt(),
        execution.completedAt(),
        execution.createdAt(),
        executionError,
        executionErrorMasked,
        // 취소 요청 정보는 원문 오류가 아니라 가리지 않는다(WD-153) — 가림 대상 조회자에게도 "누가 언제 취소했는지"는 보여야 한다.
        execution.cancelRequestedAt(),
        execution.cancelRequestedBy());
  }

  /**
   * 조회자가 이 스텝의 원문 오류·로그를 볼 수 있는가 — 스텝이 다루는 데이터셋(출력·명시 입력·SQL 참조 테이블·{@code {{#N}}} 이 가리키는 스텝 출력)을
   * 전부 VIEW 할 수 있어야 한다.
   *
   * <ul>
   *   <li>출력: 러너 TEMP 는 실행 시 입력 최대 등급으로 오르므로(판단 사항 5) 출력 VIEW 가 입력 가시성을 대부분 대신한다.
   *   <li>SQL: 저장 판정과 같은 문자열({@code {{#N}}} 더미 치환)·같은 모드로 조회자 기준 판정 — DML 쓰기 대상과 출력 없는 스텝을 덮는다. 저장
   *       모드는 {@code step_ref_N} 더미를 판정에서 빼므로, {@code {{#N}}} 은 따로 러너와 같은 방식(step_order 의 N 번째 스텝의
   *       해석된 출력)으로 풀어 그 출력을 VIEW 판정한다 — 출력 없는 DML 스텝({@code UPDATE {{#1}} …})이나 첫 실행 probe 실패(이 스텝
   *       TEMP 가 아직 없음)도 참조 스텝의 TEMP 등급으로 가려진다. 풀 수 없는 참조(범위 밖·출력 아직 없음)는 근거 없음으로 다룬다.
   *   <li>판정 근거가 없는 스텝 — 출력도 SQL 도 없는 스텝(출력 미지정 PYTHON 등. 명시 입력은 PYTHON 이 실제로 읽는 범위를 보장하지 않아 근거로 치지
   *       않는다), 파싱할 수 없는 SQL, 풀 수 없는 스텝 참조, 현재 정의에 없는 스텝 — 은 {@code undeterminedAllowed}(실행 주체
   *       본인·테넌트 관리자)일 때만 보인다. 그 외 조회자에게는 가린다(fail-closed). 근거가 있는 스텝은 이 허용을 쓰지 않는다 — 관리자 우회는 VIEW
   *       판정 안에서 이미 반영된다. 명시적인 VIEW 거부는 근거 없음보다 우선한다(실행 주체라도 지금 못 보는 데이터셋이 있으면 가린다).
   * </ul>
   */
  private boolean canSeeRawStepText(
      Clearance viewer,
      PipelineStepResponse step,
      List<PipelineStepResponse> orderedSteps,
      boolean undeterminedAllowed) {
    if (step == null) {
      return undeterminedAllowed;
    }
    boolean judged = false;
    boolean undetermined = false;
    // AI 판정 대상 — 아래 VIEW 판정을 거친 데이터셋 전부(출력·명시 입력·{{#N}} 참조 출력·SQL 읽기/쓰기 대상).
    Set<Long> touched = new LinkedHashSet<>();
    if (step.outputDatasetId() != null) {
      if (!canView(viewer, step.outputDatasetId())) {
        return false;
      }
      touched.add(step.outputDatasetId());
      judged = true;
    }
    if (step.inputDatasetIds() != null && !step.inputDatasetIds().isEmpty()) {
      if (!datasetAccessGuard.checkDatasetReads(viewer, step.inputDatasetIds()).allowed()) {
        return false;
      }
      touched.addAll(step.inputDatasetIds());
    }
    if ("SQL".equals(step.scriptType()) && step.scriptContent() != null) {
      // {{#N}} — step_order 의 N 번째 스텝(1-based)의 해석된 출력(러너 resolveStepReferences 와 같은 해석).
      Matcher ref = STEP_REFERENCE_PATTERN.matcher(step.scriptContent());
      while (ref.find()) {
        int index = Integer.parseInt(ref.group(1)) - 1;
        Long refOutput =
            index >= 0 && index < orderedSteps.size()
                ? orderedSteps.get(index).outputDatasetId()
                : null;
        if (refOutput == null) {
          undetermined = true;
        } else if (!canView(viewer, refOutput)) {
          return false;
        } else {
          touched.add(refOutput);
        }
      }
      try {
        SqlAccessResult sqlAccess =
            datasetAccessGuard.checkSql(
                viewer, substituteForValidation(step.scriptContent()), SqlAccessMode.PIPELINE_SAVE);
        if (!sqlAccess.allowed()) {
          return false;
        }
        touched.addAll(sqlAccess.readDatasetIds());
        touched.addAll(sqlAccess.writeDatasetIds());
        judged = true;
      } catch (UnsafeSqlException e) {
        // 파싱 불가·모호 표기 — 판정할 수 없으므로 근거 없는 스텝과 같이 다룬다.
        undetermined = true;
      } catch (RuntimeException e) {
        // 파싱 외 예외(DB 오류 등)는 판정 실패다 — 근거 없음으로 둔갑시키지 않고 모두에게 가린다(누출 금지).
        log.warn("실행 기록 원문 공개 판정 실패 — 원문을 가린다: stepId={}", step.id(), e);
        return false;
      }
    }
    Optional<AiCall> ai = aiCallContext.current();
    if (ai.isEmpty()) {
      return (judged && !undetermined) || undeterminedAllowed;
    }
    // AI 대행 요청(S3 §4.3): 원문(PG 오류·로그 — 행 값이 실릴 수 있다)이 외부 LLM 으로 간다. undeterminedAllowed 는 "이 사람이 봐도
    // 된다"는 근거일 뿐 "LLM 으로 보내도 된다"는 근거가 아니므로 쓰지 않는다 — 근거가 있고 관련 데이터셋이 전부 AI(+SHARE) 허용일 때만
    // 원문, 아니면 가림 문구(예외 대신 가림 — 실행 기록 자체는 계속 볼 수 있어야 한다).
    return judged
        && !undetermined
        && datasetAccessGuard.checkAiForDatasets(viewer, touched, ai.get());
  }

  /** 조회자 기준 데이터셋 VIEW 여부(없는 데이터셋도 거부). */
  private boolean canView(Clearance viewer, long datasetId) {
    return datasetAccessGuard.check(viewer, datasetId, DatasetAction.VIEW, null).allowed();
  }
}
