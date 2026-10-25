package com.smartfirehub.dbexecutor.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.dbexecutor.config.ExecutionTimeouts;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.web.ExecuteController;
import com.smartfirehub.dbexecutor.web.dto.ProbeRequest;
import com.smartfirehub.dbexecutor.web.dto.ProbeResponse;
import com.smartfirehub.dbexecutor.web.dto.SqlExecuteRequest;
import com.smartfirehub.dbexecutor.web.dto.SqlExecuteResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 요청 전체 기한은 컨트롤러 도착 시점부터다(F1). 기술자 조회가 느려도 전체가 그 기한(시험용으로 2초로 줄임) 안에 끝나고 커밋하지 않는다 — api 는
 * /execute/sql 60초·/execute/probe 35초에서 포기하므로, 실행기 기한(55초·30초)이 기술자 조회·풀 대여 뒤에 시작하면 api 가 단계를
 * FAILED 로 적은 뒤에 커밋할 수 있다.
 *
 * <p>HTTP 만 뺀 실제 경로(컨트롤러 → 실제 SqlRunner·ProbeRunner → 모의 JDBC)다. 기술자 조회만 느린 목이다.
 */
class RequestDeadlineTest {

  private static final String DELETE = "DELETE FROM \"GENIA_T5\".\"ORDERS\"";
  private static final String INSERT =
      "INSERT INTO \"GENIA_T5\".\"ORDERS\" (\"ID\", \"A\", \"_UPDATED_AT\") SELECT"
          + " \"GENIA_T5\".\"SQ_X\".NEXTVAL, src.\"A\", SYSTIMESTAMP FROM (\nSELECT 1 a FROM DUAL\n) src";

  private final FakeJdbc db = new FakeJdbc();
  private final StoreDescriptorClient descriptors = mock(StoreDescriptorClient.class);

  /** 기술자 조회가 받은 대기 상한(남은 시간). */
  private final AtomicReference<Duration> budget = new AtomicReference<>();

  RequestDeadlineTest() throws SQLException {}

  /** 전체 기한 2초(/execute/sql·/execute/probe 모두)로 줄인 컨트롤러. */
  private ExecuteController controller() {
    ExecutionTimeouts t =
        new ExecutionTimeouts(
            new ExecutionTimeouts.Sql(50, 2, 1800),
            new ExecutionTimeouts.Probe(2),
            new ExecutionTimeouts.Query(2));
    return new ExecuteController(
        descriptors,
        new SqlRunner(db.pools, t, descriptors),
        new ProbeRunner(db.pools, t, descriptors),
        new QueryRunner(db.roPools, t, descriptors),
        new LoadRunner(db.pools, t, descriptors),
        null,
        t,
        new com.fasterxml.jackson.databind.ObjectMapper());
  }

  /** 기술자 조회가 delayMs 걸린 뒤 기술자를 돌려주게 한다(남은 시간은 기록만 하고 무시 — 최악의 경우). */
  private void slowDescriptor(long delayMs) {
    when(descriptors.get(eq(5L), any(Duration.class)))
        .thenAnswer(
            inv -> {
              budget.set(inv.getArgument(1));
              Thread.sleep(delayMs);
              return FakeJdbc.DESCRIPTOR;
            });
  }

  /** 본문(INSERT) 또는 probe 문장이 cancel 될 때까지 막혀 있다가 드라이버처럼 취소 오류를 던진다. */
  private void blockUntilCancelled(String prefix) {
    db.handler =
        sql -> {
          if (sql.startsWith(prefix)) {
            if (!db.cancelled.await(10, TimeUnit.SECONDS)) {
              throw new AssertionError("cancel 이 호출되지 않았다");
            }
            throw new SQLException("statement cancelled", "72000", -1013);
          }
          return false;
        };
  }

  @Test
  void slowDescriptor_sqlStillEndsWithinRequestDeadline_andNeverCommits() {
    slowDescriptor(1_500);
    blockUntilCancelled("INSERT");

    long start = System.nanoTime();
    // 선행 문장 없는 요청 — 도착 기한 하나가 기술자 조회·대여·본문을 덮는 경로다(선행 문장이 있으면 도착 기한이 선행 상한만큼 길고 본문 기한은
    // 본문 시작부터다 — WD-236, SqlRunnerTest 가 덮는다).
    SqlExecuteResponse res = controller().executeSql(new SqlExecuteRequest(5L, INSERT, List.of()));
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertThat(res.success()).isFalse();
    assertThat(res.error()).isEqualTo("실행 시간이 2초를 넘어 취소했습니다.");
    assertThat(db.events).contains("CANCEL").endsWith("ROLLBACK").doesNotContain("COMMIT");
    // 기한이 실행 시작부터라면 1.5 + 2 = 3.5초 뒤에야 cancel 된다. 도착부터면 2초 남짓.
    assertThat(elapsedMs).isLessThan(2_800);
    // 기술자 조회에도 남은 시간(최대 2초)을 상한으로 넘긴다.
    assertThat(budget.get()).isLessThanOrEqualTo(Duration.ofSeconds(2)).isPositive();
  }

  /**
   * 선행 문장이 있으면(WD-236) 도착 기한은 선행 상한(기본 1800) + 본문 기한(2초)이고, 본문 기한은 본문 시작부터다 — 기술자 조회(1.5초)가 본문 시간을
   * 깎지 않는다. 그래도 커밋하지 않고 본문 기한 문구로 취소·롤백한다.
   */
  @Test
  void withPreStatements_bodyDeadlineStartsAtBody_notArrival() {
    slowDescriptor(1_500);
    blockUntilCancelled("INSERT");

    long start = System.nanoTime();
    SqlExecuteResponse res =
        controller().executeSql(new SqlExecuteRequest(5L, INSERT, List.of(DELETE)));
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertThat(res.success()).isFalse();
    assertThat(res.error()).isEqualTo("실행 시간이 2초를 넘어 취소했습니다.");
    assertThat(db.events).contains("CANCEL").endsWith("ROLLBACK").doesNotContain("COMMIT");
    // 기술자 조회 1.5초 + 본문 기한 2초 뒤 취소(도착 기한 기준이면 2초 남짓).
    assertThat(elapsedMs).isGreaterThanOrEqualTo(3_300).isLessThan(6_000);
    // 기술자 조회에는 도착 기한(선행 상한 + 본문 기한)의 남은 시간을 상한으로 넘긴다.
    assertThat(budget.get()).isGreaterThan(Duration.ofSeconds(1_800));
  }

  @Test
  void descriptorUsingWholeDeadline_neverBorrowsOrRuns() {
    slowDescriptor(2_300);

    SqlExecuteResponse res = controller().executeSql(new SqlExecuteRequest(5L, INSERT, List.of()));

    assertThat(res.success()).isFalse();
    assertThat(res.error()).isEqualTo("실행 시간이 2초를 넘어 취소했습니다.");
    assertThat(db.factoryCalls.get()).isZero();
    assertThat(db.events).isEmpty();
  }

  @Test
  void slowDescriptor_probeStillEndsWithinRequestDeadline() {
    slowDescriptor(1_500);
    blockUntilCancelled("SELECT * FROM (");

    long start = System.nanoTime();
    ProbeResponse res = controller().probe(new ProbeRequest(5L, "SELECT 1 a FROM DUAL"));
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertThat(res.success()).isFalse();
    assertThat(res.error()).isEqualTo("컬럼 탐지 시간이 2초를 넘어 취소했습니다.");
    assertThat(db.events).contains("CANCEL");
    assertThat(elapsedMs).isLessThan(2_800);
  }
}
