package com.smartfirehub.dbexecutor.store;

/** 테넌트 저장소 기술자를 얻지 못했다(fail-closed). 메시지는 한국어이며 비밀번호·URL 쿼리를 담지 않는다. */
public class StoreUnavailableException extends RuntimeException {

  public StoreUnavailableException(String status) {
    super("테넌트 저장소 정보를 가져오지 못했습니다(" + status + ").");
  }
}
