package com.smartfirehub.global.jobrunr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.jobrunr.server.BackgroundJobServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

/** JobRunr 서버 감시자·헬스 인디케이터 단위 테스트 — BackgroundJobServer 를 Mockito 가짜로 둔다. */
class JobRunrServerWatchdogTest {

  private final BackgroundJobServer server = mock(BackgroundJobServer.class);
  private final JobRunrServerWatchdog watchdog = new JobRunrServerWatchdog(server);

  @Test
  void 실행중이면_아무것도_하지_않는다() {
    when(server.isRunning()).thenReturn(true);
    watchdog.check();
    watchdog.check();
    verify(server, never()).start();
    verify(server, never()).stop();
  }

  @Test
  void 꺼짐을_연속으로_확인하면_재시작한다() {
    when(server.isRunning()).thenReturn(false);
    watchdog.check(); // 1회차는 일시적 false 일 수 있어 보류
    verify(server, never()).start();
    watchdog.check();
    verify(server).stop();
    verify(server).start();
  }

  @Test
  void 중간에_실행중이_확인되면_카운터가_초기화된다() {
    when(server.isRunning()).thenReturn(false, true, false);
    watchdog.check();
    watchdog.check();
    watchdog.check();
    verify(server, never()).start();
  }

  @Test
  void 재시작_실패는_삼키고_다음_주기에_다시_시도한다() {
    when(server.isRunning()).thenReturn(false);
    doThrow(new IllegalStateException("boom")).doNothing().when(server).start();
    watchdog.check();
    watchdog.check(); // 실패(예외 삼킴)
    watchdog.check(); // 카운터가 유지돼 바로 재시도
    verify(server, times(2)).start();
  }

  @Test
  void 앱_종료_중에는_되살리지_않는다() {
    when(server.isRunning()).thenReturn(false);
    watchdog.onContextClosed();
    watchdog.check();
    watchdog.check();
    verify(server, never()).start();
  }

  @Test
  void 헬스는_실행중이면_UP_꺼지면_DOWN() {
    JobRunrServerHealthIndicator indicator = new JobRunrServerHealthIndicator(server);
    when(server.isRunning()).thenReturn(true);
    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    when(server.isRunning()).thenReturn(false);
    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
  }
}
