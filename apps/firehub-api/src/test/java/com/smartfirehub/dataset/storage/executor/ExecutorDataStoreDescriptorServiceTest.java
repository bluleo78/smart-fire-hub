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
            7, DataStoreBackend.TIBERO, "h", 8629, "tibero", "GENIA_T7", owner, ro);

    assertThat(d.toString()).doesNotContain("pw").contains("****");
    assertThat(d.toString()).doesNotContain("ropw");
    assertThat(owner.toString()).doesNotContain("pw").contains("****");
  }
}
