package com.smartfirehub.notification.service;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.dto.NotificationEvent;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.DatasetAction;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

  private final SseEmitterRegistry registry;
  // 데이터셋 변경 알림 수신자 판정(보안 등급) — 그 데이터셋을 볼 수 있는 같은 테넌트 사용자에게만 보낸다.
  private final DatasetAccessGuard datasetAccessGuard;
  private final ClearanceResolver clearanceResolver;
  // API 연결 상태 알림 수신자 판정(WD-187) — 이 워크스페이스 ACTIVE 멤버 중 apiconnection:read 를 가진 사람에게만 보낸다.
  private final PermissionService permissionService;

  /** API 연결 상태 알림을 받을 수 있는 권한(WD-187) — API 연결 목록·상세를 볼 수 있는 사람이면 그 연결의 상태 변화도 볼 수 있다. */
  static final String API_CONNECTION_READ = "apiconnection:read";

  @Async
  @EventListener
  public void onPipelineCompleted(PipelineCompletedEvent event) {
    if (event.createdBy() == null) {
      log.debug(
          "PipelineCompletedEvent for pipeline {} has no createdBy, skipping notification",
          event.pipelineId());
      return;
    }

    // 취소된 실행은 「완료」로 알리지 않는다(WD-153) — 러너는 취소 시 이 이벤트를 발행하지 않지만, 아래 문구가 FAILED 가 아니면 전부 「완료」라
    // 계약이 깨지면 취소가 완료로 잘못 알려진다. 그 방어다.
    if ("CANCELLED".equals(event.status())) {
      return;
    }
    boolean failed = "FAILED".equals(event.status());
    String severity = failed ? "WARNING" : "INFO";
    // 사용자 화면에 토스트·알림으로 그대로 보이는 문구라 한국어로 쓴다(WD-97)
    String title = failed ? "파이프라인 실행 실패" : "파이프라인 실행 완료";
    String description =
        failed
            ? "파이프라인 실행(#" + event.executionId() + ")이 실패했습니다."
            : "파이프라인 실행(#" + event.executionId() + ")이 완료되었습니다.";

    NotificationEvent notification =
        new NotificationEvent(
            UUID.randomUUID().toString(),
            failed ? "PIPELINE_FAILED" : "PIPELINE_COMPLETED",
            severity,
            title,
            description,
            "PIPELINE",
            event.pipelineId(),
            Map.of("executionId", event.executionId(), "status", event.status()),
            LocalDateTime.now());

    registry.broadcast(event.createdBy(), notification);
  }

  public void notifyImportCompleted(
      Long userId, Long datasetId, String datasetName, boolean success) {
    String severity = success ? "INFO" : "WARNING";
    String eventType = success ? "IMPORT_COMPLETED" : "IMPORT_FAILED";
    // 실패는 WARNING 이라 우하단 토스트로 뜬다 — 같은 화면의 임포트 다이얼로그(「임포트 실패」)와 말을 맞춘다(WD-97)
    String title = success ? "임포트 완료" : "임포트 실패";
    String description =
        success
            ? "'" + datasetName + "' 데이터셋 임포트가 완료되었습니다."
            : "'" + datasetName + "' 데이터셋 임포트에 실패했습니다.";

    NotificationEvent notification =
        new NotificationEvent(
            UUID.randomUUID().toString(),
            eventType,
            severity,
            title,
            description,
            "DATASET",
            datasetId,
            Map.of("datasetName", datasetName),
            LocalDateTime.now());

    registry.broadcast(userId, notification);
  }

  /** 문서 인제스션 완료/실패 알림. notifyImportCompleted 와 동일한 NotificationEvent/broadcast 형태를 따른다. */
  public void notifyDocumentIngested(
      Long userId, Long datasetId, String fileName, boolean success) {
    NotificationEvent notification =
        new NotificationEvent(
            UUID.randomUUID().toString(),
            success ? "DOCUMENT_INGESTED" : "DOCUMENT_INGEST_FAILED",
            success ? "INFO" : "WARNING",
            success ? "문서 처리 완료" : "문서 처리 실패",
            "문서 '" + fileName + "' 처리가 " + (success ? "완료되었습니다." : "실패했습니다."),
            "DATASET",
            datasetId,
            Map.of("fileName", fileName),
            LocalDateTime.now());

    registry.broadcast(userId, notification);
  }

  /**
   * 트리거가 「만든 사람」 자격 문제(정지·제거·권한 회수)로 실행되지 않았음을 알린다(WD-169). 트리거는 만든 사람 이름으로 돌기 때문에 그 사람이 정지되거나 권한을
   * 잃으면 매 주기 조용히 건너뛰었다 — 파이프라인 주인과 워크스페이스 관리자가 모르는 채 데이터가 멈춰 있었다.
   *
   * <p><b>그 테넌트 연결로만</b> 보낸다({@link SseEmitterRegistry#broadcastToTenant}) — 같은 사람이 다른 워크스페이스 화면으로
   * 연 연결에는 이 워크스페이스의 트리거·파이프라인 이름이 가지 않는다. 수신자 선정·중복 억제는 호출자({@code TriggerService})가 한다.
   *
   * @param recipients 받을 사용자 id(파이프라인 주인·활성 관리자 중 이 워크스페이스 ACTIVE 멤버)
   * @param cause 토스트 본문에 붙일 짧은 원인(예: 「트리거를 만든 사람이 이 워크스페이스에서 정지·제거되었습니다」)
   * @param reason 트리거 이벤트에 남긴 것과 같은 사유 문구(metadata 로만 싣는다)
   */
  public void notifyTriggerSkipped(
      long tenantId,
      Set<Long> recipients,
      Long pipelineId,
      Long triggerId,
      String triggerName,
      String pipelineName,
      String cause,
      String reason) {
    if (recipients.isEmpty()) {
      return;
    }
    // 토스트 본문 — 화면에 그대로 보이므로 한국어로, 어떤 트리거가 왜 멈췄는지 한 줄로 알게 한다
    String description =
        "트리거 「" + triggerName + "」(파이프라인 「" + pipelineName + "」)가 실행되지 않았습니다 — " + cause;
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("triggerId", triggerId);
    metadata.put("triggerName", triggerName);
    metadata.put("pipelineName", pipelineName);
    metadata.put("reason", reason);
    NotificationEvent notification =
        new NotificationEvent(
            UUID.randomUUID().toString(),
            "TRIGGER_SKIPPED",
            // WARNING 이라 웹 알림 스트림이 별도 분기 없이 경고 토스트로 띄운다(useNotificationStream 의 severity 블록)
            "WARNING",
            "트리거 실행 건너뜀",
            description,
            "PIPELINE",
            pipelineId,
            metadata,
            LocalDateTime.now());
    registry.broadcastToTenant(tenantId, notification, recipients::contains);
  }

  /**
   * API 연결 상태 변화를 <b>현재 테넌트</b>(헬스체크 스케줄러가 테넌트 순회 중 세운 TenantContext)의 연결에만 대시보드 알림으로 보낸다(WD-19).
   * 예전 broadcastAll 은 모든 테넌트 접속자에게 API 연결 이름·오류 메시지를 보냈다. 테넌트를 모르면 보내지 않는다(fail-closed).
   *
   * <p>그 테넌트 안에서도 <b>이 워크스페이스 ACTIVE 멤버(계정 활성) 중 {@code apiconnection:read} 를 가진 사람</b>에게만
   * 보낸다(WD-187). 예전에는 같은 테넌트 연결 전원에게 보내, 그 권한이 없는 멤버(사용자 지정 역할)·정지된 멤버·제거된 멤버의 열린 연결에도 연결 이름·오류
   * 문구(호출 URL 포함)가 갔다 — 웹은 권한을 보지 않고 토스트를 띄운다. 수신 가능 집합은 보낼 때마다 한 번의 질의로 만든다(권한 회수·정지가 다음 알림부터 바로
   * 반영되고, 접속자마다 조회하지 않는다). 그 조회가 실패하면 보내지 않는다(fail-closed) — 상태 변화는 API 연결 화면·감사 로그에 그대로 남는다.
   *
   * @param eventType "API_CONNECTION_DOWN" 또는 "API_CONNECTION_UP"
   * @param message 알림 본문
   * @param metadata 추가 컨텍스트 (apiConnectionId 등)
   */
  public void broadcastApiConnectionStatus(
      String eventType, String message, Map<String, Object> metadata) {
    boolean isDown = "API_CONNECTION_DOWN".equals(eventType);
    NotificationEvent notification =
        new NotificationEvent(
            UUID.randomUUID().toString(),
            eventType,
            isDown ? "WARNING" : "INFO",
            // 끊김은 WARNING 이라 토스트로 뜬다 — 본문(ApiConnectionNotifier)처럼 제목도 한국어로(WD-97)
            isDown ? "API 연결 끊김" : "API 연결 복구",
            message,
            "API_CONNECTION",
            metadata != null ? (Long) metadata.getOrDefault("apiConnectionId", null) : null,
            metadata != null ? metadata : Map.of(),
            LocalDateTime.now());

    Long tenantId = TenantContext.get();
    if (tenantId == null) {
      log.warn("broadcastApiConnectionStatus without tenant context — skipped {}", eventType);
      return;
    }
    Set<Long> recipients;
    try {
      recipients =
          permissionService.getActiveMemberIdsWithPermission(tenantId, API_CONNECTION_READ);
    } catch (RuntimeException e) {
      log.warn("API 연결 상태 알림 수신자 조회 실패 — 보내지 않음 {}: {}", eventType, e.getMessage());
      return;
    }
    if (recipients.isEmpty()) {
      return;
    }
    registry.broadcastToTenant(tenantId, notification, recipients::contains);
  }

  /**
   * 데이터셋 변경 알림. 예전에는 broadcastAll 로 <b>모든 테넌트의 모든 접속자</b>에게 데이터셋 이름을 보냈다. 이제 현재 테넌트(폴러가 세운
   * TenantContext)의 연결 중 그 데이터셋을 VIEW 할 수 있는 사용자에게만 보낸다. 테넌트를 모르면 보내지 않는다(fail-closed). 웹 소비자는 쿼리
   * 무효화만 하므로 페이로드 계약은 그대로다.
   */
  public void notifyDatasetChanged(Long datasetId, String datasetName) {
    notifyDatasetChanged(datasetId, datasetName, new HashMap<>());
  }

  /**
   * 여러 데이터셋 알림을 한 번에 보낼 때 수신자 자격을 재사용한다(리뷰 M2 — 데이터셋 K개 × 접속자 N명마다 자격을 다시 계산하지 않게).
   *
   * @param clearanceCache userId → 자격. 같은 폴링 회차·같은 테넌트 안에서만 공유할 것(자격은 테넌트별이다)
   */
  public void notifyDatasetChanged(
      Long datasetId, String datasetName, Map<Long, Clearance> clearanceCache) {
    Long tenantId = TenantContext.get();
    if (tenantId == null) {
      log.warn("notifyDatasetChanged without tenant context — skipped datasetId={}", datasetId);
      return;
    }
    NotificationEvent notification =
        new NotificationEvent(
            UUID.randomUUID().toString(),
            "DATASET_CHANGED",
            "INFO",
            "데이터셋 변경",
            "'" + datasetName + "' 데이터셋이 변경되었습니다.",
            "DATASET",
            datasetId,
            Map.of("datasetName", datasetName),
            LocalDateTime.now());

    registry.broadcastToTenant(
        tenantId,
        notification,
        userId ->
            datasetAccessGuard
                .check(
                    clearanceCache.computeIfAbsent(userId, clearanceResolver::resolve),
                    datasetId,
                    DatasetAction.VIEW,
                    null)
                .allowed());
  }
}
