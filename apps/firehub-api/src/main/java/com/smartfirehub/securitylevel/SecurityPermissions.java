package com.smartfirehub.securitylevel;

/**
 * 보안 등급 권한 코드(스펙 §2.6). V133 이 시드하는 4개 코드의 소스 정본 — PermissionCatalogUsageTest 는 "카탈로그의 모든 코드가 소스 리터럴로
 * 존재"를 요구하므로, 사용처(@RequirePermission·Policy)가 생기기 전 태스크에서도 테스트가 녹색이다.
 */
public final class SecurityPermissions {

  public static final String SETTINGS = "security:settings";
  public static final String CLASSIFY = "dataset:classify";
  public static final String GRANT = "dataset:grant";
  public static final String EXPORT_RESTRICTED = "data:export_restricted";

  private SecurityPermissions() {}
}
