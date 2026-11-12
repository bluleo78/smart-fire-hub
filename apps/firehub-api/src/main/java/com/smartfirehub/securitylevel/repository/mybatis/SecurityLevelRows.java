package com.smartfirehub.securitylevel.repository.mybatis;

/**
 * SecurityLevelMapper 의 집계·역할 행 값 객체 모음(WD-711). Tibero 는 NUMBER 를 BigDecimal 로 돌려주므로 결과 맵 대신 Long
 * 을 명시한 생성자 매핑으로 받고, 리포지토리가 인터페이스 반환형(Map·record)으로 옮긴다.
 */
public final class SecurityLevelRows {

  private SecurityLevelRows() {}

  /** 등급(또는 역할 최대 등급) id 별 행 수. */
  public record LevelCount(Long levelId, Long cnt) {}

  /** 등급 × 역할별 허용 데이터셋 수. */
  public record LevelRoleCount(Long levelId, Long roleId, Long cnt) {}

  /** 역할 id → 역할 최대 열람 등급 id. */
  public record RoleLevelId(Long roleId, Long levelId) {}

  /** 역할 한 행(시스템 ADMIN 판정은 리포지토리가 SystemAdminRole.matches 로 한다). */
  public record RoleRow(Long id, String name, Long levelId, Boolean isSystem) {}

  /** updateRanks 의 (등급 id, 새 rank) 한 쌍 — CASE 문 바인딩용. */
  public record RankEntry(Long id, Integer rank) {}
}
