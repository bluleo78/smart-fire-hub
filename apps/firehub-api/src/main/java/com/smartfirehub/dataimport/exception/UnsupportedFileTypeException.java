package com.smartfirehub.dataimport.exception;

/**
 * 가져올 수 없는 파일(이름 없음·지원하지 않는 확장자)일 때 던진다 — 전역 핸들러가 400 으로 바꾸고 message 를 그대로 내보낸다.
 *
 * <p>WD-250: message 는 화면 토스트와 AI 챗에 그대로 실리므로 한국어로 쓴다. 가져오기 진입점(미리보기·검증·가져오기)이 같은 문구를 쓰도록 상수로 둔다.
 */
public class UnsupportedFileTypeException extends RuntimeException {

  /** 업로드 파일에 이름이 없을 때. */
  public static final String FILE_NAME_REQUIRED = "파일 이름이 없습니다. 파일을 다시 선택해 주세요.";

  /** 확장자가 CSV·XLSX·XLS·XLSB 가 아닐 때. */
  public static final String UNSUPPORTED_EXTENSION =
      "지원하지 않는 파일 형식입니다. CSV, XLSX, XLS, XLSB 파일만 가져올 수 있습니다.";

  public UnsupportedFileTypeException(String message) {
    super(message);
  }
}
