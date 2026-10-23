package com.smartfirehub.dataset.rowsearch;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreFeatureUnsupportedException;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 행 검색(pgvector 색인)이 현재 테넌트 저장소에서 쓸 수 있는지 확인하는 관문(WD-80).
 *
 * <p>행 검색 색인은 PostgreSQL 전용이다. Tibero 테넌트는 설정·판정·DB 접근 어느 것도 하기 전에 422 로 거부해야 하므로, 네 엔드포인트가 모두 이 한
 * 곳을 먼저 지난다. tenant_data_store 는 RLS 가 없어 트랜잭션 밖에서 불러도 안전하다.
 */
@Component
@RequiredArgsConstructor
public class RowSearchBackendGuard {

  private final DataStoreResolver dataStoreResolver;

  /** 현재 요청 테넌트의 저장소가 행 검색을 지원하지 않으면(Tibero) "행 검색" 미지원 예외(422)를 던진다. */
  public void requireSupported() {
    DataStoreBackend backend = dataStoreResolver.currentBackend();
    if (!backend.supportsRowSearch()) {
      throw new DataStoreFeatureUnsupportedException(backend, "행 검색");
    }
  }
}
