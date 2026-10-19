package com.smartfirehub.dataset.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
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

  /** 설정 행이 하나도 없는(= 모든 테넌트가 기본 PostgreSQL) 리포지토리. Mockito 는 Optional 반환을 empty 로 돌려준다. */
  private static TenantDataStoreRepository emptyRepo() {
    return mock(TenantDataStoreRepository.class);
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
    var resolver = new DataStoreResolver(List.of(pg), List.of(), emptyRepo());
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
        new DataStoreResolver(
            List.of(stubStore(DataStoreBackend.POSTGRESQL)), List.of(), emptyRepo());
    TenantContext.clear();
    assertThatThrownBy(resolver::tableStore).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 구현이_없는_백엔드면_예외() {
    var resolver = new DataStoreResolver(List.of(), List.of(), emptyRepo()); // PG 구현 미등록
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
                    List.of(),
                    emptyRepo()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 행_스토어도_현재_백엔드로_해석한다() {
    DataRowStore pgRows = stubRowStore(DataStoreBackend.POSTGRESQL);
    var resolver =
        new DataStoreResolver(
            List.of(stubStore(DataStoreBackend.POSTGRESQL)), List.of(pgRows), emptyRepo());
    TenantContext.runScoped(7L, () -> assertThat(resolver.rowStore()).isSameAs(pgRows));
  }

  @Test
  void 행_스토어_구현이_없는_백엔드면_예외() {
    var resolver =
        new DataStoreResolver(
            List.of(stubStore(DataStoreBackend.POSTGRESQL)), List.of(), emptyRepo());
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
                        stubRowStore(DataStoreBackend.POSTGRESQL)),
                    emptyRepo()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void backendFor_returnsPostgresqlWhenNoRow() {
    var resolver = new DataStoreResolver(List.of(), List.of(), emptyRepo());
    assertThat(resolver.backendFor(7L)).isEqualTo(DataStoreBackend.POSTGRESQL);
  }

  @Test
  void backendFor_returnsStoredBackend() {
    var repo = mock(TenantDataStoreRepository.class);
    when(repo.findBackend(7L)).thenReturn(Optional.of(DataStoreBackend.TIBERO));
    var resolver = new DataStoreResolver(List.of(), List.of(), repo);
    assertThat(resolver.backendFor(7L)).isEqualTo(DataStoreBackend.TIBERO);
  }

  @Test
  void backendFor_cachesPerTenant() {
    var repo = mock(TenantDataStoreRepository.class);
    when(repo.findBackend(7L)).thenReturn(Optional.of(DataStoreBackend.TIBERO));
    var resolver = new DataStoreResolver(List.of(), List.of(), repo);

    resolver.backendFor(7L);
    resolver.backendFor(7L);
    resolver.backendFor(7L);
    verify(repo, times(1)).findBackend(7L);

    // 다른 테넌트는 별도로 조회한다.
    assertThat(resolver.backendFor(8L)).isEqualTo(DataStoreBackend.POSTGRESQL);
    verify(repo, times(1)).findBackend(8L);
  }

  /**
   * 생성 트랜잭션이 커밋되기 전에 기본값(PostgreSQL)이 캐시되더라도, 행을 넣은 뒤 {@code invalidate} 하면 다음 조회는 저장된 백엔드를 돌려준다
   * (WD-73).
   */
  @Test
  void invalidate_dropsCachedDefault_soStoredBackendIsReadAgain() {
    var repo = mock(TenantDataStoreRepository.class);
    // 처음에는 행이 없다(커밋 전) — PostgreSQL 이 캐시된다.
    when(repo.findBackend(7L)).thenReturn(Optional.empty());
    var resolver = new DataStoreResolver(List.of(), List.of(), repo);
    assertThat(resolver.backendFor(7L)).isEqualTo(DataStoreBackend.POSTGRESQL);

    // 행이 들어온 뒤에도 무효화 전에는 캐시된 값이 남는다.
    when(repo.findBackend(7L)).thenReturn(Optional.of(DataStoreBackend.TIBERO));
    assertThat(resolver.backendFor(7L)).isEqualTo(DataStoreBackend.POSTGRESQL);

    resolver.invalidate(7L);

    assertThat(resolver.backendFor(7L)).isEqualTo(DataStoreBackend.TIBERO);
    verify(repo, times(2)).findBackend(7L);
  }

  @Test
  void tableStore_forTiberoTenantFailsClearly_noPostgresFallback() {
    var repo = mock(TenantDataStoreRepository.class);
    when(repo.findBackend(7L)).thenReturn(Optional.of(DataStoreBackend.TIBERO));
    var resolver =
        new DataStoreResolver(List.of(stubStore(DataStoreBackend.POSTGRESQL)), List.of(), repo);
    TenantContext.runScoped(
        7L,
        () ->
            assertThatThrownBy(resolver::tableStore)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TIBERO"));
  }
}
