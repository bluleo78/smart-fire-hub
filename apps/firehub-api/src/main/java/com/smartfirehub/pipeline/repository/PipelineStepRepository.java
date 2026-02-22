package com.smartfirehub.pipeline.repository;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.exception.SerializationException;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
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
  private static final Field<Long> PS_API_CONNECTION_ID =
      field(name("pipeline_step", "api_connection_id"), Long.class);

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

  public PipelineStepRepository(DSLContext dsl, ObjectMapper objectMapper) {
    this.dsl = dsl;
    this.objectMapper = objectMapper;
  }

  public List<PipelineStepResponse> findByPipelineId(Long pipelineId) {
    // Get all steps
    var steps =
        dsl.select(
                PS_ID,
                PS_NAME,
                PS_DESCRIPTION,
                PS_SCRIPT_TYPE,
                PS_SCRIPT_CONTENT,
                PS_OUTPUT_DATASET_ID,
                PS_STEP_ORDER,
                PS_LOAD_STRATEGY,
                PS_API_CONFIG,
                PS_API_CONNECTION_ID,
                D_NAME)
            .from(PIPELINE_STEP)
            .leftJoin(DATASET)
            .on(PS_OUTPUT_DATASET_ID.eq(D_ID))
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
              return new PipelineStepResponse(
                  stepId,
                  r.get(PS_NAME),
                  r.get(PS_DESCRIPTION),
                  r.get(PS_SCRIPT_TYPE),
                  r.get(PS_SCRIPT_CONTENT),
                  r.get(PS_OUTPUT_DATASET_ID),
                  r.get(D_NAME),
                  inputDatasetMap.getOrDefault(stepId, List.of()),
                  dependencyMap.getOrDefault(stepId, List.of()),
                  r.get(PS_STEP_ORDER),
                  r.get(PS_LOAD_STRATEGY) != null ? r.get(PS_LOAD_STRATEGY) : "REPLACE",
                  apiConfigMap,
                  r.get(PS_API_CONNECTION_ID));
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

  public Optional<Long> findStepIdByPipelineAndName(Long pipelineId, String name) {
    return dsl.select(PS_ID)
        .from(PIPELINE_STEP)
        .where(PS_PIPELINE_ID.eq(pipelineId))
        .and(PS_NAME.eq(name))
        .fetchOptional(r -> r.get(PS_ID));
  }
}
