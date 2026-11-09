/**
 * AI 채팅 요청 길이 상한 (WD-631)
 *
 * firehub-api `ChatRequest` 의 @Size 상한(문자 수 — UTF-16 코드 단위, JS `string.length` 와 같은 단위)과 같은 값이다.
 * 웹이 같은 상한을 알아야 하는 이유:
 * - 메시지: 넘기면 서버가 400 으로 거절하므로, 보내기 전에 막고 입력창 아래에 줄이라는 안내를 보인다.
 * - 화면/이동 컨텍스트: 사용자가 쓴 글이 아니라 부가 정보라, 넘친다고 메시지 전송까지 막히면 안 된다 → 잘라서 보낸다.
 * 서버 상한을 바꾸면 이 값도 함께 바꾼다.
 */

/** 채팅 메시지 최대 길이(문자 수) — api ChatRequest.MAX_MESSAGE_LENGTH */
export const MAX_CHAT_MESSAGE_LENGTH = 50_000;

/** 화면 이동 컨텍스트 최대 길이(문자 수) — api ChatRequest.MAX_NAVIGATION_CONTEXT_LENGTH */
export const MAX_NAVIGATION_CONTEXT_LENGTH = 10_000;

/** 화면 컨텍스트 최대 길이(문자 수) — api ChatRequest.MAX_SCREEN_CONTEXT_LENGTH */
export const MAX_SCREEN_CONTEXT_LENGTH = 100_000;

/** 컨텍스트를 잘랐을 때 끝에 붙이는 표시 — AI 가 정보가 일부만 왔음을 알 수 있게 한다 */
export const CONTEXT_TRUNCATED_MARKER = '\n…(길이 제한으로 이후 내용이 잘렸습니다)';

/**
 * 메시지가 상한을 넘었을 때 입력창 아래에 보일 안내. 서버 검증 문구(「메시지는 50,000자 이하로 입력하세요.」)와 같은 표현에
 * 현재 길이를 덧붙여 얼마나 줄여야 하는지 알게 한다.
 */
export function messageTooLongHint(length: number): string {
  return `메시지는 ${MAX_CHAT_MESSAGE_LENGTH.toLocaleString('ko-KR')}자 이하로 입력하세요. (현재 ${length.toLocaleString('ko-KR')}자)`;
}

/**
 * 컨텍스트 문자열을 상한 안으로 자른다. 상한 이하이거나 비어 있으면 그대로 돌려준다.
 * 자를 때는 잘림 표시까지 포함한 전체 길이가 상한을 넘지 않게 하고(어떤 max 에서도 결과 길이 ≤ max),
 * 상한이 잘림 표시보다 짧으면 표시 없이 앞부분만 남긴다. 서로게이트 쌍(이모지 등) 한가운데서 끊어
 * 깨진 문자가 남지 않게 한 글자를 더 뺀다.
 */
export function truncateChatContext(text: string | undefined, max: number): string | undefined {
  if (!text || text.length <= max) return text;
  const limit = Math.max(0, max);
  if (limit <= CONTEXT_TRUNCATED_MARKER.length) return sliceWithoutSplitSurrogate(text, limit);
  return sliceWithoutSplitSurrogate(text, limit - CONTEXT_TRUNCATED_MARKER.length) + CONTEXT_TRUNCATED_MARKER;
}

/** 앞에서 end 코드 단위까지 자르되, 끝이 서로게이트 쌍의 앞 절반이면 그 글자를 뺀다 */
function sliceWithoutSplitSurrogate(text: string, end: number): string {
  let cut = end;
  const last = text.charCodeAt(cut - 1);
  if (cut > 0 && last >= 0xd800 && last <= 0xdbff) cut -= 1;
  return text.slice(0, cut);
}
