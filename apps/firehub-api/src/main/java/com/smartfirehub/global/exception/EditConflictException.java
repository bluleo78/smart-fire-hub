package com.smartfirehub.global.exception;

import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;

/**
 * 낙관적 잠금 충돌 — 편집을 시작할 때 읽은 버전(수정 시각)과 지금 저장된 버전이 다르면 409 + {@link #CODE}(WD-353).
 *
 * <p>왜 필요한가: 수정 요청이 "전체 교체" 라 두 사람(또는 두 탭)이 같은 리소스를 고쳐 저장하면 나중 저장이 먼저 저장된 변경을 경고 없이 되돌렸다. 클라이언트가 편집
 * 기준 버전({@code expectedUpdatedAt})을 함께 보내면 서버가 비교해 다를 때 이 예외로 거부한다. 같은 409 인 이름 중복과는 코드로 구분된다 — 웹은
 * 코드를 보고 토스트 대신 충돌 안내 창(다시 불러오기 / 내 변경으로 덮어쓰기)을 띄운다.
 *
 * <p>공통 장치다 — 다른 리소스도 수정 요청에 기준 버전을 받고 {@link #check} 를 부르면 같은 응답·같은 화면 처리를 얻는다.
 */
public class EditConflictException extends CodedApiException {
  /** 편집 충돌을 뜻하는 기계용 오류 코드 — web lib/api-error.ts 의 EDIT_CONFLICT_CODE 와 같은 값. */
  public static final String CODE = "EDIT_CONFLICT";

  public EditConflictException(String message) {
    super(HttpStatus.CONFLICT, CODE, message);
  }

  /**
   * 기준 버전과 현재 버전을 비교해 다르면 던진다.
   *
   * @param expected 클라이언트가 편집을 시작할 때 읽은 수정 시각. null 이면 비교하지 않는다 — 기준을 보내지 않는 호출부(AI 도구 등)와 "내 변경으로
   *     덮어쓰기" 재저장은 예전처럼 그대로 저장된다.
   * @param current 지금 저장된 수정 시각(호출부가 행 잠금을 잡고 읽은 값이어야 비교와 저장 사이에 끼어들 틈이 없다)
   * @param message 리소스에 맞춘 한국어 안내 문구
   */
  public static void check(LocalDateTime expected, LocalDateTime current, String message) {
    if (expected != null && !expected.equals(current)) {
      throw new EditConflictException(message);
    }
  }
}
