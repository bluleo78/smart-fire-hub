package com.smartfirehub.proactive.exception;

/** 존재하지 않는 Proactive Job 조회 시 404를 반환하기 위한 예외 (#41) */
public class ProactiveJobNotFoundException extends RuntimeException {
  public ProactiveJobNotFoundException(String message) {
    super(message);
  }
}
