package com.smartfirehub.vectorstore.tenant;

/** 테넌트별 벡터 저장소 대상 해석의 캐시를 다루는 쪽(WD-742) — 전용 설정이 바뀌면(테넌트 생성 커밋 뒤) 다시 읽게 한다. */
public interface TenantVectorStoreTargets {

  /** 이 테넌트의 대상 해석 캐시를 지운다. */
  void invalidate(long tenantId);
}
