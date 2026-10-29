package com.smartfirehub.dataimport.exception;

/**
 * 업로드한 파일을 파서가 읽지 못했을 때(엑셀이 아닌 파일·손상된 OOXML·암호 걸린 문서·형식이 깨진 CSV 등) 던지는 예외 (WD-204).
 *
 * <p>메시지는 화면 토스트·임포트 잡 실패 사유로 그대로 보이므로 항상 한국어 안내 문장이다. POI/opencsv 원문은 사용자에게 보여 줄 수 없는 내부 정보라 메시지에
 * 싣지 않고 cause 로만 보관한다(로그 전용). {@link UnsupportedFileTypeException} 을 상속해 기존 400 핸들러가 그대로 처리한다.
 */
public class FileUnreadableException extends UnsupportedFileTypeException {

  /** 엑셀 컨테이너를 열거나 읽지 못한 경우(엑셀이 아님·손상·구형 포맷 등)의 안내 문구. */
  public static final String EXCEL_UNREADABLE = "엑셀 파일을 읽을 수 없습니다. 파일이 손상되었거나 엑셀 형식이 아닙니다.";

  /** 암호가 걸린 엑셀 문서의 안내 문구 — 사용자가 직접 해결할 수 있는 원인이라 따로 알려 준다. */
  public static final String EXCEL_ENCRYPTED = "암호가 걸린 엑셀 파일은 읽을 수 없습니다. 암호를 해제한 뒤 다시 올려 주세요.";

  /** CSV 구문이 깨진 경우(닫히지 않은 따옴표 등)의 안내 문구. */
  public static final String CSV_UNREADABLE = "CSV 파일을 읽을 수 없습니다. 따옴표·구분자 등 파일 형식이 올바른지 확인해 주세요.";

  /**
   * @param userMessage 화면에 그대로 보일 한국어 안내
   * @param cause 로그용 원인 예외. 파일 형식 문제가 아닌 사용자 선택 오류(시트 없음 등, WD-253)는 null 일 수 있다
   */
  public FileUnreadableException(String userMessage, Throwable cause) {
    super(userMessage);
    initCause(cause);
  }
}
