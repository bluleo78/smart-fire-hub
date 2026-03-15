package com.smartfirehub.pipeline.service.executor;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.pipeline.dto.AiClassifyConfig;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AiClassifyExecutor {

  private static final Logger log = LoggerFactory.getLogger(AiClassifyExecutor.class);

  private static final String DEFAULT_PROMPT_TEMPLATE =
      "Classify the following text into one of the allowed labels: {labels}. Text: {text}";

  private static final Table<?> AI_INFERENCE_CACHE = table(name("ai_inference_cache"));
  private static final Field<String> CACHE_ROW_HASH = field(name("row_hash"), String.class);
  private static final Field<String> CACHE_PROMPT_VERSION =
      field(name("prompt_version"), String.class);
  private static final Field<String> CACHE_LABEL = field(name("label"), String.class);
  private static final Field<Double> CACHE_CONFIDENCE =
      field(name("confidence"), Double.class);
  private static final Field<String> CACHE_REASON = field(name("reason"), String.class);

  private final AiAgentClient aiAgentClient;
  private final DataTableRowService dataTableRowService;
  private final DataTableService dataTableService;
  private final DatasetRepository datasetRepository;
  private final DatasetColumnRepository columnRepository;
  private final ObjectMapper objectMapper;
  private final DSLContext dsl;

  public AiClassifyExecutor(
      AiAgentClient aiAgentClient,
      DataTableRowService dataTableRowService,
      DataTableService dataTableService,
      DatasetRepository datasetRepository,
      DatasetColumnRepository columnRepository,
      ObjectMapper objectMapper,
      DSLContext dsl) {
    this.aiAgentClient = aiAgentClient;
    this.dataTableRowService = dataTableRowService;
    this.dataTableService = dataTableService;
    this.datasetRepository = datasetRepository;
    this.columnRepository = columnRepository;
    this.objectMapper = objectMapper;
    this.dsl = dsl;
  }

  public record ExecutionResult(long outputRows, String executionLog) {}

  public ExecutionResult execute(PipelineStepResponse step, Long executionId, Long userId) {
    AiClassifyConfig config = objectMapper.convertValue(step.aiConfig(), AiClassifyConfig.class);

    String outputTableName =
        datasetRepository
            .findTableNameById(step.outputDatasetId())
            .orElseThrow(
                () ->
                    new RuntimeException(
                        "Output dataset table not found for dataset ID: " + step.outputDatasetId()));

    // 0. Validate output dataset columns
    validateOutputColumns(step.outputDatasetId(), config, step.name());

    // Determine promptTemplate and promptVersion
    String promptTemplate =
        config.promptTemplate() != null ? config.promptTemplate() : DEFAULT_PROMPT_TEMPLATE;
    String promptVersion = sha256Prefix8(promptTemplate);

    // Determine targetPrefix
    String targetPrefix = config.targetPrefix() != null ? config.targetPrefix() : "ai_";
    int batchSize = config.batchSize() != null ? config.batchSize() : 20;
    double confidenceThreshold =
        config.confidenceThreshold() != null ? config.confidenceThreshold() : 0.7;
    String onLowConfidence =
        config.onLowConfidence() != null ? config.onLowConfidence() : "MARK_UNKNOWN";
    String onError = config.onError() != null ? config.onError() : "CONTINUE";

    // 1. Fetch all rows from input datasets
    List<Map<String, Object>> allInputRows = fetchInputRows(step, config);

    if (allInputRows.isEmpty()) {
      log.info("[AI_CLASSIFY] Step '{}': No input rows found, skipping", step.name());
      return new ExecutionResult(0, "No input rows found");
    }

    // 2. Filter out null/empty sourceColumn
    List<Map<String, Object>> validRows =
        allInputRows.stream()
            .filter(
                r -> {
                  Object val = r.get(config.sourceColumn());
                  return val != null && !val.toString().isBlank();
                })
            .toList();

    log.info(
        "[AI_CLASSIFY] Step '{}': {} valid rows out of {} total",
        step.name(),
        validRows.size(),
        allInputRows.size());

    // 3. Process in batches
    List<Map<String, Object>> outputRows = new ArrayList<>();
    int totalCached = 0;
    int totalProcessed = 0;
    int totalErrors = 0;

    // Determine load strategy
    String loadStrategy = step.loadStrategy() != null ? step.loadStrategy() : "REPLACE";
    boolean isReplace = "REPLACE".equalsIgnoreCase(loadStrategy);
    String targetTable = outputTableName;

    if (isReplace) {
      dataTableService.createTempTable(outputTableName);
      targetTable = outputTableName + "_tmp";
    }

    try {
      List<List<Map<String, Object>>> batches = partition(validRows, batchSize);

      for (int batchIdx = 0; batchIdx < batches.size(); batchIdx++) {
        List<Map<String, Object>> batch = batches.get(batchIdx);
        log.info(
            "[AI_CLASSIFY] Step '{}': Processing batch {}/{} ({} rows)",
            step.name(),
            batchIdx + 1,
            batches.size(),
            batch.size());

        try {
          List<Map<String, Object>> batchOutput =
              processBatch(
                  batch,
                  config,
                  promptTemplate,
                  promptVersion,
                  targetPrefix,
                  confidenceThreshold,
                  onLowConfidence,
                  userId);

          totalCached += (int) batchOutput.stream().filter(r -> Boolean.TRUE.equals(r.get("_cached"))).count();
          totalProcessed += (int) batchOutput.stream().filter(r -> !Boolean.TRUE.equals(r.get("_cached"))).count();

          // Remove internal _cached flag before inserting
          batchOutput.forEach(r -> r.remove("_cached"));
          outputRows.addAll(batchOutput);

        } catch (Exception e) {
          totalErrors++;
          log.error(
              "[AI_CLASSIFY] Step '{}': Batch {} failed: {}", step.name(), batchIdx + 1, e.getMessage());
          if ("FAIL_STEP".equals(onError)) {
            throw new RuntimeException("AI_CLASSIFY batch " + (batchIdx + 1) + " failed: " + e.getMessage(), e);
          } else if ("RETRY_BATCH".equals(onError)) {
            // Retry once with exponential backoff
            boolean retrySuccess = false;
            for (int retry = 1; retry <= 3; retry++) {
              try {
                Thread.sleep((long) Math.pow(2, retry) * 1000);
                List<Map<String, Object>> batchOutput =
                    processBatch(
                        batch,
                        config,
                        promptTemplate,
                        promptVersion,
                        targetPrefix,
                        confidenceThreshold,
                        onLowConfidence,
                        userId);
                batchOutput.forEach(r -> r.remove("_cached"));
                outputRows.addAll(batchOutput);
                retrySuccess = true;
                break;
              } catch (Exception retryEx) {
                log.warn("[AI_CLASSIFY] Retry {} failed: {}", retry, retryEx.getMessage());
              }
            }
            if (!retrySuccess) {
              log.error("[AI_CLASSIFY] All retries exhausted for batch {}", batchIdx + 1);
            }
          }
          // CONTINUE: skip this batch
        }
      }

      // 4. Insert all output rows into target table
      if (!outputRows.isEmpty()) {
        List<String> outputColumns =
            List.of(
                config.keyColumn(),
                targetPrefix + "label",
                targetPrefix + "confidence",
                targetPrefix + "reason",
                targetPrefix + "classified_at");
        dataTableRowService.insertBatch(targetTable, outputColumns, outputRows);
      }

      if (isReplace) {
        dataTableService.swapTable(outputTableName);
      }

    } catch (Exception e) {
      if (isReplace) {
        try {
          dataTableService.dropTempTable(outputTableName);
        } catch (Exception dropEx) {
          log.warn("[AI_CLASSIFY] Failed to drop temp table: {}", dropEx.getMessage());
        }
      }
      throw e;
    }

    String executionLog =
        String.format(
            "AI_CLASSIFY completed: %d rows output, %d cached, %d AI-processed, %d batch errors",
            outputRows.size(), totalCached, totalProcessed, totalErrors);
    log.info("[AI_CLASSIFY] Step '{}': {}", step.name(), executionLog);

    return new ExecutionResult(outputRows.size(), executionLog);
  }

  private List<Map<String, Object>> processBatch(
      List<Map<String, Object>> batch,
      AiClassifyConfig config,
      String promptTemplate,
      String promptVersion,
      String targetPrefix,
      double confidenceThreshold,
      String onLowConfidence,
      Long userId) {

    // Check cache for each row
    List<Map<String, Object>> cacheHits = new ArrayList<>();
    List<Map<String, Object>> cacheMissRows = new ArrayList<>();

    for (Map<String, Object> row : batch) {
      String text = String.valueOf(row.get(config.sourceColumn()));
      String rowHash = sha256(text + promptVersion);

      var cached = dsl.select(CACHE_LABEL, CACHE_CONFIDENCE, CACHE_REASON)
          .from(AI_INFERENCE_CACHE)
          .where(CACHE_ROW_HASH.eq(rowHash))
          .and(CACHE_PROMPT_VERSION.eq(promptVersion))
          .fetchOne();

      if (cached != null) {
        Map<String, Object> outputRow = new HashMap<>();
        outputRow.put(config.keyColumn(), row.get(config.keyColumn()));
        outputRow.put(targetPrefix + "label", cached.get(CACHE_LABEL));
        outputRow.put(targetPrefix + "confidence", cached.get(CACHE_CONFIDENCE));
        outputRow.put(targetPrefix + "reason", cached.get(CACHE_REASON));
        outputRow.put(targetPrefix + "classified_at", LocalDateTime.now());
        outputRow.put("_cached", true);
        cacheHits.add(outputRow);
      } else {
        Map<String, Object> missRow = new HashMap<>(row);
        missRow.put("_rowHash", rowHash);
        cacheMissRows.add(missRow);
      }
    }

    List<Map<String, Object>> results = new ArrayList<>(cacheHits);

    if (!cacheMissRows.isEmpty()) {
      // Build classify request
      List<Map<String, Object>> requestRows =
          cacheMissRows.stream()
              .map(
                  r -> {
                    Object keyVal = r.get(config.keyColumn());
                    String rowId = keyVal != null ? String.valueOf(keyVal) : "";
                    return Map.<String, Object>of("rowId", rowId, "text", String.valueOf(r.get(config.sourceColumn())));
                  })
              .toList();

      AiAgentClient.ClassifyRequest classifyRequest =
          new AiAgentClient.ClassifyRequest(requestRows, config.labels(), promptTemplate, promptVersion);

      AiAgentClient.ClassifyResponse response = aiAgentClient.classify(classifyRequest, userId);

      // Map results by rowId
      Map<String, AiAgentClient.ClassifyRowResult> resultByRowId =
          response.results().stream()
              .collect(Collectors.toMap(AiAgentClient.ClassifyRowResult::rowId, r -> r));

      for (Map<String, Object> missRow : cacheMissRows) {
        String rowId = String.valueOf(missRow.get(config.keyColumn()));
        String rowHash = (String) missRow.get("_rowHash");
        AiAgentClient.ClassifyRowResult classifyResult = resultByRowId.get(rowId);

        if (classifyResult == null) {
          log.warn("[AI_CLASSIFY] No result for rowId {}", rowId);
          continue;
        }

        String label = classifyResult.label();
        double confidence = classifyResult.confidence();

        // Apply low-confidence policy
        if (confidence < confidenceThreshold) {
          switch (onLowConfidence) {
            case "MARK_UNKNOWN" -> label = "UNKNOWN";
            case "FAIL_STEP" ->
                throw new RuntimeException(
                    "Low confidence " + confidence + " for rowId " + rowId + " (threshold: " + confidenceThreshold + ")");
            // KEEP_BEST_LABEL: keep as-is
          }
        }

        // Save to cache
        try {
          dsl.insertInto(AI_INFERENCE_CACHE)
              .set(CACHE_ROW_HASH, rowHash)
              .set(CACHE_PROMPT_VERSION, promptVersion)
              .set(CACHE_LABEL, label)
              .set(CACHE_CONFIDENCE, confidence)
              .set(CACHE_REASON, classifyResult.reason())
              .onConflictDoNothing()
              .execute();
        } catch (Exception e) {
          log.warn("[AI_CLASSIFY] Failed to save cache for rowHash {}: {}", rowHash, e.getMessage());
        }

        Map<String, Object> outputRow = new HashMap<>();
        outputRow.put(config.keyColumn(), missRow.get(config.keyColumn()));
        outputRow.put(targetPrefix + "label", label);
        outputRow.put(targetPrefix + "confidence", confidence);
        outputRow.put(targetPrefix + "reason", classifyResult.reason());
        outputRow.put(targetPrefix + "classified_at", LocalDateTime.now());
        outputRow.put("_cached", false);
        results.add(outputRow);
      }
    }

    return results;
  }

  private List<Map<String, Object>> fetchInputRows(
      PipelineStepResponse step, AiClassifyConfig config) {
    List<Map<String, Object>> allRows = new ArrayList<>();

    if (step.inputDatasetIds() == null || step.inputDatasetIds().isEmpty()) {
      return allRows;
    }

    List<String> columnsToFetch = List.of(config.keyColumn(), config.sourceColumn());

    for (Long datasetId : step.inputDatasetIds()) {
      String tableName =
          datasetRepository.findTableNameById(datasetId).orElse(null);
      if (tableName == null) continue;

      long total = dataTableRowService.countRows(tableName);
      int pageSize = 1000;
      int pages = (int) Math.ceil((double) total / pageSize);

      for (int page = 0; page < pages; page++) {
        List<Map<String, Object>> rows =
            dataTableRowService.queryData(tableName, columnsToFetch, null, page, pageSize);
        allRows.addAll(rows);
      }
    }

    return allRows;
  }

  private void validateOutputColumns(
      Long outputDatasetId, AiClassifyConfig config, String stepName) {
    List<DatasetColumnResponse> cols = columnRepository.findByDatasetId(outputDatasetId);
    Set<String> colNames = cols.stream().map(DatasetColumnResponse::columnName).collect(Collectors.toSet());

    String targetPrefix = config.targetPrefix() != null ? config.targetPrefix() : "ai_";
    List<String> required =
        List.of(
            config.keyColumn(),
            targetPrefix + "label",
            targetPrefix + "confidence",
            targetPrefix + "reason",
            targetPrefix + "classified_at");

    for (String col : required) {
      if (!colNames.contains(col)) {
        throw new RuntimeException(
            "AI_CLASSIFY step '"
                + stepName
                + "': Output dataset is missing required column '"
                + col
                + "'. Please create the output dataset with columns: "
                + required);
      }
    }
  }

  private static <T> List<List<T>> partition(List<T> list, int size) {
    List<List<T>> partitions = new ArrayList<>();
    for (int i = 0; i < list.size(); i += size) {
      partitions.add(list.subList(i, Math.min(i + size, list.size())));
    }
    return partitions;
  }

  private static String sha256(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 not available", e);
    }
  }

  private static String sha256Prefix8(String input) {
    return sha256(input).substring(0, 8);
  }
}
