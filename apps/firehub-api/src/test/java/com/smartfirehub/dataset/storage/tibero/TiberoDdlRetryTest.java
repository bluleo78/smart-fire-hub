package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tibero DDL 문장 -12033(NOWAIT) 한정 재시도({@link TiberoErrors#executeDdl}, WD-739) 단위 시험. 실제로 자지 않고
 * 대기값만 기록한다.
 */
class TiberoDdlRetryTest {

  private static SQLException lockFail() {
    return new SQLException("DDL lock NOWAIT", "72000", TiberoErrors.LOCK_NOWAIT);
  }

  /** 시도 순서대로 결과(null=성공, 아니면 던질 예외)를 정하는 가짜 Statement — 보낸 SQL 을 기록한다. */
  private static Statement statement(List<String> sent, List<SQLException> results) {
    int[] i = {0};
    return (Statement)
        Proxy.newProxyInstance(
            Statement.class.getClassLoader(),
            new Class<?>[] {Statement.class},
            (p, m, args) -> {
              if (m.getName().equals("execute")) {
                sent.add((String) args[0]);
                SQLException r = i[0] < results.size() ? results.get(i[0]) : null;
                i[0]++;
                if (r != null) throw r;
                return false;
              }
              if (m.getName().equals("close")) return null;
              throw new UnsupportedOperationException(m.getName());
            });
  }

  private static Connection connection(Statement st) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (p, m, args) -> {
              if (m.getName().equals("createStatement")) return st;
              throw new UnsupportedOperationException(m.getName());
            });
  }

  @Test
  void 잠금_실패_두_번_뒤_성공하면_성공하고_대기는_50_100() {
    List<String> sent = new ArrayList<>();
    List<Long> waits = new ArrayList<>();
    Statement st = statement(sent, Arrays.asList(lockFail(), lockFail(), null));

    assertThatCode(() -> TiberoErrors.executeDdl(st, "DDL", waits::add)).doesNotThrowAnyException();

    assertThat(sent).hasSize(3);
    assertThat(waits).containsExactly(50L, 100L);
  }

  @Test
  void 잠금_실패가_다섯_번이면_마지막_예외를_그대로_던진다() {
    List<String> sent = new ArrayList<>();
    List<Long> waits = new ArrayList<>();
    List<SQLException> fails = new ArrayList<>();
    for (int i = 0; i < 5; i++) fails.add(lockFail());
    Statement st = statement(sent, fails);

    assertThatThrownBy(() -> TiberoErrors.executeDdl(st, "DDL", waits::add)).isSameAs(fails.get(4));

    assertThat(sent).hasSize(5);
    assertThat(waits).containsExactly(50L, 100L, 200L, 400L);
  }

  @Test
  void 코드가_비고_메시지에만_있어도_재시도한다() {
    List<String> sent = new ArrayList<>();
    List<Long> waits = new ArrayList<>();
    SQLException messageOnly =
        new SQLException("JDBC-12033:Lock acquisition failed in NOWAIT mode.", "ERRFR", 0);
    Statement st = statement(sent, Arrays.asList(messageOnly, null));

    assertThatCode(() -> TiberoErrors.executeDdl(st, "DDL", waits::add)).doesNotThrowAnyException();

    assertThat(sent).hasSize(2);
    assertThat(waits).containsExactly(50L);
  }

  @Test
  void 다른_오류는_재시도하지_않는다() {
    List<String> sent = new ArrayList<>();
    List<Long> waits = new ArrayList<>();
    SQLException other = new SQLException("dup", "23000", -10007);
    Statement st = statement(sent, List.of(other));

    assertThatThrownBy(() -> TiberoErrors.executeDdl(st, "DDL", waits::add)).isSameAs(other);

    assertThat(sent).hasSize(1);
    assertThat(waits).isEmpty();
  }

  @Test
  void runIgnoring_은_없음_오류를_재시도_없이_false() throws Exception {
    List<String> sent = new ArrayList<>();
    Statement st = statement(sent, List.of(new SQLException("no table", "42000", -8033)));
    assertThat(TiberoErrors.runIgnoring(connection(st), "DROP", TiberoDialect.NotFound.TABLE))
        .isFalse();
    assertThat(sent).hasSize(1);

    List<String> sent2 = new ArrayList<>();
    Statement st2 = statement(sent2, Arrays.asList(lockFail(), null));
    assertThat(TiberoErrors.runIgnoring(connection(st2), "DROP", TiberoDialect.NotFound.TABLE))
        .isTrue();
    assertThat(sent2).hasSize(2);
  }

  @Test
  void 대기_중_인터럽트되면_표시를_되살리고_원래_예외() {
    List<String> sent = new ArrayList<>();
    SQLException first = lockFail();
    Statement st = statement(sent, List.of(first, lockFail()));
    try {
      assertThatThrownBy(
              () ->
                  TiberoErrors.executeDdl(
                      st,
                      "DDL",
                      ms -> {
                        throw new InterruptedException();
                      }))
          .isSameAs(first);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(sent).hasSize(1);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void 여러_문장_중_실패한_문장만_다시_보낸다() throws Exception {
    FakeTibero f = new FakeTibero();
    f.seed("ptmp_1_a", 77L);
    f.failCode = TiberoErrors.LOCK_NOWAIT;
    boolean[] failed = {false};
    // 두 번째 문장(시퀀스 RENAME)만 한 번 -12033 으로 실패시킨다.
    f.failOn =
        sql -> {
          if (sql.startsWith("RENAME ") && !failed[0]) {
            failed[0] = true;
            return true;
          }
          return false;
        };

    TiberoRebuildBackup.moveToBackup(f.connection(), "GENIA_T5", "ptmp_1_a", "a".repeat(32));

    assertThat(f.log.stream().filter(s -> s.startsWith("DROP TRIGGER")).count()).isEqualTo(1);
    assertThat(f.log.stream().filter(s -> s.startsWith("RENAME ")).count()).isEqualTo(2);
  }
}
