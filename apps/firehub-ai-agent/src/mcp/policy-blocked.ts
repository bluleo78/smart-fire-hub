/**
 * api 의 AI·공유 정책 차단(403 POLICY_BLOCKED)을 도구 결과로 옮기는 계약(S3 §4.3).
 * ai-agent 는 판정하지 않는다 — api 가 준 구조를 그대로 고정 JSON 표식으로 실어, 웹이 "차단됨" 상태로 그리게 한다.
 * 표식이 텍스트 안에 있으므로 sdk·cli(stdio)·opencode 어느 런타임에서도 isError 전달 여부와 무관하게 살아남는다.
 * api 대응 상수: PolicyBlockedException.CODE.
 */
export const POLICY_BLOCKED_CODE = 'POLICY_BLOCKED';

/** 차단 응답의 구조(api ErrorResponse.errors + message). */
export interface PolicyBlockedInfo {
  /** 'AI' | 'SHARE' — 어떤 정책이 막았는가. */
  action: string;
  /** 사용자가 이미 볼 수 있는 데이터셋의 등급 이름. */
  levelName: string;
  /** 'ai_policy' | 'share_policy'. */
  policyKey: string;
  /** api 가 만든 한국어 사용자 문구. */
  message: string;
}

/** api-client 인터셉터가 오류에 붙인 policyBlocked 정보를 꺼낸다. 없으면 null. */
export function policyBlockedOf(error: unknown): PolicyBlockedInfo | null {
  if (typeof error !== 'object' || error === null) return null;
  const info = (error as { policyBlocked?: PolicyBlockedInfo }).policyBlocked;
  return info && typeof info.levelName === 'string' ? info : null;
}

/** 웹 ToolCallDisplay 가 파싱하는 고정 표식(키 이름 변경 금지 — firehub-web lib/policy-blocked.ts 와 짝). */
export function policyBlockedResultText(info: PolicyBlockedInfo): string {
  return JSON.stringify({
    policyBlocked: true,
    code: POLICY_BLOCKED_CODE,
    action: info.action,
    levelName: info.levelName,
    policyKey: info.policyKey,
    message: info.message,
  });
}
