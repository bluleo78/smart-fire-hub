package com.smartfirehub.apiconnection.exception;

/**
 * API 연결이 없거나 현재 워크스페이스에서 보이지 않을 때 던진다 — HTTP 404 로 나간다(WD-180).
 *
 * <p><b>왜 따로 두는가</b>: 예전에는 이 패키지의 모든 오류가 {@link ApiConnectionException} 하나였고 전역 처리기가 그것을 통째로 404 로
 * 매핑해, 지원하지 않는 인증 방식·내부 주소 거절 같은 입력 오류까지 「찾을 수 없음」(404)으로 나갔다. 「없음」만 이 예외로 분리해 404 로 보내고, 상위 {@link
 * ApiConnectionException} 은 입력·검증 오류(400)로 쓴다. 상위 예외를 상속해 두어 「이 패키지의 오류」를 통째로 잡던 호출부는 그대로 동작한다.
 *
 * <p>RLS 아래 조회라 다른 워크스페이스의 연결과 없는 연결이 같은 경로(0행)로 와서 같은 문구가 된다 — 번호의 존재 여부가 새지 않는다(WD-176).
 */
public class ApiConnectionNotFoundException extends ApiConnectionException {

  public ApiConnectionNotFoundException(Long id) {
    super("API 연결을 찾을 수 없습니다: " + id);
  }
}
