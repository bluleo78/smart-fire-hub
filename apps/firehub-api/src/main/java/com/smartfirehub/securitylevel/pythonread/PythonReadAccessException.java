package com.smartfirehub.securitylevel.pythonread;

/**
 * PYTHON 실행 직전 읽기 슬롯을 준비하지 못했다(fail-closed). 메시지는 사용자에게 그대로 보여도 된다 — 테이블·등급 이름을 싣지 않는다(이름 자체가 기밀일 수
 * 있어서, 상세는 서버 로그에만).
 */
public class PythonReadAccessException extends RuntimeException {

  public PythonReadAccessException(String message) {
    super(message);
  }

  public PythonReadAccessException(String message, Throwable cause) {
    super(message, cause);
  }
}
