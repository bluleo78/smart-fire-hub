package com.smartfirehub.dataset.storage;

/**
 * 데이터셋 행(row)이 실제로 저장되는 백엔드 종류(WD-72).
 *
 * <p>이번 단계에서는 모든 테넌트가 {@link #POSTGRESQL} 이다. TIBERO/MYSQL 은 이후 단계에서 구현이 붙을 자리를 미리 잡아 둔 식별자다.
 */
public enum DataStoreBackend {
  POSTGRESQL,
  TIBERO,
  MYSQL
}
