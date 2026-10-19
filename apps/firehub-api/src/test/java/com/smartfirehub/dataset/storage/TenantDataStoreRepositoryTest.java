package com.smartfirehub.dataset.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.platform.repository.PlatformTenantRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link TenantDataStoreRepository} 통합 테스트(WD-73) — 전역 테이블 왕복·순서·캐스케이드를 실제 DB 로 검증한다. */
class TenantDataStoreRepositoryTest extends IntegrationTestBase {

  @Autowired private TenantDataStoreRepository repository;
  @Autowired private PlatformTenantRepository tenantRepository;
  @Autowired private DSLContext dsl;

  private long tenantId;

  /** 테스트마다 새 테넌트를 만든다. 정리는 @AfterEach 가 맡는다. */
  private long newTenant() {
    String slug = "ds-" + UUID.randomUUID().toString().substring(0, 8);
    tenantId = tenantRepository.insertTenant(slug, "저장소 테스트 " + slug);
    return tenantId;
  }

  @AfterEach
  void cleanUp() {
    if (tenantId > 0) {
      TenantRlsTestSupport.deleteProvisionedTenantCascade(
          dsl, fixtureTransactionTemplate, tenantId);
      tenantId = 0;
    }
  }

  private static TenantDataStoreRow row(long id) {
    return new TenantDataStoreRow(
        id,
        DataStoreBackend.TIBERO,
        "tibero.example",
        8629,
        "tibero",
        "GENIA_T" + id,
        "TS_T" + id,
        DataStoreProvisionMode.SCRIPT,
        TenantDataStoreStatus.PENDING);
  }

  private static List<StoredAccount> accounts(long id, String passwordPrefix) {
    return DataStoreAccountRole.all().stream()
        .map(r -> new StoredAccount(r, r.usernameFor(id), passwordPrefix + r.name()))
        .toList();
  }

  @Test
  void insert_thenFind_roundTripsRowAndAccountsInRoleOrder() {
    long id = newTenant();
    repository.insert(row(id), accounts(id, "enc-"));

    assertThat(repository.find(id)).contains(row(id));
    assertThat(repository.findBackend(id)).contains(DataStoreBackend.TIBERO);
    List<StoredAccount> found = repository.findAccounts(id);
    assertThat(found).isEqualTo(accounts(id, "enc-"));
    assertThat(found).extracting(StoredAccount::role).isEqualTo(DataStoreAccountRole.all());
  }

  @Test
  void findBackend_returnsEmptyWhenNoRow() {
    long id = newTenant();
    assertThat(repository.findBackend(id)).isEmpty();
    assertThat(repository.find(id)).isEmpty();
    assertThat(repository.findAccounts(id)).isEmpty();
  }

  @Test
  void updateStatus_changesStatusAndUpdatedAt() {
    long id = newTenant();
    repository.insert(row(id), accounts(id, "enc-"));
    // updated_at 을 과거로 돌려 두면 갱신 여부가 시계 해상도와 무관하게 드러난다.
    dsl.execute(
        "update tenant_data_store set updated_at = ? where tenant_id = ?",
        LocalDateTime.now().minusDays(1),
        id);

    repository.updateStatus(id, TenantDataStoreStatus.READY);

    assertThat(repository.find(id).orElseThrow().status()).isEqualTo(TenantDataStoreStatus.READY);
    LocalDateTime updatedAt =
        dsl.fetchOne("select updated_at from tenant_data_store where tenant_id = ?", id)
            .get(0, LocalDateTime.class);
    assertThat(updatedAt).isAfter(LocalDateTime.now().minusHours(1));
  }

  @Test
  void replacePasswords_overwritesEncryptedPasswordsOnly() {
    long id = newTenant();
    repository.insert(row(id), accounts(id, "old-"));

    repository.replacePasswords(id, accounts(id, "new-"));

    assertThat(repository.findAccounts(id)).isEqualTo(accounts(id, "new-"));
  }

  /** 갱신 대상 행이 없는 역할이 있으면 조용히 넘어가지 않고 예외로 알린다(호출자 트랜잭션이 롤백된다). */
  @Test
  void replacePasswords_missingAccountRow_throws() {
    long id = newTenant();
    // S10 행 없이 저장한다.
    repository.insert(row(id), accounts(id, "old-").subList(0, 11));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> repository.replacePasswords(id, accounts(id, "new-")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("S10")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("new-"));
  }

  /** 행 잠금은 트랜잭션 안에서 실행되며, 행이 없어도 오류가 아니다. */
  @Test
  void lockForUpdate_runsInsideTransaction_rowOrNoRow() {
    long id = newTenant();
    repository.insert(row(id), accounts(id, "enc-"));

    fixtureTransactionTemplate.executeWithoutResult(
        s -> {
          repository.lockForUpdate(id);
          repository.lockForUpdate(99_999_999L);
        });
    assertThat(repository.find(id)).isPresent();
  }

  @Test
  void deletingTenant_cascadesStoreAndAccounts() {
    long id = newTenant();
    repository.insert(row(id), accounts(id, "enc-"));

    TenantRlsTestSupport.deleteProvisionedTenantCascade(dsl, fixtureTransactionTemplate, id);
    tenantId = 0;

    assertThat(repository.find(id)).isEmpty();
    assertThat(repository.findAccounts(id)).isEmpty();
  }
}
