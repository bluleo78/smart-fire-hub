package com.smartfirehub.securitylevel.access;

/**
 * 판정 결과. 거부 사유를 문자열 코드로 구조화해 UI·감사·(S3)AI 차단 응답이 같은 값을 쓰게 한다.
 *
 * @param policyKey 거부를 일으킨 정책 키(rank / allowlist_required / export_policy / ai_policy /
 *     share_policy)
 */
public record Decision(boolean allowed, String reasonCode, Long levelId, String policyKey) {

  public static Decision allow(Long levelId) {
    return new Decision(true, null, levelId, null);
  }

  public static Decision deny(String reasonCode, Long levelId, String policyKey) {
    return new Decision(false, reasonCode, levelId, policyKey);
  }
}
