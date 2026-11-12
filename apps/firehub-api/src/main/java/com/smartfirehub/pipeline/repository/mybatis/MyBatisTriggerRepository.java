package com.smartfirehub.pipeline.repository.mybatis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.global.exception.SerializationException;
import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.global.platformdb.mybatis.PlatformJson;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.pipeline.dto.CreateTriggerRequest;
import com.smartfirehub.pipeline.dto.PipelineDeleteImpactResponse;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.dto.UpdateTriggerRequest;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import com.smartfirehub.pipeline.repository.mybatis.TriggerRows.TriggerInsert;
import com.smartfirehub.pipeline.repository.mybatis.TriggerRows.TriggerRow;
import com.smartfirehub.pipeline.repository.mybatis.TriggerRows.WatchRow;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * TriggerRepository 의 MyBatis 구현(WD-708) — {@code JooqTriggerRepository} 와 같은 의미.
 *
 * <p>트랜잭션 속성은 jOOQ 구현 그대로다 — 클래스 단위 plain {@code @Transactional}(배경 잡은 앰비언트 트랜잭션이 없고, 트랜잭션이 없으면 테넌트
 * 컨텍스트가 심기지 않아 정책이 0행이 된다), {@code mergeNextFireTime}·{@code clearNextFireTime} 은 afterCommit 콜백에서
 * 불려 REQUIRES_NEW, 같은 트랜잭션 참여판과 {@code lockChainGraph} 는 MANDATORY.
 *
 * <p>감시 목록 정리({@link #removeWatchedDataset}·{@link #clearPollSnapshot})는 스펙 D5 대로 잠근 뒤 자바에서 jOOQ
 * SQL 과 같은 의미로 고쳐 쓴다 — Tibero 에 {@code jsonb_set}·{@code #-}·{@code JSON_TRANSFORM} 이 없다. jOOQ 는 한
 * 문장 UPDATE 라 폴러 쓰기와 서로 덮지 않았는데, 여기서는 행을 {@code FOR UPDATE} 로 잠가 같은 보장을 얻는다(폴러의 {@code
 * updateTriggerState} 는 원래도 상태 전체를 쓰는 마지막 쓰기 승리라 새 유실 위험은 없다).
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisTriggerRepository implements TriggerRepository {

  /** 스냅숏 키 — jOOQ removeWatchedDataset 의 {@code #-} 경로 세 개. */
  private static final List<String> SNAPSHOT_KEYS =
      List.of("lastSnapshot", "lastChangeSnapshot", "lastRelationSnapshot");

  /** clearPollSnapshot 이 지우는 최상위 키 — jOOQ 의 {@code - 'key'} 네 개. */
  private static final List<String> POLL_KEYS =
      List.of("lastSnapshot", "lastChangeSnapshot", "lastRelationSnapshot", "lastCheckedAt");

  private final TriggerMapper mapper;
  private final ObjectMapper objectMapper;

  /** 체인 그래프 직렬화 잠금 — jOOQ 구현과 같은 플랫폼 잠금 추상화(PG advisory / Tibero DBMS_LOCK). */
  private final PlatformLocks locks;

  public MyBatisTriggerRepository(
      TriggerMapper mapper, ObjectMapper objectMapper, PlatformLocks locks) {
    this.mapper = mapper;
    this.objectMapper = objectMapper;
    this.locks = locks;
  }

  @Override
  public List<TriggerResponse> findByPipelineId(Long pipelineId) {
    return mapper.selectByPipelineId(pipelineId).stream().map(this::toResponse).toList();
  }

  @Override
  public Optional<TriggerResponse> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id)).map(this::toResponse);
  }

  @Override
  public List<TriggerResponse> findEnabledByType(String triggerType) {
    return mapper.selectEnabledByType(triggerType).stream().map(this::toResponse).toList();
  }

  @Override
  public List<TriggerResponse> findEnabledChainTriggersByUpstreamId(Long upstreamPipelineId) {
    return mapper.selectEnabledChainByUpstream(upstreamPipelineId).stream()
        .map(this::toResponse)
        .toList();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  @Override
  public void lockChainGraph(long tenantId) {
    // jOOQ 판과 같은 키(다른 테넌트 단위 잠금과 키 공간을 접두어로 나눈다).
    locks.xactLock("pipeline-chain-graph:" + tenantId);
  }

  @Override
  public List<ChainEdgeRow> findEnabledChainEdges() {
    return mapper.selectEnabledChainEdges();
  }

  @Override
  public Long create(
      Long pipelineId, CreateTriggerRequest req, Map<String, Object> config, Long userId) {
    TriggerInsert row =
        new TriggerInsert(
            pipelineId,
            req.triggerType().name(),
            req.name(),
            req.description(),
            toJson(config),
            userId);
    mapper.insert(row);
    return row.getId();
  }

  @Override
  public Optional<LockedRowVersion> lockUpdatedAtById(Long id) {
    return Optional.ofNullable(mapper.lockVersionById(id))
        .map(row -> new LockedRowVersion(row.updatedAt()));
  }

  @Override
  public void update(Long id, UpdateTriggerRequest req, Long userId) {
    // description: null 이면 바꾸지 않음, 공백이면 지움(WD-113) — jOOQ 판 그대로. 시각은 jOOQ 와 같은 자바 시각.
    boolean setDescription = req.description() != null;
    String description = setDescription && !req.description().isBlank() ? req.description() : null;
    mapper.update(
        id,
        LocalDateTime.now(),
        userId,
        req.name(),
        setDescription,
        description,
        req.isEnabled(),
        req.config() == null ? null : toJson(req.config()));
  }

  @Override
  public int delete(Long id) {
    return mapper.deleteById(id);
  }

  @Override
  public void updateEnabled(Long id, boolean enabled) {
    mapper.updateEnabled(id, enabled, LocalDateTime.now());
  }

  /** 실행 상태만 바꾼다 — 수정 시각은 건드리지 않는다(WD-576, 계약은 인터페이스 javadoc). */
  @Override
  public void updateTriggerState(Long id, Map<String, Object> state) {
    mapper.updateState(id, toJson(state));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public void mergeNextFireTime(Long id, String nextFireTimeIso) {
    doMergeNextFireTime(id, nextFireTimeIso);
  }

  /** 바깥 트랜잭션에 참여(MANDATORY)해 같은 커넥션으로 쓴다 — 커넥션 1개 원칙(WD-554). */
  @Transactional(propagation = Propagation.MANDATORY)
  @Override
  public void mergeNextFireTimeInTransaction(Long id, String nextFireTimeIso) {
    doMergeNextFireTime(id, nextFireTimeIso);
  }

  private void doMergeNextFireTime(Long id, String nextFireTimeIso) {
    mutateState(id, state -> state.put("nextFireTime", nextFireTimeIso));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public void clearNextFireTime(Long id) {
    doClearNextFireTime(id);
  }

  /** 바깥 트랜잭션에 참여(MANDATORY)해 같은 커넥션으로 쓴다 — 커넥션 1개 원칙(WD-554). */
  @Transactional(propagation = Propagation.MANDATORY)
  @Override
  public void clearNextFireTimeInTransaction(Long id) {
    doClearNextFireTime(id);
  }

  /** 상태에 nextFireTime 이 없으면 바뀐 것이 없어 쓰지 않는다(jOOQ 판과 같다). */
  private void doClearNextFireTime(Long id) {
    mutateState(id, state -> state.remove("nextFireTime"));
  }

  /**
   * jOOQ 판과 같은 읽기-수정-쓰기 — 트리거를 읽어(꺼진 트리거는 nextFireTime 을 가린 상태) 상태 복사본을 고치고, 바뀌었을 때만 상태 전체를 쓴다. 잠그지
   * 않는다(jOOQ 판과 같다 — 폴러 쓰기와는 원래 마지막 쓰기 승리).
   */
  private void mutateState(Long id, Consumer<Map<String, Object>> mutation) {
    findById(id)
        .ifPresent(
            trigger -> {
              // toResponse 는 상태를 늘 null 아닌 맵으로 준다(NULL·깨진 JSON 은 빈 맵).
              Map<String, Object> before = trigger.triggerState();
              Map<String, Object> updatedState = new HashMap<>(before);
              mutation.accept(updatedState);
              // 같은 값이면 쓰지 않는다 — clear 의 「키가 없으면 쓰지 않음」(jOOQ 판)이고, merge 는 같은 값 덮어쓰기라 결과가 같다.
              if (!updatedState.equals(before)) {
                updateTriggerState(id, updatedState);
              }
            });
  }

  @Override
  public List<PipelineDeleteImpactResponse.DownstreamChainTrigger>
      findEnabledDownstreamChainTriggers(Long upstreamPipelineId) {
    return mapper.selectEnabledDownstreamChain(upstreamPipelineId);
  }

  @Override
  public int disableByUpstreamPipelineId(Long upstreamPipelineId) {
    return mapper.disableByUpstream(upstreamPipelineId, LocalDateTime.now());
  }

  /**
   * 감시 목록에서 데이터셋 하나를 빼고 스냅숏 항목을 지운다(스펙 D5). jOOQ 한 문장의 의미를 자바로 옮겼다:
   *
   * <ul>
   *   <li>대상: DATASET_CHANGE 이고 datasetIds 가 <b>배열</b>이며 그 id 와 숫자로 같은 원소(101·101.0·1.01e2)를 가진
   *       트리거. 후보는 잠가 읽는다(PG 는 @> 로 좁힌 행, Tibero 는 DATASET_CHANGE 전부) — 최종 판정은 자바 {@link
   *       #sameNumber}.
   *   <li>datasetIds: 그 id 와 같은 숫자 원소를 모두 뺀다(jsonb 의 {@code e <> to_jsonb(id)} — 문자열 "101" 은 남는다).
   *       다 빠지면 빈 배열.
   *   <li>trigger_state: lastSnapshot·lastChangeSnapshot·lastRelationSnapshot 객체에서 그 id 키만
   *       지운다({@code #-}).
   *   <li>is_enabled: 원래 켜져 있고 남은 원소가 있을 때만 켜짐({@code is_enabled AND EXISTS(...)}).
   *   <li>updated_at: jOOQ 처럼 자바 시각.
   * </ul>
   *
   * 원소가 하나 이상 빠진 행만 쓰고 센다 — 돌려주는 수는 jOOQ UPDATE 의 행 수와 같다.
   */
  @Override
  public int removeWatchedDataset(Long datasetId) {
    BigDecimal target = BigDecimal.valueOf(datasetId);
    String key = String.valueOf(datasetId);
    LocalDateTime now = LocalDateTime.now();
    int changed = 0;
    for (WatchRow row : mapper.lockWatchingTriggers(datasetId)) {
      ObjectNode config = parseObject(row.configJson());
      // jOOQ 의 jsonb_typeof(config -> 'datasetIds') = 'array' — PG @> 는 스칼라도 맞히므로 여기서 거른다.
      if (config == null || !(config.get("datasetIds") instanceof ArrayNode ids)) {
        continue;
      }
      ArrayNode remaining = objectMapper.createArrayNode();
      for (JsonNode e : ids) {
        if (!sameNumber(e, target)) {
          remaining.add(e);
        }
      }
      if (remaining.size() == ids.size()) {
        // 같은 숫자 원소가 없는 행은 jOOQ 의 @> 대상이 아니므로 쓰지 않는다 — Tibero 는 DATASET_CHANGE 트리거를 모두 잠가 읽으므로
        // (JSON 텍스트 비교는 101.0·1.01e2 를 놓친다) 대상 판정은 여기 자바 숫자 비교가 한다.
        continue;
      }
      config.set("datasetIds", remaining);
      ObjectNode state = parseObject(row.stateJson());
      if (state == null) {
        state = objectMapper.createObjectNode();
      }
      for (String snapshot : SNAPSHOT_KEYS) {
        if (state.get(snapshot) instanceof ObjectNode snap) {
          snap.remove(key);
        }
      }
      boolean enabled = row.isEnabled() && !remaining.isEmpty();
      mapper.updateWatch(row.id(), write(config), write(state), enabled, now);
      changed++;
    }
    return changed;
  }

  @Override
  public Set<Long> findOutputDatasetIds(Long pipelineId) {
    return new HashSet<>(mapper.selectOutputDatasetIds(pipelineId));
  }

  /**
   * 폴러 스냅숏 키 네 개를 지운다(jOOQ {@code trigger_state - 'lastSnapshot' - …}). 행을 잠가 읽고, 지울 키가 있을 때만
   * trigger_state 하나를 쓴다 — 수정 시각·config 는 jOOQ 처럼 건드리지 않는다(스펙 D5).
   */
  @Override
  public void clearPollSnapshot(Long id) {
    WatchRow row = mapper.lockWatchById(id);
    if (row == null) {
      return;
    }
    ObjectNode state = parseObject(row.stateJson());
    if (state == null) {
      return;
    }
    boolean changed = false;
    for (String k : POLL_KEYS) {
      changed |= state.remove(k) != null;
    }
    if (changed) {
      mapper.updateState(id, write(state));
    }
  }

  @Override
  public Optional<TriggerResponse> findByTokenHash(String tokenHash) {
    return Optional.ofNullable(mapper.selectByTokenHash(tokenHash)).map(this::toResponse);
  }

  @Override
  public Optional<TriggerResponse> findByWebhookId(String webhookId) {
    return Optional.ofNullable(mapper.selectByWebhookId(webhookId)).map(this::toResponse);
  }

  @Override
  public boolean hasRunningExecution(Long pipelineId) {
    return mapper.selectRunningExecutionMarker(pipelineId) != null;
  }

  @Override
  public boolean isPipelineActive(Long pipelineId) {
    return Boolean.TRUE.equals(mapper.selectPipelineActive(pipelineId));
  }

  /**
   * 행 → 응답. jOOQ mapToResponse 와 같다 — 꺼진 트리거는 "다음 실행"을 내보내지 않도록 nextFireTime 을 최상위 필드와 상태 원본 모두에서
   * 뺀다(WD-114).
   */
  private TriggerResponse toResponse(TriggerRow r) {
    Map<String, Object> triggerState = parseMap(r.stateJson());
    if (!r.isEnabled()) {
      triggerState.remove("nextFireTime");
    }
    Object nextFireTimeValue = triggerState.get("nextFireTime");
    return new TriggerResponse(
        r.id(),
        r.pipelineId(),
        r.triggerType(),
        r.name(),
        r.description(),
        r.isEnabled(),
        parseMap(r.configJson()),
        triggerState,
        nextFireTimeValue == null ? null : nextFireTimeValue.toString(),
        r.createdBy(),
        r.createdAt(),
        r.updatedAt());
  }

  /** jsonb 의 숫자 같음(101 = 101.0) — 숫자가 아닌 원소(문자열 "101" 등)는 같지 않다. */
  private static boolean sameNumber(JsonNode e, BigDecimal target) {
    return e.isNumber() && e.decimalValue().compareTo(target) == 0;
  }

  /** JSON 텍스트 → 맵. jOOQ parseJsonb 와 같이 NULL·깨진 JSON 은 빈 맵. */
  private Map<String, Object> parseMap(String json) {
    try {
      Map<String, Object> map = PlatformJson.toMap(objectMapper, json, "trigger");
      return map != null ? map : Collections.emptyMap();
    } catch (SerializationException e) {
      return Collections.emptyMap();
    }
  }

  /** JSON 텍스트 → 객체 노드. NULL·객체가 아님·깨졌으면 null(감시 목록 정리 대상에서 뺀다). */
  private ObjectNode parseObject(String json) {
    try {
      return PlatformJson.toObjectNode(objectMapper, json, "trigger");
    } catch (SerializationException e) {
      return null;
    }
  }

  /** 고친 트리 → JSON 텍스트. 실패는 던진다(SerializationException — 고친 값을 잃은 채 쓰지 않는다). */
  private String write(JsonNode node) {
    return PlatformJson.write(objectMapper, node, "trigger");
  }

  /** 맵 → JSON 텍스트. jOOQ toJsonb 와 같이 null·빈 맵·직렬화 실패는 {@code {}}. */
  private String toJson(Map<String, Object> map) {
    if (map == null || map.isEmpty()) {
      return "{}";
    }
    try {
      return PlatformJson.write(objectMapper, map, "trigger");
    } catch (SerializationException e) {
      return "{}";
    }
  }
}
