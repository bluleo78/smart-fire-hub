package com.smartfirehub.auth.repository.mybatis;

/**
 * 인증 테이블의 문자열 키 열에 빈 문자열을 안전하게 넣고 찾기 위한 변환(WD-706).
 *
 * <p>왜: Tibero 는 빈 문자열을 NULL 로 본다. 로그인 시도 계정·IP 키·토큰 해시는 NOT NULL 키 열이라 {@code ""} 를 그대로 넣으면 -10005
 * 로 터지고, 찾을 때는 {@code = NULL} 이 되어 영영 안 맞는다. PG 는 {@code ""} 를 정상 값으로 다루므로 두 DB 가 같은 결과를 내도록, 이 열들은
 * <b>읽어서 돌려주는 값이 아니라 찾는 키</b>라는 점을 이용해 빈 문자열을 센티널로 바꿔 저장·조회한다. 센티널은 제어 문자로 시작해 실제 계정·IP·해시와 겹치지
 * 않는다.
 */
final class EmptyKeys {

  /** 빈 문자열 대신 저장하는 값 — 실제 키 열에는 나타나지 않는 제어 문자 접두. */
  static final String EMPTY = "\u0001<empty>";

  private EmptyKeys() {}

  /** 빈 문자열이면 센티널로, 아니면 그대로 돌려준다(null 은 그대로 — 호출자 계약 밖). */
  static String key(String value) {
    return value != null && value.isEmpty() ? EMPTY : value;
  }
}
