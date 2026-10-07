package com.smartfirehub.securitylevel.access;

/** AI 공급자의 호스팅 위치. S3 에서 자격증명 선언과 연결된다 — S1 은 Policy 매트릭스에서만 쓴다. */
public enum ProviderHosting {
  EXTERNAL,
  SELF_HOSTED
}
