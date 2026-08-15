package com.smartfirehub.pipeline.service.executor;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.global.tenant.TenantContext;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AI_CLASSIFY 스텝 실행기 — 입력 행을 배치로 묶어 ai-agent 에 분류를 맡기고, 결과를 {@code
 * ai_inference_cache} 에 캐시한다.
 *
 * <p><b>클래스/메서드 레벨 {@code @Transactional} 을 쓰지 않는 이유(P2-e R3).</b> 캐시 조회와 캐시
 * 쓰기 <b>사이</b>에서 외부 HTTP({@code aiAgentClient.classify})를 호출한다. 전체를 트랜잭션으로
 * 감싸면 LLM 왕복 시간(수 초~수십 초) 동안 DB 커넥션을 붙잡아 커넥션 풀이 마른다. 그래서 조회
 * 루프와 쓰기 루프만 각각 좁은 {@link TransactionTemplate} 으로 감싼다.
 *
 * <p><b>왜 트랜잭션이 필요한가.</b> {@code ai_inference_cache} 는 V103 으로 {@code tenant_id} NOT
 * NULL(GUC 파생 DEFAULT)이 됐고 V104 로 RLS 정책이 붙는다. RLS 격리 값은 <b>트랜잭션 로컬</b> GUC
 * 라 {@code TenantAwareTransactionManager.doBegin} 에서만 주입된다 — 트랜잭션 없이 읽으면 정책이
 * 켜진 뒤 조용히 0행(= 영구 캐시 미스), 쓰면 NOT NULL 위반이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiClassifyExecutor {

  private static final Table<?> AI_INFERENCE_CACHE = table(name("ai_inference_cache"));
  private static final Field<String> CACHE_ROW_HASH = field(name("row_hash"), String.class);
  private static final Field<String> CACHE_PROMPT_VERSION =
      field(name("prompt_version"), String.class);
  private static final Field<JSONB> CACHE_RESULT_JSON = field(name("result_json"), JSONB.class);

  /**
   * 캐시 조회에 붙이는 테넌트 술어용 컬럼. V103 이 유니크를 {@code (tenant_id, row_hash,
   * prompt_version)} 으로 접었으므로 캐시는 테넌트별로 파티션된다(R2) — {@code row_hash} 는 분류
   * 대상 <b>행 내용</b>의 해시라, 술어 없이 조회하면 A 테넌트 행의 존재와 추론 결과가 B 테넌트의
   * 캐시 히트로 관측된다. 정책(V104)에 앞서 쿼리 자체가 격리를 성립시킨다.
   */
  private static final Field<Long> CACHE_TENANT_ID = field(name("tenant_id"), Long.class);

  private final AiAgentClient aiAgentClient;
  private final DataTableRowService dataTableRowService;
  private final DataTableService dataTableService;
  private final DatasetRepository datasetRepository;
  private final ObjectMapper objectMapper;
  private final DSLContext dsl;
  private final TransactionTemplate transactionTemplate;

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

    // 1-1. 테넌트 컨텍스트 확인 — 캐시가 테넌트별로 파티션되므로(R2) 컨텍스트가 없으면 어느 테넌트의
    //      캐시인지 정할 수 없다. 조용히 진행하면 조회는 영구 미스, 쓰기는 tenant_id NOT NULL 위반이
    //      되어 "기능은 도는데 LLM 비용만 무한히 드는" 상태가 된다.
    //      **반드시 배치 루프 밖에서** 던져야 한다. 배치 루프 안(:processBatch)에서 던지면
    //      onError=CONTINUE 기본값이 예외를 삼켜 "0행 출력 + 1 batch errors" 로 성공 반환하고,
    //      onError=RETRY_BATCH 면 절대 성공할 수 없는 재시도로 14초를 잔다 — 배선 결함이 다시
    //      조용해진다. 임시 테이블 생성 전에 두어 정리할 것이 남지 않게 한다.
    if (TenantContext.get() == null) {
      throw new IllegalStateException(
          "테넌트 컨텍스트 없이 AI_CLASSIFY 를 실행할 수 없다(ai_inference_cache 는 테넌트별 파티션) — 배경 경로 배선 오류");
    }

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

  /** 캐시에 새로 적재할 항목 — 쓰기 루프를 한 트랜잭션으로 모으기 위해 잠시 모아 둔다. */
  private record PendingCacheEntry(String rowHash, String resultJson) {}

  private BatchResult processBatch(
      List<Map<String, Object>> batch,
      AiClassifyConfig config,
      String promptHash,
      List<Map<String, String>> outputColumnSpecs,
      Long userId) {

    // 캐시 술어에 쓸 테넌트. null 검사는 이미 execute() 가 배치 루프 밖에서 끝냈다(거기서 던져야
    // onError=CONTINUE 가 삼키지 않는다). 여기서는 컨텍스트를 다시 읽기만 한다 — @Async 승계가
    // 배치 중간에 사라질 일은 없지만, 값을 인자로 끌고 다니는 대신 단일 출처를 유지한다.
    Long tenantId = TenantContext.get();

    List<Map<String, Object>> cacheHits = new ArrayList<>();
    List<Map<String, Object>> cacheMissRows = new ArrayList<>();

    // 행 해시는 순수 계산이라 트랜잭션 밖에서 미리 구한다 — 경계를 DB 접근에만 좁히기 위함.
    List<String> rowHashes = batch.stream().map(row -> sha256(toJson(row) + promptHash)).toList();

    // ── 조회 루프 (좁은 트랜잭션 1/2) ──────────────────────────────────────
    // 외부 HTTP 호출은 이 블록 밖에 있다(R3). 트랜잭션이 필요한 이유는 GUC 주입이다(클래스 Javadoc).
    // 결과를 반환값이 아니라 바깥 리스트에 채우는 이유: execute(...) 의 반환은 @Nullable 이라
    // null 방어를 넣게 되고, 그 방어가 "트랜잭션이 안 돌면 전부 미스"로 조용히 성립해 버린다.
    //
    // 행마다 SELECT 를 던진다(N 왕복). IN 한 번으로 접으면 결과를 row_hash 로 되짚어야 하는데,
    // 해시는 private sha256/toJson 의 산물이라 단위 테스트가 그 계산을 복제해야만 목을 세울 수
    // 있게 된다 — 테스트를 사설 해시 구현에 묶는 대가가, LLM 호출이 지배하는 이 경로에서 아끼는
    // 몇 번의 왕복보다 크다. 배치 크기가 의미 있게 커지면 다시 볼 것.
    List<JSONB> cachedJsonByIndex = new ArrayList<>(rowHashes.size());
    transactionTemplate.executeWithoutResult(
        status -> {
          for (String rowHash : rowHashes) {
            var cached =
                dsl.select(CACHE_RESULT_JSON)
                    .from(AI_INFERENCE_CACHE)
                    .where(CACHE_ROW_HASH.eq(rowHash))
                    .and(CACHE_PROMPT_VERSION.eq(promptHash))
                    .and(CACHE_TENANT_ID.eq(tenantId))
                    .fetchOne();
            cachedJsonByIndex.add(cached != null ? cached.get(CACHE_RESULT_JSON) : null);
          }
        });

    for (int i = 0; i < batch.size(); i++) {
      Map<String, Object> row = batch.get(i);
      JSONB cachedJson = cachedJsonByIndex.get(i);

      if (cachedJson != null) {
        Map<String, Object> cachedValues = fromJson(cachedJson.data());
        Map<String, Object> outputRow = new HashMap<>(cachedValues);
        if (!outputRow.containsKey("source_id") && row.containsKey("id")) {
          outputRow.put("source_id", row.get("id"));
        }
        cacheHits.add(outputRow);
      } else {
        Map<String, Object> missRow = new HashMap<>(row);
        missRow.put("_rowHash", rowHashes.get(i));
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

      List<PendingCacheEntry> pendingCacheEntries = new ArrayList<>();

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

        pendingCacheEntries.add(new PendingCacheEntry(rowHash, toJson(outputRow)));
        results.add(outputRow);
      }

      // ── 쓰기 루프 (좁은 트랜잭션 2/2) ────────────────────────────────────
      // 외부 HTTP 호출은 이미 끝났고 이 블록 밖에 있다(R3).
      // tenant_id 는 일부러 세팅하지 않는다 — GUC 파생 DEFAULT(V103)가 채운다. 앱이 직접 싣는
      // 유일한 지점이 되면 GUC 와 어긋날 여지가 생기고, 이 저장소의 다른 테넌트 테이블 선례
      // (MappingRepository, DatasetOntologyRepository)와도 달라진다.
      // onConflictDoNothing() 은 conflict target 이 없는 형태라 인덱스 추론을 하지 않는다 —
      // V103 의 유니크 접기로 깨질 지점이 아니다(전수 확인은 Task 3).
      if (!pendingCacheEntries.isEmpty()) {
        try {
          // 조회와 대칭으로 다중 VALUES 한 방에 넣는다 — 항목마다 INSERT 를 던지면 왕복이 캐시
          // 미스 수만큼 늘고, 그 시간만큼 이 트랜잭션이 열려 있다.
          transactionTemplate.executeWithoutResult(
              status -> {
                var insert =
                    dsl.insertInto(
                        AI_INFERENCE_CACHE,
                        CACHE_ROW_HASH,
                        CACHE_PROMPT_VERSION,
                        CACHE_RESULT_JSON);
                for (PendingCacheEntry entry : pendingCacheEntries) {
                  insert =
                      insert.values(
                          entry.rowHash(), promptHash, JSONB.valueOf(entry.resultJson()));
                }
                insert.onConflictDoNothing().execute();
              });
        } catch (Exception e) {
          // log.warn 에서 승격. 여기서 실패하면 캐시가 영구 미스가 되어 같은 행에 대해 LLM 을
          // 매번 다시 호출한다 — 기능은 정상으로 보이면서 비용만 계속 드는 조용한 결함이라
          // 스택 트레이스까지 남긴다. 캐시 실패로 스텝 전체를 죽이지는 않는다(재던지지 않음).
          //
          // 실패한 해시를 함께 남기는 이유: 배치가 한 문장이라 하나만 틀어져도 전부 롤백된다.
          // 건수만 찍으면 어느 행이 원인인지 재현할 방법이 없다.
          log.error(
              "[AI_CLASSIFY] Failed to save {} cache entries (tenant {}, promptVersion {}, rowHashes {})",
              pendingCacheEntries.size(),
              tenantId,
              promptHash,
              pendingCacheEntries.stream().map(PendingCacheEntry::rowHash).toList(),
              e);
        }
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
