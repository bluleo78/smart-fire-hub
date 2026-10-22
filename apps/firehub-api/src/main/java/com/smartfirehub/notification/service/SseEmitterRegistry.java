package com.smartfirehub.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.tenant.TenantContext;
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
   * #broadcastToTenant})와 사용자 단위 브로드캐스트({@link #broadcast}, WD-173)가 다른 테넌트 연결로 새지 않게 하는 근거다.
   * SseEmitter 는 equals 를 재정의하지 않아 동일성 키로 동작한다.
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

  /**
   * 한 사용자의 연결 중 지정 테넌트 화면으로 연 연결만 닫는다(WD-187). 멤버를 정지하거나 워크스페이스에서 제거한 직후 부른다 — 그러지 않으면 이미 열린 연결(최대
   * 1시간)로 그 워크스페이스의 사용자 단위 알림(파이프라인·임포트·리포트 등)이 계속 갔다. 다른 워크스페이스 연결은 그대로 둔다(그 워크스페이스에서는 여전히 멤버다).
   * 닫힌 뒤 웹이 다시 붙으면 구독 엔드포인트의 권한 검사(ACTIVE 멤버십 조인)가 거절한다.
   *
   * <p>먼저 목록에서 빼고 닫는다: 응답에 붙기 전·이미 끊긴 연결은 complete 가 완료 콜백을 부르지 않아 목록에 남을 수 있다(오래된 연결 정리와 같은 처리).
   *
   * @param tenantId 정지·제거가 일어난 워크스페이스
   */
  public void closeUserInTenant(long tenantId, Long userId) {
    CopyOnWriteArrayList<SseEmitter> list = emitters.get(userId);
    if (list == null || list.isEmpty()) return;
    List<SseEmitter> targets = connectionsOfTenant(list, tenantId);
    for (SseEmitter emitter : targets) {
      remove(userId, emitter);
      try {
        emitter.complete();
      } catch (Exception ignored) {
        // 이미 끊긴 연결 — 목록에서는 빠졌으므로 무시한다
      }
    }
    if (!targets.isEmpty()) {
      log.debug(
          "Closed {} SSE emitter(s) for userId={} tenantId={}", targets.size(), userId, tenantId);
    }
  }

  /**
   * 한 사용자에게 알림을 보내되 <b>현재 테넌트(TenantContext) 화면으로 연 연결에만</b> 보낸다(WD-173).
   *
   * <p>예전에는 그 사용자의 모든 연결로 보냈다. 한 사람이 두 워크스페이스 멤버이고 B 화면을 보고 있으면 A 의 「리포트 도착」(리포트 제목·작업 이름)·「파이프라인
   * 실행 실패」 토스트가 B 화면에 떴고, A 에서 제거된 뒤에도 떴다. 사용자 단위 알림은 모두 그 알림이 생긴 워크스페이스의 일이므로 그 워크스페이스 연결로만 간다.
   *
   * <p>테넌트를 시그니처로 받지 않고 컨텍스트에서 읽는 이유: 호출부(파이프라인 완료·임포트·문서·리포트 CHAT 전달·발송함 CHAT·이상 탐지)는 전부 이미 테넌트
   * 스코프 안에서 돈다(요청·runScoped·TenantContextTaskDecorator). 호출부마다 테넌트를 다시 해석하게 하면 해석 지점이 늘어 각자 다르게 틀릴
   * 수 있다. 컨텍스트가 없으면 어느 워크스페이스 알림인지 모르므로 <b>보내지 않는다</b>(fail-closed) — 알림함·실행 이력에는 이미 남아 있어 실시간 토스트만
   * 빠진다.
   */
  public void broadcast(Long userId, NotificationEvent event) {
    Long tenantId = TenantContext.get();
    if (tenantId == null) {
      log.warn(
          "SSE broadcast without tenant context — skipped userId={} eventType={}",
          userId,
          event.eventType());
      return;
    }
    broadcastToUserInTenant(tenantId, userId, event);
  }

  /**
   * 한 사용자의 연결 중 지정 테넌트 화면으로 연 연결에만 보낸다(WD-173). 다른 테넌트 연결, 테넌트가 기록되지 않은 연결로는 보내지 않는다.
   *
   * @param tenantId 알림이 생긴 워크스페이스
   */
  public void broadcastToUserInTenant(long tenantId, Long userId, NotificationEvent event) {
    CopyOnWriteArrayList<SseEmitter> list = emitters.get(userId);
    if (list == null || list.isEmpty()) return;
    List<SseEmitter> targets = connectionsOfTenant(list, tenantId);
    if (targets.isEmpty()) return;
    send(userId, targets, event);
  }

  /**
   * 연결 목록 중 지정 테넌트로 구독한 연결만 고른다 — 사용자 단위({@link #broadcastToUserInTenant})와 테넌트 단위({@link
   * #broadcastToTenant}, WD-169 트리거 건너뜀 등)가 같은 판정을 쓰게 한 곳에 둔다(WD-173). {@link #emitterTenants} 에
   * 기록이 없는 연결(정리 remove 와 전송이 겹치는 경합 등)은 {@code equals(null)} 이 거짓이라 제외된다(fail-closed).
   */
  private List<SseEmitter> connectionsOfTenant(List<SseEmitter> list, long tenantId) {
    Long tenant = tenantId;
    return list.stream().filter(e -> tenant.equals(emitterTenants.get(e))).toList();
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
          List<SseEmitter> targets = connectionsOfTenant(list, tenantId);
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
