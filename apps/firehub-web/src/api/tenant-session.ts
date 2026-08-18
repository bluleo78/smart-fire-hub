/**
 * 토큰 응답에 실려 오는 테넌트 상태를 AuthContext 로 흘려보내는 단방향 통지 채널.
 *
 * <p><b>왜 별도 모듈인가.</b> 통지가 필요한 지점은 `api/client.ts` 의 401 재시도 인터셉터다 —
 * 백그라운드에서 조용히 refresh 가 일어나고, 그때 백엔드가 테넌트/멤버십 정지를 감지해 토큰을
 * **테넌트 미선택으로 강등**할 수 있다(`AuthService.refresh`). 인터셉터가 React 컨텍스트를 직접
 * import 하면 `client.ts → AuthContext → api/auth.ts → client.ts` 순환이 생기므로, 양쪽이
 * 의존해도 무해한 이 모듈을 가운데 둔다.
 *
 * <p><b>강등을 흘리지 않으면 무슨 일이 생기나.</b> 인터셉터는 새 accessToken 만 꺼내 쓰고 원래
 * 요청을 재시도한다. 강등된 토큰에는 `tenant` 클레임이 없으니 GUC 가 비고, RLS 가 0행을 반환해
 * 권한이 0개가 되어 **모든 API 가 403** 이 된다. UI 는 이유를 모른 채 빈 화면과 에러만 반복한다.
 * 이 채널이 있으면 그 순간 워크스페이스 선택 화면으로 되돌릴 수 있다.
 */
import type { MembershipResponse } from '../types/tenant';

/** 토큰 한 개가 말해 주는 테넌트 상태. */
export interface TenantSessionState {
  /** null = 테넌트 미선택(강등 포함). */
  activeTenantId: number | null;
  memberships: MembershipResponse[];
}

type Listener = (state: TenantSessionState) => void;

/**
 * 리스너는 **하나만** 둔다. 구독자는 AuthProvider 단 하나이고, 여러 개를 허용하면 StrictMode 의
 * 이중 마운트에서 해제되지 않은 구독이 남아 이전 트리의 setState 를 호출하게 된다.
 */
let listener: Listener | null = null;

/** 구독한다. 반환된 함수로 해제한다(해제하지 않으면 다음 구독이 이전 것을 덮는다). */
export function subscribeTenantSession(fn: Listener): () => void {
  listener = fn;
  return () => {
    if (listener === fn) listener = null;
  };
}

/**
 * 토큰 응답에서 읽은 테넌트 상태를 통지한다.
 *
 * <p>구독자가 없을 수도 있다(부팅 직전, 언마운트 직후) — 그때는 그냥 버린다. 이 채널은 화면 갱신을
 * 위한 힌트이지 권위 있는 저장소가 아니다. 권위는 서버측 JWT + RLS 다.
 */
export function publishTenantSession(state: TenantSessionState): void {
  listener?.(state);
}
