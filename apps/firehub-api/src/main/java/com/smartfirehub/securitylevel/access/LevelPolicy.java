package com.smartfirehub.securitylevel.access;

/**
 * 보안 등급 1개의 정책 스냅샷(security_level 한 행). 판정 함수가 DB 를 모르게 하려고 값 객체로 넘긴다.
 *
 * @param rank 테넌트 내 순서 — 클수록 높은 등급
 */
public record LevelPolicy(
    long id,
    String name,
    int rank,
    boolean isDefault,
    boolean allowlistRequired,
    boolean adminBypass,
    ExportPolicy exportPolicy,
    AiPolicy aiPolicy,
    SharePolicy sharePolicy,
    boolean auditAccess) {

  /** 내보내기 정책(스펙 §2.2). PERMISSION 은 data:export_restricted 보유자만 허용. */
  public enum ExportPolicy {
    ALLOW,
    PERMISSION,
    DENY
  }

  /** AI 분석 정책. SELF_HOSTED_ONLY 는 자체 호스팅 공급자로만 데이터를 보낼 수 있다. */
  public enum AiPolicy {
    ALL,
    SELF_HOSTED_ONLY,
    DENY
  }

  /** 공유 저장소·발송 정책(GraphRAG 적재, 리포트·이메일·Slack). */
  public enum SharePolicy {
    ALLOW,
    DENY
  }

  /** 배지 색 파생용(스펙 §5): 어떤 정책이라도 기본(무제한)보다 좁으면 "제한" 등급이다. */
  public boolean restricted() {
    return exportPolicy != ExportPolicy.ALLOW
        || aiPolicy != AiPolicy.ALL
        || sharePolicy != SharePolicy.ALLOW;
  }
}
