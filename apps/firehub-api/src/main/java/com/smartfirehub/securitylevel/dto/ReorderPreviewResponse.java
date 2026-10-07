package com.smartfirehub.securitylevel.dto;

import java.util.List;

/** 순서 변경 영향 — 열람 데이터셋 수가 바뀌는 역할만(개수만, 이름 비노출 — 스펙 §5-1). */
public record ReorderPreviewResponse(List<RoleImpact> roles) {

  /** 역할 1개의 열람 가능 데이터셋 수 증감(음수 = 줄어듦). */
  public record RoleImpact(Long roleId, String roleName, long datasetDelta) {}
}
