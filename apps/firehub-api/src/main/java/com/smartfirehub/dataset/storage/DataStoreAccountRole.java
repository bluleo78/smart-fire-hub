package com.smartfirehub.dataset.storage;

import com.smartfirehub.global.tenant.TenantPipelineRole;
import java.util.List;

/**
 * 외부 저장소 테넌트 스키마에 만드는 접속 계정의 역할(WD-73).
 *
 * <p>OWNER 는 DDL·적재, RO 는 읽기 전용, S1~S10 은 Python 파이프라인 읽기 슬롯이다. 슬롯 개수는 PostgreSQL 쪽 {@link
 * TenantPipelineRole#PYTHON_READ_SLOTS} 와 같아야 하며 테스트로 고정한다.
 */
public enum DataStoreAccountRole {
  OWNER,
  RO,
  S1,
  S2,
  S3,
  S4,
  S5,
  S6,
  S7,
  S8,
  S9,
  S10;

  /** {@link #all()} 이 매번 새 배열·목록을 만들지 않도록 한 번만 만들어 둔 불변 목록. */
  private static final List<DataStoreAccountRole> ALL = List.of(values());

  /** 저장·생성 순서(OWNER, RO, S1..S10)의 전체 역할 목록. 변경할 수 없는 목록이다. */
  public static List<DataStoreAccountRole> all() {
    return ALL;
  }

  /** 테넌트의 DB 계정 이름: {@code GENIA_T{id}}, {@code GENIA_T{id}_RO}, {@code GENIA_T{id}_S{n}}. */
  public String usernameFor(long tenantId) {
    return this == OWNER ? "GENIA_T" + tenantId : "GENIA_T" + tenantId + "_" + name();
  }
}
