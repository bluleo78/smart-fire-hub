package com.smartfirehub.platform.dto;

/**
 * 재발급한 계정 생성 스크립트(WD-73, R2). 비밀번호가 들어 있어 이 응답에서만 노출한다.
 *
 * <p>{@code toString()} 은 스크립트를 가려 로그로 새지 않게 한다.
 */
public record DataStoreScriptResponse(String script) {

  @Override
  public String toString() {
    return "DataStoreScriptResponse[script=****]";
  }
}
