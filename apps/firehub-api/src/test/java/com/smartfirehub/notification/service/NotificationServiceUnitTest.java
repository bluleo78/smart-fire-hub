package com.smartfirehub.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.*;

import com.smartfirehub.notification.dto.NotificationEvent;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * NotificationService 단위 테스트.
 *
 * <p>Spring 컨텍스트 없이 Mockito만으로 NotificationService 비즈니스 로직을 검증한다. SseEmitterRegistry를 @Mock으로
 * 주입하고 @InjectMocks로 대상 서비스를 생성한다. @Async 어노테이션은 단위 테스트에서 무시되어 동기적으로 실행되므로 Awaitility 불필요.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceUnitTest {

  /** SseEmitterRegistry 모킹 — SSE 연결 상태에 의존하지 않고 순수 로직만 검증 */
  @Mock private SseEmitterRegistry registry;

  /** 테스트 대상 서비스 — @Mock이 자동 주입된다 */
  @InjectMocks private NotificationService notificationService;

  // =========================================================================
  // onPipelineCompleted — SUCCESS
  // =========================================================================

  /**
   * 정상: COMPLETED 상태 이벤트 수신 시 registry.broadcast()가 올바른 인자로 호출되어야 한다. severity=INFO,
   * eventType=PIPELINE_COMPLETED, title="Pipeline Completed"
   */
  @Test
  void onPipelineCompleted_success_broadcastsWithCorrectEvent() {
    PipelineCompletedEvent event = new PipelineCompletedEvent(10L, 100L, "COMPLETED", 1L);

    notificationService.onPipelineCompleted(event);

    ArgumentCaptor<NotificationEvent> captor = forClass(NotificationEvent.class);
    verify(registry).broadcast(eq(1L), captor.capture());

    NotificationEvent notification = captor.getValue();
    assertThat(notification.eventType()).isEqualTo("PIPELINE_COMPLETED");
    assertThat(notification.severity()).isEqualTo("INFO");
    assertThat(notification.title()).isEqualTo("Pipeline Completed");
    assertThat(notification.entityType()).isEqualTo("PIPELINE");
    assertThat(notification.entityId()).isEqualTo(10L);
    assertThat(notification.metadata()).containsEntry("executionId", 100L);
    assertThat(notification.metadata()).containsEntry("status", "COMPLETED");
    assertThat(notification.id()).isNotBlank();
    assertThat(notification.occurredAt()).isNotNull();
  }

  // =========================================================================
  // onPipelineCompleted — FAILED
  // =========================================================================

  /** 정상: FAILED 상태 이벤트 수신 시 severity=WARNING, eventType=PIPELINE_FAILED여야 한다. */
  @Test
  void onPipelineCompleted_failed_broadcastsWithWarning() {
    PipelineCompletedEvent event = new PipelineCompletedEvent(20L, 200L, "FAILED", 2L);

    notificationService.onPipelineCompleted(event);

    ArgumentCaptor<NotificationEvent> captor = forClass(NotificationEvent.class);
    verify(registry).broadcast(eq(2L), captor.capture());

    NotificationEvent notification = captor.getValue();
    assertThat(notification.eventType()).isEqualTo("PIPELINE_FAILED");
    assertThat(notification.severity()).isEqualTo("WARNING");
    assertThat(notification.title()).isEqualTo("Pipeline Failed");
  }

  // =========================================================================
  // onPipelineCompleted — createdBy null
  // =========================================================================

  /** 엣지 케이스: createdBy가 null이면 registry와 상호작용 없이 조기 반환해야 한다. 사용자 특정 불가로 알림 전송을 스킵한다. */
  @Test
  void onPipelineCompleted_nullCreatedBy_noRegistryInteraction() {
    PipelineCompletedEvent event = new PipelineCompletedEvent(30L, 300L, "COMPLETED", null);

    notificationService.onPipelineCompleted(event);

    verifyNoInteractions(registry);
  }

  // =========================================================================
  // notifyImportCompleted — success=true
  // =========================================================================

  /** 정상: 임포트 성공 시 IMPORT_COMPLETED 이벤트 + INFO severity로 broadcast되어야 한다. */
  @Test
  void notifyImportCompleted_success_broadcastsImportCompleted() {
    notificationService.notifyImportCompleted(1L, 100L, "Sales Data", true);

    ArgumentCaptor<NotificationEvent> captor = forClass(NotificationEvent.class);
    verify(registry).broadcast(eq(1L), captor.capture());

    NotificationEvent notification = captor.getValue();
    assertThat(notification.eventType()).isEqualTo("IMPORT_COMPLETED");
    assertThat(notification.severity()).isEqualTo("INFO");
    assertThat(notification.title()).isEqualTo("Import Completed");
    assertThat(notification.entityType()).isEqualTo("DATASET");
    assertThat(notification.entityId()).isEqualTo(100L);
    assertThat(notification.metadata()).containsEntry("datasetName", "Sales Data");
    assertThat(notification.description()).contains("Sales Data");
  }

  // =========================================================================
  // notifyImportCompleted — success=false
  // =========================================================================

  /** 정상: 임포트 실패 시 IMPORT_FAILED 이벤트 + WARNING severity로 broadcast되어야 한다. */
  @Test
  void notifyImportCompleted_failure_broadcastsImportFailed() {
    notificationService.notifyImportCompleted(2L, 200L, "Bad Data", false);

    ArgumentCaptor<NotificationEvent> captor = forClass(NotificationEvent.class);
    verify(registry).broadcast(eq(2L), captor.capture());

    NotificationEvent notification = captor.getValue();
    assertThat(notification.eventType()).isEqualTo("IMPORT_FAILED");
    assertThat(notification.severity()).isEqualTo("WARNING");
    assertThat(notification.title()).isEqualTo("Import Failed");
    assertThat(notification.description()).contains("Bad Data");
  }

  // =========================================================================
  // notifyDatasetChanged
  // =========================================================================

  /**
   * 정상: 데이터셋 변경 알림은 특정 사용자가 아닌 전체 브로드캐스트여야 한다. registry.broadcastAll()이 DATASET_CHANGED 이벤트로 호출되고
   * registry.broadcast()는 호출되지 않아야 한다.
   */
  @Test
  void notifyDatasetChanged_callsBroadcastAll() {
    notificationService.notifyDatasetChanged(50L, "Geo Dataset");

    ArgumentCaptor<NotificationEvent> captor = forClass(NotificationEvent.class);
    verify(registry).broadcastAll(captor.capture());
    verify(registry, never()).broadcast(any(), any());

    NotificationEvent notification = captor.getValue();
    assertThat(notification.eventType()).isEqualTo("DATASET_CHANGED");
    assertThat(notification.severity()).isEqualTo("INFO");
    assertThat(notification.entityType()).isEqualTo("DATASET");
    assertThat(notification.entityId()).isEqualTo(50L);
    assertThat(notification.metadata()).containsEntry("datasetName", "Geo Dataset");
    assertThat(notification.title()).isEqualTo("Dataset Changed");
  }

  // =========================================================================
  // NotificationEvent 구조 검증
  // =========================================================================

  /** 정상: 각 호출마다 고유한 UUID id가 생성되어야 한다. 동일 메서드를 두 번 호출해도 id가 서로 달라야 한다. */
  @Test
  void notifyImportCompleted_eachCallGeneratesUniqueId() {
    notificationService.notifyImportCompleted(1L, 100L, "Dataset A", true);
    notificationService.notifyImportCompleted(1L, 101L, "Dataset B", true);

    ArgumentCaptor<NotificationEvent> captor = forClass(NotificationEvent.class);
    verify(registry, times(2)).broadcast(eq(1L), captor.capture());

    assertThat(captor.getAllValues().get(0).id()).isNotEqualTo(captor.getAllValues().get(1).id());
  }
}
