package com.smartfirehub.support;

import com.smartfirehub.notification.service.SseEmitterRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 통합 테스트가 실제 알림 레지스트리(싱글턴)에 연 연결을 추적하고 닫힘 여부를 보는 공용 헬퍼(WD-187·WD-190).
 *
 * <p>왜 공용인가: 멤버 정지·제거(WD-187), 전역 계정 비활성화·워크스페이스 정지(WD-190)가 모두 「커밋 뒤 연결이 닫혔는가」를 같은 방식으로 본다. 테스트가 연
 * 연결을 정리하지 않으면 싱글턴 레지스트리에 남아 다른 테스트의 브로드캐스트 판정에 섞인다.
 */
public final class OpenedSseStreams {

  private final SseEmitterRegistry registry;
  private final List<Object[]> opened = new ArrayList<>();

  public OpenedSseStreams(SseEmitterRegistry registry) {
    this.registry = registry;
  }

  /** 실제 레지스트리에 알림 연결을 연다 — 응답에 붙지 않은 연결이라 send 는 버퍼링되고, 닫힌 뒤의 send 만 IllegalStateException 이다. */
  public SseEmitter open(long userId, long tenantId) {
    SseEmitter e = registry.register(userId, tenantId);
    opened.add(new Object[] {userId, e});
    return e;
  }

  /** 연 연결을 레지스트리에서 모두 뺀다(@AfterEach). */
  public void removeAll() {
    opened.forEach(o -> registry.remove((Long) o[0], (SseEmitter) o[1]));
    opened.clear();
  }

  /** 연결이 닫혔는가 — 닫힌(complete) 연결에 send 하면 IllegalStateException 이 난다. */
  public static boolean isClosed(SseEmitter e) {
    try {
      e.send(SseEmitter.event().comment("probe"));
      return false;
    } catch (IllegalStateException closed) {
      return true;
    } catch (IOException io) {
      throw new IllegalStateException(io);
    }
  }
}
