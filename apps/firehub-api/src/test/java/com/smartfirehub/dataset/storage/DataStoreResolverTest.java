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

  /** 두 스토어 인터페이스 모두 {@code backend()} 만 스텁한 목으로 만든다. */
  private static <T> T stub(Class<T> type, DataStoreBackend backend) {
    T store = mock(type);
    if (store instanceof DataTableStore tableStore) {
      when(tableStore.backend()).thenReturn(backend);
    } else if (store instanceof DataRowStore rowStore) {
      when(rowStore.backend()).thenReturn(backend);
    }
    return store;
  }

  private static DataTableStore stubStore(DataStoreBackend backend) {
    return stub(DataTableStore.class, backend);
  }

  private static DataRowStore stubRowStore(DataStoreBackend backend) {
    return stub(DataRowStore.class, backend);
  }

  @Test
  void 테넌트_컨텍스트가_있으면_POSTGRESQL_구현을_돌려준다() {
    DataTableStore pg = stubStore(DataStoreBackend.POSTGRESQL);
    var resolver = new DataStoreResolver(List.of(pg), List.of());
    TenantContext.runScoped(
        7L,
        () -> {
          assertThat(resolver.currentBackend()).isEqualTo(DataStoreBackend.POSTGRESQL);
          assertThat(resolver.tableStore()).isSameAs(pg);
        });
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
    TenantContext.runScoped(
        7L,
        () ->
            assertThatThrownBy(resolver::tableStore)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POSTGRESQL"));
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

  @Test
  void 행_스토어도_현재_백엔드로_해석한다() {
    DataRowStore pgRows = stubRowStore(DataStoreBackend.POSTGRESQL);
    var resolver =
        new DataStoreResolver(List.of(stubStore(DataStoreBackend.POSTGRESQL)), List.of(pgRows));
    TenantContext.runScoped(7L, () -> assertThat(resolver.rowStore()).isSameAs(pgRows));
  }

  @Test
  void 행_스토어_구현이_없는_백엔드면_예외() {
    var resolver =
        new DataStoreResolver(List.of(stubStore(DataStoreBackend.POSTGRESQL)), List.of());
    TenantContext.runScoped(
        7L,
        () ->
            assertThatThrownBy(resolver::rowStore)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POSTGRESQL"));
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
