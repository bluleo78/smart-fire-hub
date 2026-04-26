package com.smartfirehub.apiconnection.service;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.notification.service.NotificationService;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * API 연결 상태 전환 시 알림(대시보드 브로드캐스트) + 감사 로그를 디스패치한다.
 *
 * <p>규칙:
 *
 * <ul>
 *   <li>최초 체크(prev=null)는 알림 없음 — 초기 상태 설정은 이벤트가 아니다.
 *   <li>상태 동일 전환(UP→UP, DOWN→DOWN)은 알림 없음.
 *   <li>상태 전환 시 대시보드 브로드캐스트 + 감사 로그 기록.
 * </ul>
 *
 * <p>TODO(proactive-chat): ProactiveJobService에 관리자 대상 단순 메시지 푸시 API가 없어 Chat 메시지 디스패치는 미구현 상태다.
 * ProactiveJobService가 "push to admins" 메서드를 지원하면 여기에 추가할 것.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiConnectionNotifier {

  private final NotificationService notificationService;
  private final AuditLogService auditLogService;

  /**
   * API 연결 상태 변화를 감지하여 알림 및 감사 로그를 디스패치한다.
   *
   * @param id API 연결 ID
   * @param name API 연결 이름 (알림 메시지에 포함)
   * @param prev 이전 상태 (null이면 최초 체크 → 알림 없음)
   * @param curr 현재 상태 ("UP" 또는 "DOWN")
   * @param errorMessage 오류 상세 메시지 (curr="DOWN"일 때 사용)
   */
  public void notifyStatusChange(
      Long id, String name, String prev, String curr, String errorMessage) {
    // 최초 체크 또는 상태 동일 시 무시
    if (prev == null || Objects.equals(prev, curr)) {
      return;
    }

    boolean isDown = "DOWN".equals(curr);
    String eventType = isDown ? "API_CONNECTION_DOWN" : "API_CONNECTION_UP";

    // 대시보드 알림 메시지 구성
    String message =
        isDown
            ? String.format(
                "API 연결 '%s'이(가) 응답하지 않습니다 (%s)",
                name, errorMessage != null ? errorMessage : "상태 이상")
            : String.format("API 연결 '%s'이(가) 복구되었습니다", name);

    // 모든 연결 사용자(관리자)에게 대시보드 SSE 브로드캐스트
    notificationService.broadcastApiConnectionStatus(
        eventType, message, Map.of("apiConnectionId", id));

    log.info("API connection status changed: id={}, name={}, {}→{}", id, name, prev, curr);

    // 상태 전환 이벤트를 감사 로그에 기록 — 관리자 추적 및 규정 준수 목적
    auditLogService.log(
        null, // userId: 시스템 발생 이벤트 — 특정 사용자 없음
        "system",
        "STATUS_CHANGE",
        "api_connection",
        String.valueOf(id),
        message,
        null, // ipAddress: 시스템 이벤트 N/A
        null, // userAgent: 시스템 이벤트 N/A
        isDown ? "FAILURE" : "SUCCESS",
        errorMessage,
        Map.of("from", prev, "to", curr));
  }
}
