package com.smartfirehub.notification.service;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.dto.NotificationEvent;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.DatasetAction;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
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

  @Async
  @EventListener
  public void onPipelineCompleted(PipelineCompletedEvent event) {
    if (event.createdBy() == null) {
      log.debug(
          "PipelineCompletedEvent for pipeline {} has no createdBy, skipping notification",
          event.pipelineId());
      return;
    }

    boolean failed = "FAILED".equals(event.status());
    String severity = failed ? "WARNING" : "INFO";
    String title = failed ? "Pipeline Failed" : "Pipeline Completed";
    String description =
        failed
            ? "Pipeline execution #" + event.executionId() + " failed."
            : "Pipeline execution #" + event.executionId() + " completed successfully.";

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
    String title = success ? "Import Completed" : "Import Failed";
    String description =
        success
            ? "Data import into dataset '" + datasetName + "' completed successfully."
            : "Data import into dataset '" + datasetName + "' failed.";

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
   * API 연결 상태 변화를 <b>현재 테넌트</b>(헬스체크 스케줄러가 테넌트 순회 중 세운 TenantContext)의 연결에만 대시보드 알림으로 보낸다(WD-19).
   * 예전 broadcastAll 은 모든 테넌트 접속자에게 API 연결 이름·오류 메시지를 보냈다. 테넌트를 모르면 보내지 않는다(fail-closed). 관리자 전용 표시
   * 여부는 기존대로 프론트엔드가 거른다.
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
            isDown ? "API Connection Down" : "API Connection Up",
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
    registry.broadcastToTenant(tenantId, notification, userId -> true);
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
            "Dataset Changed",
            "Dataset '" + datasetName + "' has been updated.",
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
