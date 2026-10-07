package com.smartfirehub.global.util;

/**
 * 애드혹 SQL 을 실행 직전 형태로 <b>한 번만</b> 정규화한 값(보안 등급 S2, 스펙 §4.1 "판정 = 실행").
 *
 * <p><b>왜 별도 타입인가.</b> 애드혹 실행 서비스는 사용자 원문을 {@link SqlValidationUtils#stripAndValidate}(주석 제거 — 리터럴을
 * 인지하지 못하는 정규식) 로 바꾼 문자열을 실행한다. SQL 판정(DatasetAccessGuard.checkSql)이 원문을 보고 실행은 정규화본으로 하면 두 문자열이 다른
 * 테이블을 참조할 수 있다(실측 우회: 리터럴 안의 {@code /*}·{@code --} 가 주석으로 오인되어 숨은 테이블 참조가 드러난다). 그래서 정규화는
 * 관문(GuardedSqlExecutor)에서 이 타입으로 단 한 번 하고, 판정과 실행이 {@link #text()} 의 <b>같은 String 인스턴스</b>를 쓴다.
 * 생성자가 비공개라 정규화를 거치지 않은 인스턴스는 만들 수 없다 — 실행 서비스의 {@code NormalizedSql} 오버로드는 다시 정규화하지 않는다.
 */
public final class NormalizedSql {

  private final String text;

  private NormalizedSql(String text) {
    this.text = text;
  }

  /**
   * 주석 제거·단일 문장·허용 키워드 검사({@link SqlValidationUtils#stripAndValidate}) 후 끝 세미콜론을 뗀다 — 실행 서비스가 예전에
   * 내부에서 하던 정규화와 바이트 단위로 같다.
   *
   * @throws com.smartfirehub.dataset.exception.SqlQueryException 다중 문장·허용되지 않은 첫 키워드
   */
  public static NormalizedSql of(String raw) {
    return new NormalizedSql(
        SqlValidationUtils.removeTrailingSemicolon(SqlValidationUtils.stripAndValidate(raw)));
  }

  /** 판정과 실행에 함께 쓰는 정규화 문자열. */
  public String text() {
    return text;
  }

  @Override
  public String toString() {
    return text;
  }
}
