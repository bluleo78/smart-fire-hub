package com.smartfirehub.securitylevel.access;

/** AI 공급자의 호스팅 위치. S3 에서 자격증명 선언과 연결된다 — S1 은 Policy 매트릭스에서만 쓴다. */
public enum ProviderHosting {
  EXTERNAL,
  SELF_HOSTED;

  /**
   * 저장값(설정 문서의 hosting 문자열) 해석 — 정확히 "SELF_HOSTED" 일 때만 자체 호스팅, 그 외(null·빈 값·모르는 값)는 외부(기본 외부 — 스펙
   * §3, fail-closed). 해석 전용이다: 요청값의 "모르는 값 400" 검증은 각 저장 경로가 따로 한다.
   */
  public static ProviderHosting fromStored(String raw) {
    return SELF_HOSTED.name().equals(raw) ? SELF_HOSTED : EXTERNAL;
  }
}
