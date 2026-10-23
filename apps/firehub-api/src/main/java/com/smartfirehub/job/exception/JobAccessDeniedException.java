package com.smartfirehub.job.exception;

import org.springframework.security.access.AccessDeniedException;

/**
 * 같은 테넌트의 다른 사용자가 시작한 비동기 작업을 조회하려 할 때 던진다 — 403 으로 응답한다(WD-203).
 *
 * <p>{@link AccessDeniedException} 을 상속해 기존 403 처리·테스트와 호환된다. 별도 타입으로 둔 이유는 진행 SSE 엔드포인트({@code
 * produces=text/event-stream})에서도 JSON 메시지 본문이 나가도록 전용 핸들러가 Content-Type 을 미리 정하기 위해서다 — 전역 403
 * 핸들러를 바꾸면 AI 채팅 SSE·파일 다운로드 등 다른 엔드포인트의 403 응답까지 달라진다.
 */
public class JobAccessDeniedException extends AccessDeniedException {

  public JobAccessDeniedException() {
    super("이 작업을 시작한 사용자만 진행 상황을 볼 수 있습니다.");
  }
}
