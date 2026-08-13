package com.smartfirehub.auth.exception;

/** 소속되지 않았거나 정지된 테넌트를 활성 테넌트로 선택하려 할 때 발생한다. */
public class TenantAccessDeniedException extends RuntimeException {
  public TenantAccessDeniedException(String message) {
    super(message);
  }
}
