/**
 * opencode 모델 문자열(`providerId/modelId`) 파싱 — <b>이 저장 규약을 해석하는 유일한 자리</b>.
 *
 * 같은 규칙이 이 앱 안에서 두 번 구현돼 있었다: `agent/agent-opencode.ts` 의
 * `splitOpencodeModel`(슬래시가 없으면 throw)과 `providers/openai-compat-completion-provider.ts`
 * 의 인라인 `indexOf('/')` + `slice()`(슬래시가 없으면 그대로 통과). 파싱 규칙만 여기로 모으고
 * <b>두 호출부의 동작(throw 대 통과)은 그대로 둔다</b> — 동작을 통일하는 것이 목적이 아니라,
 * "맨 앞 세그먼트가 providerId 이고 providerId 자체엔 '/' 가 없다"는 저장 계약이 두 곳에서 따로
 * 늙지 않게 하는 것이 목적이다.
 */

/** 분해 결과. opencode 가 기대하는 필드명(`providerID`/`modelID`)을 그대로 쓴다. */
export interface OpencodeModelParts {
  providerID: string;
  modelID: string;
}

/**
 * `model` 을 `providerId/modelId` 로 분해한다. <b>던지지 않는다</b> — 슬래시가 없으면(= 아직
 * opencode 형식이 아닌 모델 문자열이면) `null` 을 돌려주고, 그 경우를 어떻게 다룰지는 호출부가
 * 정한다(설정 오류로 막을 수도, 접두어가 없는 것으로 보고 그대로 쓸 수도 있다).
 */
export function splitOpencodeModelOrNull(model: string): OpencodeModelParts | null {
  const idx = model.indexOf('/');
  if (idx < 0) {
    return null;
  }
  return { providerID: model.slice(0, idx), modelID: model.slice(idx + 1) };
}
