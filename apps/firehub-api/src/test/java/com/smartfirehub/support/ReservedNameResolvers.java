package com.smartfirehub.support;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.smartfirehub.dataset.service.PostgresDataTableStore;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.dataset.storage.DataTableStore;

/**
 * DB 없이 {@code DataTableService} 의 새 컬럼명 검증만 쓰는 단위 테스트용 리졸버 목(WD-564).
 *
 * <p>예약 컬럼명은 백엔드마다 다르므로(PG 는 xmin 등 PG 시스템 컬럼 포함) 검증이 현재 테넌트의 저장소를 묻는다. 여기서는 저장소의 예약어 규칙 메서드만 실제로
 * 동작하는 목을 돌려준다 — DDL 메서드는 부르지 않는 테스트 전용이다.
 */
public final class ReservedNameResolvers {

  private ReservedNameResolvers() {}

  /** PG 저장소 예약어 규칙(앱 시스템 컬럼 + PG 시스템 컬럼)을 쓰는 리졸버. */
  public static DataStoreResolver postgres() {
    return withStore(mock(PostgresDataTableStore.class, CALLS_REAL_METHODS));
  }

  /** Tibero 처럼 인터페이스 기본 예약어 규칙(앱 시스템 컬럼만)을 쓰는 리졸버. */
  public static DataStoreResolver appReservedOnly() {
    return withStore(mock(DataTableStore.class, CALLS_REAL_METHODS));
  }

  private static DataStoreResolver withStore(DataTableStore store) {
    DataStoreResolver resolver = mock(DataStoreResolver.class);
    // lenient — 검증을 부르지 않는 테스트(엄격 스텁 MockitoExtension)에서 미사용 스텁으로 실패하지 않게 한다.
    lenient().when(resolver.tableStore()).thenReturn(store);
    return resolver;
  }
}
