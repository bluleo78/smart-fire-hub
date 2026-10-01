package com.smartfirehub.pipeline.service.executor;

/**
 * executor 응답 본문이 {@link ExecutorClient} 의 버퍼 한도({@code app.executor.max-response-bytes})를 넘었다
 * (#761).
 *
 * <p>WebClient 는 한도 초과를 "200 OK … but response failed with cause: DataBufferLimitException" 같은 일반
 * 예외로 감싸 던지므로, 호출부가 이를 "연결 실패"로 오인하지 않도록 별도 타입으로 구분한다. 메시지는 사용자에게 그대로 보여 줄 수 있는 한국어 안내(한도 MB 포함)다.
 */
public class ExecutorResponseTooLargeException extends RuntimeException {

  private final int limitBytes;

  public ExecutorResponseTooLargeException(int limitBytes, Throwable cause) {
    super(
        "결과가 너무 큽니다(응답 한도 " + formatSize(limitBytes) + " 초과) — LIMIT·조회 행 수를 줄이거나 필요한 컬럼만 선택하세요.",
        cause);
    this.limitBytes = limitBytes;
  }

  /** 한도를 사람이 읽는 단위로 표기한다 — 1MB 미만(테스트·작은 설정값)은 KB 로 써서 "0MB" 가 되지 않게 한다. */
  private static String formatSize(int bytes) {
    return bytes >= 1024 * 1024 ? (bytes / (1024 * 1024)) + "MB" : (bytes / 1024) + "KB";
  }

  /** 초과된 한도(바이트). */
  public int getLimitBytes() {
    return limitBytes;
  }
}
