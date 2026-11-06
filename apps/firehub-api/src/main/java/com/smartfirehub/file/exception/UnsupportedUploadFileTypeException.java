package com.smartfirehub.file.exception;

/** 업로드할 수 없는 파일 형식 — 전역 처리에서 400 과 이 문구로 답한다. 화면에 그대로 보이므로 한국어로 쓴다(WD-521). */
public class UnsupportedUploadFileTypeException extends RuntimeException {
  public UnsupportedUploadFileTypeException(String mimeType) {
    super("지원하지 않는 파일 형식입니다: " + mimeType);
  }
}
