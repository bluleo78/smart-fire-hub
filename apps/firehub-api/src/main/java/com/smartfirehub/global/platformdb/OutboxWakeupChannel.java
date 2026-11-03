package com.smartfirehub.global.platformdb;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * 알림 아웃박스 워커를 깨우는 신호 채널 추상화(WD-486).
 *
 * <p>왜: 아웃박스에 행이 들어오면 폴링 주기를 기다리지 않고 워커를 즉시 깨우기 위해 PostgreSQL 의 {@code LISTEN/NOTIFY} 를 직접 썼다. 플랫폼
 * DB 를 다른 DBMS 로 옮길 수 있게 발행·구독을 인터페이스 뒤로 모으고, PG 구현은 {@code postgres.PostgresOutboxWakeupChannel} 이
 * 맡는다. 재연결·대기·스레드 수명은 호출자({@code OutboxListenerLoop}) 몫이며 동작은 바뀌지 않는다.
 */
public interface OutboxWakeupChannel {

  /** 아웃박스 워커를 깨우는 신호를 즉시 보낸다(호출자 트랜잭션과 무관). 실패는 warn 로그만 남기고 삼킨다. */
  void publish();

  /**
   * 전용 연결 하나로 신호를 구독하고, running 이 true 인 동안 pollTimeout 마다 깨어나 신호가 오면 onSignal 을 부른다. 연결 오류는 예외로
   * 던진다 — 재연결·대기는 호출자 몫. running 이 false 가 되면 연결을 닫고 정상 반환.
   */
  void listen(BooleanSupplier running, Duration pollTimeout, Runnable onSignal) throws Exception;
}
