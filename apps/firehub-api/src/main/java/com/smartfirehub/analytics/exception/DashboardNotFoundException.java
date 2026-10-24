package com.smartfirehub.analytics.exception;

/**
 * 대시보드가 없거나 요청자가 볼 수(또는 고칠 수) 없을 때 던진다 — 전역 핸들러가 404 로 바꾼다.
 *
 * <p>WD-196: 메시지는 응답 message 로 그대로 화면 토스트에 실리므로 한국어로 쓴다. 남의 비공개 자원과 없는 자원을 같은 문구로 답해 존재를 드러내지 않는
 * 정책은 그대로다.
 *
 * <p>WD-240: 내부 id 를 문구에서 뺐다(예전 「대시보드를 찾을 수 없습니다: 999999」). 문구가 흩어지지 않도록 기본 생성자·팩토리로만 만든다.
 */
public class DashboardNotFoundException extends RuntimeException {

  /** 대시보드가 없거나 볼 수 없을 때 — 웹의 없는 URL 진입 화면 문구(「대시보드를 찾을 수 없습니다.」)와 앞부분을 맞춘다. */
  public static final String MESSAGE = "대시보드를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.";

  /** 방금 저장한 대시보드를 다시 읽지 못했을 때(저장 직후 재조회 실패 — 동시 삭제 등). */
  public static final String RELOAD_FAILED_MESSAGE = "저장한 대시보드를 다시 읽지 못했습니다";

  /** 대시보드를 찾지 못했다는 기본 문구로 만든다. */
  public DashboardNotFoundException() {
    this(MESSAGE);
  }

  private DashboardNotFoundException(String message) {
    super(message);
  }

  /** 저장 직후 재조회에 실패했을 때의 예외. */
  public static DashboardNotFoundException reloadFailed() {
    return new DashboardNotFoundException(RELOAD_FAILED_MESSAGE);
  }
}
