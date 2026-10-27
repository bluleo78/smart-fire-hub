package com.smartfirehub.dbexecutor.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dbexecutor.LogCapture;
import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.store.StoreUnavailableException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 슬롯 스트리밍 조회(WD-78): NDJSON 형식, 행 상한 없음(Review Focus 5), 중간 DB 오류는 실패 마지막 줄, 클라이언트 끊김은 문장 취소·롤백·반납,
 * 검증 거부는 커넥션을 빌리기 전.
 */
class SlotQueryRunnerTest {

  /** 슬롯 계정 S2 가 있는 기술자(FakeJdbc 기본 기술자에 슬롯을 더한 것). */
  static final StoreDescriptor SLOT_DESCRIPTOR =
      new StoreDescriptor(
          5L,
          "TIBERO",
          "10.0.0.1",
          8629,
          "tibero",
          "GENIA_T5",
          new StoreDescriptor.Account("GENIA_T5", "pw-secret"),
          new StoreDescriptor.Account("GENIA_T5_RO", "ro-secret"),
          List.of(new StoreDescriptor.SlotAccount(2, "GENIA_T5_S2", "slot-secret")),
          3L);

  private final FakeJdbc db = new FakeJdbc();
  private final StoreDescriptorClient descriptors = mock(StoreDescriptorClient.class);

  /** 운영 설정(write-bigdecimal-as-plain)과 같은 매퍼. */
  private final ObjectMapper mapper =
      new ObjectMapper().enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

  private final SlotQueryRunner runner = new SlotQueryRunner(db.slotPools, descriptors, mapper);

  SlotQueryRunnerTest() throws SQLException {}

  private List<JsonNode> stream(StoreDescriptor d, int slot, String query, int seconds)
      throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (Deadline deadline = new Deadline(seconds)) {
      runner.stream(d, slot, query, deadline, out);
    }
    return lines(out.toString(StandardCharsets.UTF_8));
  }

  private List<JsonNode> stream(String query) throws IOException {
    return stream(SLOT_DESCRIPTOR, 2, query, 30);
  }

  /** 마지막 호출의 원문 줄(숫자 표기를 그대로 보려고 — readTree 는 0.00 을 0.0 으로 읽는다). */
  private List<String> rawLines = List.of();

  private List<JsonNode> lines(String body) throws IOException {
    List<JsonNode> out = new ArrayList<>();
    assertThat(body).endsWith("\n");
    rawLines = List.of(body.split("\n"));
    for (String line : rawLines) {
      out.add(mapper.readTree(line));
    }
    return out;
  }

  /** 정수 컬럼 ID 하나에 n 행(값 1..n)을 주는 결과 집합. {@code failAfter} 행 뒤 next 가 DB 오류를 던진다(0 이면 없음). */
  private ResultSet idRows(int n, int failAfter, AtomicInteger nextCalls) throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    ResultSetMetaData md = mock(ResultSetMetaData.class);
    when(rs.getMetaData()).thenReturn(md);
    when(md.getColumnCount()).thenReturn(1);
    when(md.getColumnLabel(1)).thenReturn("ID");
    when(md.getColumnType(1)).thenReturn(Types.NUMERIC);
    when(md.getPrecision(1)).thenReturn(19);
    when(md.getScale(1)).thenReturn(0);
    AtomicInteger current = new AtomicInteger();
    when(rs.next())
        .thenAnswer(
            inv -> {
              nextCalls.incrementAndGet();
              if (failAfter > 0 && current.get() == failAfter) {
                throw new SQLException("TBR-17004: 테이블이 없습니다", "42000", -17004);
              }
              return current.incrementAndGet() <= n;
            });
    when(rs.getBigDecimal(1)).thenAnswer(inv -> BigDecimal.valueOf(current.get()));
    return rs;
  }

  @Test
  void streams10001Rows_noTruncation_ndjsonShape() throws Exception {
    AtomicInteger nextCalls = new AtomicInteger();
    db.resultSet = idRows(10_001, 0, nextCalls);

    List<JsonNode> lines = stream("SELECT id FROM t;");

    assertThat(lines).hasSize(1 + 10_001 + 1);
    assertThat(lines.get(0).toString()).isEqualTo("{\"columns\":[\"id\"]}");
    for (int i = 1; i <= 10_001; i++) {
      assertThat(lines.get(i).get("row").get("id").asLong()).isEqualTo(i);
      assertThat(lines.get(i).size()).isEqualTo(1);
    }
    assertThat(lines.get(lines.size() - 1).toString())
        .isEqualTo("{\"success\":true,\"row_count\":10001}");
    // 행 상한을 걸지 않는다(분석 조회의 setMaxRows 없음) — 대신 가져오기 크기로 나눠 받는다.
    assertThat(db.maxRows).isEmpty();
    assertThat(db.fetchSizes).containsExactly(SlotQueryRunner.FETCH_SIZE);
    // 끝 세미콜론을 떼고 실행, 읽기만 했어도 트랜잭션을 롤백으로 닫고 커넥션을 반납한다.
    assertThat(db.events).containsExactly("SELECT id FROM t", "ROLLBACK");
    verify(db.connection).close();
    assertThat(db.slotFactorySlots).containsExactly(2);
  }

  @Test
  void midStreamDbError_lastLineIsFailureWithDbError() throws Exception {
    db.resultSet = idRows(10, 3, new AtomicInteger());

    List<JsonNode> lines = stream("SELECT id FROM t");

    assertThat(lines).hasSize(1 + 3 + 1);
    JsonNode last = lines.get(lines.size() - 1);
    assertThat(last.get("success").asBoolean()).isFalse();
    assertThat(last.get("error").asText()).contains("TBR-17004");
    assertThat(last.get("db_error").get("code").asInt()).isEqualTo(-17004);
    assertThat(last.get("db_error").get("sql_state").asText()).isEqualTo("42000");
    assertThat(last.has("row_count")).isFalse();
    assertThat(db.events).endsWith("ROLLBACK");
    verify(db.connection).close();
  }

  /** 클라이언트가 중간에 끊으면(쓰기 IOException) 문장을 취소하고 롤백한 뒤 커넥션을 반납한다 — 나머지 행은 읽지 않는다. */
  @Test
  void clientDisconnect_cancelsStatement_rollsBack_releasesLease() throws Exception {
    AtomicInteger nextCalls = new AtomicInteger();
    db.resultSet = idRows(100_000, 0, nextCalls);
    OutputStream broken =
        new OutputStream() {
          int written;

          @Override
          public void write(int b) throws IOException {
            if (++written > 50_000) {
              throw new IOException("Broken pipe");
            }
          }
        };

    try (LogCapture logs = LogCapture.of(LeasedExecution.class);
        Deadline deadline = new Deadline(30)) {
      runner.stream(SLOT_DESCRIPTOR, 2, "SELECT id FROM t", deadline, broken);
      // 끊김은 내부 결함이 아니다 — 예기치 않은 오류(ERROR) 로그를 남기지 않는다.
      assertThat(logs.events()).noneMatch(e -> e.getLevel() == Level.ERROR);
    }

    assertThat(db.events).contains("CANCEL").endsWith("ROLLBACK");
    verify(db.connection).close();
    assertThat(nextCalls.get()).isLessThan(100_000);
  }

  @Test
  void rejectedStatements_singleFailureLine_neverBorrow() throws Exception {
    for (String sql :
        new String[] {
          "DELETE FROM t",
          "SELECT * FROM t FOR UPDATE",
          "SELECT OTHER_SCHEMA.F(1) FROM DUAL",
          "SELECT * FROM SYS.DBA_USERS",
          "SELECT * FROM \"SYS\" . \"DBA_USERS\"",
          "BEGIN NULL; END;",
          "",
        }) {
      List<JsonNode> lines = stream(sql);
      assertThat(lines).as(sql).hasSize(1);
      assertThat(lines.get(0).get("success").asBoolean()).as(sql).isFalse();
      assertThat(lines.get(0).get("error").asText()).as(sql).isNotEmpty();
      assertThat(lines.get(0).has("db_error")).as(sql).isTrue();
    }
    assertThat(db.factoryCalls.get()).isZero();
    assertThat(db.events).isEmpty();
  }

  /** 슬롯 계정이 하나도 없는 같은 테넌트 기술자 — 슬롯 계정을 만들기 전에 캐시된 옛 기술자를 흉내 낸다(WD-263). */
  static final StoreDescriptor NO_SLOT_DESCRIPTOR =
      new StoreDescriptor(
          5L,
          "TIBERO",
          "10.0.0.1",
          8629,
          "tibero",
          "GENIA_T5",
          new StoreDescriptor.Account("GENIA_T5", "pw-secret"),
          new StoreDescriptor.Account("GENIA_T5_RO", "ro-secret"),
          List.of(),
          3L);

  /**
   * WD-263: 기술자에 슬롯이 없으면 새로 받아(refresh) 다시 확인한다 — 새로 받아도 없으면 지금처럼 {@code NO_SLOT_ACCOUNT}. 커넥션은 빌리지
   * 않는다. (스펙이 「무효화하고 바로 실패」에서 「한 번 새로 받아 확인」으로 동작을 바꿨다.)
   */
  @Test
  void missingSlotAccount_failsWhenStillMissingAfterRefresh() throws Exception {
    when(descriptors.refresh(eq(5L), any(Duration.class))).thenReturn(SLOT_DESCRIPTOR);

    List<JsonNode> lines = stream(SLOT_DESCRIPTOR, 7, "SELECT 1 FROM DUAL", 30);

    assertThat(lines).hasSize(1);
    assertThat(lines.get(0).get("error").asText()).isEqualTo(SlotQueryRunner.NO_SLOT_ACCOUNT);
    verify(descriptors).refresh(eq(5L), any(Duration.class));
    assertThat(db.factoryCalls.get()).isZero();
  }

  /**
   * WD-263: 새 슬롯 계정을 만든 직후(캐시된 기술자에 아직 슬롯이 없음) 한 번 새로 받은 기술자로 정상 실행한다. 재조회는 스트리밍 첫 줄을 쓰기 전에 끝난다 —
   * 이미 보낸 줄을 다시 보내지 않게.
   */
  @Test
  void missingSlot_refreshesOnce_thenSucceeds() throws Exception {
    AtomicBoolean refreshed = new AtomicBoolean();
    when(descriptors.refresh(eq(5L), any(Duration.class)))
        .thenAnswer(
            inv -> {
              refreshed.set(true);
              return SLOT_DESCRIPTOR;
            });
    db.resultSet = idRows(2, 0, new AtomicInteger());
    AtomicBoolean wroteBeforeRefresh = new AtomicBoolean();
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    OutputStream out =
        new OutputStream() {
          @Override
          public void write(int b) {
            // 예외로 알리면 끊김으로 처리되므로 표시만 남긴다.
            if (!refreshed.get()) {
              wroteBeforeRefresh.set(true);
            }
            sink.write(b);
          }
        };

    try (Deadline deadline = new Deadline(30)) {
      runner.stream(NO_SLOT_DESCRIPTOR, 2, "SELECT id FROM t", deadline, out);
    }
    List<JsonNode> lines = lines(sink.toString(StandardCharsets.UTF_8));

    assertThat(wroteBeforeRefresh).as("재조회는 첫 줄보다 먼저 끝난다").isFalse();
    assertThat(lines).hasSize(1 + 2 + 1);
    assertThat(lines.get(0).toString()).isEqualTo("{\"columns\":[\"id\"]}");
    assertThat(lines.get(lines.size() - 1).toString())
        .isEqualTo("{\"success\":true,\"row_count\":2}");
    // 새로 받은 기술자의 슬롯 계정(S2)으로 빌렸다 — 옛 기술자에는 S2 가 없어 빌릴 수 없다.
    assertThat(db.slotFactorySlots).containsExactly(2);
    verify(descriptors, times(1)).refresh(eq(5L), any(Duration.class));
    // 강제 갱신이 무효화를 겸한다 — 따로 무효화하면 동시 요청의 재조회가 한 번으로 묶이지 않는다.
    verify(descriptors, never()).invalidate(anyLong());
  }

  /** WD-263: 새로 받아도 슬롯이 없으면 실패하고, 재조회는 요청당 한 번뿐이다(무한 반복 없음). */
  @Test
  void missingSlot_stillMissingAfterRefresh_failsWithoutSecondRefresh() throws Exception {
    when(descriptors.refresh(eq(5L), any(Duration.class))).thenReturn(NO_SLOT_DESCRIPTOR);

    List<JsonNode> lines = stream(NO_SLOT_DESCRIPTOR, 2, "SELECT id FROM t", 30);

    assertThat(lines).hasSize(1);
    assertThat(lines.get(0).get("success").asBoolean()).isFalse();
    assertThat(lines.get(0).get("error").asText()).isEqualTo(SlotQueryRunner.NO_SLOT_ACCOUNT);
    verify(descriptors, times(1)).refresh(eq(5L), any(Duration.class));
    verifyNoMoreInteractions(descriptors);
    assertThat(db.factoryCalls.get()).isZero();
  }

  /**
   * WD-263: 재조회 자체가 실패하면(api 503·시간 초과) 「기술자를 얻지 못함」 문구로 실패 줄 하나를 쓴다 — 컨트롤러의 기술자 조회 실패와 같은 문구다. 슬롯이
   * 없다고 단정할 수 없으니 {@code NO_SLOT_ACCOUNT} 가 아니다(내가 정한 것).
   */
  @Test
  void missingSlot_refreshFails_storeUnavailableLine() throws Exception {
    when(descriptors.refresh(eq(5L), any(Duration.class)))
        .thenThrow(new StoreUnavailableException("503"));

    List<JsonNode> lines = stream(NO_SLOT_DESCRIPTOR, 2, "SELECT id FROM t", 30);

    assertThat(lines).hasSize(1);
    assertThat(lines.get(0).get("success").asBoolean()).isFalse();
    assertThat(lines.get(0).get("error").asText()).isEqualTo("테넌트 저장소 정보를 가져오지 못했습니다(503).");
    assertThat(lines.get(0).has("db_error")).isTrue();
    verify(descriptors, times(1)).refresh(eq(5L), any(Duration.class));
    assertThat(db.factoryCalls.get()).isZero();
  }

  /** 슬롯이 있으면 재조회하지 않는다 — 정상 요청마다 기술자를 새로 받지 않는다(스펙 실패 조건). */
  @Test
  void slotPresent_noRefresh() throws Exception {
    db.resultSet = idRows(1, 0, new AtomicInteger());

    stream("SELECT id FROM t");

    verify(descriptors, never()).refresh(anyLong(), any(Duration.class));
  }

  @Test
  void connectFailure_fixedMessage_invalidatesDescriptor_noPasswordLeak() throws Exception {
    db.connectFailure = new SQLException("login denied for GENIA_T5_S2/slot-secret", "28000", -1);

    List<JsonNode> lines = stream("SELECT 1 FROM DUAL");

    assertThat(lines).hasSize(1);
    assertThat(lines.get(0).get("error").asText()).isEqualTo(LeasedExecution.CONNECT_FAILED);
    assertThat(lines.get(0).toString()).doesNotContain("slot-secret");
    verify(descriptors, atLeastOnce()).invalidate(5L);
  }

  /** 기한(토큰 만료)이 스트리밍 도중 지나면 남은 행을 읽지 않고 시간 초과 마지막 줄로 끝낸다. */
  @Test
  void deadlineDuringStream_timeoutLastLine() throws Exception {
    ResultSet rs = idRows(1_000_000, 0, new AtomicInteger());
    AtomicInteger seen = new AtomicInteger();
    when(rs.getBigDecimal(1))
        .thenAnswer(
            inv -> {
              if (seen.incrementAndGet() == 2) {
                Thread.sleep(1_100);
              }
              return BigDecimal.ONE;
            });
    db.resultSet = rs;

    List<JsonNode> lines = stream(SLOT_DESCRIPTOR, 2, "SELECT id FROM t", 1);

    JsonNode last = lines.get(lines.size() - 1);
    assertThat(last.get("success").asBoolean()).isFalse();
    assertThat(last.get("error").asText()).isEqualTo(QueryRunner.timeoutMessage(1));
    assertThat(lines.size()).isLessThan(10);
    assertThat(db.events).endsWith("ROLLBACK");
  }

  /** 값은 비스트리밍 결과와 같은 ValueConverter 규칙, 겹치는 컬럼 이름은 고유하게. */
  @Test
  void valuesUseValueConverter_andColumnNamesAreUnique() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    ResultSetMetaData md = mock(ResultSetMetaData.class);
    when(rs.getMetaData()).thenReturn(md);
    when(md.getColumnCount()).thenReturn(3);
    when(md.getColumnLabel(anyInt())).thenReturn("AMOUNT", "AMOUNT", "CREATED");
    when(md.getColumnType(1)).thenReturn(Types.NUMERIC);
    when(md.getPrecision(1)).thenReturn(18);
    when(md.getScale(1)).thenReturn(2);
    when(md.getColumnType(2)).thenReturn(Types.VARCHAR);
    when(md.getColumnType(3)).thenReturn(Types.TIMESTAMP);
    when(rs.next()).thenReturn(true, false);
    when(rs.getBigDecimal(1)).thenReturn(new BigDecimal("0.0000001"));
    when(rs.getString(2)).thenReturn("가");
    when(rs.getTimestamp(3)).thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 1, 2, 3, 4, 5)));
    db.resultSet = rs;

    List<JsonNode> lines = stream("SELECT a.amount, b.amount, created FROM a, b");

    assertThat(lines.get(0).toString())
        .isEqualTo("{\"columns\":[\"amount\",\"amount_2\",\"created\"]}");
    assertThat(rawLines.get(1))
        .isEqualTo(
            "{\"row\":{\"amount\":0.00,\"amount_2\":\"가\",\"created\":\"2026-01-02T03:04:05\"}}");
    verify(rs, never()).getObject(anyInt());
  }

  /**
   * 실서버 테스트(TiberoLiveSlotStreamTest)가 슬롯 풀로 흘리는 문장이 가드를 통과한다 — 실서버 실패가 가드 때문이 아니게. 세션 확인은 이제 SQL 이
   * 아니라 Java 쪽 물리 커넥션 비교라 가드를 거치는 문장은 이 둘뿐이다.
   */
  @Test
  void guard_acceptsLiveTestQueries() {
    for (String sql :
        new String[] {
          "SELECT \"ID\", \"NAME\" FROM \"ORDERS\" ORDER BY \"ID\"", "SELECT \"V\" FROM \"SECRET\""
        }) {
      assertThat(QueryRunner.validatedQuery(sql, "GENIA_T5")).isEqualTo(sql);
    }
  }

  /**
   * 슬롯 풀이 꽉 차 대기 시간이 지나면(Hikari 의 원인 없는 SQLTransientConnectionException) 접속 실패가 아니라 「같은 등급 슬롯의 동시
   * 조회가 많습니다」 실패 줄이다(Ruling 17). 기술자 탓이 아니므로 캐시를 지우지 않는다.
   */
  @Test
  void slotPoolExhausted_busyMessage_notConnectFailed_keepsDescriptor() throws Exception {
    db.connectFailure =
        new java.sql.SQLTransientConnectionException(
            "tibero-tenant-5-GENIA_T5_S2 - Connection is not available, request timed out after 3000ms.");

    List<JsonNode> lines = stream("SELECT 1 FROM DUAL");

    assertThat(lines).hasSize(1);
    assertThat(lines.get(0).get("success").asBoolean()).isFalse();
    assertThat(lines.get(0).get("error").asText()).isEqualTo(SlotQueryRunner.SLOT_BUSY);
    assertThat(SlotQueryRunner.SLOT_BUSY).isEqualTo("같은 등급 슬롯의 동시 조회가 많습니다 — 잠시 후 다시 실행하세요");
    verify(descriptors, never()).invalidate(anyLong());
  }
}
