package com.smartfirehub.pipeline.repository.jooq;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.exception.SerializationException;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.dto.StepCursor;
import com.smartfirehub.pipeline.repository.PipelineStepRepository;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.repository.jooq.JooqDatasetVisibility;
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

/** PipelineStepRepository 의 jOOQ(PostgreSQL) 구현 — WD-485. */
@Repository
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
// 배경 잡(JobRunr/@Async/@Scheduled)은 앰비언트 트랜잭션이 없다. RLS GUC 는 트랜잭션 시작
// 시점에만 주입되므로, 트랜잭션이 없으면 TenantContext 를 세워도 정책이 전 행을 차단한다.
// REQUIRED 라 서비스가 이미 연 트랜잭션에는 합류한다(기존 경로 동작 불변).
@Transactional
public class JooqPipelineStepRepository implements PipelineStepRepository {

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

  /**
   * 스텝 출력이 「자동 생성」인지 식(WD-222) — 저장된 출력이 없거나, 저장된 출력이 이 스텝의 러너 TEMP({@code source_pipeline_step_id}
   * = 스텝 id) 자체인 경우. 화면 저장이 해석된 TEMP id 를 명시 출력으로 되돌려 보내 고정한 기존 스텝도 자동 생성으로 본다(마이그레이션 없이 판정).
   */
  private static Field<Boolean> outputAutoGenerated() {
    return field(
        PS_OUTPUT_DATASET_ID
            .isNull()
            .or(
                PS_OUTPUT_DATASET_ID.in(
                    select(D_ID).from(DATASET).where(D_SOURCE_PIPELINE_STEP_ID.eq(PS_ID)))));
  }

  @Override
  public List<PipelineStepResponse> findByPipelineId(Long pipelineId) {
    return findByPipelineId(pipelineId, DatasetVisibility.UNRESTRICTED);
  }

  @Override
  public List<PipelineStepResponse> findByPipelineId(
      Long pipelineId, DatasetVisibility outputNameVisibility) {
    Condition outputDatasetNameVisible = JooqDatasetVisibility.condition(outputNameVisibility);
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
                outputAutoGenerated().as("output_auto_generated"),
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
                  null,
                  Boolean.TRUE.equals(r.get("output_auto_generated", Boolean.class)));
            })
        .toList();
  }

  @Override
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

  @Override
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

  @Override
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

  @Override
  public void saveStepInput(Long stepId, Long datasetId) {
    dsl.insertInto(PIPELINE_STEP_INPUT)
        .set(PSI_STEP_ID, stepId)
        .set(PSI_DATASET_ID, datasetId)
        .execute();
  }

  @Override
  public void saveStepDependency(Long stepId, Long dependsOnStepId) {
    dsl.insertInto(PIPELINE_STEP_DEPENDENCY)
        .set(PSD_STEP_ID, stepId)
        .set(PSD_DEPENDS_ON_STEP_ID, dependsOnStepId)
        .execute();
  }

  @Override
  public void deleteByPipelineId(Long pipelineId) {
    // Get all step IDs
    var stepIds =
        dsl.select(PS_ID)
            .from(PIPELINE_STEP)
            .where(PS_PIPELINE_ID.eq(pipelineId))
            .fetch(r -> r.get(PS_ID));
    deleteSteps(pipelineId, stepIds);
  }

  @Override
  public void deleteSteps(Long pipelineId, Collection<Long> stepIds) {
    if (stepIds.isEmpty()) {
      return;
    }
    clearInputsAndDependencies(stepIds);
    dsl.deleteFrom(PIPELINE_STEP)
        .where(PS_PIPELINE_ID.eq(pipelineId).and(PS_ID.in(stepIds)))
        .execute();
  }

  @Override
  public void clearInputsAndDependencies(Collection<Long> stepIds) {
    if (stepIds.isEmpty()) {
      return;
    }
    dsl.deleteFrom(PIPELINE_STEP_DEPENDENCY)
        .where(PSD_STEP_ID.in(stepIds).or(PSD_DEPENDS_ON_STEP_ID.in(stepIds)))
        .execute();
    dsl.deleteFrom(PIPELINE_STEP_INPUT).where(PSI_STEP_ID.in(stepIds)).execute();
  }

  @Override
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

  @Override
  public Map<Long, Long> findTempOwnerStepIdsByDatasetIds(Collection<Long> datasetIds) {
    Map<Long, Long> result = new HashMap<>();
    if (datasetIds.isEmpty()) {
      return result;
    }
    dsl.select(D_ID, D_SOURCE_PIPELINE_STEP_ID)
        .from(DATASET)
        .where(
            D_ID.in(datasetIds)
                .and(D_ORIGIN_TYPE.eq("TEMP"))
                .and(D_SOURCE_PIPELINE_STEP_ID.isNotNull()))
        .fetch()
        .forEach(r -> result.put(r.get(D_ID), r.get(D_SOURCE_PIPELINE_STEP_ID)));
    return result;
  }

  @Override
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

  @Override
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

  @Override
  public List<Long> findStepIdsByPipelineId(Long pipelineId) {
    return dsl.select(PS_ID).from(PIPELINE_STEP).where(PS_PIPELINE_ID.eq(pipelineId)).fetch(PS_ID);
  }

  @Override
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

  @Override
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

  @Override
  public void setFullRebuildPending(Long stepId, boolean pending) {
    dsl.update(PIPELINE_STEP)
        .set(PS_FULL_REBUILD_PENDING, pending)
        .where(PS_ID.eq(stepId))
        .execute();
  }

  @Override
  public void setCursor(Long stepId, OffsetDateTime lastRunAt, boolean pending) {
    dsl.update(PIPELINE_STEP)
        .set(PS_LAST_RUN_AT, lastRunAt)
        .set(PS_FULL_REBUILD_PENDING, pending)
        .where(PS_ID.eq(stepId))
        .execute();
  }

  @Override
  public Optional<Long> findStepIdByPipelineAndName(Long pipelineId, String name) {
    return dsl.select(PS_ID)
        .from(PIPELINE_STEP)
        .where(PS_PIPELINE_ID.eq(pipelineId))
        .and(PS_NAME.eq(name))
        .fetchOptional(r -> r.get(PS_ID));
  }
}
