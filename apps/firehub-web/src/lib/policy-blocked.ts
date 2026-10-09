/**
 * AI 도구 결과의 정책 차단 표식(S3 §4.3·§5-4).
 * firehub-ai-agent `mcp/policy-blocked.ts` 의 policyBlockedResultText 와 짝 — 키 이름을 바꾸지 않는다.
 * 차단은 오류가 아니라 "정책상 보낼 수 없음" 상태라 ToolCallDisplay 가 실패와 구분해 그린다.
 */
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

/**
 * 도구 결과 문자열이 차단 표식이면 그 정보, 아니면 null.
 * isError 여부는 보지 않는다 — 런타임(cli·opencode)에 따라 isError 가 전달되지 않아도 표식 텍스트는 살아남기 때문이다.
 */
export function parsePolicyBlocked(result: string | undefined): PolicyBlockedInfo | null {
  if (!result || !result.startsWith('{')) return null;
  // 표식은 JSON.stringify 한 줄이다(값 안의 개행은 \n 으로 이스케이프된다) — 뒤에 다른 블록이 이어 붙어도 첫 줄만 읽는다.
  const firstLine = result.split('\n', 1)[0];
  try {
    const parsed = JSON.parse(firstLine) as Partial<PolicyBlockedInfo> & { policyBlocked?: unknown };
    if (parsed.policyBlocked !== true || typeof parsed.levelName !== 'string') return null;
    return {
      action: String(parsed.action ?? ''),
      levelName: parsed.levelName,
      policyKey: String(parsed.policyKey ?? ''),
      message: String(parsed.message ?? ''),
    };
  } catch {
    return null;
  }
}

/** 보조 줄 문구. 등급 이름은 사용자가 이미 볼 수 있는 데이터셋의 것이다(api 가 VIEW 통과 후에만 싣는다). */
export function policyBlockedLabel(info: PolicyBlockedInfo): string {
  return info.action === 'SHARE'
    ? `'${info.levelName}' 등급 — 공유·외부 발송 불가`
    : `'${info.levelName}' 등급 — 현재 AI 공급자로 보낼 수 없음`;
}
