package com.smartfirehub.dataset.exception;

/** 다른 리소스(파이프라인 스텝 등)가 이 데이터셋을 참조하고 있어 삭제할 수 없을 때 발생한다. */
public class DatasetInUseException extends RuntimeException {
  public DatasetInUseException(String message) {
    super(message);
  }
}
