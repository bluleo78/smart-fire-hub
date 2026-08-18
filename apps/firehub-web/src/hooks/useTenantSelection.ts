/**
 * 워크스페이스(테넌트) 확정 요청의 진행 상태를 다루는 훅.
 *
 * <p>무엇을: `selectTenant` 호출 + 진행 중인 대상 id 보관 + 실패 시 토스트/상태 복구까지를 한 곳에
 * 모은다. 최초 선택 화면({@link SelectTenantPage})과 사이드바 전환 UI({@link TenantSwitcher})가 같은
 * 엔드포인트를 같은 방식으로 호출하므로, 두 화면이 이 훅을 공유한다.
 *
 * <p>왜 공유하나: 실패 처리에 눈에 보이지 않는 두 가지 사정이 걸려 있다 — (1) 성공하면 하드
 * 리로드가 걸려 컴포넌트가 사라지므로 성공 경로에 상태 정리가 없고, (2) 접근할 수 없는 테넌트는
 * 401 이 아니라 403 이라 `client.ts` 의 refresh 인터셉터를 타지 않고 그대로 여기로 온다. 화면마다
 * 이 처리를 따로 쓰면 한쪽만 고쳐지고 다른 쪽은 조용히 멈춘 UI 가 된다.
 *
 * <p><b>담지 않는 것:</b> "이미 현재 테넌트인가" 판정은 호출부에 남긴다. 전환 UI 는 그 경우
 * 드롭다운의 기본 동작(메뉴 닫힘)을 살려야 하고, 선택 화면에는 애초에 그런 항목이 없다 — 판정
 * 자체는 같아도 뒤따르는 처리가 다르므로 훅으로 끌어올리면 어느 쪽이든 잘못된 동작이 된다.
 */
import { useCallback, useState } from 'react';

import { handleApiError } from '../lib/api-error';
import { useAuth } from './useAuth';

export interface TenantSelection {
  /** 전환 요청이 진행 중인 테넌트 id. `null` 이면 진행 중이 아니다. */
  pendingTenantId: number | null;
  /**
   * 전환을 시작한다. 진행 중이면 무시한다 — 두 번째 요청이 첫 번째 하드 리로드와 경합하면 어느
   * 테넌트로 들어갈지 예측할 수 없게 된다. 결과를 기다릴 필요가 없도록 Promise 를 반환하지 않는다.
   */
  select: (tenantId: number) => void;
}

export function useTenantSelection(errorMessage: string): TenantSelection {
  const { selectTenant } = useAuth();
  const [pendingTenantId, setPendingTenantId] = useState<number | null>(null);

  const select = useCallback(
    (tenantId: number) => {
      if (pendingTenantId !== null) return;
      setPendingTenantId(tenantId);
      selectTenant(tenantId).catch((error: unknown) => {
        handleApiError(error, errorMessage);
        setPendingTenantId(null);
      });
    },
    [errorMessage, pendingTenantId, selectTenant]
  );

  return { pendingTenantId, select };
}
