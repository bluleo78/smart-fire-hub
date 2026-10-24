package com.smartfirehub.dataset.exception;

/**
 * 데이터셋에서 지우려는 태그를 찾지 못했을 때 던진다 — 전역 핸들러가 404 로 바꾸고 message 를 그대로 내보낸다.
 *
 * <p>WD-242: 예전에는 없는 태그(또는 대소문자만 다른 태그) 삭제도 204 를 줘서 지운 것처럼 보였다. 문구는 WD-240 의 다른 not-found 와 같은
 * 형식으로 쓰고, 흩어지지 않도록 기본 생성자로만 만든다.
 */
public class DatasetTagNotFoundException extends RuntimeException {

  /** 데이터셋에 그 이름(대소문자 무시)의 태그가 없을 때. */
  public static final String MESSAGE = "태그를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.";

  /** 태그를 찾지 못했다는 기본 문구로 만든다. */
  public DatasetTagNotFoundException() {
    super(MESSAGE);
  }
}
