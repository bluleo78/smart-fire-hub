package com.smartfirehub.dataset.storage;

import com.smartfirehub.global.tenant.TenantContext;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 테넌트가 사용하는 데이터셋 저장소 백엔드를 해석하고 그 구현({@link DataTableStore}, {@link DataRowStore})을 돌려준다(WD-72).
 *
 * <p>구현체들은 생성 시 백엔드별로 색인한다. 같은 백엔드 구현이 둘이면 어느 쪽이 쓰일지 모호하므로 기동 시점에 실패시킨다.
 */
@Component
public class DataStoreResolver {

  private final Map<DataStoreBackend, DataTableStore> tableStores =
      new EnumMap<>(DataStoreBackend.class);

  private final Map<DataStoreBackend, DataRowStore> rowStores =
      new EnumMap<>(DataStoreBackend.class);

  public DataStoreResolver(List<DataTableStore> tableStores, List<DataRowStore> rowStores) {
    for (DataTableStore store : tableStores) {
      DataTableStore previous = this.tableStores.put(store.backend(), store);
      if (previous != null) {
        throw new IllegalStateException("데이터셋 저장소 구현이 중복됨: " + store.backend());
      }
    }
    for (DataRowStore store : rowStores) {
      DataRowStore previous = this.rowStores.put(store.backend(), store);
      if (previous != null) {
        throw new IllegalStateException("데이터셋 행 저장소 구현이 중복됨: " + store.backend());
      }
    }
  }

  /** 테넌트의 저장소 백엔드. 이번 단계에서는 모든 테넌트가 PostgreSQL 이다. */
  public DataStoreBackend backendFor(long tenantId) {
    return DataStoreBackend.POSTGRESQL;
  }

  /** 현재 요청/잡의 테넌트 컨텍스트 기준 백엔드. 컨텍스트가 없으면 예외를 던진다(조용한 기본값 금지). */
  public DataStoreBackend currentBackend() {
    return backendFor(TenantContext.require("데이터셋 저장소 해석"));
  }

  /** 현재 테넌트 백엔드의 DDL 스토어. 구현이 등록돼 있지 않으면 예외를 던진다. */
  public DataTableStore tableStore() {
    DataStoreBackend backend = currentBackend();
    DataTableStore store = tableStores.get(backend);
    if (store == null) {
      throw new IllegalStateException("데이터셋 저장소 구현 없음: " + backend);
    }
    return store;
  }

  /** 현재 테넌트 백엔드의 행 조회·적재 스토어. 구현이 등록돼 있지 않으면 예외를 던진다. */
  public DataRowStore rowStore() {
    DataStoreBackend backend = currentBackend();
    DataRowStore store = rowStores.get(backend);
    if (store == null) {
      throw new IllegalStateException("데이터셋 행 저장소 구현 없음: " + backend);
    }
    return store;
  }
}
