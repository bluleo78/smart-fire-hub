package com.smartfirehub.dataimport.service;

/**
 * 임포트 파일 헤더(첫 행 열 이름)와 미리보기 샘플 값의 크기 상한을 한곳에 모은 상수(WD-612).
 *
 * <p>미리보기 응답은 헤더를 {@code fileHeaders}·{@code suggestedMappings[].fileColumn}·샘플 행 Map 의 키로 여러 번
 * 되풀이한다. 상한이 없을 때 줄바꿈 없는 60MB CSV 한 줄이 120MB 응답이 되고, 100만 열(9.9MB) 파일이 105MB 응답이 됐다(업로드 상한 512MB 면
 * 호출 한 번에 약 1GB). 그래서 헤더를 읽는 공통 지점({@link FileHeaderValidator}, {@link FileParserService})이 이 값으로
 * 미리보기·사전 검증·실제 임포트를 같은 규칙으로 거절한다. 자르지 않고 거절하는 이유는, 자르면 미리보기에 보인 열 이름과 임포트 매핑 이름이 달라지기 때문이다.
 */
final class ImportFileLimits {

  private ImportFileLimits() {}

  /**
   * 헤더 행의 최대 열 수. PostgreSQL 테이블 한 개가 가질 수 있는 최대 컬럼 수(MaxHeapAttributeNumber = 1600)와 같다 — 데이터셋은
   * {@code data} 스키마의 실제 테이블이라 이보다 많은 열은 어차피 한 데이터셋에 모두 담을 수 없다. 시스템 컬럼 몫까지 빼서 더 낮추지 않는 것은, 일부 열만
   * 매핑해 가져오는 파일(열이 데이터셋보다 많은 원본)도 받기 위해서다.
   */
  static final int MAX_HEADER_COLUMNS = 1600;

  /**
   * 헤더 셀(열 이름) 하나의 최대 글자 수(Java char 기준). 데이터셋 컬럼 표시 이름은 {@code VARCHAR(100)}, 물리 컬럼명은 63자지만 파일 열
   * 이름은 그대로 저장되지 않고 매핑에만 쓰이므로, 설문 문항처럼 긴 한글 설명 헤더도 받도록 넉넉하게 500자로 둔다. 최악의 경우(1,600열 × 500자)도 미리보기
   * 응답이 수 MB 안에 머문다.
   */
  static final int MAX_HEADER_NAME_LENGTH = 500;

  /**
   * CSV 헤더 줄을 읽는 동안 리더에서 꺼낼 수 있는 최대 글자 수. opencsv 는 한 줄을 통째로 문자열로 만든 뒤 셀로 나누므로, 줄바꿈 없는 60MB 파일이면 셀
   * 검사 전에 이미 60MB 문자열이 생긴다. 이를 막으려고 읽는 도중에 끊는다.
   *
   * <p>열마다 따옴표로 감싸고 모든 글자를 {@code ""} 로 이스케이프해도(2배) 구분자·따옴표·줄바꿈 몫 3자를 더하면 열 하나는 {@code 2 ×
   * MAX_HEADER_NAME_LENGTH + 3} 자를 넘지 않는다. 그래서 이 값을 넘는 헤더 줄은 반드시 열 수나 열 이름 길이 상한 중 하나를 어긴다 — 정상 파일이
   * 이 검사에만 걸리는 일은 없다(약 160만 자).
   */
  static final long MAX_HEADER_LINE_CHARS =
      (long) MAX_HEADER_COLUMNS * (2L * MAX_HEADER_NAME_LENGTH + 3);

  /**
   * CSV 헤더 행 하나가 따옴표 안 줄바꿈으로 이어 붙을 수 있는 최대 물리 줄 수. 첫 줄에 더해 열 이름마다 줄바꿈(엑셀 Alt+Enter)이 하나씩 있어도 받도록 「열
   * 수 상한 + 1」로 둔다. opencsv 는 따옴표 안에서 줄이 이어질 때마다 그때까지 모은 값을 다시 만들어 비용이 「줄 수 × 글자 수」로 커지므로, 닫히지 않은
   * 따옴표 뒤 짧은 줄이 아주 많은 파일이 글자 수 상한({@link #MAX_HEADER_LINE_CHARS}) 안에서 스레드를 오래 붙잡지 않게 줄 수도 따로 막는다.
   */
  static final int MAX_HEADER_PHYSICAL_LINES = MAX_HEADER_COLUMNS + 1;

  /**
   * 미리보기 샘플 값({@code sampleRows}의 셀 값) 하나의 최대 표시 글자 수. 샘플은 화면 표에 보여 주기만 하고 매핑·검증·적재에는 쓰이지 않으므로, 이보다
   * 긴 값은 잘라 말줄임표를 붙인다(실제 임포트는 원본 값을 그대로 쓴다). 긴 GeoJSON·본문 텍스트 열이 있어도 응답이 파일 크기만큼 커지지 않게 한다.
   */
  static final int MAX_PREVIEW_VALUE_LENGTH = 1000;
}
