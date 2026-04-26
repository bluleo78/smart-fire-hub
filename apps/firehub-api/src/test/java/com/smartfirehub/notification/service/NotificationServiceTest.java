package com.smartfirehub.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.*;

import com.smartfirehub.notification.dto.NotificationEvent;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * NotificationService 통합 테스트.
 *
 * <p>onPipelineCompleted, notifyImportCompleted, notifyDatasetChanged 3개 public 메서드 커버.
 * SseEmitterRegistry를 @MockitoBean으로 모킹하여 broadcast 호출 및 NotificationEvent 내용을 검증한다. @Async
 * 메서드(onPipelineCompleted)는 직접 호출하여 동기적으로 검증한다.
 */
class NotificationServiceTest extends IntegrationTestBase {

  @Autowired private NotificationService notificationService;

  /** SseEmitterRegistry는 SSE 연결 상태에 의존하므로 모킹하여 순수 비즈니스 로직만 검증 */
  @MockitoBean private SseEmitterRegistry registry;

  // =========================================================================
  // onPipelineCompleted — 성공 케이스
  // =========================================================================

  /**
   * 정상: 파이프라인 COMPLETED 이벤트 수신 시 registry.broadcast()가 호출되고 이벤트 타입이 PIPELINE_COMPLETED, severity가
   * INFO여야 한다.
   */
  @Test
  void onPipelineCompleted_success_broadcastsPipelineCompletedEvent() {
    PipelineCompletedEvent event = new PipelineCompletedEvent(10L, 100L, "COMPLETED", 1L);

    // @Async 메서드이므로 Awaitility로 비동기 완료를 대기한다
    notificationService.onPipelineCompleted(event);

    ArgumentCaptor<NotificationEvent> captor = forClass(NotificationEvent.class);
    await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(() -> verify(registry).broadcast(eq(1L), captor.capture()));

    NotificationEvent notification = captor.getValue();
    assertThat(notification.eventType()).isEqualTo("PIPELINE_COMPLETED");
    assertThat(notification.severity()).isEqualTo("INFO");
    assertThat(notification.entityType()).isEqualTo("PIPELINE");
    assertThat(notification.entityId()).isEqualTo(10L);
    assertThat(notification.metadata()).containsEntry("executionId", 100L);
    assertThat(notification.metadata()).containsEntry("status", "COMPLETED");
    assertThat(notification.id()).isNotBlank();
    assertThat(notification.occurredAt()).isNotNull();
  }

  // =========================================================================
  // onPipelineCompleted — 실패 케이스
  // =========================================================================

  /** 정상: 파이프라인 FAILED 이벤트 수신 시 이벤트 타입이 PIPELINE_FAILED, severity가 WARNING이어야 한다. */
  @Test
  void onPipelineCompleted_failed_broadcastsPipelineFailedEvent() {
    PipelineCompletedEvent event = new PipelineCompletedEvent(20L, 200L, "FAILED", 2L);

    notificationService.onPipelineCompleted(event);

    ArgumentCaptor<NotificationEvent> captor = forClass(NotificationEvent.class);
    await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(() -> verify(registry).broadcast(eq(2L), captor.capture()));

    NotificationEvent notification = captor.getValue();
    assertThat(notification.eventType()).isEqualTo("PIPELINE_FAILED");
    assertThat(notification.severity()).isEqualTo("WARNING");
    assertThat(notification.title()).isEqualTo("Pipeline Failed");
  }

  /**
   * 엣지 케이스: createdBy가 null인 이벤트는 registry.broadcast()가 호출되지 않아야 한다. (알림을 보낼 대상 사용자를 특정할 수 없으므로
   * 스킵) @Async이므로 충분히 대기한 후 interaction이 없음을 검증한다.
   */
  @Test
  void onPipelineCompleted_nullCreatedBy_doesNotBroadcast() throws InterruptedException {
    PipelineCompletedEvent event = new PipelineCompletedEvent(30L, 300L, "COMPLETED", null);

    notificationService.onPipelineCompleted(event);

    // @Async 실행이 완료될 수 있도록 잠시 대기 후 no-interaction 검증
    Thread.sleep(500);
    verifyNoInteractions(registry);
  }

  // =========================================================================
  // notifyImportCompleted
  // =========================================================================

  /** 정상: 임포트 성공 시 registry.broadcast()가 IMPORT_COMPLETED 이벤트로 호출되어야 한다. */
  @Test
  void notifyImportCompleted_success_broadcastsImportCompletedEvent() {
    notificationService.notifyImportCompleted(1L, 100L, "Sales Data", true);

    ArgumentCaptor<NotificationEvent> captor = forClass(NotificationEvent.class);
    verify(registry).broadcast(eq(1L), captor.capture());

    NotificationEvent notification = captor.getValue();
    assertThat(notification.eventType()).isEqualTo("IMPORT_COMPLETED");
    assertThat(notification.severity()).isEqualTo("INFO");
    assertThat(notification.entityType()).isEqualTo("DATASET");
    assertThat(notification.entityId()).isEqualTo(100L);
    assertThat(notification.metadata()).containsEntry("datasetName", "Sales Data");
    assertThat(notification.title()).isEqualTo("Import Completed");
  }

  /** 정상: 임포트 실패 시 registry.broadcast()가 IMPORT_FAILED 이벤트 + WARNING severity로 호출되어야 한다. */
  @Test
  void notifyImportCompleted_failure_broadcastsImportFailedEvent() {
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
   * 정상: 데이터셋 변경 알림 시 registry.broadcastAll()이 DATASET_CHANGED 이벤트로 호출되어야 한다. 특정 사용자가 아닌 전체
   * 브로드캐스트이므로 broadcastAll()을 사용해야 한다.
   */
  @Test
  void notifyDatasetChanged_broadcastsToAll() {
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

  /** 정상: 각 NotificationEvent는 고유한 UUID id를 가져야 한다. 두 번 호출 시 서로 다른 id가 생성되어야 한다. */
  @Test
  void notifyImportCompleted_eachCallGeneratesUniqueId() {
    notificationService.notifyImportCompleted(1L, 100L, "Dataset A", true);
    notificationService.notifyImportCompleted(1L, 101L, "Dataset B", true);

    ArgumentCaptor<NotificationEvent> captor = forClass(NotificationEvent.class);
    verify(registry, times(2)).broadcast(eq(1L), captor.capture());

    assertThat(captor.getAllValues().get(0).id()).isNotEqualTo(captor.getAllValues().get(1).id());
  }
}
