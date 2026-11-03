package com.smartfirehub.notification.service;

import com.smartfirehub.global.platformdb.OutboxWakeupChannel;
import org.springframework.stereotype.Component;

/**
 * Postgres NOTIFY 발행으로 워커를 즉시 깨움. 실패해도 30초 폴백 폴링이 처리하므로 warn log만 남기고 조용히 실패.
 *
 * <p><b>테넌트 배선 제외(P2-f)</b>: {@code NOTIFY} 만 발행하고 도메인 행을 읽거나 쓰지 않으므로 RLS 의 영향을 받지 않는다. 깨어난 워커가 자기
 * 테넌트 스코프를 스스로 연다.
 */
@Component
public class OutboxNotifier {

  private final OutboxWakeupChannel channel;

  public OutboxNotifier(OutboxWakeupChannel channel) {
    this.channel = channel;
  }

  /** 신호 발행을 채널에 위임한다(실패는 채널이 warn 로그로 삼킨다). */
  public void notifyOutboxNew() {
    channel.publish();
  }
}
