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

    ExecutorDataStoreDescriptor d = service.describe(3, true);

    assertThat(d.backend()).isEqualTo(DataStoreBackend.POSTGRESQL);
    assertThat(d.tenantId()).isEqualTo(3);
    assertThat(d.host()).isNull();
    assertThat(d.owner()).isNull();
  }

  @Test
  void describe_POSTGRESQL_행_POSTGRESQL() {
    when(repository.find(3))
        .thenReturn(Optional.of(row(DataStoreBackend.POSTGRESQL, TenantDataStoreStatus.READY)));

    ExecutorDataStoreDescriptor d = service.describe(3, true);

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

    ExecutorDataStoreDescriptor d = service.describe(7, true);

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
  void describe_TIBERO_PENDING_409() {
    when(repository.find(7))
        .thenReturn(Optional.of(row(DataStoreBackend.TIBERO, TenantDataStoreStatus.PENDING)));

    assertThatThrownBy(() -> service.describe(7, true))
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

    assertThatThrownBy(() -> service.describe(7, true))
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

    assertThatThrownBy(() -> service.describe(7, true))
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

    assertThatThrownBy(() -> service.describe(7, false))
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

    assertThat(service.describe(3, false).backend()).isEqualTo(DataStoreBackend.POSTGRESQL);
  }

  @Test
  void toString_비밀번호_마스킹() {
    var owner = new ExecutorDataStoreDescriptor.Account("GENIA_T7", "pw");
    var d =
        new ExecutorDataStoreDescriptor(
            7, DataStoreBackend.TIBERO, "h", 8629, "tibero", "GENIA_T7", owner);

    assertThat(d.toString()).doesNotContain("pw").contains("****");
    assertThat(owner.toString()).doesNotContain("pw").contains("****");
  }
}
