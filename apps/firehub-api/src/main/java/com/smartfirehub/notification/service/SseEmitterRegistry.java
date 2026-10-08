package com.smartfirehub.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.notification.dto.NotificationEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongPredicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
@RequiredArgsConstructor
@Slf4j
public class SseEmitterRegistry {

  private static final long EMITTER_TIMEOUT =
      3_600_000L; // 1 hour (safety net; heartbeat detects dead connections every 30s)
  private static final int MAX_EMITTERS_PER_USER = 3;

  private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> emitters =
      new ConcurrentHashMap<>();

  /**
   * 연결(emitter)별 구독 테넌트. 사용자 단위가 아니라 연결 단위인 이유: 한 사용자가 두 테넌트 화면을 동시에 열 수 있다. 테넌트 범위 브로드캐스트 ({@link
   * #broadcastToTenant})가 다른 테넌트 연결로 새지 않게 하는 근거다. SseEmitter 는 equals 를 재정의하지 않아 동일성 키로 동작한다.
   */
  private final ConcurrentHashMap<SseEmitter, Long> emitterTenants = new ConcurrentHashMap<>();

  private final ObjectMapper objectMapper;

  /**
   * 알림 스트림 연결을 등록한다.
   *
   * @param tenantId 구독 요청의 테넌트(TenantContext) — 테넌트 범위 브로드캐스트의 수신 판정에 쓴다
   */
  public SseEmitter register(Long userId, long tenantId) {
    CopyOnWriteArrayList<SseEmitter> list =
        emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());

    // Evict oldest if at limit
    if (list.size() >= MAX_EMITTERS_PER_USER) {
      SseEmitter oldest = list.isEmpty() ? null : list.get(0);
      if (oldest != null) {
        list.remove(oldest);
        emitterTenants.remove(oldest);
        try {
          oldest.complete();
        } catch (Exception ignored) {
          // Ignore completion errors on eviction
        }
      }
    }

    SseEmitter emitter = createEmitter();
    emitter.onCompletion(() -> remove(userId, emitter));
    emitter.onTimeout(() -> remove(userId, emitter));
    emitter.onError(e -> remove(userId, emitter));
    emitterTenants.put(emitter, tenantId);
    list.add(emitter);

    log.debug("Registered SSE emitter for userId={}, total={}", userId, list.size());
    return emitter;
  }

  /** 새 연결 객체. 테스트가 전송 내역을 기록하는 emitter 로 바꿔 끼울 수 있게 분리했다(수신자 범위 TC). */
  SseEmitter createEmitter() {
    return new SseEmitter(EMITTER_TIMEOUT);
  }

  public void remove(Long userId, SseEmitter emitter) {
    emitterTenants.remove(emitter);
    CopyOnWriteArrayList<SseEmitter> list = emitters.get(userId);
    if (list != null) {
      list.remove(emitter);
      emitters.computeIfPresent(userId, (k, v) -> v.isEmpty() ? null : v);
    }
  }

  public void broadcast(Long userId, NotificationEvent event) {
    CopyOnWriteArrayList<SseEmitter> list = emitters.get(userId);
    if (list == null || list.isEmpty()) return;
    send(userId, list, event);
  }

  /** 지정 연결들에 이벤트를 보내고 끊긴 연결은 정리한다. */
  private void send(Long userId, List<SseEmitter> list, NotificationEvent event) {
    String json = toJson(event);
    SseEmitter.SseEventBuilder sseEvent =
        SseEmitter.event().id(event.id()).name("notification").data(json);

    List<SseEmitter> dead = new ArrayList<>();
    for (SseEmitter emitter : list) {
      try {
        emitter.send(sseEvent);
      } catch (IOException | IllegalStateException e) {
        log.debug("SSE send failed for userId={}: {}", userId, e.getMessage());
        dead.add(emitter);
      }
    }
    dead.forEach(e -> remove(userId, e));
  }

  /**
   * 한 테넌트의 연결 중 수신자 판정을 통과한 사용자에게만 보낸다(보안 등급 — 데이터셋 이름이 실린 알림이 다른 테넌트나 그 데이터셋을 볼 수 없는 사용자에게 가지 않게).
   * 판정은 그 테넌트 연결이 있는 사용자에 대해서만, 사용자당 한 번 부른다. 판정이 예외를 던지면 그 사용자는 받지 않는다 (fail-closed).
   *
   * @param recipientAllowed userId → 수신 허용 여부
   */
  public void broadcastToTenant(
      long tenantId, NotificationEvent event, LongPredicate recipientAllowed) {
    emitters.forEach(
        (userId, list) -> {
          List<SseEmitter> targets =
              list.stream()
                  .filter(e -> Long.valueOf(tenantId).equals(emitterTenants.get(e)))
                  .toList();
          if (targets.isEmpty()) {
            return;
          }
          boolean allowed;
          try {
            allowed = recipientAllowed.test(userId);
          } catch (RuntimeException e) {
            log.warn("SSE 수신자 판정 실패 — 보내지 않음 userId={}: {}", userId, e.getMessage());
            allowed = false;
          }
          if (allowed) {
            send(userId, targets, event);
          }
        });
  }

  @Scheduled(fixedRate = 30_000)
  public void sendHeartbeat() {
    List<Long> deadUsers = new ArrayList<>();

    emitters.forEach(
        (userId, list) -> {
          if (list.isEmpty()) {
            deadUsers.add(userId);
            return;
          }
          List<SseEmitter> dead = new ArrayList<>();
          for (SseEmitter emitter : list) {
            try {
              emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException e) {
              log.debug("Heartbeat failed for userId={}: {}", userId, e.getMessage());
              dead.add(emitter);
            }
          }
          dead.forEach(e -> remove(userId, e));
        });

    deadUsers.forEach(emitters::remove);
    log.debug("Heartbeat sent. Active users with SSE: {}", emitters.size());
  }

  private String toJson(NotificationEvent event) {
    try {
      return objectMapper.writeValueAsString(event);
    } catch (JsonProcessingException e) {
      log.warn("Failed to serialize NotificationEvent: {}", e.getMessage());
      return "{}";
    }
  }
}
