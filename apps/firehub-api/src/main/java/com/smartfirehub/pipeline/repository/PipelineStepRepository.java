package com.smartfirehub.pipeline.repository;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.exception.SerializationException;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.dto.StepCursor;
import java.time.OffsetDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
// 배경 잡(JobRunr/@Async/@Scheduled)은 앰비언트 트랜잭션이 없다. RLS GUC 는 트랜잭션 시작
// 시점에만 주입되므로, 트랜잭션이 없으면 TenantContext 를 세워도 정책이 전 행을 차단한다.
// REQUIRED 라 서비스가 이미 연 트랜잭션에는 합류한다(기존 경로 동작 불변).
@Transactional
public class PipelineStepRepository {

  private final DSLContext dsl;
  private final ObjectMapper objectMapper;

  // Table constants
  private static final Table<?> PIPELINE_STEP = table(name("pipeline_step"));
  private static final Field<Long> PS_ID = field(name("pipeline_step", "id"), Long.class);
  private static final Field<Long> PS_PIPELINE_ID =
      field(name("pipeline_step", "pipeline_id"), Long.class);
  private static final Field<String> PS_NAME = field(name("pipeline_step", "name"), String.class);
  private static final Field<String> PS_DESCRIPTION =
      field(name("pipeline_step", "description"), String.class);
  private static final Field<String> PS_SCRIPT_TYPE =
      field(name("pipeline_step", "script_type"), String.class);
  private static final Field<String> PS_SCRIPT_CONTENT =
      field(name("pipeline_step", "script_content"), String.class);
  private static final Field<Long> PS_OUTPUT_DATASET_ID =
      field(name("pipeline_step", "output_dataset_id"), Long.class);
  private static final Field<Integer> PS_STEP_ORDER =
      field(name("pipeline_step", "step_order"), Integer.class);
  private static final Field<String> PS_LOAD_STRATEGY =
      field(name("pipeline_step", "load_strategy"), String.class);
  private static final Field<JSONB> PS_API_CONFIG =
      field(name("pipeline_step", "api_config"), JSONB.class);
  private static final Field<JSONB> PS_AI_CONFIG =
      field(name("pipeline_step", "ai_config"), JSONB.class);
  private static final Field<JSONB> PS_PYTHON_CONFIG =
      field(name("pipeline_step", "python_config"), JSONB.class);
  private static final Field<Long> PS_API_CONNECTION_ID =
      field(name("pipeline_step", "api_connection_id"), Long.class);

  // 증분 처리 책갈피(V123). last_run_at 은 "마지막 성공 실행 직전에 캡처한 후보값"이고,
  // full_rebuild_pending 은 "다음 1회는 전체를 읽고 출력을 비워라"는 예약 플래그다.
  private static final Field<OffsetDateTime> PS_LAST_RUN_AT =
      field(name("pipeline_step", "last_run_at"), OffsetDateTime.class);
  private static final Field<Boolean> PS_FULL_REBUILD_PENDING =
      field(name("pipeline_step", "full_rebuild_pending"), Boolean.class);

  private static final Table<?> PIPELINE_STEP_INPUT = table(name("pipeline_step_input"));
  private static final Field<Long> PSI_STEP_ID =
      field(name("pipeline_step_input", "step_id"), Long.class);
  private static final Field<Long> PSI_DATASET_ID =
      field(name("pipeline_step_input", "dataset_id"), Long.class);

  private static final Table<?> PIPELINE_STEP_DEPENDENCY = table(name("pipeline_step_dependency"));
  private static final Field<Long> PSD_STEP_ID =
      field(name("pipeline_step_dependency", "step_id"), Long.class);
  private static final Field<Long> PSD_DEPENDS_ON_STEP_ID =
      field(name("pipeline_step_dependency", "depends_on_step_id"), Long.class);

  private static final Table<?> DATASET = table(name("dataset"));
  private static final Field<Long> D_ID = field(name("dataset", "id"), Long.class);
  private static final Field<String> D_NAME = field(name("dataset", "name"), String.class);
  // 러너 TEMP(출력 미지정 스텝용 임시 데이터셋)가 어느 스텝 것인지 — V36. 출력 해석 규칙의 유일한 근거다.
  private static final Field<Long> D_SOURCE_PIPELINE_STEP_ID =
      field(name("dataset", "source_pipeline_step_id"), Long.class);
  // 데이터셋 출처(SOURCE/DERIVED/TEMP, V66). 러너가 자동 생성한 임시 데이터셋은 'TEMP' 다(WD-170 정리 대상 판별).
  private static final Field<String> D_ORIGIN_TYPE =
      field(name("dataset", "origin_type"), String.class);

  /**
   * 이름을 바꿀 스텝을 잠시 비켜 두는 임시 이름의 접두사(WD-108, {@link #renameStepTemporarily}). 뒤에 스텝 id 를 붙여 파이프라인 안에서
   * 겹치지 않게 하고, 같은 트랜잭션 안에서 곧바로 최종 이름으로 덮인다.
   */
  private static final String RENAMING_PLACEHOLDER_PREFIX = "__renaming_";

  /**
   * 스텝의 "해석된" 출력 데이터셋 id 식 — 지정 출력이 있으면 그것, 없으면 그 스텝의 러너 TEMP({@code source_pipeline_step_id} = 스텝
   * id). 화면 응답({@link #findByPipelineId})과 책갈피 전진 가드({@link #advanceCursor})가 같은 규칙을 쓰도록 한 곳에 둔다.
   */
  private static Field<Long> resolvedOutputDatasetId() {
    return coalesce(
        PS_OUTPUT_DATASET_ID,
        field(select(D_ID).from(DATASET).where(D_SOURCE_PIPELINE_STEP_ID.eq(PS_ID)).limit(1)));
  }

  /** 실행·내부 경로용 — 출력 데이터셋 이름을 가리지 않는다(러너·트리거 스레드에는 요청 사용자가 없어 가시성 조건을 만들 수 없다). */
  public List<PipelineStepResponse> findByPipelineId(Long pipelineId) {
    return findByPipelineId(pipelineId, trueCondition());
  }

  /**
   * 화면 응답용 — {@code outputDatasetNameVisible} 을 출력 데이터셋 LEFT JOIN 의 ON 에 더해, 조회자가 볼 수 없는 출력 데이터셋은
   * 이름만 null 이 된다(보안 등급, 스펙 §2.5). <b>출력·입력 데이터셋 id 는 그대로 둔다</b> — 웹 편집기가 PUT 으로 그대로 되돌려 보내므로 id 를
   * 지우면 저장 한 번에 참조가 사라진다.
   *
   * @param outputDatasetNameVisible {@code "dataset"} 이름 관례의 가시성
   *     조건(DatasetAccessGuard#visibleCondition())
   */
  public List<PipelineStepResponse> findByPipelineId(
      Long pipelineId, Condition outputDatasetNameVisible) {
    // output_dataset_id가 null이면 source_pipeline_step_id로 임시 데이터셋 폴백
    var resolvedOutputExpr = resolvedOutputDatasetId();

    var steps =
        dsl.select(
                PS_ID,
                PS_NAME,
                PS_DESCRIPTION,
                PS_SCRIPT_TYPE,
                PS_SCRIPT_CONTENT,
                resolvedOutputExpr.as("resolved_output_dataset_id"),
                PS_STEP_ORDER,
                PS_LOAD_STRATEGY,
                PS_API_CONFIG,
                PS_AI_CONFIG,
                PS_PYTHON_CONFIG,
                PS_API_CONNECTION_ID,
                PS_LAST_RUN_AT,
                PS_FULL_REBUILD_PENDING,
                D_NAME)
            .from(PIPELINE_STEP)
            .leftJoin(DATASET)
            .on(resolvedOutputExpr.eq(D_ID).and(outputDatasetNameVisible))
            .where(PS_PIPELINE_ID.eq(pipelineId))
            .orderBy(PS_STEP_ORDER.asc())
            .fetch();

    // Get all input datasets for these steps
    var stepIds = steps.stream().map(r -> r.get(PS_ID)).toList();
    Map<Long, List<Long>> inputDatasetMap = new HashMap<>();

    if (!stepIds.isEmpty()) {
      dsl.select(PSI_STEP_ID, PSI_DATASET_ID)
          .from(PIPELINE_STEP_INPUT)
          .where(PSI_STEP_ID.in(stepIds))
          .fetch()
          .forEach(
              r -> {
                Long stepId = r.get(PSI_STEP_ID);
                Long datasetId = r.get(PSI_DATASET_ID);
                inputDatasetMap.computeIfAbsent(stepId, k -> new ArrayList<>()).add(datasetId);
              });
    }

    // Get all dependencies and resolve to step names
    Map<Long, List<String>> dependencyMap = new HashMap<>();

    if (!stepIds.isEmpty()) {
      var deps =
          dsl.select(
                  PSD_STEP_ID,
                  field(name("dep_step", "name"), String.class).as("depends_on_step_name"))
              .from(PIPELINE_STEP_DEPENDENCY)
              .join(PIPELINE_STEP.as("dep_step"))
              .on(PSD_DEPENDS_ON_STEP_ID.eq(field(name("dep_step", "id"), Long.class)))
              .where(PSD_STEP_ID.in(stepIds))
              .fetch();

      deps.forEach(
          r -> {
            Long stepId = r.get(PSD_STEP_ID);
            String depStepName = r.get("depends_on_step_name", String.class);
            dependencyMap.computeIfAbsent(stepId, k -> new ArrayList<>()).add(depStepName);
          });
    }

    // Build response
    return steps.stream()
        .map(
            r -> {
              Long stepId = r.get(PS_ID);
              Map<String, Object> apiConfigMap = null;
              JSONB apiConfigJsonb = r.get(PS_API_CONFIG);
              if (apiConfigJsonb != null && apiConfigJsonb.data() != null) {
                try {
                  apiConfigMap =
                      objectMapper.readValue(
                          apiConfigJsonb.data(), new TypeReference<Map<String, Object>>() {});
                } catch (JsonProcessingException e) {
                  throw new SerializationException("Failed to parse api_config JSON", e);
                }
              }
              Map<String, Object> aiConfigMap = null;
              JSONB aiConfigJsonb = r.get(PS_AI_CONFIG);
              if (aiConfigJsonb != null && aiConfigJsonb.data() != null) {
                try {
                  aiConfigMap =
                      objectMapper.readValue(
                          aiConfigJsonb.data(), new TypeReference<Map<String, Object>>() {});
                } catch (JsonProcessingException e) {
                  throw new SerializationException("Failed to parse ai_config JSON", e);
                }
              }
              Map<String, Object> pythonConfigMap = null;
              JSONB pythonConfigJsonb = r.get(PS_PYTHON_CONFIG);
              if (pythonConfigJsonb != null && pythonConfigJsonb.data() != null) {
                try {
                  pythonConfigMap =
                      objectMapper.readValue(
                          pythonConfigJsonb.data(), new TypeReference<Map<String, Object>>() {});
                } catch (JsonProcessingException e) {
                  throw new SerializationException("Failed to parse python_config JSON", e);
                }
              }
              return new PipelineStepResponse(
                  stepId,
                  r.get(PS_NAME),
                  r.get(PS_DESCRIPTION),
                  r.get(PS_SCRIPT_TYPE),
                  r.get(PS_SCRIPT_CONTENT),
                  r.get("resolved_output_dataset_id", Long.class),
                  r.get(D_NAME),
                  inputDatasetMap.getOrDefault(stepId, List.of()),
                  dependencyMap.getOrDefault(stepId, List.of()),
                  r.get(PS_STEP_ORDER),
                  r.get(PS_LOAD_STRATEGY) != null ? r.get(PS_LOAD_STRATEGY) : "REPLACE",
                  apiConfigMap,
                  aiConfigMap,
                  pythonConfigMap,
                  r.get(PS_API_CONNECTION_ID),
                  r.get(PS_LAST_RUN_AT),
                  Boolean.TRUE.equals(r.get(PS_FULL_REBUILD_PENDING)),
                  // 경고/재생성 모드는 SQL 구조 분석이 필요해 리포지토리 책임이 아니다 — PipelineService 가
                  // 상세 조립 시 SqlValidator·PipelineAsyncRunner 로 계산해 withIncrementalMeta 로
                  // 붙인다. 여기서는 항상 빈 목록/null.
                  List.of(),
                  null);
            })
        .toList();
  }

  public Long saveStep(Long pipelineId, PipelineStepRequest request, int stepOrder) {
    var insert =
        dsl.insertInto(PIPELINE_STEP)
            .set(PS_PIPELINE_ID, pipelineId)
            .set(PS_NAME, request.name())
            .set(PS_DESCRIPTION, request.description())
            .set(PS_SCRIPT_TYPE, request.scriptType())
            .set(PS_SCRIPT_CONTENT, request.scriptContent())
            .set(PS_OUTPUT_DATASET_ID, request.outputDatasetId())
            .set(PS_STEP_ORDER, stepOrder)
            .set(
                PS_LOAD_STRATEGY,
                request.loadStrategy() != null ? request.loadStrategy() : "REPLACE")
            .set(PS_API_CONFIG, toJsonb(request.apiConfig(), "apiConfig"))
            .set(PS_AI_CONFIG, toJsonb(request.aiConfig(), "aiConfig"))
            .set(PS_PYTHON_CONFIG, toJsonb(request.pythonConfig(), "pythonConfig"))
            .set(PS_API_CONNECTION_ID, request.apiConnectionId());

    return insert.returning(PS_ID).fetchOne(r -> r.get(PS_ID));
  }

  /**
   * 기존 스텝을 제자리 갱신한다(WD-108) — id 를 유지해야 과거 실행 기록(pipeline_step_execution.step_id)과 러너 TEMP
   * (dataset.source_pipeline_step_id)가 같은 스텝에 계속 이어진다. 저장 요청이 담는 정의 필드만 바꾸고, 책갈피({@code
   * last_run_at}·{@code full_rebuild_pending})와 {@code created_at} 은 건드리지 않는다 — 책갈피는 호출부가 이월 규칙으로
   * 따로 정한다({@link #setCursor}).
   *
   * <p>{@code pipelineId} 를 조건에 함께 거는 이유: 호출부가 이 파이프라인의 스텝 id 만 넘기도록 검증하지만, 리포지토리 단에서도 다른 파이프라인의 행을
   * 고칠 수 없게 이중으로 막는다.
   */
  public void updateStep(Long pipelineId, Long stepId, PipelineStepRequest request, int stepOrder) {
    dsl.update(PIPELINE_STEP)
        .set(PS_NAME, request.name())
        .set(PS_DESCRIPTION, request.description())
        .set(PS_SCRIPT_TYPE, request.scriptType())
        .set(PS_SCRIPT_CONTENT, request.scriptContent())
        .set(PS_OUTPUT_DATASET_ID, request.outputDatasetId())
        .set(PS_STEP_ORDER, stepOrder)
        .set(PS_LOAD_STRATEGY, request.loadStrategy() != null ? request.loadStrategy() : "REPLACE")
        .set(PS_API_CONFIG, toJsonb(request.apiConfig(), "apiConfig"))
        .set(PS_AI_CONFIG, toJsonb(request.aiConfig(), "aiConfig"))
        .set(PS_PYTHON_CONFIG, toJsonb(request.pythonConfig(), "pythonConfig"))
        .set(PS_API_CONNECTION_ID, request.apiConnectionId())
        .where(PS_ID.eq(stepId).and(PS_PIPELINE_ID.eq(pipelineId)))
        .execute();
  }

  /**
   * 이름을 바꿀 스텝에 잠시 겹치지 않는 임시 이름을 준다(WD-108).
   *
   * <p>{@code UNIQUE (pipeline_id, name)}(V3:24)은 문장마다 즉시 검사된다. 두 스텝의 이름을 맞바꾸는 저장(A→B, B→A)을 차례로
   * UPDATE 하면 첫 문장에서 아직 B 인 행과 충돌한다. 이름이 바뀌는 스텝을 먼저 전부 임시 이름으로 비켜 두면 이후 최종 이름 UPDATE 가 서로 부딪히지 않는다.
   * 임시 이름은 같은 트랜잭션 안에서 곧바로 최종 이름으로 덮인다(실패하면 롤백).
   */
  public void renameStepTemporarily(Long pipelineId, Long stepId) {
    dsl.update(PIPELINE_STEP)
        .set(PS_NAME, RENAMING_PLACEHOLDER_PREFIX + stepId)
        .where(PS_ID.eq(stepId).and(PS_PIPELINE_ID.eq(pipelineId)))
        .execute();
  }

  /** 맵 설정값(api/ai/python config)을 JSONB 로 직렬화한다. null 은 null 로 둔다. */
  private JSONB toJsonb(Map<String, Object> value, String label) {
    if (value == null) {
      return null;
    }
    try {
      return JSONB.jsonb(objectMapper.writeValueAsString(value));
    } catch (JsonProcessingException e) {
      throw new SerializationException("Failed to serialize " + label, e);
    }
  }

  public void saveStepInput(Long stepId, Long datasetId) {
    dsl.insertInto(PIPELINE_STEP_INPUT)
        .set(PSI_STEP_ID, stepId)
        .set(PSI_DATASET_ID, datasetId)
        .execute();
  }

  public void saveStepDependency(Long stepId, Long dependsOnStepId) {
    dsl.insertInto(PIPELINE_STEP_DEPENDENCY)
        .set(PSD_STEP_ID, stepId)
        .set(PSD_DEPENDS_ON_STEP_ID, dependsOnStepId)
        .execute();
  }

  public void deleteByPipelineId(Long pipelineId) {
    // Get all step IDs
    var stepIds =
        dsl.select(PS_ID)
            .from(PIPELINE_STEP)
            .where(PS_PIPELINE_ID.eq(pipelineId))
            .fetch(r -> r.get(PS_ID));
    deleteSteps(pipelineId, stepIds);
  }

  /**
   * 지정한 스텝들을 입력·의존과 함께 지운다(WD-108 — 저장 요청에서 빠진 스텝만 지울 때 쓴다).
   *
   * <p>이 스텝들의 과거 실행 기록은 지워지지 않는다 — V139 가 {@code pipeline_step_execution.step_id} FK 를 {@code ON
   * DELETE SET NULL} 로 바꿨으므로 기록은 {@code step_name} 스냅샷과 함께 남고 스텝 연결만 끊긴다. 의존은 양방향({@code
   * step_id}·{@code depends_on_step_id})을 모두 지운다 — 남은 스텝이 지워진 스텝을 가리키는 엣지는 FK CASCADE 로도 지워지지만,
   * 명시적으로 지워 두면 FK 정의가 바뀌어도 의미가 흔들리지 않는다.
   */
  public void deleteSteps(Long pipelineId, Collection<Long> stepIds) {
    if (stepIds.isEmpty()) {
      return;
    }
    clearInputsAndDependencies(stepIds);
    dsl.deleteFrom(PIPELINE_STEP)
        .where(PS_PIPELINE_ID.eq(pipelineId).and(PS_ID.in(stepIds)))
        .execute();
  }

  /**
   * 제자리 갱신할 스텝들의 입력·의존을 비운다(WD-108). 저장 요청이 입력·의존 전체를 다시 보내므로(전체 교체 계약) 남길 행을 골라내지 않고 비운 뒤 다시 넣는다 —
   * 스텝 행(id)은 그대로라 실행 기록은 영향이 없다.
   */
  public void clearInputsAndDependencies(Collection<Long> stepIds) {
    if (stepIds.isEmpty()) {
      return;
    }
    dsl.deleteFrom(PIPELINE_STEP_DEPENDENCY)
        .where(PSD_STEP_ID.in(stepIds).or(PSD_DEPENDS_ON_STEP_ID.in(stepIds)))
        .execute();
    dsl.deleteFrom(PIPELINE_STEP_INPUT).where(PSI_STEP_ID.in(stepIds)).execute();
  }

  /**
   * 스텝별 러너 TEMP(실행기가 출력 미지정 스텝용으로 만든 임시 데이터셋) id 를 한 번에 읽는다(스텝 id → TEMP id, TEMP 없는 스텝은 맵에 없음).
   * {@link #resolvedOutputDatasetId} 의 폴백과 같은 규칙({@code source_pipeline_step_id} = 스텝 id)이며, 한 스텝에
   * TEMP 가 여럿이면 그 식의 {@code LIMIT 1} 처럼 하나만 쓴다.
   */
  public Map<Long, Long> findTempDatasetIdsByStepIds(Collection<Long> stepIds) {
    Map<Long, Long> result = new HashMap<>();
    if (stepIds.isEmpty()) {
      return result;
    }
    dsl.select(D_SOURCE_PIPELINE_STEP_ID, D_ID)
        .from(DATASET)
        .where(D_SOURCE_PIPELINE_STEP_ID.in(stepIds))
        .fetch()
        .forEach(r -> result.putIfAbsent(r.get(D_SOURCE_PIPELINE_STEP_ID), r.get(D_ID)));
    return result;
  }

  /**
   * 주어진 스텝들이 만든(러너 자동 생성) 임시 데이터셋 id 를 모두 읽는다(WD-170 — 스텝·파이프라인 삭제 때 함께 지울 후보).
   *
   * <p>{@link #findTempDatasetIdsByStepIds} 와 달리 한 스텝에 여럿이어도 전부 돌려준다 — 정리 대상이므로 하나도 빠뜨리면 안 된다.
   * {@code origin_type='TEMP'} 로 한정해, 출처가 TEMP 가 아닌 데이터셋에 스텝 연결이 남아 있더라도(정상 경로로는 생기지 않음) 사용자가 만든
   * 데이터셋을 지우지 않게 한다. 스텝을 지우면 {@code source_pipeline_step_id} 가 SET NULL(V36)이 되어 더는 찾을 수 없으므로 반드시
   * 스텝 삭제 <b>전에</b> 불러야 한다.
   */
  public List<Long> findAutoTempDatasetIdsByStepIds(Collection<Long> stepIds) {
    if (stepIds.isEmpty()) {
      return List.of();
    }
    return dsl.select(D_ID)
        .from(DATASET)
        .where(D_SOURCE_PIPELINE_STEP_ID.in(stepIds).and(D_ORIGIN_TYPE.eq("TEMP")))
        .orderBy(D_ID)
        .fetch(D_ID);
  }

  /**
   * 주어진 데이터셋들 중 {@code excludedStepIds} 밖의 스텝이 <b>출력</b>({@code output_dataset_id})으로 쓰고 있는 것의 id 를
   * 돌려준다 (WD-170 — 다른 스텝이 출력으로 쓰는 임시 데이터셋은 지우지 않는다. {@code DatasetService.deleteDataset} 의 출력 참조
   * 거부와 같은 규칙이며, 그 거부가 트랜잭션을 롤백 전용으로 만들기 전에 미리 걸러 내는 데 쓴다).
   *
   * <p>제외 스텝은 "곧 지워질 스텝" — 스텝이 자기 임시 데이터셋을 출력으로 되돌려 저장해 둔 경우(웹이 해석된 출력을 왕복 저장)를 참조로 세지 않기 위해서다. 이미
   * 지운 뒤에 부르면 제외 목록은 비어 있어도 된다.
   */
  public Set<Long> findDatasetIdsUsedAsOutputExcludingSteps(
      Collection<Long> datasetIds, Collection<Long> excludedStepIds) {
    if (datasetIds.isEmpty()) {
      return Set.of();
    }
    Condition cond = PS_OUTPUT_DATASET_ID.in(datasetIds);
    if (!excludedStepIds.isEmpty()) {
      cond = cond.and(PS_ID.notIn(excludedStepIds));
    }
    return new HashSet<>(
        dsl.selectDistinct(PS_OUTPUT_DATASET_ID)
            .from(PIPELINE_STEP)
            .where(cond)
            .fetch(PS_OUTPUT_DATASET_ID));
  }

  /** 파이프라인의 스텝 id 목록(WD-170 — 파이프라인 삭제·삭제 영향 조회에서 함께 지울 임시 데이터셋을 찾는 데 쓴다). */
  public List<Long> findStepIdsByPipelineId(Long pipelineId) {
    return dsl.select(PS_ID).from(PIPELINE_STEP).where(PS_PIPELINE_ID.eq(pipelineId)).fetch(PS_ID);
  }

  /** 스텝 증분 책갈피 조회. 스텝이 없으면 빈 Optional. */
  public Optional<StepCursor> findCursor(Long stepId) {
    return dsl.select(PS_LAST_RUN_AT, PS_FULL_REBUILD_PENDING, PS_OUTPUT_DATASET_ID)
        .from(PIPELINE_STEP)
        .where(PS_ID.eq(stepId))
        .fetchOptional(
            r ->
                new StepCursor(
                    r.get(PS_LAST_RUN_AT),
                    Boolean.TRUE.equals(r.get(PS_FULL_REBUILD_PENDING)),
                    r.get(PS_OUTPUT_DATASET_ID)));
  }

  /**
   * 출력 커밋 성공 <b>후에만</b> 호출 — 책갈피를 전진시키고 전체 재생성 예약을 해제한다.
   *
   * <p>실패·부분 적용 경로에서 호출하면 그 실행이 읽지 못한 행을 영원히 건너뛴다. 출력과 이 갱신은 서로 다른 커넥션이라 한 트랜잭션으로 묶을 수 없다 — 그래서 "출력
   * 커밋 → 책갈피 전진" 순서가 계약이고, 그 사이에 죽으면 다음 실행이 같은 구간을 다시 읽는다(MERGE 멱등성이 흡수).
   *
   * <p><b>예약 해제는 {@code wasFullRebuild} 일 때만 한다.</b> 무조건 false 로 쓰면, 이번 실행이 {@code findCursor} 로
   * 예약을 읽은 뒤(=이번 실행은 증분으로 돌기로 확정된 뒤) 운영자가 켠 예약이 전체 재생성을 한 번도 하지 않은 채 사라진다. 반대로 "이번 실행이 실제로 전체
   * 재생성이었을 때" 해제하면, 그 사이에 켜진 예약을 함께 해제하더라도 이미 전체를 다시 만든 뒤이므로 무해하다.
   *
   * <p><b>스텝의 현재 출력이 이번 실행이 실제로 쓴 출력과 같을 때만 전진한다(WD-108).</b> 저장이 스텝을 제자리 갱신(id 유지)하게 되면서, 실행 도중
   * 사용자가 그 스텝의 출력 데이터셋을 바꿔 저장하면 끝난 실행이 <b>새 출력</b>의 책갈피를 전진시킬 수 있게 됐다 — 새 출력은 그 구간 데이터를 받은 적이 없으므로
   * 다음 실행부터 그 구간이 영원히 빈다(조용한 데이터 누락). 예전에는 저장이 스텝 id 를 바꿨기 때문에 이 갱신이 사라진 id 에 맞아 아무 일도 없었다. 출력 비교는
   * {@link #resolvedOutputDatasetId}(지정 출력, 없으면 러너 TEMP)로 한다. 스텝이 그 사이 지워졌으면 0행 갱신이다.
   *
   * @param wasFullRebuild 이번 실행이 전체 재생성이었는지(예약을 소비했는지)
   * @param writtenOutputDatasetId 이번 실행이 실제로 적재한 출력 데이터셋 id(러너가 만든 TEMP 포함, 출력 없는 사용자 DML 은 null)
   */
  public void advanceCursor(
      Long stepId, OffsetDateTime candidate, boolean wasFullRebuild, Long writtenOutputDatasetId) {
    var query = dsl.update(PIPELINE_STEP).set(PS_LAST_RUN_AT, candidate);
    if (wasFullRebuild) {
      query = query.set(PS_FULL_REBUILD_PENDING, false);
    }
    int updated =
        query
            .where(PS_ID.eq(stepId))
            .and(resolvedOutputDatasetId().isNotDistinctFrom(writtenOutputDatasetId))
            .execute();
    if (updated == 0) {
      log.warn("스텝 {} 의 출력이 실행 도중 바뀌었거나 스텝이 지워져 증분 책갈피를 전진시키지 않습니다(다음 실행이 해당 구간을 다시 읽습니다)", stepId);
    }
  }

  /** 전체 재생성 예약(해제). 예약은 다음 성공 실행에서만 해제된다 — 실패하면 유지된다. */
  public void setFullRebuildPending(Long stepId, boolean pending) {
    dsl.update(PIPELINE_STEP)
        .set(PS_FULL_REBUILD_PENDING, pending)
        .where(PS_ID.eq(stepId))
        .execute();
  }

  /**
   * 책갈피를 지정 값으로 맞춘다(WD-108 — 제자리 갱신 저장의 이월 규칙 적용). 이월하지 않을 때는 {@code (null, false)} 로 "한 번도 실행되지 않은
   * 스텝"과 같은 상태로 되돌린다.
   */
  public void setCursor(Long stepId, OffsetDateTime lastRunAt, boolean pending) {
    dsl.update(PIPELINE_STEP)
        .set(PS_LAST_RUN_AT, lastRunAt)
        .set(PS_FULL_REBUILD_PENDING, pending)
        .where(PS_ID.eq(stepId))
        .execute();
  }

  public Optional<Long> findStepIdByPipelineAndName(Long pipelineId, String name) {
    return dsl.select(PS_ID)
        .from(PIPELINE_STEP)
        .where(PS_PIPELINE_ID.eq(pipelineId))
        .and(PS_NAME.eq(name))
        .fetchOptional(r -> r.get(PS_ID));
  }
}
