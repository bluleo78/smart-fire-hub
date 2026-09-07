package com.smartfirehub.dataset.exception;

/**
 * 카테고리에 연결된 데이터셋이 남아있어 삭제할 수 없을 때 던지는 예외.
 * DatasetInUseException과 동일하게 409 Conflict로 응답한다 (#518).
 */
public class CategoryInUseException extends RuntimeException {
  public CategoryInUseException(String message) {
    super(message);
  }
}
