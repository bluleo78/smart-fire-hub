package com.smartfirehub.dataset.exception;

/**
 * 데이터셋을 찾지 못했을 때 던진다 — 전역 핸들러가 404 로 바꾸고 message 를 그대로 내보낸다.
 *
 * <p>WD-240: message 는 화면 토스트와 AI 챗에 그대로 실리므로 한국어로 쓰고, 내부 id 는 넣지 않는다(예전 「Dataset not found: N」).
 * 문구가 호출처마다 흩어지지 않도록 기본 생성자로만 만들게 한다(임의 문자열 생성자는 두지 않는다). 볼 수 없는(보안 등급·권한) 데이터셋과 없는 데이터셋을 같은 문구로 답해
 * 존재를 드러내지 않는 정책은 그대로다.
 */
public class DatasetNotFoundException extends RuntimeException {

  /** 데이터셋이 없거나 볼 수 없을 때 — 웹의 없는 URL 진입 화면 문구(「데이터셋을 찾을 수 없습니다.」)와 앞부분을 맞춘다. */
  public static final String MESSAGE = "데이터셋을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.";

  /** 데이터셋을 찾지 못했다는 기본 문구로 만든다. */
  public DatasetNotFoundException() {
    super(MESSAGE);
  }
}
