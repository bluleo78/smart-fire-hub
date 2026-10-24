package com.smartfirehub.dataimport.exception;

/**
 * 가져오기(임포트) 이력을 찾지 못했을 때 던진다 — 전역 핸들러가 404 로 바꾸고 message 를 그대로 내보낸다(WD-250).
 *
 * <p>예전에는 {@link IllegalArgumentException}(「Import not found: N」·「Import does not belong to this
 * dataset」)이라 400 + 영어 문구가 나갔다. 다른 데이터셋의 가져오기 id 도 같은 문구로 답해 그 id 가 다른 데이터셋에 있다는 사실을 드러내지 않는다. 문구가
 * 호출처마다 흩어지지 않도록 기본 생성자만 둔다(WD-226·240 방식).
 */
public class ImportNotFoundException extends RuntimeException {

  /** 가져오기 이력이 없거나 이 데이터셋의 것이 아닐 때. */
  public static final String MESSAGE = "가져오기 이력을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.";

  /** 가져오기 이력을 찾지 못했다는 기본 문구로 만든다. */
  public ImportNotFoundException() {
    super(MESSAGE);
  }
}
