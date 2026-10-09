package com.smartfirehub.securitylevel.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.AccessDenialAction;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 보안 등급 감사 기록의 단일 진입점(스펙 §4.6). 흐름 B 소유 — 다른 흐름은 감사 호출을 직접 넣지 않고 가드·게이트를 거친다.
 *
 * <ul>
 *   <li>{@link #record} — 관리 작업(등급 변경·허용 목록 변경 등). 호출자 트랜잭션에 합류해 변경과 감사가 함께 커밋/롤백된다.
 *   <li>{@link #recordDenial}·{@link #recordAccess} — 접근 거부와 감사 등급 접근. 요청이 403/404 로 끝나 호출자 트랜잭션이
 *       롤백돼도 남아야 하므로 <b>REQUIRES_NEW</b> 로 쓴다. 같은 키는 1분에 1건(보충 스펙 §3 폭주 방지) — 인스턴스 메모리 기준이다(다중
 *       인스턴스면 인스턴스 수만큼 중복될 수 있다).
 * </ul>
 *
 * <p>거부·접근 감사 실패는 요청을 실패시키지 않는다(경고 로그만) — 감사는 판정이 아니라 기록이다.
 */
@Component
@Slf4j
public class SecurityAuditRecorder {

  /** 접근 거부 감사 action_type. */
  public static final String ACCESS_DENIED_ACTION = "DATASET_ACCESS_DENIED";

  /** 감사 등급(audit_access) 접근 기록 action_type. */
  public static final String ACCESS_ACTION = "DATASET_ACCESS";

  /** 같은 거부·접근을 하나로 합치는 창. */
  static final Duration COALESCE_WINDOW = Duration.ofMinutes(1);

  /** 감사 등급 접근의 종류(스펙 §4.6 "audit_access 등급만"). */
  public enum AccessKind {
    ROW_VIEW,
    SQL,
    PIPELINE,
    AI
  }

  private final AuditLogService auditLogService;
  private final UserRepository userRepository;
  private final SecurityLevelRepository levelRepository;

  /** 호출자 트랜잭션과 분리된 새 트랜잭션 — 호출자 롤백에 감사 행이 휩쓸리지 않게 한다. */
  private final TransactionTemplate requiresNew;

  /** 최근 1분 안에 기록한 키(값은 의미 없음). 키에 테넌트를 넣어 테넌트 간 합치기를 막는다. */
  private final Cache<String, Boolean> recent;

  /** 운영용 — 시스템 시계로 1분 창을 잰다. */
  @Autowired
  public SecurityAuditRecorder(
      AuditLogService auditLogService,
      UserRepository userRepository,
      SecurityLevelRepository levelRepository,
      PlatformTransactionManager txManager) {
    this(auditLogService, userRepository, levelRepository, txManager, Ticker.systemTicker());
  }

  /** 테스트용 — 시계를 주입해 1분 창을 테스트 안에서 넘긴다. */
  SecurityAuditRecorder(
      AuditLogService auditLogService,
      UserRepository userRepository,
      SecurityLevelRepository levelRepository,
      PlatformTransactionManager txManager,
      Ticker ticker) {
    this.auditLogService = auditLogService;
    this.userRepository = userRepository;
    this.levelRepository = levelRepository;
    TransactionTemplate t = new TransactionTemplate(txManager);
    t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.requiresNew = t;
    this.recent =
        Caffeine.newBuilder()
            .expireAfterWrite(COALESCE_WINDOW)
            .maximumSize(100_000)
            .ticker(ticker)
            .build();
  }

  /** 관리 작업 감사. 호출자 트랜잭션에 합류하므로 변경과 감사가 함께 커밋/롤백된다(기존 계약 그대로). */
  public void record(
      long actorUserId,
      String action,
      String resource,
      String resourceId,
      String description,
      Map<String, Object> metadata) {
    auditLogService.log(
        actorUserId,
        actorName(actorUserId),
        action,
        resource,
        resourceId,
        description,
        null,
        null,
        "SUCCESS",
        null,
        metadata);
  }

  /**
   * 접근 거부 1건. 실제 사유(404 로 가려진 경우에도)를 metadata 에 남긴다 — 감사 화면은 관리자 전용이라 테이블명을 실어도 된다.
   *
   * <p>테넌트 컨텍스트가 없으면 기록하지 않는다 — tenant_id DEFAULT 가 GUC 에서 오므로 쓸 곳이 없다.
   *
   * @param datasetId 거부된 데이터셋(매핑 없는 테이블·다른 스키마면 null)
   * @param tableName SQL 경로에서 거부를 일으킨 테이블 이름(그 외 null)
   */
  public void recordDenial(
      long actorUserId,
      AccessDenialAction action,
      String reasonCode,
      Long datasetId,
      String tableName) {
    Long tenantId = TenantContext.get();
    if (tenantId == null || actorUserId <= 0) {
      return;
    }
    // 합치기 키: (테넌트, 사용자, 동작, 데이터셋, 테이블, 사유)
    String key =
        String.join(
            "|",
            "D",
            tenantId.toString(),
            Long.toString(actorUserId),
            action.name(),
            String.valueOf(datasetId),
            String.valueOf(tableName),
            String.valueOf(reasonCode));
    if (recent.asMap().putIfAbsent(key, Boolean.TRUE) != null) {
      return;
    }
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("action", action.name());
    meta.put("reason", reasonCode);
    if (tableName != null) {
      meta.put("tableName", tableName);
    }
    try {
      requiresNew.executeWithoutResult(
          s ->
              auditLogService.log(
                  actorUserId,
                  actorName(actorUserId),
                  ACCESS_DENIED_ACTION,
                  "dataset",
                  datasetId == null ? null : String.valueOf(datasetId),
                  action.name() + " 거부: " + reasonCode,
                  null,
                  null,
                  "FAILURE",
                  null,
                  meta));
    } catch (RuntimeException e) {
      // 실패한 키는 다음 거부가 다시 시도하도록 비운다.
      recent.invalidate(key);
      log.warn("접근 거부 감사 기록 실패 — 요청 처리는 그대로 진행한다", e);
    }
  }

  /**
   * 감사 등급(audit_access) 데이터셋 접근 기록. 등급 조회 전에 합치기 키를 먼저 본다 — 같은 1분 안의 반복 접근은 DB 를 다시 보지 않는다(감사 등급이
   * 아닌 데이터셋도 1분간 건너뛴다. 그 1분 안에 감사 등급으로 바뀐 데이터셋의 첫 접근은 빠질 수 있다).
   */
  public void recordAccess(long actorUserId, AccessKind kind, Collection<Long> datasetIds) {
    Long tenantId = TenantContext.get();
    if (tenantId == null || actorUserId <= 0 || datasetIds.isEmpty()) {
      return;
    }
    // 1분 안에 처음 보는 데이터셋만 추린다(id → 합치기 키).
    Map<Long, String> fresh = new LinkedHashMap<>();
    for (Long id : datasetIds) {
      if (id == null) {
        continue;
      }
      String key =
          String.join(
              "|",
              "A",
              tenantId.toString(),
              Long.toString(actorUserId),
              kind.name(),
              id.toString());
      if (recent.asMap().putIfAbsent(key, Boolean.TRUE) == null) {
        fresh.put(id, key);
      }
    }
    if (fresh.isEmpty()) {
      return;
    }
    try {
      requiresNew.executeWithoutResult(
          s -> {
            Set<Long> audited = levelRepository.findAuditedDatasetIds(fresh.keySet());
            String name = actorName(actorUserId);
            for (Long id : fresh.keySet()) {
              // 감사 등급인 데이터셋만 남긴다(스펙 §4.6).
              if (audited.contains(id)) {
                auditLogService.log(
                    actorUserId,
                    name,
                    ACCESS_ACTION,
                    "dataset",
                    String.valueOf(id),
                    kind.name(),
                    null,
                    null,
                    "SUCCESS",
                    null,
                    Map.of("kind", kind.name()));
              }
            }
          });
    } catch (RuntimeException e) {
      fresh.values().forEach(recent::invalidate);
      log.warn("감사 등급 접근 기록 실패 — 요청 처리는 그대로 진행한다", e);
    }
  }

  /** 감사 행의 사용자 이름(없으면 "unknown"). */
  private String actorName(long actorUserId) {
    return userRepository.findById(actorUserId).map(UserResponse::username).orElse("unknown");
  }
}
