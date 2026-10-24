package com.smartfirehub.dataset.exception;

/**
 * 데이터셋의 행을 찾지 못했을 때 던진다 — 전역 핸들러가 404 로 바꾸고 message 를 그대로 내보낸다.
 *
 * <p>WD-240: message 는 화면 토스트에 그대로 실리므로 한국어로 쓰고, 내부 행 id 는 넣지 않는다(예전 「Row not found: N」). 문구가 흩어지지
 * 않도록 기본 생성자로만 만든다.
 */
public class RowNotFoundException extends RuntimeException {

  /** 행이 없을 때(단건 조회·수정 공용). */
  public static final String MESSAGE = "행을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.";

  /** 행을 찾지 못했다는 기본 문구로 만든다. */
  public RowNotFoundException() {
    super(MESSAGE);
  }
}
