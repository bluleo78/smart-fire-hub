package com.smartfirehub.dataset.storage;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.smartfirehub.global.tenant.TenantContext;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * 테넌트가 사용하는 데이터셋 저장소 백엔드를 해석하고 그 구현({@link DataTableStore}, {@link DataRowStore})을 돌려준다(WD-72).
 *
 * <p>테넌트별 백엔드는 {@code tenant_data_store} 행으로 정해지고 행이 없으면 PostgreSQL 이다. 파사드 호출마다 불리므로 Caffeine 으로
 * 캐시한다(최대 10,000 테넌트, 만료 없음).
 *
 * <p>만료가 없는 이유(R5): 한 테넌트의 백엔드는 테넌트 생성 시 한 번 정해지고 이후 바뀌지 않는다. 다만 생성 트랜잭션 도중 누군가 그 테넌트의 백엔드를 먼저 해석하면
 * "행 없음 = PostgreSQL" 이 캐시에 남을 수 있으므로, 저장소 행을 넣는 쪽({@code TenantDataStoreSetupService})이 커밋 뒤
 * {@link #invalidate} 를 부른다(WD-73). 백엔드 변경 기능이 생기면 그 지점에서도 {@link #invalidate} 를 불러야 한다.
 *
 * <p>구현체들은 생성 시 백엔드별로 색인한다. 같은 백엔드 구현이 둘이면 어느 쪽이 쓰일지 모호하므로 기동 시점에 실패시킨다.
 */
@Component
public class DataStoreResolver {

  private final Map<DataStoreBackend, DataTableStore> tableStores;
  private final Map<DataStoreBackend, DataRowStore> rowStores;

  private final TenantDataStoreRepository repository;
  private final Cache<Long, DataStoreBackend> backends =
      Caffeine.newBuilder().maximumSize(10_000).build();

  public DataStoreResolver(
      List<DataTableStore> tableStores,
      List<DataRowStore> rowStores,
      TenantDataStoreRepository repository) {
    this.repository = repository;
    this.tableStores = index(tableStores, DataTableStore::backend, "데이터셋 저장소 구현이 중복됨: ");
    this.rowStores = index(rowStores, DataRowStore::backend, "데이터셋 행 저장소 구현이 중복됨: ");
  }

  /** 구현 목록을 백엔드별로 색인한다. 같은 백엔드가 둘이면 어느 쪽을 쓸지 모호하므로 즉시 실패시킨다. */
  private static <T> Map<DataStoreBackend, T> index(
      List<T> stores, Function<T, DataStoreBackend> backendOf, String duplicateMessage) {
    Map<DataStoreBackend, T> indexed = new EnumMap<>(DataStoreBackend.class);
    for (T store : stores) {
      T previous = indexed.put(backendOf.apply(store), store);
      if (previous != null) {
        throw new IllegalStateException(duplicateMessage + backendOf.apply(store));
      }
    }
    return indexed;
  }

  /** 색인에서 백엔드 구현을 찾는다. 등록돼 있지 않으면 예외를 던진다. */
  private static <T> T lookup(
      Map<DataStoreBackend, T> stores, DataStoreBackend backend, String missingMessage) {
    T store = stores.get(backend);
    if (store == null) {
      throw new IllegalStateException(missingMessage + backend);
    }
    return store;
  }

  /** 테넌트의 저장소 백엔드. 설정 행이 없으면 PostgreSQL 이며, 조회 결과는 테넌트별로 캐시한다. */
  public DataStoreBackend backendFor(long tenantId) {
    return backends.get(
        tenantId, id -> repository.findBackend(id).orElse(DataStoreBackend.POSTGRESQL));
  }

  /**
   * 테넌트의 캐시된 백엔드를 버린다. 다음 {@link #backendFor} 는 저장소 행을 다시 읽는다.
   *
   * <p>저장소 행을 커밋한 직후 불러, 커밋 전에 캐시된 기본값(PostgreSQL)이 남지 않게 한다.
   */
  public void invalidate(long tenantId) {
    backends.invalidate(tenantId);
  }

  /** 현재 요청/잡의 테넌트 컨텍스트 기준 백엔드. 컨텍스트가 없으면 예외를 던진다(조용한 기본값 금지). */
  public DataStoreBackend currentBackend() {
    return backendFor(TenantContext.require("데이터셋 저장소 해석"));
  }

  /** 현재 테넌트 백엔드의 DDL 스토어. 구현이 등록돼 있지 않으면 예외를 던진다. */
  public DataTableStore tableStore() {
    return lookup(tableStores, currentBackend(), "데이터셋 저장소 구현 없음: ");
  }

  /** 현재 테넌트 백엔드의 행 조회·적재 스토어. 구현이 등록돼 있지 않으면 예외를 던진다. */
  public DataRowStore rowStore() {
    return lookup(rowStores, currentBackend(), "데이터셋 행 저장소 구현 없음: ");
  }
}
