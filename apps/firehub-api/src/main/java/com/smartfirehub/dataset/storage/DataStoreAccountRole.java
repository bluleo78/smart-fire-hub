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
  OWNER(0),
  RO(0),
  S1(1),
  S2(2),
  S3(3),
  S4(4),
  S5(5),
  S6(6),
  S7(7),
  S8(8),
  S9(9),
  S10(10);

  /** 읽기 슬롯 번호(1..10). 슬롯이 아닌 역할(OWNER·RO)은 0 — 이름을 파싱하지 않고 역할에 명시한다(WD-78). */
  private final int readSlot;

  DataStoreAccountRole(int readSlot) {
    this.readSlot = readSlot;
  }

  /** Python 파이프라인 읽기 슬롯 계정(S1..S10)인지. */
  public boolean isReadSlot() {
    return readSlot > 0;
  }

  /**
   * 읽기 슬롯 번호(1..10).
   *
   * @throws IllegalStateException 슬롯이 아닌 역할(OWNER·RO)일 때
   */
  public int readSlot() {
    if (readSlot == 0) {
      throw new IllegalStateException(name() + " 는 읽기 슬롯 계정이 아닙니다");
    }
    return readSlot;
  }

  /** {@link #all()} 이 매번 새 배열·목록을 만들지 않도록 한 번만 만들어 둔 불변 목록. */
  private static final List<DataStoreAccountRole> ALL = List.of(values());

  /** 저장·생성 순서(OWNER, RO, S1..S10)의 전체 역할 목록. 변경할 수 없는 목록이다. */
  public static List<DataStoreAccountRole> all() {
    return ALL;
  }

  /**
   * 읽기 슬롯 번호(1..10)의 역할(S1..S10) — 이름 문자열({@code "S" + n})을 이어 붙여 찾지 않도록 역할에 적힌 번호로 고른다(WD-78).
   *
   * @throws IllegalArgumentException 슬롯 번호가 1..10 밖일 때
   */
  public static DataStoreAccountRole forSlot(int slot) {
    if (slot > 0) {
      for (DataStoreAccountRole role : ALL) {
        if (role.readSlot == slot) {
          return role;
        }
      }
    }
    throw new IllegalArgumentException("읽기 슬롯 번호는 1~10 이어야 합니다: " + slot);
  }

  /** 테넌트의 DB 계정 이름: {@code GENIA_T{id}}, {@code GENIA_T{id}_RO}, {@code GENIA_T{id}_S{n}}. */
  public String usernameFor(long tenantId) {
    return this == OWNER ? "GENIA_T" + tenantId : "GENIA_T" + tenantId + "_" + name();
  }
}
