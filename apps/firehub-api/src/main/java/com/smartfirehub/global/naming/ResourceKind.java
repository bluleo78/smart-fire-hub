package com.smartfirehub.global.naming;

/**
 * 이름 중복을 막는 자산 종류(WD-824). 라벨은 사용자에게 보이는 409 문구에 그대로 들어간다 — 쿼리·차트·대시보드·스마트 작업·리포트 양식이 같은 문장 꼴을 쓰게 한다.
 */
public enum ResourceKind {
  SAVED_QUERY("쿼리"),
  CHART("차트"),
  DASHBOARD("대시보드"),
  PROACTIVE_JOB("스마트 작업"),
  REPORT_TEMPLATE("리포트 양식");

  private final String label;

  ResourceKind(String label) {
    this.label = label;
  }

  /** 문구에 쓰는 한국어 이름. */
  public String label() {
    return label;
  }
}
