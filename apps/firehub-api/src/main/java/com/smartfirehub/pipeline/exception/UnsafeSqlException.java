package com.smartfirehub.pipeline.exception;

/**
 * 파이프라인 SQL 스텝이 안전 정책(단일 스테이트먼트 + SELECT/INSERT/UPDATE/DELETE + data 스키마만 참조)을 위반했을 때 던진다. 저장 시·실행
 * 시 양쪽에서 사용한다.
 *
 * <p>HTTP 매핑: 400 Bad Request — {@code GlobalExceptionHandler} 참고.
 */
public class UnsafeSqlException extends RuntimeException {

  public UnsafeSqlException(String message) {
    super(message);
  }

  public UnsafeSqlException(String message, Throwable cause) {
    super(message, cause);
  }
}
