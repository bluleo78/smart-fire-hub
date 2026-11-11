package com.smartfirehub.dataset.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.repository.jooq.JooqTenantDataStoreRepository;
import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreProvisionMode;
import com.smartfirehub.dataset.storage.StoredAccount;
import com.smartfirehub.dataset.storage.TenantDataStoreRow;
import com.smartfirehub.dataset.storage.TenantDataStoreStatus;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.platform.repository.PlatformTenantRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * TenantDataStoreRepository 의 PG 전용 시험(WD-485, WD-707) — PG 카탈로그(relrowsecurity)·PG 등급 동기화·FK
 * 캐스케이드를 본다. 두 DB 공통 시나리오는 {@code TenantDataStoreRepositoryContractTest} 에 있다.
 */
class TenantDataStorePgOnlyTest extends IntegrationTestBase {

  @Autowired private JooqTenantDataStoreRepository repository;
  @Autowired private PlatformTenantRepository tenantRepository;
  @Autowired private DSLContext dsl;
  @Autowired private com.smartfirehub.securitylevel.pythonread.PythonReadGrantSync pgSync;

  private long tenantId;

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

  private static List<StoredAccount> accounts(long id) {
    return DataStoreAccountRole.all().stream()
        .map(r -> new StoredAccount(r, r.usernameFor(id), "enc-" + r.name()))
        .toList();
  }

  @Test
  @DisplayName("테넌트를 지우면 저장소 행과 계정이 FK 캐스케이드로 함께 사라진다")
  void deletingTenant_cascadesStoreAndAccounts() {
    long id = newTenant();
    repository.insert(row(id), accounts(id));

    TenantRlsTestSupport.deleteProvisionedTenantCascade(dsl, fixtureTransactionTemplate, id);
    tenantId = 0;

    assertThat(repository.find(id)).isEmpty();
    assertThat(repository.findAccounts(id)).isEmpty();
  }

  /** PG 의 등급 정의 변경 동기화는 세대 번호를 건드리지 않는다(PG 는 세션을 끊는다). */
  @Test
  @DisplayName("PG 등급 정의 변경 동기화는 세대 번호를 건드리지 않는다")
  void pgSyncTenantForLevelsChange_doesNotTouchEpoch() {
    long id = newTenant();
    repository.insert(row(id), accounts(id));
    repository.incrementScriptTokenEpoch(id);

    TenantContext.runScoped(id, pgSync::syncTenantForLevelsChange);

    assertThat(repository.findScriptTokenEpoch(id)).contains(1L);
  }

  /** 누가 RLS 를 걸면 이 시험이 먼저 알려 준다 — 백엔드 해석이 테넌트 GUC 없이 읽는 근거. */
  @Test
  @DisplayName("tenant_data_store 에는 RLS 가 없다(PG 카탈로그)")
  void tenantDataStore_hasNoRowLevelSecurity() {
    assertThat(
            dsl.fetchSingle(
                    "select relrowsecurity from pg_class where oid = 'public.tenant_data_store'::regclass")
                .get(0, Boolean.class))
        .isFalse();
    assertThat(
            dsl.fetchSingle(
                    "select relrowsecurity from pg_class where oid = 'public.tenant_data_store_account'::regclass")
                .get(0, Boolean.class))
        .isFalse();
  }
}
