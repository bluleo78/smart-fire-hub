package com.smartfirehub.securitylevel.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.GeneratedId;

/**
 * 보안 등급 INSERT 파라미터 겸 생성 키 홀더(WD-711). {@code useGeneratedKeys} 가 생성된 id 를 {@code id} 에 써 넣는다 —
 * Tibero 에는 RETURNING 이 없어 jOOQ 의 {@code returning(ID)} 대신 JDBC 생성 키로 받는다. 정책 enum 은 이름 문자열로 바꿔
 * 둔다(CHECK 제약과 같은 집합).
 */
public class SecurityLevelInsert extends GeneratedId {

  private final int rank;
  private final String name;
  private final boolean allowlistRequired;
  private final boolean adminBypass;
  private final String exportPolicy;
  private final String aiPolicy;
  private final String sharePolicy;
  private final boolean auditAccess;
  private final long actor;

  public SecurityLevelInsert(
      int rank,
      String name,
      boolean allowlistRequired,
      boolean adminBypass,
      String exportPolicy,
      String aiPolicy,
      String sharePolicy,
      boolean auditAccess,
      long actor) {
    this.rank = rank;
    this.name = name;
    this.allowlistRequired = allowlistRequired;
    this.adminBypass = adminBypass;
    this.exportPolicy = exportPolicy;
    this.aiPolicy = aiPolicy;
    this.sharePolicy = sharePolicy;
    this.auditAccess = auditAccess;
    this.actor = actor;
  }

  /** #{rank} 바인딩용. */
  public int getRank() {
    return rank;
  }

  /** #{name} 바인딩용(이미 trim 됨). */
  public String getName() {
    return name;
  }

  /** boolLiteral 조각의 allowlistRequired 용. */
  public boolean getAllowlistRequired() {
    return allowlistRequired;
  }

  /** boolLiteral 조각의 adminBypass 용. */
  public boolean getAdminBypass() {
    return adminBypass;
  }

  /** #{exportPolicy} 바인딩용. */
  public String getExportPolicy() {
    return exportPolicy;
  }

  /** #{aiPolicy} 바인딩용. */
  public String getAiPolicy() {
    return aiPolicy;
  }

  /** #{sharePolicy} 바인딩용. */
  public String getSharePolicy() {
    return sharePolicy;
  }

  /** boolLiteral 조각의 auditAccess 용. */
  public boolean getAuditAccess() {
    return auditAccess;
  }

  /** created_by·updated_by 에 넣을 작업자. */
  public long getActor() {
    return actor;
  }
}
