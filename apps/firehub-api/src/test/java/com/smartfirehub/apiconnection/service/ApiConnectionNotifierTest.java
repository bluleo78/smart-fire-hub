package com.smartfirehub.apiconnection.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** ApiConnectionNotifier 단위 테스트. 상태 전환 조건(prev=null, 동일, 전환)에 따른 알림 발송 여부를 검증한다. */
@ExtendWith(MockitoExtension.class)
class ApiConnectionNotifierTest {

  @Mock NotificationService notificationService;
  @Mock AuditLogService auditLogService;

  @InjectMocks ApiConnectionNotifier notifier;

  /** UP→DOWN 전환 시 대시보드 알림 + 감사 로그가 발송되어야 한다. */
  @Test
  void notifyStatusChange_upToDown_sendsDashboardAndAudit() {
    notifier.notifyStatusChange(1L, "API-X", "UP", "DOWN", "HTTP 500");

    verify(notificationService)
        .broadcastApiConnectionStatus(eq("API_CONNECTION_DOWN"), contains("API-X"), any());
    verify(auditLogService)
        .log(
            isNull(),
            anyString(),
            anyString(),
            eq("api_connection"),
            eq("1"),
            anyString(),
            isNull(),
            isNull(),
            eq("FAILURE"),
            eq("HTTP 500"),
            any());
  }

  /** DOWN→UP 복구 시 복구 알림이 발송되어야 한다. */
  @Test
  void notifyStatusChange_downToUp_sendsRecovery() {
    notifier.notifyStatusChange(1L, "API-X", "DOWN", "UP", null);

    verify(notificationService)
        .broadcastApiConnectionStatus(eq("API_CONNECTION_UP"), contains("복구"), any());
    verify(auditLogService)
        .log(
            isNull(),
            anyString(),
            anyString(),
            eq("api_connection"),
            eq("1"),
            anyString(),
            isNull(),
            isNull(),
            eq("SUCCESS"),
            isNull(),
            any());
  }

  /** 최초 체크(prev=null)는 알림 없음 — 초기 상태 설정은 이벤트가 아니다. */
  @Test
  void notifyStatusChange_firstCheck_noNotification() {
    notifier.notifyStatusChange(1L, "API-X", null, "UP", null);

    verifyNoInteractions(notificationService, auditLogService);
  }

  /** 동일 상태 전환(UP→UP)은 알림 없음. */
  @Test
  void notifyStatusChange_sameStatus_noNotification() {
    notifier.notifyStatusChange(1L, "API-X", "UP", "UP", null);

    verifyNoInteractions(notificationService, auditLogService);
  }

  /** DOWN→DOWN 동일 상태 반복도 알림 없음. */
  @Test
  void notifyStatusChange_sameStatusDown_noNotification() {
    notifier.notifyStatusChange(1L, "API-X", "DOWN", "DOWN", "timeout");

    verifyNoInteractions(notificationService, auditLogService);
  }
}
