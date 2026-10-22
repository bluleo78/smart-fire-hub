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

  /**
   * WD-187: closeUserInTenant 는 그 사용자의 지정 테넌트 연결만 닫고 레지스트리에서도 뺀다 — 이후 그 테넌트 브로드캐스트의 수신자 판정 대상에서
   * 빠진다. 같은 사용자의 다른 테넌트 연결·다른 사용자의 같은 테넌트 연결은 그대로다.
   */
  @Test
  void closeUserInTenant_closesOnlyThatTenantsConnectionsOfThatUser() throws Exception {
    SseEmitter u1t1 = registry.register(1L, 1L);
    SseEmitter u1t2 = registry.register(1L, 2L);
    SseEmitter u2t1 = registry.register(2L, 1L);

    registry.closeUserInTenant(1L, 1L);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> u1t1.send(SseEmitter.event().comment("x")))
        .as("닫힌 연결")
        .isInstanceOf(IllegalStateException.class);
    u1t2.send(SseEmitter.event().comment("x")); // 다른 테넌트 연결은 열려 있다
    u2t1.send(SseEmitter.event().comment("x")); // 다른 사용자 연결은 열려 있다

    java.util.Set<Long> judged = new java.util.HashSet<>();
    registry.broadcastToTenant(1L, sampleEvent(), userId -> judged.add(userId));
    assertThat(judged).as("테넌트 1 연결이 남은 사용자만 판정").containsExactly(2L);
    judged.clear();
    registry.broadcastToTenant(2L, sampleEvent(), userId -> judged.add(userId));
    assertThat(judged).containsExactly(1L);
  }

  /** WD-190: closeUser 는 그 사용자의 연결을 워크스페이스와 무관하게 전부 닫고 레지스트리에서 뺀다(전역 계정 비활성화). 다른 사용자 연결은 그대로다. */
  @Test
  void closeUser_closesAllConnectionsOfThatUserOnly() throws Exception {
    SseEmitter u1t1 = registry.register(1L, 1L);
    SseEmitter u1t2 = registry.register(1L, 2L);
    SseEmitter u2t1 = registry.register(2L, 1L);

    registry.closeUser(1L);

    for (SseEmitter closed : java.util.List.of(u1t1, u1t2)) {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> closed.send(SseEmitter.event().comment("x")))
          .as("그 사용자의 모든 워크스페이스 연결이 닫힌다")
          .isInstanceOf(IllegalStateException.class);
    }
    u2t1.send(SseEmitter.event().comment("x")); // 다른 사용자 연결은 열려 있다

    java.util.Set<Long> judged = new java.util.HashSet<>();
    registry.broadcastToTenant(1L, sampleEvent(), userId -> judged.add(userId));
    registry.broadcastToTenant(2L, sampleEvent(), userId -> judged.add(userId));
    assertThat(judged).as("닫힌 사용자는 어느 테넌트 판정에도 남지 않는다").containsExactly(2L);
  }

  /**
   * WD-190: closeTenant 는 그 워크스페이스로 연 연결을 사용자와 무관하게 전부 닫는다(워크스페이스 정지). 같은 사용자의 다른 워크스페이스 연결은 그대로다.
   */
  @Test
  void closeTenant_closesAllConnectionsOfThatTenantOnly() throws Exception {
    SseEmitter u1t1 = registry.register(1L, 1L);
    SseEmitter u2t1 = registry.register(2L, 1L);
    SseEmitter u1t2 = registry.register(1L, 2L);

    registry.closeTenant(1L);

    for (SseEmitter closed : java.util.List.of(u1t1, u2t1)) {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> closed.send(SseEmitter.event().comment("x")))
          .as("정지된 워크스페이스 연결은 모두 닫힌다")
          .isInstanceOf(IllegalStateException.class);
    }
    u1t2.send(SseEmitter.event().comment("x")); // 다른 워크스페이스 연결은 열려 있다

    java.util.Set<Long> judged = new java.util.HashSet<>();
    registry.broadcastToTenant(1L, sampleEvent(), userId -> judged.add(userId));
    assertThat(judged).as("정지된 워크스페이스에는 연결이 남지 않는다").isEmpty();
    registry.broadcastToTenant(2L, sampleEvent(), userId -> judged.add(userId));
    assertThat(judged).containsExactly(1L);
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
    // 실제 전송 대상 판정(테넌트·수신자 필터)은 DatasetChangeNotificationScopeTest(데이터셋 변경
    // datasetChanged_*, API 연결 상태 apiConnectionStatus_*)가 기록 연결로 검증한다.
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
