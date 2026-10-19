package com.smartfirehub.dataset.storage.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.tibero.PlannedAccount;
import com.smartfirehub.dataset.storage.tibero.ProvisioningStep;
import com.smartfirehub.dataset.storage.tibero.TiberoAccountPlan;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

/** Tibero 계정 생성 실행기 단위 테스트. 보상 DROP 순서와 비밀 비노출을 확인한다. */
class ExternalAccountProvisionerTest {

  private static final ExternalDbTarget TARGET =
      new ExternalDbTarget(DataStoreBackend.TIBERO, "h", 8629, "tibero");
  private static final String SECRET_OWNER = "OwnerSecretAbc123xyz789Q";

  private final ExternalDbConnector connector = mock(ExternalDbConnector.class);
  private final ExternalAccountProvisioner provisioner = new ExternalAccountProvisioner(connector);
  private final Connection conn = mock(Connection.class);
  private final Statement stmt = mock(Statement.class);

  /** 계정 존재 사전 확인({@code ALL_USERS}) 용. 기본은 빈 결과 — 아무 계정도 없다. */
  private final PreparedStatement precheck = mock(PreparedStatement.class);

  private final ResultSet precheckRows = mock(ResultSet.class);
  private TiberoAccountPlan plan;

  @BeforeEach
  void setUp() throws Exception {
    List<PlannedAccount> list = new ArrayList<>();
    for (DataStoreAccountRole role : DataStoreAccountRole.all()) {
      String pw =
          role == DataStoreAccountRole.OWNER ? SECRET_OWNER : "Pw" + role.name() + "AbcdefghijKLMN";
      list.add(new PlannedAccount(role, role.usernameFor(5), pw));
    }
    plan = TiberoAccountPlan.of(5, null, list);
    when(connector.open(TARGET, "sys", "dbapw")).thenReturn(conn);
    when(conn.createStatement()).thenReturn(stmt);
    when(conn.prepareStatement(anyString())).thenReturn(precheck);
    when(precheck.executeQuery()).thenReturn(precheckRows);
  }

  @Test
  void provision_executesAllStepsInOrder_returnsCreatedUsernames() throws Exception {
    List<String> created = provisioner.provision(TARGET, "sys", "dbapw", plan);

    assertThat(created).hasSize(12).startsWith("GENIA_T5", "GENIA_T5_RO", "GENIA_T5_S1");
    InOrder order = Mockito.inOrder(stmt);
    for (ProvisioningStep step : plan.provisioningSteps()) {
      order.verify(stmt).execute(step.sql());
    }
    verify(stmt, times(25)).execute(anyString());
    verify(stmt, times(25)).setQueryTimeout(10);
    verify(stmt, times(25)).close();
    verify(conn).close();
  }

  @Test
  void provision_executesPrecheckWithAll12UsernamesBeforeAnyDdl() throws Exception {
    provisioner.provision(TARGET, "sys", "dbapw", plan);

    InOrder order = Mockito.inOrder(conn, precheck, stmt);
    order.verify(conn).prepareStatement(argThat(sql -> sql.contains("ALL_USERS")));
    order.verify(precheck).setQueryTimeout(10);
    order.verify(precheck).executeQuery();
    order.verify(stmt).execute(argThat(s -> s.startsWith("CREATE USER \"GENIA_T5\" ")));
    for (int i = 0; i < 12; i++) {
      verify(precheck).setString(i + 1, DataStoreAccountRole.all().get(i).usernameFor(5));
    }
    verify(precheck).close();
    verify(precheckRows).close();
  }

  /** 같은 이름의 계정이 이미 있으면 DDL·DROP 을 하나도 실행하지 않는다 — 남의 계정(스키마)을 지우지 않는다. */
  @Test
  void provision_precheckFindsExisting_throwsListingThem_noDdlNoDrop() throws Exception {
    when(precheckRows.next()).thenReturn(true, true, false);
    when(precheckRows.getString(1)).thenReturn("GENIA_T5_S3", "GENIA_T5");

    assertThatThrownBy(() -> provisioner.provision(TARGET, "sys", "dbapw", plan))
        .isInstanceOfSatisfying(
            DataStoreProvisioningException.class,
            e -> {
              assertThat(e.code()).isEqualTo("DATA_STORE_PROVISIONING_FAILED");
              // 계획 순서로 나열한다.
              assertThat(e.getMessage())
                  .isEqualTo("이미 존재하는 계정이 있어 생성하지 않았습니다: GENIA_T5, GENIA_T5_S3");
            });
    verify(conn, never()).createStatement();
    verify(stmt, never()).execute(anyString());
    verify(conn).close();
  }

  /** 존재 확인 자체가 실패하면 모르는 채로 진행하지 않는다. */
  @Test
  void provision_precheckQueryFails_throws_noDdl() throws Exception {
    when(precheck.executeQuery())
        .thenThrow(new SQLException("ORA-00942 table or view does not exist"));

    assertThatThrownBy(() -> provisioner.provision(TARGET, "sys", "dbapw", plan))
        .isInstanceOfSatisfying(
            DataStoreProvisioningException.class,
            e ->
                assertThat(e.getMessage())
                    .startsWith("계정 존재 여부를 확인하지 못했습니다: ")
                    .contains("ORA-00942"));
    verify(conn, never()).createStatement();
    verify(stmt, never()).execute(anyString());
    verify(conn).close();
  }

  /** 서버가 거절한 CREATE USER(일반 SQLException)는 계정이 만들어지지 않았다 — 실패한 계정은 지우지 않고 앞서 만든 계정만 역순 DROP 한다. */
  @Test
  void provision_serverRejectsCreate_dropsOnlyPreviouslyCreated() throws Exception {
    doThrow(new SQLException("ORA-01920 user name conflicts"))
        .when(stmt)
        .execute(argThat(s -> s != null && s.startsWith("CREATE USER \"GENIA_T5_S1\"")));

    assertThatThrownBy(() -> provisioner.provision(TARGET, "sys", "dbapw", plan))
        .isInstanceOfSatisfying(
            DataStoreProvisioningException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
              assertThat(e.code()).isEqualTo("DATA_STORE_PROVISIONING_FAILED");
              assertThat(e.getMessage()).contains("GENIA_T5_S1 계정 생성 실패");
              assertThat(e.getMessage())
                  .doesNotContain(SECRET_OWNER)
                  .doesNotContain("IDENTIFIED BY");
            });

    InOrder order = Mockito.inOrder(stmt);
    order.verify(stmt).execute("DROP USER \"GENIA_T5_RO\" CASCADE");
    order.verify(stmt).execute("DROP USER \"GENIA_T5\" CASCADE");
    verify(stmt, never()).execute("DROP USER \"GENIA_T5_S1\" CASCADE");
    // 6번째 단계 이후는 실행되지 않는다: CREATE 6 + DROP 2 = 8번만 실행.
    verify(stmt, times(8)).execute(anyString());
    verify(stmt, never()).execute("GRANT CREATE SESSION TO \"GENIA_T5_S1\"");
    verify(conn).close();
  }

  /** 쿼리 시간 초과는 서버가 CREATE 를 끝냈을 수 있다 — 실패한 계정까지 가장 먼저 지운다. */
  @Test
  void provision_createTimesOut_alsoDropsFailedAccountFirst() throws Exception {
    doThrow(new SQLTimeoutException("query timed out"))
        .when(stmt)
        .execute(argThat(s -> s != null && s.startsWith("CREATE USER \"GENIA_T5_S1\"")));

    assertThatThrownBy(() -> provisioner.provision(TARGET, "sys", "dbapw", plan))
        .isInstanceOf(DataStoreProvisioningException.class);

    InOrder order = Mockito.inOrder(stmt);
    order.verify(stmt).execute("DROP USER \"GENIA_T5_S1\" CASCADE");
    order.verify(stmt).execute("DROP USER \"GENIA_T5_RO\" CASCADE");
    order.verify(stmt).execute("DROP USER \"GENIA_T5\" CASCADE");
    // CREATE 6 + DROP 3 = 9번.
    verify(stmt, times(9)).execute(anyString());
  }

  /** SQLState 08xxx(연결 오류)도 모호한 실패로 본다. */
  @Test
  void provision_connectionErrorDuringCreate_alsoDropsFailedAccount() throws Exception {
    doThrow(new SQLException("connection reset", "08006"))
        .when(stmt)
        .execute(argThat(s -> s != null && s.startsWith("CREATE USER \"GENIA_T5_RO\"")));

    assertThatThrownBy(() -> provisioner.provision(TARGET, "sys", "dbapw", plan))
        .isInstanceOf(DataStoreProvisioningException.class);

    verify(stmt).execute("DROP USER \"GENIA_T5_RO\" CASCADE");
    verify(stmt).execute("DROP USER \"GENIA_T5\" CASCADE");
  }

  @Test
  void mayHaveCompleted_classifiesAmbiguousFailures() {
    assertThat(ExternalAccountProvisioner.mayHaveCompleted(new SQLTimeoutException("t"))).isTrue();
    assertThat(
            ExternalAccountProvisioner.mayHaveCompleted(new java.sql.SQLRecoverableException("r")))
        .isTrue();
    assertThat(ExternalAccountProvisioner.mayHaveCompleted(new SQLException("c", "08001")))
        .isTrue();
    assertThat(ExternalAccountProvisioner.mayHaveCompleted(new IllegalStateException("x")))
        .isTrue();
    assertThat(ExternalAccountProvisioner.mayHaveCompleted(new SQLException("ORA-01920", "42000")))
        .isFalse();
    assertThat(ExternalAccountProvisioner.mayHaveCompleted(new SQLException("no state"))).isFalse();
  }

  @Test
  void provision_failureMessageScrubsPasswordEchoedByDriver() throws Exception {
    doThrow(new SQLException("syntax error near " + SECRET_OWNER))
        .when(stmt)
        .execute(argThat(s -> s != null && s.startsWith("CREATE USER \"GENIA_T5\" ")));

    assertThatThrownBy(() -> provisioner.provision(TARGET, "sys", "dbapw", plan))
        .isInstanceOf(DataStoreProvisioningException.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET_OWNER));
    // 서버가 거절한 첫 CREATE 라 만든 계정이 없다 — DROP 은 하나도 실행하지 않는다.
    verify(stmt, never()).execute(argThat(s -> s != null && s.startsWith("DROP USER")));
  }

  @Test
  void provision_failureMessageCutsAtIdentifiedBy() throws Exception {
    doThrow(new SQLException("error in CREATE USER \"GENIA_T5\" identified by \"" + "OwnerSec"))
        .when(stmt)
        .execute(argThat(s -> s != null && s.startsWith("CREATE USER \"GENIA_T5\" ")));

    assertThatThrownBy(() -> provisioner.provision(TARGET, "sys", "dbapw", plan))
        .isInstanceOf(DataStoreProvisioningException.class)
        .satisfies(
            e ->
                assertThat(e.getMessage())
                    .doesNotContainIgnoringCase("IDENTIFIED BY")
                    .doesNotContain("OwnerSec")
                    .contains("…"));
  }

  @Test
  void provision_runtimeExceptionFromDriver_alsoCompensatesAndWraps() throws Exception {
    doThrow(new IllegalStateException("driver blew up"))
        .when(stmt)
        .execute(argThat(s -> s != null && s.startsWith("CREATE USER \"GENIA_T5_S1\"")));

    assertThatThrownBy(() -> provisioner.provision(TARGET, "sys", "dbapw", plan))
        .isInstanceOf(DataStoreProvisioningException.class);
    // 드라이버 비검사 예외는 모호한 실패라 실패한 S1 도 지운다.
    InOrder order = Mockito.inOrder(stmt);
    order.verify(stmt).execute("DROP USER \"GENIA_T5_S1\" CASCADE");
    order.verify(stmt).execute("DROP USER \"GENIA_T5_RO\" CASCADE");
    order.verify(stmt).execute("DROP USER \"GENIA_T5\" CASCADE");
  }

  @Test
  void provision_dropFailureDuringCompensation_stillThrowsOriginal() throws Exception {
    doThrow(new SQLException("ORA-01920 conflict"))
        .when(stmt)
        .execute(argThat(s -> s != null && s.startsWith("CREATE USER \"GENIA_T5_S1\"")));
    doThrow(new SQLException("ORA-01031 insufficient privileges"))
        .when(stmt)
        .execute(argThat(s -> s != null && s.startsWith("DROP USER")));

    assertThatThrownBy(() -> provisioner.provision(TARGET, "sys", "dbapw", plan))
        .isInstanceOfSatisfying(
            DataStoreProvisioningException.class,
            e -> assertThat(e.getMessage()).contains("GENIA_T5_S1 계정 생성 실패"));
    // 첫 DROP 이 실패해도 나머지 DROP 은 계속 시도한다.
    verify(stmt).execute("DROP USER \"GENIA_T5_RO\" CASCADE");
    verify(stmt).execute("DROP USER \"GENIA_T5\" CASCADE");
  }

  @Test
  void provision_connectFails_throwsProvisioningExceptionWithReason() throws Exception {
    when(connector.open(TARGET, "sys", "dbapw"))
        .thenThrow(new ExternalDbConnectionException("Tibero JDBC 드라이버가 설치되지 않았습니다"));

    assertThatThrownBy(() -> provisioner.provision(TARGET, "sys", "dbapw", plan))
        .isInstanceOfSatisfying(
            DataStoreProvisioningException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
              assertThat(e.getMessage()).contains("드라이버");
            });
    verify(stmt, never()).execute(anyString());
    verify(conn, never()).prepareStatement(anyString());
  }

  @Test
  void dropAccounts_dropsInReverse_neverThrows() throws Exception {
    doThrow(new SQLException("ORA-01918 user does not exist"))
        .when(stmt)
        .execute("DROP USER \"GENIA_T5_RO\" CASCADE");

    assertThatCode(
            () ->
                provisioner.dropAccounts(
                    TARGET, "sys", "dbapw", plan, List.of("GENIA_T5", "GENIA_T5_RO")))
        .doesNotThrowAnyException();
    // 역순: RO 가 먼저(실패해도 계속), 그다음 OWNER.
    InOrder order = Mockito.inOrder(stmt);
    order.verify(stmt).execute("DROP USER \"GENIA_T5_RO\" CASCADE");
    order.verify(stmt).execute("DROP USER \"GENIA_T5\" CASCADE");
    verify(conn).close();
  }

  @Test
  void dropAccounts_connectFails_neverThrows() throws Exception {
    when(connector.open(TARGET, "sys", "dbapw"))
        .thenThrow(new ExternalDbConnectionException("연결 실패: refused"));
    assertThatCode(
            () -> provisioner.dropAccounts(TARGET, "sys", "dbapw", plan, List.of("GENIA_T5")))
        .doesNotThrowAnyException();
  }
}
