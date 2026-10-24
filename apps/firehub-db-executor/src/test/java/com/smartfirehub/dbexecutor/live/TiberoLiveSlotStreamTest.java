package com.smartfirehub.dbexecutor.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dbexecutor.sql.Deadline;
import com.smartfirehub.dbexecutor.sql.SlotQueryRunner;
import com.smartfirehub.dbexecutor.store.HikariPoolFactory;
import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.store.TenantPools;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tibero 실서버 슬롯 스트리밍 테스트(WD-78 Task 13, 스펙 §5.1·§5.2).
 *
 * <ol>
 *   <li>버리는 테넌트(예약 대역 {@code GENIA_T9xxxxxxxx})와 슬롯 계정 S2 를 만들고, 소유 계정으로 테이블 하나에만 SELECT 를 준다.
 *   <li>실제 슬롯 풀(Hikari, 크기 3·최소 유휴 0, {@code CURRENT_SCHEMA = GENIA_T{id}})로 10,050 행을 스키마 없는 이름으로
 *       끝까지 스트리밍한다(행 상한 없음).
 *   <li>권한 없는 테이블은 DB 가 거부한다(실패 마지막 줄).
 *   <li>REVOKE 뒤 <b>같은 풀 커넥션(같은 세션)</b>으로 같은 문장을 다시 읽으면 거부된다 — Task 12 가 확인하지 못한 §5.1 의 "풀로 재사용해도
 *       문장 단위 권한 확인"을 실서버로 확인한다. 같은 세션인지는 SQL 이 아니라 Java 쪽에서 본다 — 슬롯 풀에서 빌린 커넥션의 물리 커넥션 (Hikari
 *       프록시를 {@code unwrap} 한 드라이버 커넥션)이 REVOKE 전후로 같은 객체인지. 읽기가 순차이고 최소 유휴가 0 이라 풀은 물리 커넥션 하나만 만들고
 *       같은 스레드에 그것을 다시 내준다. (Tibero 는 {@code SYS_CONTEXT('USERENV','SID')} 를 JDBC-11006 으로 거부해 SQL
 *       로 세션을 식별하지 않는다.)
 * </ol>
 *
 * <p>{@code TIBERO_TEST_*} 나 드라이버가 없으면 건너뛴다. 실행은 {@code scripts/tibero-test.sh db-executor}. 접속 값은
 * 출력하지 않는다. 만든 계정(소유·RO·슬롯)만 끝나면 지운다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoLiveSlotStreamTest {

  private static final int SLOT = 2;
  private static final int ROWS = 10_050;

  private final ObjectMapper mapper =
      new ObjectMapper().enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

  private TiberoLiveAccount account;
  private TenantPools slotPools;
  private SlotQueryRunner runner;
  private StoreDescriptor descriptor;

  @BeforeAll
  void setUp() throws Exception {
    account = TiberoLiveAccount.open();
    slotPools = TenantPools.forSlots(HikariPoolFactory.forSlots(), 8);
    // 슬롯 풀이 세션을 쥐고 있으면 DROP USER 가 거절되므로 먼저 닫는다.
    account.closeBeforeDrop(slotPools::closeAll);
    account.createSlot(SLOT);
    Connection owner = account.owner();
    String s = "\"" + account.schema() + "\"";
    TiberoLiveAccount.exec(
        owner, "CREATE TABLE " + s + ".\"ORDERS\" (\"ID\" NUMBER(19), \"NAME\" VARCHAR2(50))");
    TiberoLiveAccount.exec(
        owner,
        "INSERT INTO "
            + s
            + ".\"ORDERS\" SELECT LEVEL, 'n' || LEVEL FROM DUAL CONNECT BY LEVEL <= "
            + ROWS);
    TiberoLiveAccount.exec(owner, "CREATE TABLE " + s + ".\"SECRET\" (\"V\" NUMBER)");
    TiberoLiveAccount.exec(owner, "INSERT INTO " + s + ".\"SECRET\" VALUES (1)");
    owner.commit();
    // Task 12 의 동기화처럼 소유 계정이 슬롯 계정에 테이블 하나만 GRANT 한다.
    TiberoLiveAccount.exec(
        owner, "GRANT SELECT ON " + s + ".\"ORDERS\" TO \"" + account.slotUser(SLOT) + "\"");
    descriptor = account.descriptorWithSlots();
    runner = new SlotQueryRunner(slotPools, mock(StoreDescriptorClient.class), mapper);
  }

  @AfterAll
  void tearDown() throws Exception {
    if (account != null) {
      account.close();
    }
  }

  private List<JsonNode> stream(String sql) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (Deadline deadline = new Deadline(120)) {
      runner.stream(descriptor, SLOT, sql, deadline, out);
    }
    List<JsonNode> lines = new ArrayList<>();
    for (String l : out.toString(StandardCharsets.UTF_8).split("\n")) {
      lines.add(mapper.readTree(l));
    }
    return lines;
  }

  /** 지금 슬롯 풀이 내주는 물리 커넥션(드라이버 객체). 러너와 같은 풀·같은 스레드로 빌렸다 바로 돌려준다 — 같은 객체면 같은 DB 세션이다. */
  private Connection physicalConnection() throws Exception {
    try (TenantPools.Lease lease = slotPools.borrow(descriptor, SLOT)) {
      return lease.connection().unwrap(Connection.class);
    }
  }

  @Test
  void live_slotStream_grantedTable_thenRevokeDeniesOnSamePooledSession() throws Exception {
    String query = "SELECT \"ID\", \"NAME\" FROM \"ORDERS\" ORDER BY \"ID\"";

    // 1) GRANT 된 테이블: 스키마 없는 이름(CURRENT_SCHEMA)으로 10,050 행 전부.
    Connection sessionBefore = physicalConnection();
    List<JsonNode> lines = stream(query);
    JsonNode last = lines.get(lines.size() - 1);
    System.out.println("LIVE slot stream lines=" + lines.size() + " last=" + last);
    assertThat(last.get("success").asBoolean()).as(last.toString()).isTrue();
    assertThat(last.get("row_count").asInt()).isEqualTo(ROWS);
    assertThat(lines).hasSize(1 + ROWS + 1);
    assertThat(lines.get(0).toString()).isEqualTo("{\"columns\":[\"id\",\"name\"]}");
    assertThat(lines.get(ROWS).get("row").get("id").asLong()).isEqualTo(ROWS);

    // 2) GRANT 없는 테이블은 DB 가 거부한다.
    List<JsonNode> denied = stream("SELECT \"V\" FROM \"SECRET\"");
    JsonNode deniedLast = denied.get(denied.size() - 1);
    System.out.println("LIVE slot ungranted=" + deniedLast);
    assertThat(deniedLast.get("success").asBoolean()).isFalse();
    assertThat(deniedLast.get("db_error").isNull()).isFalse();

    // 3) REVOKE 뒤 같은 풀 세션으로 같은 문장 → 거부(문장 단위 권한 확인, 스펙 §5.1).
    assertThat(physicalConnection()).as("REVOKE 전 — 같은 물리 커넥션").isSameAs(sessionBefore);
    TiberoLiveAccount.exec(
        account.owner(),
        "REVOKE SELECT ON \""
            + account.schema()
            + "\".\"ORDERS\" FROM \""
            + account.slotUser(SLOT)
            + "\"");
    List<JsonNode> afterRevoke = stream(query);
    JsonNode revokedLast = afterRevoke.get(afterRevoke.size() - 1);
    Connection sessionAfter = physicalConnection();
    System.out.println(
        "LIVE slot afterRevoke="
            + revokedLast
            + " sameSession="
            + (sessionAfter == sessionBefore)
            + " lines="
            + afterRevoke.size());
    assertThat(sessionAfter).as("REVOKE 뒤 거부된 조회도 같은 풀 세션에서 돌았다").isSameAs(sessionBefore);
    assertThat(revokedLast.get("success").asBoolean()).as(revokedLast.toString()).isFalse();
    assertThat(revokedLast.get("db_error").isNull()).isFalse();
    assertThat(afterRevoke).as("행이 하나도 나가지 않았다").hasSize(1);
  }
}
