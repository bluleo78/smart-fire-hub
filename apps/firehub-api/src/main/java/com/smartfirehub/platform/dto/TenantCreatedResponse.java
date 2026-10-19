package com.smartfirehub.platform.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * 테넌트 생성 응답. 기존 {@link TenantSummaryResponse} 필드는 JSON 최상위에 그대로 두고(언랩), 스크립트 모드일 때만 스크립트 필드를 하나 더
 * 싣는다.
 *
 * @param dataStoreSetupScript SCRIPT 모드의 계정 생성 스크립트(비밀번호 포함, 이 응답에서만 1회 노출). 그 밖에는 null 이라 JSON 에서
 *     빠진다
 */
public record TenantCreatedResponse(
    @JsonUnwrapped TenantSummaryResponse tenant,
    @JsonInclude(JsonInclude.Include.NON_NULL) String dataStoreSetupScript) {

  /** toString 에 스크립트(비밀번호 포함)가 찍히지 않게 한다. */
  @Override
  public String toString() {
    return "TenantCreatedResponse[tenant="
        + tenant
        + ", dataStoreSetupScript="
        + (dataStoreSetupScript == null ? "null" : "****")
        + "]";
  }
}
