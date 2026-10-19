package com.smartfirehub.dataset.storage.external;

/** 외부 DB 접속 실패. 메시지는 사용자에게 그대로 보여 줄 한국어 사유이며 비밀번호를 포함하지 않는다. */
public class ExternalDbConnectionException extends Exception {

  public ExternalDbConnectionException(String message) {
    super(message);
  }
}
