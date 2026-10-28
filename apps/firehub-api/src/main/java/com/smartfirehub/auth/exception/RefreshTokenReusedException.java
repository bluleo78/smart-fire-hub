package com.smartfirehub.auth.exception;

/**
 * 이미 회전된(폐기된) 리프레시 토큰이 다시 제시됐다 — 탈취 의심(WD-268).
 *
 * <p>{@link InvalidTokenException} 과 같은 401 로 응답하지만 타입을 따로 둔다. 이 예외는 <b>패밀리 전체 폐기를 쓴 뒤</b> 던져지므로,
 * 갱신 메서드가 {@code @Transactional(noRollbackFor = RefreshTokenReusedException.class)} 로 그 폐기를 커밋할 수
 * 있어야 한다. 다른 사유의 {@code InvalidTokenException} 까지 커밋 대상으로 넓히지 않으려고 하위 타입으로 좁혔다.
 */
public class RefreshTokenReusedException extends InvalidTokenException {
  public RefreshTokenReusedException() {
    super("이미 사용된 토큰입니다. 다시 로그인해 주세요.");
  }
}
