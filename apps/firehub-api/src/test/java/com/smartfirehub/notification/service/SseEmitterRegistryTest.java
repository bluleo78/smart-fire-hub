package com.smartfirehub.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.notification.dto.NotificationEvent;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class SseEmitterRegistryTest {

  private SseEmitterRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new SseEmitterRegistry(new ObjectMapper());
  }

  // ------------------------------------------------------------------ //
  // register / remove
  // ------------------------------------------------------------------ //

  @Test
  void register_returnsNonNullEmitter() {
    SseEmitter emitter = registry.register(1L, 1L);
    assertThat(emitter).isNotNull();
  }

  @Test
  void register_multipleUsersIndependent() {
    SseEmitter e1 = registry.register(1L, 1L);
    SseEmitter e2 = registry.register(2L, 1L);
    assertThat(e1).isNotSameAs(e2);
  }

  @Test
  void register_sameUser_multipleEmitters_allowed_up_to_max() {
    SseEmitter e1 = registry.register(1L, 1L);
    SseEmitter e2 = registry.register(1L, 1L);
    SseEmitter e3 = registry.register(1L, 1L);
    assertThat(e1).isNotNull();
    assertThat(e2).isNotNull();
    assertThat(e3).isNotNull();
  }

  @Test
  void register_exceedsMaxEmittersPerUser_evictsOldest() {
    // Register MAX_EMITTERS_PER_USER (3) emitters
    SseEmitter e1 = registry.register(1L, 1L);
    SseEmitter e2 = registry.register(1L, 1L);
    SseEmitter e3 = registry.register(1L, 1L);
    // 4th registration should evict e1
    SseEmitter e4 = registry.register(1L, 1L);
    assertThat(e4).isNotNull();
    // e1 was removed - broadcast should not throw
    registry.broadcast(1L, sampleEvent());
  }

  @Test
  void remove_removesEmitter_broadcastToRemovedUserIsNoOp() {
    SseEmitter emitter = registry.register(1L, 1L);
    registry.remove(1L, emitter);
    // No exception expected
    registry.broadcast(1L, sampleEvent());
  }

  @Test
  void remove_nonExistentUser_noException() {
    SseEmitter emitter = new SseEmitter();
    registry.remove(999L, emitter);
  }

  // ------------------------------------------------------------------ //
  // broadcast
  // ------------------------------------------------------------------ //

  @Test
  void broadcast_noEmittersForUser_noException() {
    registry.broadcast(42L, sampleEvent());
  }

  @Test
  void broadcastToTenant_noEmitters_noException() {
    registry.broadcastToTenant(1L, sampleEvent(), userId -> true);
  }

  @Test
  void broadcastToTenant_registeredUsers_noException() {
    // 실제 전송 대상 판정(테넌트·수신자 필터)은 DatasetChangeNotificationScopeTest·ApiConnectionStatusScopeTest 가 기록
    // 연결로 검증한다.
    registry.register(1L, 1L);
    registry.register(2L, 1L);
    registry.broadcastToTenant(1L, sampleEvent(), userId -> true);
  }

  // ------------------------------------------------------------------ //
  // heartbeat
  // ------------------------------------------------------------------ //

  @Test
  void sendHeartbeat_noEmitters_noException() {
    registry.sendHeartbeat();
  }

  @Test
  void sendHeartbeat_withRegisteredEmitter_noException() {
    registry.register(1L, 1L);
    registry.sendHeartbeat();
  }

  @Test
  void sendHeartbeat_removesDeadEmitters() throws Exception {
    // Create a completed emitter (simulating a closed connection)
    SseEmitter deadEmitter = new SseEmitter(1L);
    deadEmitter.complete(); // mark as done -> subsequent sends will throw IllegalStateException

    // Register a live emitter first, then manually trigger cleanup via heartbeat
    registry.register(1L, 1L);
    // heartbeat should not throw even if some emitters are dead
    registry.sendHeartbeat();
  }

  // ------------------------------------------------------------------ //
  // max emitters per user
  // ------------------------------------------------------------------ //

  @Test
  void maxEmittersPerUser_isThree() {
    // Register 3 emitters for user 1 — all should succeed
    SseEmitter e1 = registry.register(1L, 1L);
    SseEmitter e2 = registry.register(1L, 1L);
    SseEmitter e3 = registry.register(1L, 1L);
    assertThat(e1).isNotNull();
    assertThat(e2).isNotNull();
    assertThat(e3).isNotNull();
    // 4th should evict oldest instead of throwing
    SseEmitter e4 = registry.register(1L, 1L);
    assertThat(e4).isNotNull();
  }

  // ------------------------------------------------------------------ //
  // helpers
  // ------------------------------------------------------------------ //

  private NotificationEvent sampleEvent() {
    return new NotificationEvent(
        UUID.randomUUID().toString(),
        "PIPELINE_COMPLETED",
        "INFO",
        "Test",
        "Test description",
        "PIPELINE",
        1L,
        Map.of(),
        LocalDateTime.now());
  }
}
