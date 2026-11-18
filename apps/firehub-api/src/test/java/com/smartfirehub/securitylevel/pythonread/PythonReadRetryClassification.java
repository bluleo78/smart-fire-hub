package com.smartfirehub.securitylevel.pythonread;

/**
 * 다른 패키지의 시험이 Python 읽기 권한 재시도 분류({@code PythonReadGrantBackend.isTransient})를 실제 번역된 예외에 대어 볼 수 있게
 * 여는 시험 전용 창구(WD-791).
 *
 * <p>분류기는 패키지 전용 인터페이스의 정적 메서드라 저장소 계약 시험({@code dataset.repository})에서 직접 부를 수 없다. 운영 코드의 공개 범위를
 * 넓히지 않으려고 시험 소스에만 둔다.
 */
public final class PythonReadRetryClassification {

  private PythonReadRetryClassification() {}

  /** {@code PythonReadGrantBackend.isTransient} 그대로 — 일시 실패(다시 해 볼 만함)면 true. */
  public static boolean isTransient(Throwable e) {
    return PythonReadGrantBackend.isTransient(e);
  }
}
