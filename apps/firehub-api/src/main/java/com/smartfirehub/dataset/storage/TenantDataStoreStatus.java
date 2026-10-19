package com.smartfirehub.dataset.storage;

/** 테넌트 외부 저장소의 프로비저닝 상태(WD-73). */
public enum TenantDataStoreStatus {
  /** 설정만 저장됐고 아직 사용할 수 없다. */
  PENDING,
  /** 프로비저닝이 끝나 사용할 수 있다. */
  READY
}
