package com.smartfirehub.pipeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.exception.PipelineInactiveException;
import com.smartfirehub.pipeline.exception.PipelineNameConflictException;
import com.smartfirehub.pipeline.exception.PipelineNotFoundException;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import com.smartfirehub.pipeline.service.validator.PythonScriptValidator;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import com.smartfirehub.user.repository.UserRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  private final SqlValidator sqlValidator;
  private final PythonScriptValidator pythonScriptValidator;
  private final DatasetColumnRepository columnRepository;

  @Transactional
  public PipelineDetailResponse createPipeline(CreatePipelineRequest request, Long userId) {
    // 이름 중복 검사 — 동일 이름의 파이프라인이 존재하면 409 반환 (#181)
    if (pipelineRepository.existsByName(request.name())) {
      throw new PipelineNameConflictException(
          "Pipeline with name '" + request.name() + "' already exists");
    }

    // Validate DAG (cycle detection)
    executionService.validateDAG(request.steps());

    // Save pipeline
    PipelineResponse pipeline =
        pipelineRepository.save(request.name(), request.description(), userId);

    // Save steps
    saveSteps(pipeline.id(), request.steps());

    // Return full detail
    return getPipelineById(pipeline.id());
  }

  private void saveSteps(Long pipelineId, List<PipelineStepRequest> stepRequests) {
    if (stepRequests == null || stepRequests.isEmpty()) {
      return;
    }

    // Step 1: Save all steps and build name -> stepId map
    Map<String, Long> stepNameToId = new HashMap<>();

    for (int i = 0; i < stepRequests.size(); i++) {
      PipelineStepRequest stepRequest = stepRequests.get(i);

      // Validate API_CALL step requirements
      if ("API_CALL".equals(stepRequest.scriptType())) {
        if (stepRequest.apiConfig() == null || stepRequest.apiConfig().isEmpty()) {
          throw new IllegalArgumentException(
              "API_CALL step '" + stepRequest.name() + "' requires apiConfig");
        }
      }

      // Validate AI_CLASSIFY step requirements
      if ("AI_CLASSIFY".equals(stepRequest.scriptType())) {
        validateAiClassifyStep(stepRequest);
      }

      // PYTHON 스텝 escalation 코드(shell/동적실행 등) 차단 — pythonConfig 유무와 무관하게 항상 검증 (#270)
      if ("PYTHON".equals(stepRequest.scriptType())) {
        pythonScriptValidator.validate(stepRequest.scriptContent());
      }

      // Validate PYTHON step outputColumns
      if ("PYTHON".equals(stepRequest.scriptType()) && stepRequest.pythonConfig() != null) {
        validatePythonStep(stepRequest);
      }

      // SQL 스텝 안전 정책(단일 statement + DML + data 스키마 + 위험 함수 차단) 검증 (#136)
      // {{#N}} 스텝 참조는 유효한 SQL 문법이 아니므로, 실행 시점과 동일하게 먼저 더미 테이블
      // 참조로 치환한 뒤 검증한다 — 그렇지 않으면 UI가 권장하는 표준 사용법이 저장 단계에서
      // 항상 파싱 실패로 거부된다 (#643).
      if ("SQL".equals(stepRequest.scriptType())) {
        sqlValidator.validate(substituteStepReferencesForValidation(stepRequest.scriptContent()));
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
          && "SQL".equals(stepRequest.scriptType())
          && LastRunAtPlaceholder.isUsedIn(stepRequest.scriptContent())) {
        throw new IllegalArgumentException(
            "{{last_run_at}} 은 REPLACE 와 함께 쓸 수 없습니다(매 실행 출력이 변경분만 남습니다). MERGE 를 사용하세요: "
                + stepRequest.name());
      }

      if (ls == LoadStrategy.MERGE) {
        if (!"SQL".equals(stepRequest.scriptType())) {
          throw new IllegalArgumentException("MERGE 로드 전략은 SQL 스텝에서만 사용할 수 있습니다: " + stepRequest.name());
        }
        // Fix round 1, must 3 — 사용자가 직접 쓴 INSERT/UPDATE/DELETE 스텝에 MERGE 를 걸면 실행 시점의
        // 출력 비우기 판단과 SELECT 래핑 두 블록이 모두 스킵돼(둘 다 SELECT 자동 적재 경로 전용) MERGE 가
        // 조용히 아무 의미도 없는 채로 사용자 SQL 이 그대로 실행된다 — PK 없음과 같은 무동작 부류라 저장
        // 시점에 막는다.
        if (!isSelectAsRunnerWouldJudge(stepRequest.scriptContent())) {
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

      // Save step
      Long stepId = stepRepository.saveStep(pipelineId, stepRequest, i);
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
   * <p>실행 시점({@code PipelineAsyncRunner#resolveStepReferences})은 {{#N}}을 {@link DataSchema#qualify}가
   * 만든 실제 테이블 FQN으로 치환한 뒤 {@link SqlValidator#validate}를 호출하므로 정상 통과한다.
   * 반면 저장 시점은 지금까지 원본(치환 전) SQL을 그대로 파싱했기 때문에, {{#N}}이 SQL
   * 문법상 유효하지 않아(예: FROM절) 항상 파싱 실패로 저장이 거부됐다 (#643). 두 시점의 검증 대상을
   * 구조적으로 맞추기 위해, 여기서도 실제 치환과 동일한 형태(현재 테넌트 데이터 스키마 + 식별자)의
   * 더미로 바꿔 넣는다 — 스텝 번호가 유효한지, 참조 대상이 실제로 존재하는지는 실행 시점에만 알 수 있으므로
   * (저장 시점엔 DAG의 나머지 스텝이 아직 없을 수도 있음) 검사하지 않고, 오직 "SQL 구조가 유효한가"만 본다.
   */
  private String substituteStepReferencesForValidation(String sql) {
    // 증분 플레이스홀더도 여기서 유효한 타임스탬프 리터럴로 바꿔 둔다 — {{last_run_at}} 은 SQL 문법이
    // 아니므로 그대로 두면 저장 시점 SQL 가드(AST 파싱)가 항상 실패해 증분 스텝을 아예 저장할 수 없다.
    // 값 자체는 검증에 영향이 없으므로 고정된 epoch 를 쓴다(실행 시점 값은 러너가 따로 주입한다).
    return LastRunAtPlaceholder.substitute(
        substituteStepReferences(sql), java.time.OffsetDateTime.parse("1970-01-01T00:00:00Z"));
  }

  /**
   * 실행 시점(PipelineAsyncRunner)이 내릴 것과 <b>똑같은</b> "이게 SELECT 인가" 판단을 저장/조회 시점에 내린다.
   *
   * <p>두 시점의 판단이 갈리면 저장은 SELECT 로 통과했는데 실행은 DML 로 보고 다르게 도는(또는 상세 조회가 실제와
   * 다른 전체 재생성 모드를 보여주는) 결함이 생긴다. 그래서 치환 + 판별의 조합 자체를 이 메서드 하나로 고정하고,
   * 호출하는 쪽은 언제나 이것만 쓴다.
   */
  private boolean isSelectAsRunnerWouldJudge(String scriptContent) {
    return PipelineAsyncRunner.isSelectStatement(
        substituteStepReferencesForValidation(scriptContent));
  }

  /**
   * {@code {{#N}}} 스텝 참조만 더미 테이블 참조로 치환한다({@code {{last_run_at}}} 은 그대로 둔다).
   *
   * <p>{@link #substituteStepReferencesForValidation}과 분리한 이유(코드리뷰 MEDIUM): {@link
   * SqlValidator#incrementalWarnings}는 <b>{@code {{last_run_at}}} 이 원문에 남아 있어야</b> 동작한다
   * (없으면 증분 스텝이 아니라고 보고 빈 목록을 돌려준다). 그런데 스텝 참조가 남아 있으면 JSqlParser
   * 파싱이 실패하고, 그 예외는 조용히 삼켜져 역시 빈 목록이 된다 — 즉 두 치환을 한 덩어리로 쓰든 아예
   * 안 쓰든 경고가 영원히 안 나온다. 그래서 "스텝 참조만" 바꾸는 이 단계가 따로 필요하다.
   */
  private String substituteStepReferences(String sql) {
    Matcher matcher = STEP_REFERENCE_PATTERN.matcher(sql);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      String dummyTableRef = DataSchema.qualify("step_ref_" + matcher.group(1));
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
        pipelineRepository
            .findById(id)
            .orElseThrow(() -> new PipelineNotFoundException("Pipeline not found: " + id));

    List<PipelineStepResponse> steps =
        stepRepository.findByPipelineId(id).stream().map(this::attachIncrementalMeta).toList();

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
   * SQL 스텝에 증분 처리 관련 어드바이저리 경고와 "전체 재생성 예약이 실제로 무엇을 하는가"({@code
   * fullRebuildMode})를 계산해 붙인다(Task 7). 저장을 막지 않는다 — 상세 조회 시 매번 다시 계산해 사용자에게
   * 보여줄 뿐이다.
   *
   * <p>경고:
   *
   * <ul>
   *   <li>{@code {{last_run_at}}} + GROUP BY/집계/윈도우/DISTINCT — {@link SqlValidator#incrementalWarnings}
   *   <li>{@code {{last_run_at}}} + APPEND — 매 실행 바뀐 행이 추가로 한 번 더 쌓여 중복된다(MERGE 권장)
   * </ul>
   *
   * <p>{@code fullRebuildMode} 는 문자열 경고가 아니라 별도 필드로 노출한다 — 웹 UI(Task 8)가 문구를
   * 파싱하지 않고도 "전체 재생성"(출력 재작성)과 "전체 재읽기"(출력은 그대로, 입력만 전체)를 정확히 갈라
   * 라벨을 붙이도록 하기 위해서다. 판정은 저장 시점 MERGE 검증(#saveSteps)과 같은 {@link
   * #isSelectAsRunnerWouldJudge}로 내린다.
   */
  private PipelineStepResponse attachIncrementalMeta(PipelineStepResponse step) {
    if (!"SQL".equals(step.scriptType()) || step.scriptContent() == null) {
      return step;
    }
    if (!LastRunAtPlaceholder.isUsedIn(step.scriptContent())) {
      return step; // 증분 스텝이 아니면 경고도 재생성 모드도 없다(둘 다 기본값 유지).
    }
    // 스텝 참조({{#N}})를 먼저 치환하고 넘긴다 — 원문 그대로 넘기면 JSqlParser 가 파싱에 실패하고
    // incrementalWarnings 가 그 RuntimeException 을 삼켜 항상 빈 목록을 돌려준다(코드리뷰 MEDIUM).
    // 이전 스텝 출력을 읽는 형태가 증분 스텝의 가장 흔한 모양이라, 사실상 경고가 아예 안 나왔다.
    // {{last_run_at}} 은 남겨야 한다 — incrementalWarnings 가 그 존재로 증분 여부를 판단한다.
    List<String> warnings =
        new java.util.ArrayList<>(
            sqlValidator.incrementalWarnings(substituteStepReferences(step.scriptContent())));
    // equalsIgnoreCase — 실행기(PipelineAsyncRunner)가 로드 전략을 대소문자 무시로 해석하므로
    // 여기만 대소문자를 가리면 소문자 레거시 행("append")이 APPEND 로 실행되면서 경고만 빠진다.
    if ("APPEND".equalsIgnoreCase(step.loadStrategy())) {
      warnings.add(
          "APPEND 와 {{last_run_at}} 을 함께 쓰면 수정된 행이 한 줄 더 추가되어 중복됩니다. MERGE 를 권장합니다.");
    }
    String fullRebuildMode =
        isSelectAsRunnerWouldJudge(step.scriptContent())
            ? PipelineStepResponse.FULL_REBUILD_MODE_REBUILD_OUTPUT
            : PipelineStepResponse.FULL_REBUILD_MODE_READ_ALL;
    return step.withIncrementalMeta(warnings, fullRebuildMode);
  }

  /**
   * 전체 재생성/재읽기 예약(해제). 이 시점에는 데이터를 지우지 않는다 — 다음 실행이 비우기(SELECT 자동
   * 적재 스텝) 또는 전체 읽기(사용자 DML 스텝)를 실제로 한 트랜잭션에서 수행한다.
   *
   * <p><b>주의(운영 문서화 대상) — 사용자가 직접 쓴 INSERT/UPDATE/DELETE 증분 스텝은 "예약"이 출력을
   * 재생성하지 않는다.</b> {@code {{last_run_at}}} 이 {@code -infinity} 로 바뀌어 전체 행을 다시 읽을
   * 뿐이고, 그 다음에 무엇을 하는지는 사용자 SQL 자체(INSERT/UPDATE/DELETE 로직)에 달려 있다. 반면 SELECT
   * 자동 적재 스텝(MERGE/APPEND)은 실행기가 출력을 비우고 전체를 다시 채운다. 두 경우를 뭉뚱그려 "전체
   * 재생성"이라 안내하면 사용자 DML 스텝에서는 거짓 약속이 된다 — 호출부(웹 UI 등)는 스텝의 로드 전략을
   * 보고 문구를 갈라 써야 한다.
   *
   * @throws PipelineNotFoundException 파이프라인에 그 stepId 가 없을 때(다른 파이프라인의 스텝 포함)
   * @throws IllegalArgumentException {@code pending=true} 인데 스텝 SQL 이 {@code {{last_run_at}}} 을
   *     쓰지 않을 때 — 그런 스텝은 실행기가 증분 경로를 타지 않아 예약 플래그를 영원히 해제하지 못한다.
   */
  @Transactional
  public void setFullRebuildPending(Long pipelineId, Long stepId, boolean pending) {
    PipelineStepResponse step =
        stepRepository.findByPipelineId(pipelineId).stream()
            .filter(s -> s.id().equals(stepId))
            .findFirst()
            .orElseThrow(
                () ->
                    new PipelineNotFoundException(
                        "Step not found in pipeline " + pipelineId + ": " + stepId));
    if (pending && !LastRunAtPlaceholder.isUsedIn(step.scriptContent())) {
      throw new IllegalArgumentException(
          "{{last_run_at}} 을 쓰는 SQL 스텝만 전체 재생성을 예약할 수 있습니다.");
    }
    stepRepository.setFullRebuildPending(stepId, pending);
  }

  @Transactional
  public void updatePipeline(Long id, UpdatePipelineRequest request, Long userId) {
    // Verify pipeline exists
    pipelineRepository
        .findById(id)
        .orElseThrow(() -> new PipelineNotFoundException("Pipeline not found: " + id));

    // 이름 변경 시 중복 검사 — 다른 파이프라인에 동일 이름이 있으면 409 반환 (#181)
    if (request.name() != null) {
      String currentName = pipelineRepository.findNameById(id).orElse(null);
      if (!request.name().equals(currentName) && pipelineRepository.existsByName(request.name())) {
        throw new PipelineNameConflictException(
            "Pipeline with name '" + request.name() + "' already exists");
      }
    }

    // Validate DAG if steps provided
    if (request.steps() != null) {
      executionService.validateDAG(request.steps());
    }

    // Update pipeline metadata
    pipelineRepository.update(
        id, request.name(), request.description(), request.isActive(), userId);

    // Delete old steps and save new ones (full replacement)
    if (request.steps() != null) {
      // 스텝은 전체 삭제·재생성되므로 스텝 id 가 매번 바뀐다 — 증분 책갈피를 이름 기준으로 떠 두었다가
      // 복원한다. 복원하지 않으면 파이프라인을 한 번 저장할 때마다 책갈피가 사라져 매번 전체를 다시 읽는다.
      //
      // 출력 데이터셋이 바뀐 스텝은 이월하지 않는다 — 새 출력은 과거 실행분을 받은 적이 없는데 책갈피만
      // 이어받으면 그 구간이 영원히 비는(=조용한 데이터 누락) 결과가 된다. 전체 읽기로 되돌리는 쪽이 안전하다.
      //
      // 출력이 null(임시 데이터셋 자동 생성)인 스텝도 이월하지 않는다 — null == null 은 "같은 출력"이
      // 아니다. 임시 데이터셋은 source_pipeline_step_id = 스텝 id 로 묶여 있는데 재저장으로 id 가 바뀌면
      // 러너가 기존 임시 데이터셋을 찾지 못하고 빈 것을 새로 만든다. 그 새 출력에 책갈피만 이어받으면
      // 이전 실행분이 통째로 빠진 채 변경분만 쌓인다.
      Map<String, StepCursor> cursors = stepRepository.findCursorsByPipelineId(id);
      stepRepository.deleteByPipelineId(id);
      saveSteps(id, request.steps());
      // 새로 저장하는 쪽의 동명 스텝은 따로 거를 필요가 없다 — pipeline_step 에는
      // UNIQUE (pipeline_id, name) 제약이 있어(V3:24) saveSteps 가 이 루프에 닿기 전에 실패하고
      // 트랜잭션 전체가 롤백된다. 이름을 이월 키로 쓸 수 있는 근거도 그 제약이다.
      for (PipelineStepRequest s : request.steps()) {
        StepCursor c = cursors.get(s.name());
        if (c != null
            && c.outputDatasetId() != null
            && java.util.Objects.equals(c.outputDatasetId(), s.outputDatasetId())) {
          stepRepository.restoreCursor(id, s.name(), c.lastRunAt(), c.fullRebuildPending());
        }
      }
    }
  }

  private void validateAiClassifyStep(PipelineStepRequest step) {
    String stepName = step.name();

    if (step.aiConfig() == null || step.aiConfig().isEmpty()) {
      throw new IllegalArgumentException("AI_CLASSIFY step '" + stepName + "' requires aiConfig");
    }

    AiClassifyConfig config = objectMapper.convertValue(step.aiConfig(), AiClassifyConfig.class);

    if (config.prompt() == null || config.prompt().isBlank()) {
      throw new IllegalArgumentException(
          "AI_CLASSIFY step '" + stepName + "' requires aiConfig.prompt");
    }
    if (config.outputColumns() == null || config.outputColumns().isEmpty()) {
      throw new IllegalArgumentException(
          "AI_CLASSIFY step '" + stepName + "' requires at least 1 outputColumn");
    }
    for (AiClassifyConfig.OutputColumn col : config.outputColumns()) {
      if (col.type() == null || !VALID_OUTPUT_COLUMN_TYPES.contains(col.type())) {
        throw new IllegalArgumentException(
            "AI_CLASSIFY step '"
                + stepName
                + "' outputColumn type must be one of: "
                + VALID_OUTPUT_COLUMN_TYPES);
      }
    }
    if (config.batchSize() != null && (config.batchSize() < 1 || config.batchSize() > 100)) {
      throw new IllegalArgumentException(
          "AI_CLASSIFY step '" + stepName + "' batchSize must be between 1 and 100");
    }
    if (config.onError() != null && !VALID_ON_ERROR.contains(config.onError())) {
      throw new IllegalArgumentException(
          "AI_CLASSIFY step '" + stepName + "' onError must be one of: " + VALID_ON_ERROR);
    }
  }

  private void validatePythonStep(PipelineStepRequest step) {
    PythonStepConfig config =
        objectMapper.convertValue(step.pythonConfig(), PythonStepConfig.class);
    if (config.outputColumns() != null) {
      for (PythonStepConfig.OutputColumn col : config.outputColumns()) {
        if (col.type() == null || !VALID_OUTPUT_COLUMN_TYPES.contains(col.type().toUpperCase())) {
          throw new IllegalArgumentException(
              "Python step '"
                  + step.name()
                  + "' outputColumn type must be one of: "
                  + VALID_OUTPUT_COLUMN_TYPES);
        }
      }
    }
  }

  @Transactional
  public void deletePipeline(Long id) {
    // Verify pipeline exists
    pipelineRepository
        .findById(id)
        .orElseThrow(() -> new PipelineNotFoundException("Pipeline not found: " + id));

    // Disable chain triggers that reference this pipeline as upstream
    int disabled = triggerRepository.disableByUpstreamPipelineId(id);
    if (disabled > 0) {
      log.info("Disabled {} chain triggers referencing pipeline {}", disabled, id);
    }

    // Delete steps (cascade deletes inputs and dependencies)
    stepRepository.deleteByPipelineId(id);

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
        pipelineRepository
            .findById(pipelineId)
            .orElseThrow(() -> new PipelineNotFoundException("Pipeline not found: " + pipelineId));

    // 비활성 파이프라인은 수동 실행 불가 — 활성화 후 재시도해야 함 (#187)
    if (!pipeline.isActive()) {
      throw new PipelineInactiveException(
          "Pipeline " + pipelineId + " is inactive and cannot be executed");
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

  @Transactional(readOnly = true)
  public List<PipelineExecutionResponse> getExecutionsByPipelineId(Long pipelineId) {
    // Verify pipeline exists
    pipelineRepository
        .findById(pipelineId)
        .orElseThrow(() -> new PipelineNotFoundException("Pipeline not found: " + pipelineId));

    return executionRepository.findExecutionsByPipelineId(pipelineId);
  }

  @Transactional(readOnly = true)
  public ExecutionDetailResponse getExecutionById(Long pipelineId, Long executionId) {
    // 파이프라인 존재 확인
    pipelineRepository
        .findById(pipelineId)
        .orElseThrow(() -> new PipelineNotFoundException("Pipeline not found: " + pipelineId));

    // 실행 조회 후, 해당 실행이 요청한 파이프라인에 속하는지 소유권 검증 (#188).
    // pipelineId가 일치하지 않으면 다른 파이프라인의 실행 상세가 노출되므로
    // 존재 여부 자체를 숨기기 위해 404(PipelineNotFoundException) 반환.
    ExecutionDetailResponse execution =
        executionRepository
            .findExecutionById(executionId)
            .orElseThrow(
                () ->
                    new PipelineNotFoundException(
                        "Execution not found: " + executionId + " in pipeline " + pipelineId));

    if (!pipelineId.equals(execution.pipelineId())) {
      // 크로스-파이프라인 접근 차단. 정보 노출 방지를 위해 동일한 not-found 메시지 형식 사용.
      throw new PipelineNotFoundException(
          "Execution not found: " + executionId + " in pipeline " + pipelineId);
    }

    return execution;
  }
}
