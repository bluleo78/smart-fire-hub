package com.smartfirehub.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.notification.dto.NotificationEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class SseEmitterRegistry {

  private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

  private static final long EMITTER_TIMEOUT =
      3_600_000L; // 1 hour (safety net; heartbeat detects dead connections every 30s)
  private static final int MAX_EMITTERS_PER_USER = 3;

  private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> emitters =
      new ConcurrentHashMap<>();

  private final ObjectMapper objectMapper;

  public SseEmitterRegistry(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public SseEmitter register(Long userId) {
    CopyOnWriteArrayList<SseEmitter> list =
        emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());

    // Evict oldest if at limit
    if (list.size() >= MAX_EMITTERS_PER_USER) {
      SseEmitter oldest = list.isEmpty() ? null : list.get(0);
      if (oldest != null) {
        list.remove(oldest);
        try {
          oldest.complete();
        } catch (Exception ignored) {
          // Ignore completion errors on eviction
        }
      }
    }

    SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT);
    emitter.onCompletion(() -> remove(userId, emitter));
    emitter.onTimeout(() -> remove(userId, emitter));
    emitter.onError(e -> remove(userId, emitter));
    list.add(emitter);

    log.debug("Registered SSE emitter for userId={}, total={}", userId, list.size());
    return emitter;
  }

  public void remove(Long userId, SseEmitter emitter) {
    CopyOnWriteArrayList<SseEmitter> list = emitters.get(userId);
    if (list != null) {
      list.remove(emitter);
      emitters.computeIfPresent(userId, (k, v) -> v.isEmpty() ? null : v);
    }
  }

  public void broadcast(Long userId, NotificationEvent event) {
    CopyOnWriteArrayList<SseEmitter> list = emitters.get(userId);
    if (list == null || list.isEmpty()) return;

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

  public void broadcastAll(NotificationEvent event) {
    emitters.keySet().forEach(userId -> broadcast(userId, event));
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
