package com.smartfirehub.pipeline.service.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.service.executor.ExecutorClient.DbError;
import com.smartfirehub.pipeline.service.executor.ExecutorClient.SqlExecuteResult;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link DbExecutorBulkMover}: 옮기기 SQL·선행문 배선, 실패 번역, 테넌트 가드(WD-77). */
class DbExecutorBulkMoverTest {

  private static final String S = "GENIA_T5";
  private static final String STG = "STG_0123456789ABCDEF01234567";
  private DbExecutorClient client;
  private DbExecutorBulkMover mover;

  @BeforeEach
  void setUp() {
    TenantContext.set(5L);
    client = mock(DbExecutorClient.class);
    mover = new DbExecutorBulkMover(client, 1800);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  private static SqlExecuteResult ok() {
    return new SqlExecuteResult(true, null, null, 1, "ok", null, null, null);
  }

  @Test
  void moveReplace_sendsDeleteAsPreStatement_andMoveBody_withTimeout() {
    when(client.executeSql(anyString(), anyList(), anyInt())).thenReturn(ok());

    mover.move(5L, S, "orders", STG, List.of("code"), true);

    org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(client)
        .executeSql(body.capture(), eq(List.of("DELETE FROM \"GENIA_T5\".\"ORDERS\"")), eq(1800));
    assertThat(body.getValue())
        .startsWith("INSERT INTO \"GENIA_T5\".\"ORDERS\" (\"ID\", \"CODE\", \"_UPDATED_AT\")")
        .endsWith("FROM \"GENIA_T5\".\"" + STG + "\" ORDER BY \"_SEQ\")");
  }

  @Test
  void moveAppend_hasNoPreStatement() {
    when(client.executeSql(anyString(), anyList(), anyInt())).thenReturn(ok());

    mover.move(5L, S, "orders", STG, List.of("code"), false);

    verify(client).executeSql(anyString(), eq(List.of()), eq(1800));
  }

  @Test
  void moveFailure_translatedToScriptExecutionException() {
    when(client.executeSql(anyString(), anyList(), anyInt()))
        .thenReturn(
            new SqlExecuteResult(
                false, null, null, 0, "", "boom", null, new DbError(-10007, "JDBC-10007:dup")));

    assertThatThrownBy(() -> mover.move(5L, S, "orders", STG, List.of("code"), true))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("JDBC-10007");
  }

  @Test
  void tenantMismatch_failsClosed() {
    assertThatThrownBy(() -> mover.move(6L, S, "orders", STG, List.of("code"), false))
        .isInstanceOf(IllegalStateException.class);
    org.mockito.Mockito.verifyNoInteractions(client);
  }
}
