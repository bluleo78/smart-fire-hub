package com.smartfirehub.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * WD-169: 트리거 건너뜀 알림(트리거·파이프라인 이름이 실린다)은 <b>그 워크스페이스</b>의 <b>수신자</b> 연결로만 간다. 같은 수신자가 다른 워크스페이스
 * 화면으로 연 연결, 수신자가 아닌 사람의 연결로는 가지 않는다 — {@code broadcast(userId)} 가 다른 테넌트 연결로도 보내는 결함(WD-173)을 피한다.
 */
class TriggerSkippedNotificationScopeTest {

  /** 전송 횟수를 세는 연결 — 실제 HTTP 응답 없이 send 를 기록한다. */
  static class RecordingEmitter extends SseEmitter {
    final AtomicInteger sent = new AtomicInteger();

    @Override
    public void send(SseEventBuilder builder) {
      sent.incrementAndGet();
    }
  }

  /** 등록 순서대로 기록용 연결을 돌려주는 레지스트리. */
  static class RecordingRegistry extends SseEmitterRegistry {
    final List<RecordingEmitter> created = new ArrayList<>();

    RecordingRegistry(ObjectMapper om) {
      super(om);
    }

    @Override
    SseEmitter createEmitter() {
      RecordingEmitter e = new RecordingEmitter();
      created.add(e);
      return e;
    }
  }

  @Test
  void notifyTriggerSkipped_sendsOnlyToRecipientConnectionsOfThatTenant() {
    long t1 = 900_001L;
    long t2 = 900_002L;
    RecordingRegistry registry = new RecordingRegistry(new ObjectMapper().findAndRegisterModules());
    // 이 알림은 보안 등급 판정을 쓰지 않는다
    NotificationService service = new NotificationService(registry, null, null);
    registry.register(10L, t1); // 수신자 · 이 워크스페이스
    registry.register(10L, t2); // 같은 수신자 · 다른 워크스페이스 화면
    registry.register(20L, t1); // 수신자 아님 · 이 워크스페이스

    service.notifyTriggerSkipped(t1, Set.of(10L), 1L, 2L, "트리거", "파이프라인", "원인", "사유");

    assertThat(registry.created.get(0).sent.get()).isEqualTo(1);
    assertThat(registry.created.get(1).sent.get()).isZero();
    assertThat(registry.created.get(2).sent.get()).isZero();
  }
}
