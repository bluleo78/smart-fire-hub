/**
 * 관리자가 **읽고 조치할 수 있는** 실패. 이 오류의 메시지만 채팅 SSE 로 그대로 전달된다.
 *
 * <p><b>왜 별도 타입이 필요한가.</b> `routes/chat.ts` 의 catch 는 모든 예외를 고정 문구
 * ("Agent 처리 중 오류가 발생했습니다")로 바꾼다. 내부 오류 원문이 사용자 화면에 새는 것을 막기
 * 위한 의도된 설계다(이슈 #350/#313 원칙). 그런데 그 때문에, 설정을 고쳐야만 풀리는 실패까지
 * 원인 없는 한 줄로 뭉개진다 — 관리자는 무엇을 고쳐야 할지 알 수 없고, 운영자가 DB 질의로
 * 대상을 찾아다니는 수밖에 없다.
 *
 * <p>이것은 `AiAgentProxyService`(자바)가 opencode 모델 형식 검사를 <b>호출 전에</b> 하는 바로
 * 그 이유이기도 하다 — SSE 헤더가 나간 뒤에 던지면 프론트엔드가 구체적 원인을 못 본다. 자바
 * 쪽에 대응 검사가 없는 가드(#697 ambient auth, #698 baseUrl 재검증)는 ai-agent 안에서만 알 수
 * 있으므로, 그 두 경우에 한해 메시지를 통과시킨다.
 *
 * <p><b>메시지 작성 규칙</b>: 이 타입으로 던지는 메시지에는 파일 경로·호스트·IP·스택 등 내부
 * 정보를 담지 않는다. 그런 상세는 `console.error` 로만 남긴다 — 채팅 사용자가 반드시 플랫폼
 * 관리자인 것은 아니다.
 */
export class AdminActionableError extends Error {
  constructor(
    message: string,
    /** 로그에만 남길 내부 상세(경로·원인 등). 사용자 메시지에는 절대 포함하지 않는다. */
    readonly detail?: string,
  ) {
    super(message);
    this.name = 'AdminActionableError';
  }
}
