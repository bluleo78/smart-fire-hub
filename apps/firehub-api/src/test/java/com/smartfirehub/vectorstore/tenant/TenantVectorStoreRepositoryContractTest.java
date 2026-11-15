package com.smartfirehub.vectorstore.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.user.repository.TenantPairContractBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link TenantVectorStoreRepository} 계약 테스트(WD-742) — 구현마다 하위 클래스로 실행한다(jOOQ·MyBatis PG·MyBatis
 * Tibero).
 *
 * <p>전역 테이블(RLS 없음)이라 보장은 「인자로 준 테넌트 id 의 행만 읽는다」이다. A·B 를 다른 값으로 채우고 A·C(행 없음)로 읽어 B 가 섞이지 않음을 본다.
 */
abstract class TenantVectorStoreRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract TenantVectorStoreRepository repository();

  @Test
  @DisplayName("insert 후 find — 주소·포트·TLS·암호문이 왕복하고, 다른 테넌트 행은 섞이지 않는다")
  void insert_thenFind_roundTrips_perTenant() {
    TenantVectorStore a = new TenantVectorStore(tenantA, "qdrant-a.example", 6334, true, "enc-a");
    TenantVectorStore b = new TenantVectorStore(tenantB, "qdrant-b.example", 16334, false, "enc-b");
    repository().insert(a);
    repository().insert(b);

    assertThat(repository().find(tenantA)).contains(a);
    assertThat(repository().find(tenantB)).contains(b);
    assertThat(repository().findTenantIds()).contains(tenantA, tenantB);
  }

  @Test
  @DisplayName("행이 없는 테넌트는 empty(= 공유 Qdrant)")
  void find_missing_isEmpty() {
    long tenantC = fx.createTenant();
    repository().insert(new TenantVectorStore(tenantA, "q", 6334, false, "enc"));
    assertThat(repository().find(tenantC)).isEmpty();
    assertThat(repository().findTenantIds()).doesNotContain(tenantC);
  }

  @Test
  @DisplayName("테넌트당 한 행 — 두 번째 insert 는 키 위반")
  void insert_twice_fails() {
    repository().insert(new TenantVectorStore(tenantA, "q", 6334, false, "enc"));
    assertThatThrownBy(
            () -> repository().insert(new TenantVectorStore(tenantA, "q2", 6334, false, "enc2")))
        .isInstanceOf(RuntimeException.class);
  }
}
