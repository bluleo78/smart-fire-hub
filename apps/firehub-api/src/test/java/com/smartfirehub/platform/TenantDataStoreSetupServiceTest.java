package com.smartfirehub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreFeatures;
import com.smartfirehub.dataset.storage.DataStorePasswordGenerator;
import com.smartfirehub.dataset.storage.DataStoreProvisionMode;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.dataset.storage.StoredAccount;
import com.smartfirehub.dataset.storage.TenantDataStoreRepository;
import com.smartfirehub.dataset.storage.TenantDataStoreRow;
import com.smartfirehub.dataset.storage.TenantDataStoreStatus;
import com.smartfirehub.dataset.storage.external.AccountCheck;
import com.smartfirehub.dataset.storage.external.ConnectionTestResult;
import com.smartfirehub.dataset.storage.external.DataStoreConnectionTester;
import com.smartfirehub.dataset.storage.external.DataStoreProvisioningException;
import com.smartfirehub.dataset.storage.external.ExternalAccountProvisioner;
import com.smartfirehub.dataset.storage.external.ExternalDbTarget;
import com.smartfirehub.dataset.storage.tibero.PlannedAccount;
import com.smartfirehub.dataset.storage.tibero.TiberoAccountPlan;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.platform.dto.DataStoreBackendOption;
import com.smartfirehub.platform.dto.DataStoreConnectionTestRequest;
import com.smartfirehub.platform.dto.DataStoreScriptResponse;
import com.smartfirehub.platform.dto.DataStoreSetupRequest;
import com.smartfirehub.platform.dto.DataStoreVerifyResponse;
import com.smartfirehub.platform.dto.DbaCredentials;
import com.smartfirehub.platform.dto.TenantDataStoreResponse;
import com.smartfirehub.platform.exception.TenantNotFoundException;
import com.smartfirehub.platform.repository.PlatformTenantRepository;
import com.smartfirehub.platform.service.PlatformAuditRecorder;
import com.smartfirehub.platform.service.TenantDataStoreSetupService;
import com.smartfirehub.platform.service.TenantDataStoreSetupService.SetupOutcome;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 테넌트 생성 시 외부 저장소 설정(행 저장·외부 생성)·보상·캐시 무효화와 운영자 API(WD-73).
 *
 * <p>EncryptionService 는 패키지 전용 생성자라 이 패키지에서 만들 수 없어 되돌릴 수 있는 가짜({@code enc:} 접두)로 대체한다.
 */
@ExtendWith(MockitoExtension.class)
class TenantDataStoreSetupServiceTest {

  @Mock TenantDataStoreRepository repository;
  @Mock DataStoreFeatures features;
  @Mock EncryptionService encryptionService;
  @Mock ExternalAccountProvisioner provisioner;
  @Mock PlatformTenantRepository tenantRepository;
  @Mock PlatformAuditRecorder auditRecorder;
  @Mock DataStoreConnectionTester tester;
  @Mock DataStoreResolver resolver;

  private final DataStorePasswordGenerator generator = new DataStorePasswordGenerator();
  private TenantDataStoreSetupService service;

  private static final DbaCredentials DBA = new DbaCredentials("sys", "Sup3rSecretDbaPw");

  @BeforeEach
  void setUp() {
    service =
        new TenantDataStoreSetupService(
            repository,
            features,
            generator,
            encryptionService,
            provisioner,
            tenantRepository,
            auditRecorder,
            tester,
            resolver,
            // mock 템플릿은 콜백을 실행하지 않으므로 실물을 쓴다 — 매니저만 mock 이라 실제 트랜잭션은 열리지 않지만 콜백은 돈다.
            new TransactionTemplate(mock(PlatformTransactionManager.class)));
    TransactionSynchronizationManager.initSynchronization();
  }

  @AfterEach
  void tearDown() {
    // 동기화를 일부러 끈 테스트가 있어 활성일 때만 정리한다.
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  private void givenTiberoAvailable() {
    when(features.isAvailable(DataStoreBackend.TIBERO)).thenReturn(true);
  }

  private void givenFakeEncryption() {
    when(encryptionService.encrypt(any())).thenAnswer(i -> "enc:" + i.getArgument(0));
  }

  private static DataStoreSetupRequest tibero(DataStoreProvisionMode mode, DbaCredentials dba) {
    return new DataStoreSetupRequest(
        DataStoreBackend.TIBERO, "db.example.com", 8629, "TIBERO", null, mode, dba);
  }

  /** 지금까지 등록된 모든 동기화에 afterCompletion 을 보낸다 — 순서·개수(무효화 + 보상)에 기대지 않는다. */
  private static void completeAll(int status) {
    for (TransactionSynchronization sync :
        TransactionSynchronizationManager.getSynchronizations()) {
      sync.afterCompletion(status);
    }
  }

  /** 지금까지 등록된 모든 동기화에 afterCommit 을 보낸다. */
  private static void commitAll() {
    for (TransactionSynchronization sync :
        TransactionSynchronizationManager.getSynchronizations()) {
      sync.afterCommit();
    }
  }

  /** DBA_AUTO 실행기가 주어진 계정을 만들었다고 답하게 한다. */
  private void givenProvisionerCreates(List<String> created) {
    when(provisioner.provision(
            any(ExternalDbTarget.class),
            eq("sys"),
            eq("Sup3rSecretDbaPw"),
            any(TiberoAccountPlan.class)))
        .thenReturn(created);
  }

  @Test
  void setUp_nullOrPostgresql_writesNothing() {
    SetupOutcome a = service.setUp(7L, null);
    SetupOutcome b =
        service.setUp(
            7L,
            new DataStoreSetupRequest(
                DataStoreBackend.POSTGRESQL, null, null, null, null, null, null));

    assertThat(a).isSameAs(SetupOutcome.none());
    assertThat(b).isSameAs(SetupOutcome.none());
    assertThat(a.auditDetails()).isEmpty();
    assertThat(a.setupScript()).isEmpty();
    verifyNoInteractions(repository, provisioner, resolver);
    assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
  }

  @Test
  void validate_backendUnavailable_throwsBeforeSideEffects() {
    when(features.isAvailable(DataStoreBackend.TIBERO)).thenReturn(false);
    when(features.isAvailable(DataStoreBackend.MYSQL)).thenReturn(false);

    assertThatThrownBy(() -> service.validate(tibero(DataStoreProvisionMode.SCRIPT, null)))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(e.code()).isEqualTo("DATA_STORE_BACKEND_UNAVAILABLE");
            });
    DataStoreSetupRequest mysql =
        new DataStoreSetupRequest(
            DataStoreBackend.MYSQL, "h", 3306, "d", null, DataStoreProvisionMode.SCRIPT, null);
    assertThatThrownBy(() -> service.validate(mysql))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> assertThat(e.code()).isEqualTo("DATA_STORE_BACKEND_UNAVAILABLE"));

    verifyNoInteractions(repository, provisioner);
  }

  @Test
  void validate_tiberoMissingFields_400() {
    givenTiberoAvailable();
    DataStoreProvisionMode s = DataStoreProvisionMode.SCRIPT;
    assertThatThrownBy(
            () ->
                service.validate(
                    new DataStoreSetupRequest(
                        DataStoreBackend.TIBERO, null, 8629, "T", null, s, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("host");
    assertThatThrownBy(
            () ->
                service.validate(
                    new DataStoreSetupRequest(
                        DataStoreBackend.TIBERO, "h", null, "T", null, s, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("port");
    assertThatThrownBy(
            () ->
                service.validate(
                    new DataStoreSetupRequest(
                        DataStoreBackend.TIBERO, "h", 1, null, null, s, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("database");
    assertThatThrownBy(
            () ->
                service.validate(
                    new DataStoreSetupRequest(
                        DataStoreBackend.TIBERO, "h", 1, "T", null, null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mode");
    assertThatThrownBy(() -> service.validate(tibero(DataStoreProvisionMode.DBA_AUTO, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dba");
    verifyNoInteractions(repository);
  }

  @Test
  void setUp_tiberoScript_savesPendingRowWith12EncryptedAccounts_andReturnsScript() {
    givenFakeEncryption();

    SetupOutcome outcome = service.setUp(42L, tibero(DataStoreProvisionMode.SCRIPT, null));

    ArgumentCaptor<TenantDataStoreRow> row = ArgumentCaptor.forClass(TenantDataStoreRow.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<StoredAccount>> accounts = ArgumentCaptor.forClass(List.class);
    verify(repository).insert(row.capture(), accounts.capture());
    assertThat(row.getValue().status()).isEqualTo(TenantDataStoreStatus.PENDING);
    assertThat(row.getValue().mode()).isEqualTo(DataStoreProvisionMode.SCRIPT);
    assertThat(row.getValue().schemaName()).isEqualTo("GENIA_T42");
    assertThat(row.getValue().tablespace()).isEqualTo("USR");
    assertThat(row.getValue().tenantId()).isEqualTo(42L);

    assertThat(accounts.getValue()).hasSize(12);
    // 저장된 암호문은 스크립트에 든 비밀번호를 암호화한 값이어야 하고 평문이면 안 된다.
    String script = outcome.setupScript().orElseThrow();
    assertThat(script).contains("CREATE USER \"GENIA_T42\"");
    for (StoredAccount a : accounts.getValue()) {
      assertThat(a.encryptedPassword()).startsWith("enc:");
      String plain = a.encryptedPassword().substring("enc:".length());
      assertThat(script).contains("\"" + a.username() + "\" IDENTIFIED BY \"" + plain + "\"");
    }
    // SCRIPT 는 외부 접속이 없고 롤백 보상도 없다.
    verifyNoInteractions(provisioner);
    completeAll(TransactionSynchronization.STATUS_ROLLED_BACK);
    verify(provisioner, never()).dropAccounts(any(), any(), any(), any(), anyCollection());
  }

  @Test
  void setUp_tiberoDbaAuto_savesReadyRow_andProvisionsWithSameTargetAndPlan() {
    givenFakeEncryption();
    givenProvisionerCreates(List.of("GENIA_T42"));

    SetupOutcome outcome = service.setUp(42L, tibero(DataStoreProvisionMode.DBA_AUTO, DBA));

    assertThat(outcome.setupScript()).isEmpty();
    ArgumentCaptor<TenantDataStoreRow> row = ArgumentCaptor.forClass(TenantDataStoreRow.class);
    verify(repository).insert(row.capture(), any());
    assertThat(row.getValue().status()).isEqualTo(TenantDataStoreStatus.READY);
    assertThat(row.getValue().mode()).isEqualTo(DataStoreProvisionMode.DBA_AUTO);
    // 행을 넣은 뒤 외부 계정을 만든다(실패하면 예외 전파로 행도 롤백된다).
    ArgumentCaptor<TiberoAccountPlan> plan = ArgumentCaptor.forClass(TiberoAccountPlan.class);
    InOrder order = Mockito.inOrder(repository, provisioner);
    order.verify(repository).insert(any(), any());
    order
        .verify(provisioner)
        .provision(
            eq(new ExternalDbTarget(DataStoreBackend.TIBERO, "db.example.com", 8629, "TIBERO")),
            eq("sys"),
            eq("Sup3rSecretDbaPw"),
            plan.capture());
    assertThat(plan.getValue().schemaName()).isEqualTo("GENIA_T42");
  }

  @Test
  void setUp_dbaAuto_committed_doesNotDrop() {
    givenFakeEncryption();
    givenProvisionerCreates(List.of("GENIA_T42", "GENIA_T42_RO"));
    service.setUp(42L, tibero(DataStoreProvisionMode.DBA_AUTO, DBA));

    commitAll();
    completeAll(TransactionSynchronization.STATUS_COMMITTED);

    verify(provisioner, never()).dropAccounts(any(), any(), any(), any(), anyCollection());
  }

  @Test
  void setUp_dbaAuto_rolledBack_dropsCreatedAccountsOnceWithSameTargetAndPlan() {
    givenFakeEncryption();
    List<String> created = List.of("GENIA_T42", "GENIA_T42_RO");
    givenProvisionerCreates(created);
    service.setUp(42L, tibero(DataStoreProvisionMode.DBA_AUTO, DBA));

    completeAll(TransactionSynchronization.STATUS_ROLLED_BACK);
    // 보상은 한 번만 실행되고 참조를 비운다 — 다시 불려도 아무것도 하지 않는다.
    completeAll(TransactionSynchronization.STATUS_ROLLED_BACK);

    // 보상은 생성에 쓴 것과 같은 접속 대상·계획으로 실행해야 한다.
    ArgumentCaptor<ExternalDbTarget> provisionTarget =
        ArgumentCaptor.forClass(ExternalDbTarget.class);
    ArgumentCaptor<TiberoAccountPlan> provisionPlan =
        ArgumentCaptor.forClass(TiberoAccountPlan.class);
    verify(provisioner).provision(provisionTarget.capture(), any(), any(), provisionPlan.capture());
    ArgumentCaptor<ExternalDbTarget> dropTarget = ArgumentCaptor.forClass(ExternalDbTarget.class);
    ArgumentCaptor<TiberoAccountPlan> dropPlan = ArgumentCaptor.forClass(TiberoAccountPlan.class);
    verify(provisioner, times(1))
        .dropAccounts(
            dropTarget.capture(),
            eq("sys"),
            eq("Sup3rSecretDbaPw"),
            dropPlan.capture(),
            eq(created));
    assertThat(dropTarget.getValue()).isSameAs(provisionTarget.getValue());
    assertThat(dropPlan.getValue()).isSameAs(provisionPlan.getValue());
  }

  @Test
  void setUp_dbaAuto_withoutActiveSynchronization_provisionsAndInvalidatesImmediately() {
    givenFakeEncryption();
    givenProvisionerCreates(List.of("GENIA_T42"));
    TransactionSynchronizationManager.clearSynchronization();

    assertThat(service.setUp(42L, tibero(DataStoreProvisionMode.DBA_AUTO, DBA)).setupScript())
        .isEmpty();

    // 동기화가 없어 보상만 못 걸 뿐, 계정 생성 자체는 수행됐어야 한다. 캐시는 바로 지운다.
    verify(provisioner).provision(any(), eq("sys"), eq("Sup3rSecretDbaPw"), any());
    verify(resolver).invalidate(42L);
  }

  @Test
  void setUp_dbaAuto_failurePropagates_noCompensationRegistered() {
    givenFakeEncryption();
    when(provisioner.provision(any(), any(), any(), any()))
        .thenThrow(new DataStoreProvisioningException("DBA 접속 실패"));

    assertThatThrownBy(() -> service.setUp(42L, tibero(DataStoreProvisionMode.DBA_AUTO, DBA)))
        .isInstanceOf(DataStoreProvisioningException.class);
    // 실행기가 이미 보상했으므로 롤백 시 다시 지우지 않는다.
    completeAll(TransactionSynchronization.STATUS_ROLLED_BACK);
    verify(provisioner, never()).dropAccounts(any(), any(), any(), any(), anyCollection());
  }

  /** 커밋 전에 캐시된 "행 없음 = PostgreSQL" 이 남지 않도록 커밋 뒤 리졸버 캐시를 지운다 — 커밋 전에는 지우지 않는다. */
  @Test
  void setUp_tibero_invalidatesResolverAfterCommitOnly() {
    givenFakeEncryption();

    service.setUp(42L, tibero(DataStoreProvisionMode.SCRIPT, null));

    verify(resolver, never()).invalidate(anyLong());
    commitAll();
    verify(resolver).invalidate(42L);
  }

  @Test
  void auditDetails_containNoSecrets() {
    givenFakeEncryption();
    givenProvisionerCreates(List.of("GENIA_T42"));

    SetupOutcome outcome = service.setUp(42L, tibero(DataStoreProvisionMode.DBA_AUTO, DBA));

    assertThat(outcome.auditDetails().keySet())
        .containsExactly("dataStoreBackend", "dataStoreHost", "dataStoreMode");
    assertThat(outcome.auditDetails().values().toString())
        .doesNotContain("Sup3rSecretDbaPw", "enc:")
        .contains("TIBERO", "db.example.com", "DBA_AUTO");
  }

  @Test
  void dbaCredentials_toStringMasksPassword() {
    assertThat(DBA.toString()).isEqualTo("DbaCredentials[username=sys, password=****]");
    assertThat(tibero(DataStoreProvisionMode.DBA_AUTO, DBA).toString())
        .doesNotContain("Sup3rSecretDbaPw");
  }

  /** 결과·보상 객체의 toString 에 스크립트·생성 비밀번호·DBA 비밀번호가 나오지 않는다. */
  @Test
  void setupOutcomeAndCompensation_toStringMaskSecrets() {
    givenFakeEncryption();
    SetupOutcome scriptOutcome = service.setUp(42L, tibero(DataStoreProvisionMode.SCRIPT, null));
    givenProvisionerCreates(List.of("GENIA_T42"));
    service.setUp(43L, tibero(DataStoreProvisionMode.DBA_AUTO, DBA));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<StoredAccount>> accounts = ArgumentCaptor.forClass(List.class);
    verify(repository, times(2)).insert(any(), accounts.capture());

    StringBuilder text = new StringBuilder(scriptOutcome.toString());
    for (TransactionSynchronization sync :
        TransactionSynchronizationManager.getSynchronizations()) {
      text.append(sync);
    }
    assertThat(text.toString())
        .doesNotContain("Sup3rSecretDbaPw", "IDENTIFIED", "enc:")
        .contains("****");
    for (List<StoredAccount> saved : accounts.getAllValues()) {
      for (StoredAccount a : saved) {
        assertThat(text.toString())
            .doesNotContain(a.encryptedPassword().substring("enc:".length()));
      }
    }
  }

  // ── Task 5: 운영자 API ─────────────────────────────────────────────────────

  private static final long TID = 42L;

  private void givenTenantExists() {
    when(tenantRepository.existsById(TID)).thenReturn(true);
  }

  private static TenantDataStoreRow row(DataStoreProvisionMode mode, TenantDataStoreStatus status) {
    return new TenantDataStoreRow(
        TID,
        DataStoreBackend.TIBERO,
        "db.example.com",
        8629,
        "TIBERO",
        "GENIA_T42",
        "TS1",
        mode,
        status);
  }

  /** 역할별로 "pw-역할" 평문을 가진 12개 저장 계정(암호문은 enc: 접두). */
  private static List<StoredAccount> storedAccounts() {
    return DataStoreAccountRole.all().stream()
        .map(r -> new StoredAccount(r, r.usernameFor(TID), "enc:pw-" + r.name()))
        .toList();
  }

  private void givenStore(DataStoreProvisionMode mode, TenantDataStoreStatus status) {
    givenTenantExists();
    when(repository.find(TID)).thenReturn(Optional.of(row(mode, status)));
  }

  private void givenReversibleDecrypt() {
    when(encryptionService.decrypt(any()))
        .thenAnswer(i -> ((String) i.getArgument(0)).substring("enc:".length()));
  }

  private static List<AccountCheck> checks(String... failedUsernames) {
    List<String> failed = List.of(failedUsernames);
    return DataStoreAccountRole.all().stream()
        .map(r -> r.usernameFor(TID))
        .map(u -> new AccountCheck(u, !failed.contains(u), failed.contains(u) ? "실패" : "접속 성공"))
        .toList();
  }

  @Test
  void verify_allOk_setsReadyAndAudits() {
    givenStore(DataStoreProvisionMode.SCRIPT, TenantDataStoreStatus.PENDING);
    givenReversibleDecrypt();
    when(repository.findAccounts(TID)).thenReturn(storedAccounts());
    when(tester.verifyAccounts(any(), eq("GENIA_T42"), any())).thenReturn(checks());

    DataStoreVerifyResponse res = service.verify(TID, 9L);

    assertThat(res.status()).isEqualTo(TenantDataStoreStatus.READY);
    assertThat(res.result()).isEqualTo("READY");
    assertThat(res.checks()).hasSize(12);
    verify(repository).updateStatus(TID, TenantDataStoreStatus.READY);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
    verify(auditRecorder)
        .record(
            eq(9L),
            eq("TENANT_DATA_STORE_VERIFY"),
            eq("tenant"),
            eq("42"),
            any(),
            details.capture());
    assertThat(details.getValue()).containsEntry("result", "READY");
    assertThat((List<?>) details.getValue().get("failedAccounts")).isEmpty();
  }

  @Test
  void verify_someFail_staysPending_auditsFailedUsernames() {
    givenStore(DataStoreProvisionMode.SCRIPT, TenantDataStoreStatus.PENDING);
    givenReversibleDecrypt();
    when(repository.findAccounts(TID)).thenReturn(storedAccounts());
    when(tester.verifyAccounts(any(), any(), any()))
        .thenReturn(checks("GENIA_T42_RO", "GENIA_T42_S3"));

    DataStoreVerifyResponse res = service.verify(TID, 9L);

    assertThat(res.status()).isEqualTo(TenantDataStoreStatus.PENDING);
    assertThat(res.result()).isEqualTo("FAILED");
    verify(repository, never()).updateStatus(anyLong(), any());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
    verify(auditRecorder).record(eq(9L), any(), any(), any(), any(), details.capture());
    assertThat(details.getValue()).containsEntry("result", "FAILED");
    assertThat(details.getValue().get("failedAccounts"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
        .containsExactly("GENIA_T42_RO", "GENIA_T42_S3");
    // 감사 details 에 비밀번호·암호문이 없다.
    assertThat(details.getValue().toString()).doesNotContain("pw-", "enc:");
  }

  @Test
  void verify_readyTenantWithFailure_staysReady() {
    givenStore(DataStoreProvisionMode.DBA_AUTO, TenantDataStoreStatus.READY);
    givenReversibleDecrypt();
    when(repository.findAccounts(TID)).thenReturn(storedAccounts());
    when(tester.verifyAccounts(any(), any(), any())).thenReturn(checks("GENIA_T42"));

    assertThat(service.verify(TID, 9L).status()).isEqualTo(TenantDataStoreStatus.READY);
    verify(repository, never()).updateStatus(anyLong(), any());
  }

  /**
   * 읽은 뒤 쓰기 전에 재발급으로 비밀번호가 바뀌면, 이전 비밀번호로 확인한 결과로 READY 를 확정하지 않는다 — PENDING 유지, updateStatus 미호출,
   * 감사 result 는 PASSWORDS_CHANGED.
   */
  @Test
  void verify_passwordsReissuedBetweenReadAndWrite_staysPending() {
    givenStore(DataStoreProvisionMode.SCRIPT, TenantDataStoreStatus.PENDING);
    givenReversibleDecrypt();
    List<StoredAccount> reissued =
        DataStoreAccountRole.all().stream()
            .map(r -> new StoredAccount(r, r.usernameFor(TID), "enc:new-" + r.name()))
            .toList();
    when(repository.findAccounts(TID)).thenReturn(storedAccounts(), reissued);
    when(tester.verifyAccounts(any(), any(), any())).thenReturn(checks());

    DataStoreVerifyResponse res = service.verify(TID, 9L);

    assertThat(res.status()).isEqualTo(TenantDataStoreStatus.PENDING);
    assertThat(res.result()).isEqualTo("PASSWORDS_CHANGED");
    assertThat(res.checks()).hasSize(12);
    verify(repository, never()).updateStatus(anyLong(), any());
    // 다시 읽기 전에 재발급과 같은 행을 잠근다.
    InOrder order = Mockito.inOrder(repository);
    order.verify(repository).findAccounts(TID);
    order.verify(repository).lockForUpdate(TID);
    order.verify(repository).findAccounts(TID);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
    verify(auditRecorder).record(eq(9L), any(), any(), any(), any(), details.capture());
    assertThat(details.getValue()).containsEntry("result", "PASSWORDS_CHANGED");
    assertThat(details.getValue().toString()).doesNotContain("enc:", "pw-", "new-");
  }

  /** 외부 접속(verifyAccounts)은 트랜잭션 밖에서 일어난다 — 읽기 전용 트랜잭션이 끝난 뒤, 쓰기 트랜잭션이 시작되기 전. */
  @Test
  void verify_externalChecksRunOutsideAnyTransaction() {
    PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    TenantDataStoreSetupService svc =
        new TenantDataStoreSetupService(
            repository,
            features,
            generator,
            encryptionService,
            provisioner,
            tenantRepository,
            auditRecorder,
            tester,
            resolver,
            new TransactionTemplate(txManager));
    givenStore(DataStoreProvisionMode.SCRIPT, TenantDataStoreStatus.PENDING);
    givenReversibleDecrypt();
    when(repository.findAccounts(TID)).thenReturn(storedAccounts());
    when(tester.verifyAccounts(any(), any(), any())).thenReturn(checks());

    svc.verify(TID, 9L);

    InOrder order = Mockito.inOrder(txManager, tester, repository);
    order.verify(txManager).getTransaction(argThat(d -> d != null && d.isReadOnly()));
    order.verify(txManager).commit(any());
    order.verify(tester).verifyAccounts(any(), any(), any());
    order.verify(txManager).getTransaction(argThat(d -> d != null && !d.isReadOnly()));
    order.verify(repository).updateStatus(TID, TenantDataStoreStatus.READY);
    order.verify(txManager).commit(any());
  }

  @Test
  void verify_decryptsStoredPasswordsForEachAccount() {
    givenStore(DataStoreProvisionMode.SCRIPT, TenantDataStoreStatus.PENDING);
    givenReversibleDecrypt();
    when(repository.findAccounts(TID)).thenReturn(storedAccounts());
    when(tester.verifyAccounts(any(), any(), any())).thenReturn(checks());

    service.verify(TID, 9L);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PlannedAccount>> planned = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<ExternalDbTarget> target = ArgumentCaptor.forClass(ExternalDbTarget.class);
    verify(tester).verifyAccounts(target.capture(), eq("GENIA_T42"), planned.capture());
    assertThat(target.getValue())
        .isEqualTo(new ExternalDbTarget(DataStoreBackend.TIBERO, "db.example.com", 8629, "TIBERO"));
    assertThat(planned.getValue()).hasSize(12);
    for (PlannedAccount a : planned.getValue()) {
      assertThat(a.password()).isEqualTo("pw-" + a.role().name());
    }
  }

  @Test
  void verify_noStoreRow_400() {
    givenTenantExists();
    when(repository.find(TID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.verify(TID, 9L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("외부 데이터셋 저장소를 쓰지 않는 테넌트입니다");
    assertThatThrownBy(() -> service.reissueScript(TID, 9L))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(tester, auditRecorder);
  }

  @Test
  void verifyDetailReissue_unknownTenant_notFound() {
    when(tenantRepository.existsById(TID)).thenReturn(false);

    assertThatThrownBy(() -> service.verify(TID, 9L)).isInstanceOf(TenantNotFoundException.class);
    assertThatThrownBy(() -> service.detail(TID)).isInstanceOf(TenantNotFoundException.class);
    assertThatThrownBy(() -> service.reissueScript(TID, 9L))
        .isInstanceOf(TenantNotFoundException.class);
  }

  @Test
  void reissueScript_pendingScriptMode_rotatesAllPasswords() {
    givenStore(DataStoreProvisionMode.SCRIPT, TenantDataStoreStatus.PENDING);
    givenFakeEncryption();

    DataStoreScriptResponse res = service.reissueScript(TID, 9L);

    // verify 의 READY 전환과 직렬화하도록 행을 먼저 잠그고 읽는다.
    InOrder lockOrder = Mockito.inOrder(repository);
    lockOrder.verify(repository).lockForUpdate(TID);
    lockOrder.verify(repository).find(TID);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<StoredAccount>> saved = ArgumentCaptor.forClass(List.class);
    verify(repository).replacePasswords(eq(TID), saved.capture());
    assertThat(saved.getValue()).hasSize(12);
    List<String> oldEnc = storedAccounts().stream().map(StoredAccount::encryptedPassword).toList();
    for (StoredAccount a : saved.getValue()) {
      assertThat(oldEnc).doesNotContain(a.encryptedPassword());
      String plain = a.encryptedPassword().substring("enc:".length());
      assertThat(res.script()).contains("\"" + a.username() + "\" IDENTIFIED BY \"" + plain + "\"");
    }
    // 저장된 테이블스페이스를 유지한다.
    assertThat(res.script()).contains("\"TS1\"");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
    verify(auditRecorder)
        .record(
            eq(9L),
            eq("TENANT_DATA_STORE_SCRIPT_REISSUE"),
            eq("tenant"),
            eq("42"),
            any(),
            details.capture());
    assertThat(details.getValue().toString()).doesNotContain("enc:", "IDENTIFIED");
    for (StoredAccount a : saved.getValue()) {
      assertThat(details.getValue().toString())
          .doesNotContain(a.encryptedPassword().substring("enc:".length()));
    }
  }

  @Test
  void reissueScript_readyOrDbaAuto_409NotPending() {
    givenStore(DataStoreProvisionMode.SCRIPT, TenantDataStoreStatus.READY);
    assertNotPending();
    when(repository.find(TID))
        .thenReturn(
            Optional.of(row(DataStoreProvisionMode.DBA_AUTO, TenantDataStoreStatus.PENDING)));
    assertNotPending();
    verify(repository, never()).replacePasswords(anyLong(), any());
    verifyNoInteractions(auditRecorder);
  }

  private void assertNotPending() {
    assertThatThrownBy(() -> service.reissueScript(TID, 9L))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(e.code()).isEqualTo("DATA_STORE_NOT_PENDING");
            });
  }

  @Test
  void detail_noRow_postgresqlDefaults() {
    givenTenantExists();
    when(repository.find(TID)).thenReturn(Optional.empty());

    TenantDataStoreResponse res = service.detail(TID);

    assertThat(res.backend()).isEqualTo(DataStoreBackend.POSTGRESQL);
    assertThat(res.host()).isNull();
    assertThat(res.status()).isNull();
    assertThat(res.accountUsernames()).isEmpty();
  }

  @Test
  void detail_externalRow_listsUsernamesOnly() {
    givenStore(DataStoreProvisionMode.SCRIPT, TenantDataStoreStatus.PENDING);
    when(repository.findAccounts(TID)).thenReturn(storedAccounts());

    TenantDataStoreResponse res = service.detail(TID);

    assertThat(res.backend()).isEqualTo(DataStoreBackend.TIBERO);
    assertThat(res.schemaName()).isEqualTo("GENIA_T42");
    assertThat(res.tablespace()).isEqualTo("TS1");
    assertThat(res.accountUsernames()).hasSize(12).startsWith("GENIA_T42", "GENIA_T42_RO");
    assertThat(res.toString()).doesNotContain("enc:");
  }

  @Test
  void availableBackends_orderAndFlags() {
    when(features.isAvailable(DataStoreBackend.POSTGRESQL)).thenReturn(true);
    when(features.isAvailable(DataStoreBackend.TIBERO)).thenReturn(true);
    when(features.isAvailable(DataStoreBackend.MYSQL)).thenReturn(false);

    assertThat(service.availableBackends())
        .containsExactly(
            new DataStoreBackendOption(DataStoreBackend.POSTGRESQL, true),
            new DataStoreBackendOption(DataStoreBackend.TIBERO, true),
            new DataStoreBackendOption(DataStoreBackend.MYSQL, false));
  }

  @Test
  void testConnection_tibero_delegatesToTester() {
    ConnectionTestResult ok = new ConnectionTestResult(true, "7", List.of(), "연결 성공");
    when(tester.testDba(any(), eq("sys"), eq("Sup3rSecretDbaPw"))).thenReturn(ok);

    ConnectionTestResult res =
        service.testConnection(
            new DataStoreConnectionTestRequest(
                DataStoreBackend.TIBERO, "db.example.com", 8629, "TIBERO", DBA));

    assertThat(res).isSameAs(ok);
    verify(tester)
        .testDba(
            eq(new ExternalDbTarget(DataStoreBackend.TIBERO, "db.example.com", 8629, "TIBERO")),
            any(),
            any());
  }

  @Test
  void testConnection_nonTibero_400() {
    assertThatThrownBy(
            () ->
                service.testConnection(
                    new DataStoreConnectionTestRequest(
                        DataStoreBackend.MYSQL, "h", 3306, "d", DBA)))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(tester);
  }

  @Test
  void afterCompletion_unknown_logsErrorWithUsernamesNoPasswords() {
    givenFakeEncryption();
    givenProvisionerCreates(List.of("GENIA_T42", "GENIA_T42_RO"));
    service.setUp(42L, tibero(DataStoreProvisionMode.DBA_AUTO, DBA));
    Logger logger = (Logger) LoggerFactory.getLogger(TenantDataStoreSetupService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      completeAll(TransactionSynchronization.STATUS_UNKNOWN);
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(appender.list)
        .anySatisfy(
            e -> {
              assertThat(e.getLevel()).isEqualTo(Level.ERROR);
              assertThat(e.getFormattedMessage())
                  .contains("GENIA_T42", "GENIA_T42_RO")
                  .doesNotContain("Sup3rSecretDbaPw");
            });
    verify(provisioner, never()).dropAccounts(any(), any(), any(), any(), anyCollection());
  }
}
