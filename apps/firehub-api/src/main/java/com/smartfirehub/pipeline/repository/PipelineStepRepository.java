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

  public List<PipelineStepResponse> findByPipelineId(Long pipelineId) {
    // output_dataset_id가 null이면 source_pipeline_step_id로 임시 데이터셋 폴백
    var resolvedOutputExpr =
        coalesce(
            PS_OUTPUT_DATASET_ID,
            field(
                select(D_ID)
                    .from(DATASET)
                    .where(field(name("dataset", "source_pipeline_step_id"), Long.class).eq(PS_ID))
                    .limit(1)));

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
            .on(resolvedOutputExpr.eq(D_ID))
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
    JSONB apiConfigJsonb = null;
    if (request.apiConfig() != null) {
      try {
        String json = objectMapper.writeValueAsString(request.apiConfig());
        apiConfigJsonb = JSONB.jsonb(json);
      } catch (JsonProcessingException e) {
        throw new SerializationException("Failed to serialize apiConfig", e);
      }
    }

    JSONB aiConfigJsonb = null;
    if (request.aiConfig() != null) {
      try {
        String json = objectMapper.writeValueAsString(request.aiConfig());
        aiConfigJsonb = JSONB.jsonb(json);
      } catch (JsonProcessingException e) {
        throw new SerializationException("Failed to serialize aiConfig", e);
      }
    }

    JSONB pythonConfigJsonb = null;
    if (request.pythonConfig() != null) {
      try {
        String json = objectMapper.writeValueAsString(request.pythonConfig());
        pythonConfigJsonb = JSONB.jsonb(json);
      } catch (JsonProcessingException e) {
        throw new SerializationException("Failed to serialize pythonConfig", e);
      }
    }

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
            .set(PS_API_CONFIG, apiConfigJsonb)
            .set(PS_AI_CONFIG, aiConfigJsonb)
            .set(PS_PYTHON_CONFIG, pythonConfigJsonb)
            .set(PS_API_CONNECTION_ID, request.apiConnectionId());

    return insert.returning(PS_ID).fetchOne(r -> r.get(PS_ID));
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

    if (!stepIds.isEmpty()) {
      // Delete dependencies
      dsl.deleteFrom(PIPELINE_STEP_DEPENDENCY).where(PSD_STEP_ID.in(stepIds)).execute();

      // Delete inputs
      dsl.deleteFrom(PIPELINE_STEP_INPUT).where(PSI_STEP_ID.in(stepIds)).execute();

      // Delete steps
      dsl.deleteFrom(PIPELINE_STEP).where(PS_PIPELINE_ID.eq(pipelineId)).execute();
    }
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
   * <p>실패·부분 적용 경로에서 호출하면 그 실행이 읽지 못한 행을 영원히 건너뛴다. 출력과 이 갱신은 서로 다른
   * 커넥션이라 한 트랜잭션으로 묶을 수 없다 — 그래서 "출력 커밋 → 책갈피 전진" 순서가 계약이고, 그 사이에
   * 죽으면 다음 실행이 같은 구간을 다시 읽는다(MERGE 멱등성이 흡수).
   *
   * <p><b>예약 해제는 {@code wasFullRebuild} 일 때만 한다.</b> 무조건 false 로 쓰면, 이번 실행이
   * {@code findCursor} 로 예약을 읽은 뒤(=이번 실행은 증분으로 돌기로 확정된 뒤) 운영자가 켠 예약이
   * 전체 재생성을 한 번도 하지 않은 채 사라진다. 반대로 "이번 실행이 실제로 전체 재생성이었을 때"
   * 해제하면, 그 사이에 켜진 예약을 함께 해제하더라도 이미 전체를 다시 만든 뒤이므로 무해하다.
   *
   * @param wasFullRebuild 이번 실행이 전체 재생성이었는지(예약을 소비했는지)
   */
  public void advanceCursor(Long stepId, OffsetDateTime candidate, boolean wasFullRebuild) {
    var query = dsl.update(PIPELINE_STEP).set(PS_LAST_RUN_AT, candidate);
    if (wasFullRebuild) {
      query = query.set(PS_FULL_REBUILD_PENDING, false);
    }
    query.where(PS_ID.eq(stepId)).execute();
  }

  /** 전체 재생성 예약(해제). 예약은 다음 성공 실행에서만 해제된다 — 실패하면 유지된다. */
  public void setFullRebuildPending(Long stepId, boolean pending) {
    dsl.update(PIPELINE_STEP)
        .set(PS_FULL_REBUILD_PENDING, pending)
        .where(PS_ID.eq(stepId))
        .execute();
  }

  /**
   * 파이프라인 재저장(스텝 전체 삭제·재생성) 전에 이름별 책갈피를 떠 둔다.
   *
   * <p>{@code PipelineService.updatePipeline} 은 스텝을 통째로 지우고 다시 넣으므로 스텝 id 가 매번 바뀐다 —
   * 이월의 키가 id 가 아니라 이름인 이유다.
   *
   * <p><b>이름 중복은 현재 DB 가 막는다</b> — {@code pipeline_step} 에 {@code UNIQUE (pipeline_id,
   * name)} 제약이 있고(V3:24, 이후 어떤 마이그레이션도 드롭하지 않는다) 이름을 이월 키로 쓸 수 있는
   * 근거가 바로 그 제약이다. {@code PipelineServiceTest.pipelineStepName_isUniquePerPipeline} 이 그
   * 불변식을 실측으로 고정한다.
   *
   * <p>그럼에도 {@code fetchMap} 이 아니라 {@code fetchGroups} 를 쓰는 이유는 <b>고장 방향</b> 때문이다.
   * 언젠가 그 제약이 사라지면 {@code fetchMap} 은 {@code InvalidResultException} 을 던지고, 이 호출은
   * {@code deleteByPipelineId} 보다 먼저 일어나므로 <b>해당 파이프라인 편집이 통째로 불가능</b>해진다.
   * 반면 여기서 중복 이름을 버리면 "이월하지 않음"(=전체를 다시 읽음)으로 degrade 될 뿐이다 — 이름만으로는
   * 어느 책갈피를 이어받을지 정할 수도 없으므로 그쪽이 옳은 답이기도 하다.
   */
  public Map<String, StepCursor> findCursorsByPipelineId(Long pipelineId) {
    Map<String, List<StepCursor>> byName =
        dsl.select(PS_NAME, PS_LAST_RUN_AT, PS_FULL_REBUILD_PENDING, PS_OUTPUT_DATASET_ID)
            .from(PIPELINE_STEP)
            .where(PS_PIPELINE_ID.eq(pipelineId))
            .fetchGroups(
                r -> r.get(PS_NAME),
                r ->
                    new StepCursor(
                        r.get(PS_LAST_RUN_AT),
                        Boolean.TRUE.equals(r.get(PS_FULL_REBUILD_PENDING)),
                        r.get(PS_OUTPUT_DATASET_ID)));

    Map<String, StepCursor> result = new HashMap<>();
    byName.forEach(
        (stepName, cursors) -> {
          if (cursors.size() == 1) {
            result.put(stepName, cursors.get(0));
          } else {
            log.warn(
                "파이프라인 {} 의 스텝 이름 '{}' 이 {}건 중복이라 증분 책갈피를 이월하지 않습니다(전체를 다시 읽습니다)",
                pipelineId,
                stepName,
                cursors.size());
          }
        });
    return result;
  }

  /**
   * 재저장으로 새로 만들어진 스텝(이름 기준)에 책갈피를 복원한다.
   *
   * <p>이 {@code WHERE} 가 한 행만 맞히는 근거는 {@code UNIQUE (pipeline_id, name)}(V3:24)이다. 그
   * 제약이 사라져도 {@link #findCursorsByPipelineId} 가 중복 이름을 애초에 돌려주지 않으므로 이 메서드가
   * 동명의 스텝 전부를 갱신하는 일은 없다.
   */
  public void restoreCursor(
      Long pipelineId, String stepName, OffsetDateTime lastRunAt, boolean pending) {
    dsl.update(PIPELINE_STEP)
        .set(PS_LAST_RUN_AT, lastRunAt)
        .set(PS_FULL_REBUILD_PENDING, pending)
        .where(PS_PIPELINE_ID.eq(pipelineId).and(PS_NAME.eq(stepName)))
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
