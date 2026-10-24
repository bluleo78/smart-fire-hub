package com.smartfirehub.dataset.storage.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreProvisionMode;
import com.smartfirehub.dataset.storage.StoredAccount;
import com.smartfirehub.dataset.storage.TenantDataStoreRepository;
import com.smartfirehub.dataset.storage.TenantDataStoreRow;
import com.smartfirehub.dataset.storage.TenantDataStoreStatus;
import com.smartfirehub.global.exception.CodedApiException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** {@link ExecutorDataStoreDescriptorService} 단위 테스트(WD-75) — repository·암호화는 mock. */
class ExecutorDataStoreDescriptorServiceTest {

  private final TenantDataStoreRepository repository = mock(TenantDataStoreRepository.class);
  private final EncryptionService encryption = mock(EncryptionService.class);
  private final ExecutorDataStoreDescriptorService service =
      new ExecutorDataStoreDescriptorService(repository, encryption);

  private static TenantDataStoreRow row(DataStoreBackend backend, TenantDataStoreStatus status) {
    return new TenantDataStoreRow(
        7,
        backend,
        "tibero.example",
        8629,
        "tibero",
        "GENIA_T7",
        "TS_T7",
        DataStoreProvisionMode.SCRIPT,
        status);
  }

  @Test
  void describe_행없음_POSTGRESQL() {
    when(repository.find(3)).thenReturn(Optional.empty());

    ExecutorDataStoreDescriptor d = service.describe(3, true, false);

    assertThat(d.backend()).isEqualTo(DataStoreBackend.POSTGRESQL);
    assertThat(d.tenantId()).isEqualTo(3);
    assertThat(d.host()).isNull();
    assertThat(d.owner()).isNull();
  }

  @Test
  void describe_POSTGRESQL_행_POSTGRESQL() {
    when(repository.find(3))
        .thenReturn(Optional.of(row(DataStoreBackend.POSTGRESQL, TenantDataStoreStatus.READY)));

    ExecutorDataStoreDescriptor d = service.describe(3, true, false);

    assertThat(d.backend()).isEqualTo(DataStoreBackend.POSTGRESQL);
    assertThat(d.host()).isNull();
    assertThat(d.owner()).isNull();
  }

  @Test
  void describe_TIBERO_READY_소유계정_복호화() {
    when(repository.find(7))
        .thenReturn(Optional.of(row(DataStoreBackend.TIBERO, TenantDataStoreStatus.READY)));
    when(repository.findAccounts(7))
        .thenReturn(
            List.of(
                new StoredAccount(DataStoreAccountRole.OWNER, "GENIA_T7", "enc"),
                new StoredAccount(DataStoreAccountRole.RO, "GENIA_T7_RO", "enc-ro"),
                new StoredAccount(DataStoreAccountRole.S1, "GENIA_T7_S1", "enc-s1")));
    when(encryption.decrypt("enc")).thenReturn("pw");

    ExecutorDataStoreDescriptor d = service.describe(7, true, false);

    assertThat(d.backend()).isEqualTo(DataStoreBackend.TIBERO);
    assertThat(d.host()).isEqualTo("tibero.example");
    assertThat(d.port()).isEqualTo(8629);
    assertThat(d.database()).isEqualTo("tibero");
    assertThat(d.schema()).isEqualTo("GENIA_T7");
    assertThat(d.owner().username()).isEqualTo("GENIA_T7");
    assertThat(d.owner().password()).isEqualTo("pw");
    // RO·슬롯 암호문은 복호화하지 않는다.
    verify(encryption, times(1)).decrypt(any());
  }

  @Test
  void describe_includeRo_RO와_소유계정만_복호화() {
    when(repository.find(7))
        .thenReturn(Optional.of(row(DataStoreBackend.TIBERO, TenantDataStoreStatus.READY)));
    when(repository.findAccounts(7))
        .thenReturn(
            List.of(
                new StoredAccount(DataStoreAccountRole.OWNER, "GENIA_T7", "enc"),
                new StoredAccount(DataStoreAccountRole.RO, "GENIA_T7_RO", "enc-ro"),
                new StoredAccount(DataStoreAccountRole.S1, "GENIA_T7_S1", "enc-s1")));
    when(encryption.decrypt("enc")).thenReturn("pw");
    when(encryption.decrypt("enc-ro")).thenReturn("ropw");

    ExecutorDataStoreDescriptor d = service.describe(7, true, true);

    assertThat(d.owner().password()).isEqualTo("pw");
    assertThat(d.ro().username()).isEqualTo("GENIA_T7_RO");
    assertThat(d.ro().password()).isEqualTo("ropw");
    verify(encryption, times(2)).decrypt(any());
  }

  /**
   * RO 암호문이 손상·키 교체로 복호화되지 않으면 ro 만 빼고 기술자를 낸다 — db-executor 가 모든 기술자를 include=ro 로 받으므로, 던지면 OWNER
   * 만 쓰는 파이프라인까지 멈춘다. 경고 로그에는 비밀 정보(암호문·예외 메시지)가 남지 않는다.
   */
  @Test
  void describe_includeRo_RO복호화실패_ro만_빼고_경고() {
    when(repository.find(7))
        .thenReturn(Optional.of(row(DataStoreBackend.TIBERO, TenantDataStoreStatus.READY)));
    when(repository.findAccounts(7))
        .thenReturn(
            List.of(
                new StoredAccount(DataStoreAccountRole.OWNER, "GENIA_T7", "enc"),
                new StoredAccount(DataStoreAccountRole.RO, "GENIA_T7_RO", "enc-ro-corrupt")));
    when(encryption.decrypt("enc")).thenReturn("pw");
    when(encryption.decrypt("enc-ro-corrupt"))
        .thenThrow(new IllegalStateException("Decryption failed for enc-ro-corrupt"));
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(ExecutorDataStoreDescriptorService.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      ExecutorDataStoreDescriptor d = service.describe(7, true, true);

      assertThat(d.ro()).isNull();
      assertThat(d.owner().password()).isEqualTo("pw");
      assertThat(appender.list).hasSize(1);
      ch.qos.logback.classic.spi.ILoggingEvent event = appender.list.get(0);
      assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
      assertThat(event.getFormattedMessage())
          .contains("tenant=7")
          .contains("IllegalStateException")
          .doesNotContain("enc-ro-corrupt")
          .doesNotContain("Decryption failed");
      assertThat(event.getThrowableProxy()).isNull();
    } finally {
      logger.detachAppender(appender);
    }
  }

  /** OWNER 복호화 실패는 그대로 던진다 — OWNER 없이는 어떤 실행도 할 수 없다. */
  @Test
  void describe_includeRo_소유계정복호화실패는_예외() {
    when(repository.find(7))
        .thenReturn(Optional.of(row(DataStoreBackend.TIBERO, TenantDataStoreStatus.READY)));
    when(repository.findAccounts(7))
        .thenReturn(
            List.of(
                new StoredAccount(DataStoreAccountRole.OWNER, "GENIA_T7", "enc-bad"),
                new StoredAccount(DataStoreAccountRole.RO, "GENIA_T7_RO", "enc-ro")));
    when(encryption.decrypt("enc-ro")).thenReturn("ropw");
    when(encryption.decrypt("enc-bad")).thenThrow(new IllegalStateException("Decryption failed"));

    assertThatThrownBy(() -> service.describe(7, true, true))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> service.describe(7, true, false))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void describe_includeRo_RO행없음_ro_null() {
    when(repository.find(7))
        .thenReturn(Optional.of(row(DataStoreBackend.TIBERO, TenantDataStoreStatus.READY)));
    when(repository.findAccounts(7))
        .thenReturn(List.of(new StoredAccount(DataStoreAccountRole.OWNER, "GENIA_T7", "enc")));
    when(encryption.decrypt("enc")).thenReturn("pw");

    ExecutorDataStoreDescriptor d = service.describe(7, true, true);

    assertThat(d.ro()).isNull();
    assertThat(d.owner().password()).isEqualTo("pw");
  }

  @Test
  void describe_includeRo_비밀허용안됨_503_복호화_안함() {
    when(repository.find(7))
        .thenReturn(Optional.of(row(DataStoreBackend.TIBERO, TenantDataStoreStatus.READY)));

    assertThatThrownBy(() -> service.describe(7, false, true))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> assertThat(e.code()).isEqualTo("EXECUTOR_TOKEN_NOT_CONFIGURED"));
    verify(encryption, never()).decrypt(any());
  }

  @Test
  void describe_includeRo_PG는_ro없음() {
    when(repository.find(3)).thenReturn(Optional.empty());

    ExecutorDataStoreDescriptor d = service.describe(3, true, true);

    assertThat(d.backend()).isEqualTo(DataStoreBackend.POSTGRESQL);
    assertThat(d.ro()).isNull();
  }

  @Test
  void describe_TIBERO_PENDING_409() {
    when(repository.find(7))
        .thenReturn(Optional.of(row(DataStoreBackend.TIBERO, TenantDataStoreStatus.PENDING)));

    assertThatThrownBy(() -> service.describe(7, true, false))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(e.code()).isEqualTo("DATA_STORE_NOT_READY");
            });
  }

  @Test
  void describe_MYSQL_409() {
    when(repository.find(7))
        .thenReturn(Optional.of(row(DataStoreBackend.MYSQL, TenantDataStoreStatus.READY)));

    assertThatThrownBy(() -> service.describe(7, true, false))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(e.code()).isEqualTo("DATA_STORE_BACKEND_UNSUPPORTED");
            });
  }

  @Test
  void describe_소유계정없음_409_NOT_READY() {
    when(repository.find(7))
        .thenReturn(Optional.of(row(DataStoreBackend.TIBERO, TenantDataStoreStatus.READY)));
    when(repository.findAccounts(7))
        .thenReturn(List.of(new StoredAccount(DataStoreAccountRole.RO, "GENIA_T7_RO", "enc-ro")));

    assertThatThrownBy(() -> service.describe(7, true, false))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(e.code()).isEqualTo("DATA_STORE_NOT_READY");
            });
  }

  @Test
  void describe_TIBERO_비밀허용안됨_503_복호화_안함() {
    when(repository.find(7))
        .thenReturn(Optional.of(row(DataStoreBackend.TIBERO, TenantDataStoreStatus.READY)));

    assertThatThrownBy(() -> service.describe(7, false, false))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(e.code()).isEqualTo("EXECUTOR_TOKEN_NOT_CONFIGURED");
            });
    verify(encryption, never()).decrypt(any());
    verify(repository, never()).findAccounts(anyLong());
  }

  @Test
  void describe_POSTGRESQL_비밀허용안됨도_정상() {
    when(repository.find(3)).thenReturn(Optional.empty());

    assertThat(service.describe(3, false, false).backend()).isEqualTo(DataStoreBackend.POSTGRESQL);
  }

  @Test
  void toString_비밀번호_마스킹() {
    var owner = new ExecutorDataStoreDescriptor.Account("GENIA_T7", "pw");
    var ro = new ExecutorDataStoreDescriptor.Account("GENIA_T7_RO", "ropw");
    var d =
        new ExecutorDataStoreDescriptor(
            7,
            DataStoreBackend.TIBERO,
            "h",
            8629,
            "tibero",
            "GENIA_T7",
            owner,
            ro,
            List.of(new ExecutorDataStoreDescriptor.SlotAccount(2, "GENIA_T7_S2", "slotpw")),
            null);

    assertThat(d.toString()).doesNotContain("pw").contains("****");
    assertThat(d.toString()).doesNotContain("slotpw");
    assertThat(d.toString()).doesNotContain("ropw");
    assertThat(owner.toString()).doesNotContain("pw").contains("****");
  }

  /** 종류만 조회: Tibero 여도 계정을 읽거나 복호화하지 않고 backend·tenantId 만 낸다. */
  @Test
  void describeBackendOnly_Tibero_비밀없음_복호화안함() {
    when(repository.find(7))
        .thenReturn(Optional.of(row(DataStoreBackend.TIBERO, TenantDataStoreStatus.READY)));

    ExecutorDataStoreDescriptor d = service.describeBackendOnly(7);

    assertThat(d.backend()).isEqualTo(DataStoreBackend.TIBERO);
    assertThat(d.tenantId()).isEqualTo(7);
    assertThat(d.host()).isNull();
    assertThat(d.owner()).isNull();
    assertThat(d.ro()).isNull();
    verify(repository, never()).findAccounts(anyLong());
    verify(encryption, never()).decrypt(any());
  }

  @Test
  void describeBackendOnly_행없음_POSTGRESQL() {
    when(repository.find(3)).thenReturn(Optional.empty());

    assertThat(service.describeBackendOnly(3).backend()).isEqualTo(DataStoreBackend.POSTGRESQL);
  }

  @Test
  void describeBackendOnly_MySQL_409() {
    when(repository.find(7))
        .thenReturn(Optional.of(row(DataStoreBackend.MYSQL, TenantDataStoreStatus.READY)));

    assertThatThrownBy(() -> service.describeBackendOnly(7))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> assertThat(e.code()).isEqualTo("DATA_STORE_BACKEND_UNSUPPORTED"));
  }

  /** 전체 기술자(db-executor 경로)는 스크립트 토큰 세대 번호를 싣는다(Task 13 이 토큰과 비교). JSON 키 이름은 scriptTokenEpoch. */
  @Test
  void describe_TIBERO_전체기술자는_scriptTokenEpoch를_싣는다() throws Exception {
    when(repository.find(7))
        .thenReturn(Optional.of(row(DataStoreBackend.TIBERO, TenantDataStoreStatus.READY)));
    when(repository.findAccounts(7))
        .thenReturn(List.of(new StoredAccount(DataStoreAccountRole.OWNER, "GENIA_T7", "enc")));
    when(encryption.decrypt("enc")).thenReturn("pw");
    when(repository.findScriptTokenEpoch(7)).thenReturn(Optional.of(5L));

    ExecutorDataStoreDescriptor d = service.describe(7, true, false);

    assertThat(d.scriptTokenEpoch()).isEqualTo(5L);
    var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(d);
    assertThat(json.get("scriptTokenEpoch").asLong()).isEqualTo(5L);
  }

  /** Python 실행기용(백엔드만) 기술자와 PG 기술자는 변하지 않는다 — 세대 번호가 없고 JSON 키도 없다. */
  @Test
  void describeBackendOnly_와_PG는_scriptTokenEpoch가_없다() throws Exception {
    when(repository.find(7))
        .thenReturn(Optional.of(row(DataStoreBackend.TIBERO, TenantDataStoreStatus.READY)));
    when(repository.find(3)).thenReturn(Optional.empty());
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    ExecutorDataStoreDescriptor backendOnly = service.describeBackendOnly(7);
    ExecutorDataStoreDescriptor pg = service.describe(3, true, false);

    assertThat(backendOnly.scriptTokenEpoch()).isNull();
    assertThat(mapper.valueToTree(backendOnly).has("scriptTokenEpoch")).isFalse();
    assertThat(mapper.valueToTree(pg).has("scriptTokenEpoch")).isFalse();
    verify(repository, never()).findScriptTokenEpoch(anyLong());
  }

  // ---- include=slots (WD-78): 슬롯 계정 S1..S10 은 요청했을 때만 복호화한다 ----

  private void tiberoWithSlots(String... slotCiphers) {
    when(repository.find(7))
        .thenReturn(Optional.of(row(DataStoreBackend.TIBERO, TenantDataStoreStatus.READY)));
    java.util.ArrayList<StoredAccount> accounts = new java.util.ArrayList<>();
    accounts.add(new StoredAccount(DataStoreAccountRole.OWNER, "GENIA_T7", "enc"));
    accounts.add(new StoredAccount(DataStoreAccountRole.RO, "GENIA_T7_RO", "enc-ro"));
    // 저장 순서와 무관하게 슬롯 번호 순으로 내는지 보려고 뒤집어 넣는다.
    for (int k = slotCiphers.length; k >= 1; k--) {
      accounts.add(
          new StoredAccount(
              DataStoreAccountRole.valueOf("S" + k), "GENIA_T7_S" + k, slotCiphers[k - 1]));
    }
    when(repository.findAccounts(7)).thenReturn(accounts);
    when(encryption.decrypt("enc")).thenReturn("pw");
    when(encryption.decrypt("enc-ro")).thenReturn("ropw");
  }

  @Test
  void describe_includeSlots_슬롯만_복호화_번호순() {
    tiberoWithSlots("enc-s1", "enc-s2", "enc-s3");
    when(encryption.decrypt("enc-s1")).thenReturn("s1pw");
    when(encryption.decrypt("enc-s2")).thenReturn("s2pw");
    when(encryption.decrypt("enc-s3")).thenReturn("s3pw");

    ExecutorDataStoreDescriptor d = service.describe(7, true, false, true);

    assertThat(d.ro()).isNull();
    assertThat(d.slots())
        .extracting(
            ExecutorDataStoreDescriptor.SlotAccount::slot,
            ExecutorDataStoreDescriptor.SlotAccount::username,
            ExecutorDataStoreDescriptor.SlotAccount::password)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(1, "GENIA_T7_S1", "s1pw"),
            org.assertj.core.groups.Tuple.tuple(2, "GENIA_T7_S2", "s2pw"),
            org.assertj.core.groups.Tuple.tuple(3, "GENIA_T7_S3", "s3pw"));
    verify(encryption, never()).decrypt("enc-ro");
  }

  /** 요청하지 않으면 슬롯 암호문은 복호화하지 않고 slots 도 없다(JSON 키 생략). */
  @Test
  void describe_includeSlots_아니면_슬롯_복호화안함() {
    tiberoWithSlots("enc-s1", "enc-s2");

    ExecutorDataStoreDescriptor d = service.describe(7, true, true, false);

    assertThat(d.slots()).isNull();
    verify(encryption, never()).decrypt("enc-s1");
    verify(encryption, never()).decrypt("enc-s2");
    assertThat(service.describe(7, true, true).slots()).isNull();
  }

  /** 슬롯 하나가 복호화되지 않으면 그 슬롯만 빼고 경고 — 모든 경로가 include=slots 로 받으므로 던지면 파이프라인 전체가 멈춘다. */
  @Test
  void describe_includeSlots_복호화실패_그슬롯만_빼고_경고() {
    tiberoWithSlots("enc-s1", "enc-s2-corrupt");
    when(encryption.decrypt("enc-s1")).thenReturn("s1pw");
    when(encryption.decrypt("enc-s2-corrupt"))
        .thenThrow(new IllegalStateException("Decryption failed for enc-s2-corrupt"));
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(ExecutorDataStoreDescriptorService.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      ExecutorDataStoreDescriptor d = service.describe(7, true, true, true);

      assertThat(d.slots())
          .extracting(ExecutorDataStoreDescriptor.SlotAccount::slot)
          .containsExactly(1);
      assertThat(d.ro().password()).isEqualTo("ropw");
      assertThat(appender.list).hasSize(1);
      assertThat(appender.list.get(0).getFormattedMessage())
          .contains("tenant=7")
          .contains("S2")
          .doesNotContain("enc-s2-corrupt")
          .doesNotContain("Decryption failed");
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void describe_includeSlots_PG테넌트는_슬롯없음() {
    when(repository.find(3)).thenReturn(Optional.empty());

    assertThat(service.describe(3, true, true, true).slots()).isNull();
    verify(encryption, never()).decrypt(any());
  }
}
