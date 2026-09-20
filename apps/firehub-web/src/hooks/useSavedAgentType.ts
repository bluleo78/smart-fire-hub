import { useState } from 'react';

import type { AgentType } from '../lib/ai-credential';
import { credentialIsDirty } from '../lib/ai-credential-screen';
import type { UseAiCredentialFormResult } from './useAiCredentialForm';

/**
 * "서버와 마지막으로 동기화된" 유형을 기억한다 — 유형 전환 경고·저장 확인 다이얼로그(설계서
 * §193 "유형 전환")가 비교할 기준.
 *
 * <b>훅(`useAiCredentialForm`)이 이 값을 직접 내주지 않는다</b>(내부 `original.agentType` 은
 * 비공개다). 그렇다고 재구현하지 않고, "지금 폼이 완전히 동기화된 상태(변경 없음 + 라디오가
 * 실제 소유 평면과 일치)일 때만 스냅샷을 갱신한다"는 규칙으로 <b>바깥에서</b> 같은 값을
 * 재구성한다 — 최초 조회 직후에도, 저장 후 재조회 직후(`save()` 가 끝에서 `fetchAndApply` 를
 * 불러 폼을 서버 값으로 다시 채운 바로 그 순간)에도 이 조건이 참이 되므로 두 시점 모두에서
 * 최신 "저장된 유형"을 정확히 따라간다. 사용자가 유형을 바꾸는 순간(dirty)에는 갱신을 멈추고
 * 마지막 값을 그대로 들고 있는다 — 그것이 비교 대상이어야 하기 때문이다.
 *
 * <b>`useRef` 가 아니라 렌더 중 `setState` 로 구현한다.</b> 렌더 바디에서 ref 를 읽고 쓰면
 * (`ref.current = ...`) `react-hooks/refs` 가 막는다 — ref 는 렌더와 무관한 값이라 렌더 중
 * 접근을 허용하지 않는다. 그렇다고 `useEffect` 안에서 `setState` 하면 한 프레임 늦게 반영되고
 * (`react-hooks/set-state-in-effect` 가 경고하는 cascading render) 화면이 잠깐 낡은 값을 보여줄
 * 여지가 생긴다. React 공식 문서가 권하는 "prop 변경에 맞춰 state 조정" 패턴 — 렌더 바디에서
 * 조건부로 `setState` 를 호출해 <b>같은 렌더 사이클 안에서</b> 바로잡는다 — 을 그대로 쓴다.
 * `saved !== cred.agentType` 가드가 없으면 매 렌더마다 `setState` 를 불러 무한 루프가 된다.
 */
export function useSavedAgentType(cred: UseAiCredentialFormResult): AgentType {
  const synced = !credentialIsDirty(cred);
  const [saved, setSaved] = useState<AgentType>(cred.agentType);
  if (synced && saved !== cred.agentType) {
    setSaved(cred.agentType);
  }
  return saved;
}
