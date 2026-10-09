package com.smartfirehub.securitylevel.pythonread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.tenant.repository.TenantRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * PythonReadGrantScheduler.runAll 단위 테스트(WD-29, Task 4 리뷰 후속) — Spring 없이 스케줄러를 직접 만든다. 실제 {@link
 * TenantScopedRunner} 에 가짜 테넌트 목록(목 리포지토리)을 넣어, 테넌트마다 그 테넌트의 {@link TenantContext} 로 syncTenant 가
 * 불리는지·한 테넌트 예외가 다른 테넌트를 막지 않는지·꺼져 있으면 무동작인지·부분 실패가 로그에 성공/실패 수로 남는지를 본다.
 */
class PythonReadGrantSchedulerTest {

  private final PythonReadGrantSync sync = mock(PythonReadGrantSync.class);
  private final TenantRepository tenantRepository = mock(TenantRepository.class);
  private final TenantScopedRunner runner = new TenantScopedRunner(tenantRepository);

  private final Logger schedulerLog =
      (Logger) LoggerFactory.getLogger(PythonReadGrantScheduler.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void setUp() {
    TenantContext.clear();
    logs.start();
    schedulerLog.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    schedulerLog.detachAppender(logs);
    TenantContext.clear();
  }

  /** 테넌트마다 그 테넌트 컨텍스트로 syncTenant 를 부르고, 중간 테넌트의 예외가 뒤 테넌트를 막지 않는다. 실패 수가 로그에 남는다. */
  @Test
  void runAll_syncsEachTenantInItsOwnContext_andIsolatesFailures() {
    when(tenantRepository.findActiveTenantIds()).thenReturn(List.of(11L, 22L, 33L));
    List<Long> seen = new ArrayList<>();
    when(sync.syncTenant())
        .thenAnswer(
            inv -> {
              Long t = TenantContext.get();
              seen.add(t);
              if (t != null && t == 22L) {
                throw new IllegalStateException("테넌트 22 동기화 실패");
              }
              return null;
            });

    new PythonReadGrantScheduler(sync, runner, true).runAll("테스트");

    assertThat(seen).as("테넌트마다 자기 컨텍스트로, 22 실패 뒤 33 도 처리").containsExactly(11L, 22L, 33L);
    assertThat(TenantContext.get()).as("순회 후 컨텍스트 복원").isNull();
    assertThat(logs.list)
        .filteredOn(e -> e.getLevel() == Level.WARN)
        .extracting(ILoggingEvent::getFormattedMessage)
        .singleElement()
        .asString()
        .contains("성공 2개", "실패 1개");
    assertThat(logs.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .noneMatch(msg -> msg.contains("동기화 완료"));
  }

  /** 전부 성공하면 완료 로그에 성공 수가 남고 경고는 없다. */
  @Test
  void runAll_allSucceed_logsCompletionWithCount() {
    when(tenantRepository.findActiveTenantIds()).thenReturn(List.of(11L, 22L));

    new PythonReadGrantScheduler(sync, runner, true).runAll("테스트");

    assertThat(logs.list)
        .filteredOn(e -> e.getLevel() == Level.INFO)
        .extracting(ILoggingEvent::getFormattedMessage)
        .singleElement()
        .asString()
        .contains("동기화 완료", "성공 2개");
    assertThat(logs.list).noneMatch(e -> e.getLevel() == Level.WARN);
  }

  /** enabled=false 면 테넌트 목록조차 읽지 않고 아무것도 하지 않는다. */
  @Test
  void runAll_disabled_doesNothing() {
    new PythonReadGrantScheduler(sync, runner, false).runAll("테스트");

    verifyNoInteractions(tenantRepository);
    verify(sync, never()).syncTenant();
  }
}
