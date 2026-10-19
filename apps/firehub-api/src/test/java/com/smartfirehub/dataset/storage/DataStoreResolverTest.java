package com.smartfirehub.dataset.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link DataStoreResolver} 단위 테스트(WD-72) — Spring 없이 백엔드 색인·테넌트 컨텍스트 요구를 검증한다. */
class DataStoreResolverTest {

  /** backend() 만 스텁한 DataTableStore 목. */
  private static DataTableStore stubStore(DataStoreBackend backend) {
    DataTableStore store = mock(DataTableStore.class);
    when(store.backend()).thenReturn(backend);
    return store;
  }

  @Test
  void 테넌트_컨텍스트가_있으면_POSTGRESQL_구현을_돌려준다() {
    DataTableStore pg = stubStore(DataStoreBackend.POSTGRESQL);
    var resolver = new DataStoreResolver(List.of(pg), List.of());
    TenantContext.set(7L);
    try {
      assertThat(resolver.currentBackend()).isEqualTo(DataStoreBackend.POSTGRESQL);
      assertThat(resolver.tableStore()).isSameAs(pg);
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void 테넌트_컨텍스트가_없으면_예외() {
    var resolver =
        new DataStoreResolver(List.of(stubStore(DataStoreBackend.POSTGRESQL)), List.of());
    TenantContext.clear();
    assertThatThrownBy(resolver::tableStore).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 구현이_없는_백엔드면_예외() {
    var resolver = new DataStoreResolver(List.of(), List.of()); // PG 구현 미등록
    TenantContext.set(7L);
    try {
      assertThatThrownBy(resolver::tableStore)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("POSTGRESQL");
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void 같은_백엔드_구현이_둘이면_생성_시_예외() {
    assertThatThrownBy(
            () ->
                new DataStoreResolver(
                    List.of(
                        stubStore(DataStoreBackend.POSTGRESQL),
                        stubStore(DataStoreBackend.POSTGRESQL)),
                    List.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  /** backend() 만 스텁한 DataRowStore 목. */
  private static DataRowStore stubRowStore(DataStoreBackend backend) {
    DataRowStore store = mock(DataRowStore.class);
    when(store.backend()).thenReturn(backend);
    return store;
  }

  @Test
  void 행_스토어도_현재_백엔드로_해석한다() {
    DataRowStore pgRows = stubRowStore(DataStoreBackend.POSTGRESQL);
    var resolver =
        new DataStoreResolver(List.of(stubStore(DataStoreBackend.POSTGRESQL)), List.of(pgRows));
    TenantContext.set(7L);
    try {
      assertThat(resolver.rowStore()).isSameAs(pgRows);
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void 행_스토어_구현이_없는_백엔드면_예외() {
    var resolver =
        new DataStoreResolver(List.of(stubStore(DataStoreBackend.POSTGRESQL)), List.of());
    TenantContext.set(7L);
    try {
      assertThatThrownBy(resolver::rowStore)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("POSTGRESQL");
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void 같은_백엔드_행_스토어가_둘이면_생성_시_예외() {
    assertThatThrownBy(
            () ->
                new DataStoreResolver(
                    List.of(),
                    List.of(
                        stubRowStore(DataStoreBackend.POSTGRESQL),
                        stubRowStore(DataStoreBackend.POSTGRESQL))))
        .isInstanceOf(IllegalStateException.class);
  }
}
