package com.smartfirehub.apiconnection.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.notification.service.NotificationService;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.util.Map;
import java.util.Optional;
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
  @Mock UserRepository userRepository;

  @InjectMocks ApiConnectionNotifier notifier;

  /** UP→DOWN 전환 시 대시보드 알림 + 감사 로그가 발송되어야 한다. */
  @Test
  void notifyStatusChange_upToDown_sendsDashboardAndAudit() {
    notifier.notifyStatusChange(1L, "API-X", "UP", "DOWN", "HTTP 500", null);

    verify(notificationService)
        .broadcastApiConnectionStatus(eq("API_CONNECTION_DOWN"), contains("API-X"), any());
    verify(auditLogService)
        .log(
            isNull(),
            eq("system"),
            eq("STATUS_CHANGE"),
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
    notifier.notifyStatusChange(1L, "API-X", "DOWN", "UP", null, null);

    verify(notificationService)
        .broadcastApiConnectionStatus(eq("API_CONNECTION_UP"), contains("복구"), any());
    verify(auditLogService)
        .log(
            isNull(),
            eq("system"),
            eq("STATUS_CHANGE"),
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
    notifier.notifyStatusChange(1L, "API-X", null, "UP", null, null);

    verifyNoInteractions(notificationService, auditLogService);
  }

  /** 동일 상태 전환(UP→UP)은 알림 없음. */
  @Test
  void notifyStatusChange_sameStatus_noNotification() {
    notifier.notifyStatusChange(1L, "API-X", "UP", "UP", null, null);

    verifyNoInteractions(notificationService, auditLogService);
  }

  /** DOWN→DOWN 동일 상태 반복도 알림 없음. */
  @Test
  void notifyStatusChange_sameStatusDown_noNotification() {
    notifier.notifyStatusChange(1L, "API-X", "DOWN", "DOWN", "timeout", null);

    verifyNoInteractions(notificationService, auditLogService);
  }

  /** 사람이 누른 경로(「테스트」·전체 새로고침)의 전환은 그 사용자를 감사 행위자로 남긴다(WD-189). 주기 점검(행위자 null)은 위 TC 처럼 system. */
  @Test
  void notifyStatusChange_withActor_auditsUserAsActor() {
    when(userRepository.findById(7L))
        .thenReturn(
            Optional.of(new UserResponse(7L, "alice", "a@example.com", "Alice", true, null)));

    notifier.notifyStatusChange(1L, "API-X", "UP", "DOWN", "HTTP 500", 7L);

    verify(notificationService)
        .broadcastApiConnectionStatus(eq("API_CONNECTION_DOWN"), contains("API-X"), any());
    verify(auditLogService)
        .log(
            eq(7L),
            eq("alice"),
            eq("STATUS_CHANGE"),
            eq("api_connection"),
            eq("1"),
            anyString(),
            isNull(),
            isNull(),
            eq("FAILURE"),
            eq("HTTP 500"),
            eq(Map.of("from", "UP", "to", "DOWN")));
  }

  /** 같은 상태면 사용자 경로여도 알림·감사·사용자 조회 모두 없다. */
  @Test
  void notifyStatusChange_withActor_sameStatus_noNotification() {
    notifier.notifyStatusChange(1L, "API-X", "DOWN", "DOWN", "timeout", 7L);

    verifyNoInteractions(notificationService, auditLogService, userRepository);
  }
}
