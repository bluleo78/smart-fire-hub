package com.smartfirehub.analytics.exception;

/**
 * 이 대시보드에 없는 위젯을 단건 수정·삭제하려 할 때 던진다 — 전역 핸들러가 404 로 바꾼다.
 *
 * <p>WD-367: 예전에는 400(IllegalArgumentException)이었다. 다른 탭·사용자가 먼저 지운 위젯은 요청이 틀린 것이 아니라 대상이 없어진 것이므로,
 * 대시보드·차트({@link DashboardNotFoundException}·{@link ChartNotFoundException})와 같이 404 로 답한다. 문구는
 * WD-196·WD-240 의 한국어·번호 없는 형식 그대로이고, 기본 생성자로만 만든다.
 */
public class WidgetNotFoundException extends RuntimeException {

  /** 위젯이 없을 때 — 다른 not-found 문구(「…을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.」)와 형식을 맞춘다. */
  public static final String MESSAGE = "위젯을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.";

  /** 위젯을 찾지 못했다는 기본 문구로 만든다. */
  public WidgetNotFoundException() {
    super(MESSAGE);
  }
}
