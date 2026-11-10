package com.smartfirehub.file.exception;

/**
 * 저장 경로가 저장 폴더를 벗어났을 때 던지는 예외 (WD-610).
 *
 * <p>저장 파일명은 uuid + 안전한 확장자뿐이라 정상이라면 일어날 수 없다 — 즉 코드 결함 신호다. 전용 핸들러를 두지 않아 GlobalExceptionHandler 의
 * 기본 처리(500 + 일반 안내 문구)로 간다. IllegalStateException 은 409 로 매핑되므로 쓰지 않는다. 메시지에는 저장 파일명을 싣지 않는다(상세는
 * 서버 로그에만 남긴다).
 */
public class StoragePathEscapeException extends RuntimeException {
  public StoragePathEscapeException() {
    super("저장 경로가 저장 폴더를 벗어났습니다.");
  }
}
