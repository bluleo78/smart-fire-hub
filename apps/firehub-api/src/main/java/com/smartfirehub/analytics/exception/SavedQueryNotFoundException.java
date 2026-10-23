package com.smartfirehub.analytics.exception;

/**
 * 저장 쿼리가 없거나 요청자가 볼 수(또는 고칠 수) 없을 때 던진다 — 전역 핸들러가 404 로 바꾼다.
 *
 * <p>WD-196: 메시지는 응답 message 로 그대로 화면 토스트에 실리므로 한국어로 쓴다. 남의 비공개 자원과 없는 자원을 같은 문구로 답해 존재를
 * 드러내지 않는 정책은 그대로다.
 */
public class SavedQueryNotFoundException extends RuntimeException {
  public SavedQueryNotFoundException(String message) {
    super(message);
  }

  /** 「저장 쿼리를 찾을 수 없습니다: id」 — 다른 모듈의 한국어 NotFound 관례(「API 연결을 찾을 수 없습니다: id」)를 따른다. */
  public static SavedQueryNotFoundException forId(Long id) {
    return new SavedQueryNotFoundException("저장 쿼리를 찾을 수 없습니다: " + id);
  }
}
