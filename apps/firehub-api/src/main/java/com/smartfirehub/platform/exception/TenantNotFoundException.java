package com.smartfirehub.platform.exception;

/**
 * 운영자 평면에서 대상 테넌트를 찾지 못했을 때. 404 로 매핑된다.
 *
 * <p>이 프로젝트는 예외별로 상태를 매핑하므로(공통 {@code ResourceNotFoundException} 이 없다) 평면
 * 전용 예외를 둔다. 잘못된 요청은 기존 {@code IllegalArgumentException}(400) 매핑을 재사용한다.
 */
public class TenantNotFoundException extends RuntimeException {

  public TenantNotFoundException(String message) {
    super(message);
  }
}
