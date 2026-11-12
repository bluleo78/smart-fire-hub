package com.smartfirehub.securitylevel.repository.mybatis;

import com.smartfirehub.securitylevel.access.LevelPolicy.AiPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy.ExportPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy.SharePolicy;

/**
 * DatasetAccessMapper 의 행 값 객체 모음(WD-711). Tibero 는 NUMBER 를 BigDecimal 로 돌려주므로 Long·Integer 를 명시한
 * 생성자 매핑으로 받고, 리포지토리가 인터페이스 반환형(AccessFacts·ClearanceRow·Map)으로 옮긴다.
 */
public final class DatasetAccessRows {

  private DatasetAccessRows() {}

  /**
   * 데이터셋 1개의 판정 사실 평평한 행 — 데이터셋 id·테이블 이름·허용 목록 여부(onList 1/0)와 등급 열 전부. 리포지토리가 등급 열을 LevelPolicy 로
   * 묶는다(jOOQ toPolicy 와 같은 엄격한 enum 변환은 결과 매핑의 EnumTypeHandler 가 한다).
   */
  public record FactRow(
      Long datasetId,
      String tableName,
      Integer onList,
      Long levelId,
      String levelName,
      Integer rank,
      Boolean isDefault,
      Boolean allowlistRequired,
      Boolean adminBypass,
      ExportPolicy exportPolicy,
      AiPolicy aiPolicy,
      SharePolicy sharePolicy,
      Boolean auditAccess) {}

  /** 자격 계산 공통 조인 한 행 — (사용자, 역할, 역할 이름·시스템 여부, 역할 최대 등급 rank). */
  public record ClearanceRoleRow(
      Long userId, Long roleId, String roleName, Boolean isSystem, Integer rank) {}
}
