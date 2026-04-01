package com.smartfirehub.pipeline.service.executor;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.pipeline.dto.AiClassifyConfig;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiClassifyExecutor {

  private static final Table<?> AI_INFERENCE_CACHE = table(name("ai_inference_cache"));
  private static final Field<String> CACHE_ROW_HASH = field(name("row_hash"), String.class);
  private static final Field<String> CACHE_PROMPT_VERSION =
      field(name("prompt_version"), String.class);
  private static final Field<JSONB> CACHE_RESULT_JSON = field(name("result_json"), JSONB.class);

  private final AiAgentClient aiAgentClient;
  private final DataTableRowService dataTableRowService;
  private final DataTableService dataTableService;
  private final DatasetRepository datasetRepository;
  private final ObjectMapper objectMapper;
  private final DSLContext dsl;

  public record ExecutionResult(long outputRows, String executionLog) {}

  public ExecutionResult execute(PipelineStepResponse step, Long executionId, Long userId) {
    AiClassifyConfig config = objectMapper.convertValue(step.aiConfig(), AiClassifyConfig.class);

    String outputTableName =
        datasetRepository
            .findTableNameById(step.outputDatasetId())
            .orElseThrow(
                () ->
                    new RuntimeException(
                        "Output dataset table not found for dataset ID: "
                            + step.outputDatasetId()));

    int batchSize = config.batchSize() != null ? config.batchSize() : 20;
    String onError = config.onError() != null ? config.onError() : "CONTINUE";

    // prompt_hash = SHA-256(prompt + JSON(outputColumns))[:8] — changes when outputColumns change
    String promptHash = buildPromptHash(config);

    // outputColumns spec for AI agent: [{name, type}, ...]
    List<Map<String, String>> outputColumnSpecs =
        config.outputColumns().stream()
            .map(col -> Map.of("name", col.name(), "type", col.type()))
            .toList();

    // 1. Fetch input rows (filtered by inputColumns if specified; id always included)
    List<Map<String, Object>> allInputRows = fetchInputRows(step, config);

    if (allInputRows.isEmpty()) {
      log.info("[AI_CLASSIFY] Step '{}': No input rows found, skipping", step.name());
      return new ExecutionResult(0, "No input rows found");
    }

    log.info("[AI_CLASSIFY] Step '{}': {} input rows", step.name(), allInputRows.size());

    // 2. Load strategy
    String loadStrategy = step.loadStrategy() != null ? step.loadStrategy() : "REPLACE";
    boolean isReplace = "REPLACE".equalsIgnoreCase(loadStrategy);
    String targetTable = outputTableName;

    if (isReplace) {
      dataTableService.createTempTable(outputTableName);
      targetTable = outputTableName + "_tmp";
    }

    // 3. Output column names: source_id + each outputColumn
    List<String> outputColumnNames = new ArrayList<>();
    outputColumnNames.add("source_id");
    config.outputColumns().forEach(col -> outputColumnNames.add(col.name()));

    List<Map<String, Object>> outputRows = new ArrayList<>();
    int totalProcessed = 0;
    int totalCached = 0;
    int totalErrors = 0;

    try {
      List<List<Map<String, Object>>> batches = partition(allInputRows, batchSize);

      for (int batchIdx = 0; batchIdx < batches.size(); batchIdx++) {
        List<Map<String, Object>> batch = batches.get(batchIdx);
        log.info(
            "[AI_CLASSIFY] Step '{}': Processing batch {}/{} ({} rows)",
            step.name(),
            batchIdx + 1,
            batches.size(),
            batch.size());

        try {
          BatchResult batchResult =
              processBatch(batch, config, promptHash, outputColumnSpecs, userId);
          totalCached += batchResult.cached();
          totalProcessed += batchResult.processed();
          outputRows.addAll(batchResult.rows());

        } catch (Exception e) {
          totalErrors++;
          log.error(
              "[AI_CLASSIFY] Step '{}': Batch {} failed: {}",
              step.name(),
              batchIdx + 1,
              e.getMessage());

          if ("FAIL_STEP".equals(onError)) {
            throw new RuntimeException(
                "AI_CLASSIFY batch " + (batchIdx + 1) + " failed: " + e.getMessage(), e);
          } else if ("RETRY_BATCH".equals(onError)) {
            boolean retrySuccess = false;
            for (int retry = 1; retry <= 3; retry++) {
              try {
                Thread.sleep((long) Math.pow(2, retry) * 1000);
                BatchResult batchResult =
                    processBatch(batch, config, promptHash, outputColumnSpecs, userId);
                totalCached += batchResult.cached();
                totalProcessed += batchResult.processed();
                outputRows.addAll(batchResult.rows());
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
          // CONTINUE: skip batch
        }
      }

      // 4. Insert all output rows (AI는 모든 값을 문자열/숫자로 반환 → Java 타입 변환 후 삽입)
      if (!outputRows.isEmpty()) {
        Map<String, String> columnTypes = new HashMap<>();
        columnTypes.put("source_id", "BIGINT");
        config.outputColumns().forEach(col -> columnTypes.put(col.name(), col.type()));

        for (Map<String, Object> row : outputRows) {
          coerceRowValues(row, columnTypes);
        }
        dataTableRowService.insertBatch(targetTable, outputColumnNames, outputRows, columnTypes);
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

  private record BatchResult(List<Map<String, Object>> rows, int cached, int processed) {}

  private BatchResult processBatch(
      List<Map<String, Object>> batch,
      AiClassifyConfig config,
      String promptHash,
      List<Map<String, String>> outputColumnSpecs,
      Long userId) {

    List<Map<String, Object>> cacheHits = new ArrayList<>();
    List<Map<String, Object>> cacheMissRows = new ArrayList<>();

    for (Map<String, Object> row : batch) {
      String rowJson = toJson(row);
      String rowHash = sha256(rowJson + promptHash);

      var cached =
          dsl.select(CACHE_RESULT_JSON)
              .from(AI_INFERENCE_CACHE)
              .where(CACHE_ROW_HASH.eq(rowHash))
              .and(CACHE_PROMPT_VERSION.eq(promptHash))
              .fetchOne();

      if (cached != null && cached.get(CACHE_RESULT_JSON) != null) {
        Map<String, Object> cachedValues = fromJson(cached.get(CACHE_RESULT_JSON).data());
        Map<String, Object> outputRow = new HashMap<>(cachedValues);
        if (!outputRow.containsKey("source_id") && row.containsKey("id")) {
          outputRow.put("source_id", row.get("id"));
        }
        cacheHits.add(outputRow);
      } else {
        Map<String, Object> missRow = new HashMap<>(row);
        missRow.put("_rowHash", rowHash);
        cacheMissRows.add(missRow);
      }
    }

    List<Map<String, Object>> results = new ArrayList<>(cacheHits);

    if (!cacheMissRows.isEmpty()) {
      // Strip internal tracking keys before sending to AI agent
      List<Map<String, Object>> requestRows =
          cacheMissRows.stream()
              .map(
                  r -> {
                    Map<String, Object> clean = new HashMap<>(r);
                    clean.remove("_rowHash");
                    return clean;
                  })
              .toList();

      AiAgentClient.ClassifyRequest classifyRequest =
          new AiAgentClient.ClassifyRequest(requestRows, config.prompt(), outputColumnSpecs);

      AiAgentClient.ClassifyResponse response = aiAgentClient.classify(classifyRequest, userId);

      // Map results by source_id (as String for type-safe matching: Long vs Integer)
      Map<String, AiAgentClient.ClassifyRowResult> resultBySourceId =
          response.results().stream()
              .filter(r -> r.values().containsKey("source_id"))
              .collect(
                  Collectors.toMap(
                      r -> String.valueOf(r.values().get("source_id")), r -> r, (a, b) -> a));

      for (Map<String, Object> missRow : cacheMissRows) {
        String rowHash = (String) missRow.get("_rowHash");
        Object sourceId = missRow.get("id");
        AiAgentClient.ClassifyRowResult classifyResult =
            sourceId != null ? resultBySourceId.get(String.valueOf(sourceId)) : null;

        if (classifyResult == null) {
          log.warn("[AI_CLASSIFY] No result for source_id {}", sourceId);
          continue;
        }

        Map<String, Object> outputRow = new HashMap<>(classifyResult.values());
        if (!outputRow.containsKey("source_id") && sourceId != null) {
          outputRow.put("source_id", sourceId);
        }

        // Save to cache
        try {
          String resultJson = toJson(outputRow);
          dsl.insertInto(AI_INFERENCE_CACHE)
              .set(CACHE_ROW_HASH, rowHash)
              .set(CACHE_PROMPT_VERSION, promptHash)
              .set(CACHE_RESULT_JSON, JSONB.valueOf(resultJson))
              .onConflictDoNothing()
              .execute();
        } catch (Exception e) {
          log.warn(
              "[AI_CLASSIFY] Failed to save cache for rowHash {}: {}", rowHash, e.getMessage());
        }

        results.add(outputRow);
      }
    }

    return new BatchResult(results, cacheHits.size(), cacheMissRows.size());
  }

  private List<Map<String, Object>> fetchInputRows(
      PipelineStepResponse step, AiClassifyConfig config) {
    List<Map<String, Object>> allRows = new ArrayList<>();

    if (step.inputDatasetIds() == null || step.inputDatasetIds().isEmpty()) {
      return allRows;
    }

    // queryData always includes "id" (as "_id" in result Map), no need to add it
    List<String> columnsToFetch = null;
    if (config.inputColumns() != null && !config.inputColumns().isEmpty()) {
      List<String> cols = new ArrayList<>(config.inputColumns());
      cols.remove("id"); // queryData already adds id automatically
      columnsToFetch = cols;
    }

    for (Long datasetId : step.inputDatasetIds()) {
      String tableName = datasetRepository.findTableNameById(datasetId).orElse(null);
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

  private String buildPromptHash(AiClassifyConfig config) {
    try {
      String outputColumnsJson = objectMapper.writeValueAsString(config.outputColumns());
      return sha256Prefix8(config.prompt() + outputColumnsJson);
    } catch (JsonProcessingException e) {
      return sha256Prefix8(config.prompt());
    }
  }

  private String toJson(Map<String, Object> map) {
    try {
      return objectMapper.writeValueAsString(map);
    } catch (JsonProcessingException e) {
      return "{}";
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> fromJson(String json) {
    try {
      return objectMapper.readValue(json, Map.class);
    } catch (JsonProcessingException e) {
      return new HashMap<>();
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

  private static final java.util.regex.Pattern DIGITS_ONLY =
      java.util.regex.Pattern.compile("\\d+");

  /** AI 반환값을 DB 컬럼 타입에 맞는 Java 타입으로 변환 (epoch millis → Timestamp 등) */
  private static void coerceRowValues(Map<String, Object> row, Map<String, String> columnTypes) {
    for (Map.Entry<String, String> entry : columnTypes.entrySet()) {
      String col = entry.getKey();
      Object value = row.get(col);
      if (value == null) continue;

      String type = entry.getValue().toUpperCase();
      try {
        if (type.contains("TIMESTAMP")) {
          if (value instanceof Number n) {
            row.put(col, new Timestamp(n.longValue()));
          } else if (value instanceof String s && DIGITS_ONLY.matcher(s).matches()) {
            row.put(col, new Timestamp(Long.parseLong(s)));
          }
        } else if (type.contains("BOOLEAN")) {
          if (value instanceof String s) {
            row.put(col, Boolean.parseBoolean(s));
          }
        } else if (type.equals("BIGINT") || type.equals("BIGSERIAL")) {
          if (value instanceof String s) {
            row.put(col, Long.parseLong(s));
          } else if (value instanceof Number n) {
            row.put(col, n.longValue());
          }
        } else if (type.equals("INTEGER") || type.equals("INT") || type.equals("SMALLINT")) {
          if (value instanceof String s) {
            row.put(col, Integer.parseInt(s));
          } else if (value instanceof Number n) {
            row.put(col, n.intValue());
          }
        } else if (type.contains("NUMERIC") || type.contains("DECIMAL")) {
          if (value instanceof String s) {
            row.put(col, new BigDecimal(s));
          }
        }
      } catch (NumberFormatException e) {
        log.warn("[AI_CLASSIFY] Failed to coerce column '{}' value '{}' to {}", col, value, type);
      }
    }
  }
}
