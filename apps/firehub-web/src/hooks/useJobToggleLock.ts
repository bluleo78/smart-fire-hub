import { useCallback, useRef, useState } from 'react';

/**
 * 스마트 작업 활성 스위치의 행별 잠금(WD-511).
 *
 * 왜: 목록의 스위치는 서버 목록 캐시(job.enabled)만 보아, 전환 요청이 끝나기 전 다시 누르면 낡은 값 기준으로
 * 같은 요청(끄기)이 한 번 더 나갔다. 요청 중인 행은 요청한 값을 바로 보이고 잠근다.
 *
 * - 중복 판정은 ref(Set)로 동기적으로 한다 — 같은 렌더 안에서 두 번 불려도(이벤트 일괄 처리) 한 번만 보낸다.
 * - 화면에 보일 값은 state 로 둔다(ref 는 다시 그리지 않으므로).
 * - 요청 성공 시 훅(useDisableProactiveJob·useUpdateProactiveJob)이 목록 캐시의 enabled 를 바로 고쳐 두므로,
 *   잠금을 풀어도 낡은 값으로 되돌아가 보이지 않는다(재조회가 취소·실패해도).
 */
export function useJobToggleLock() {
  const inFlight = useRef(new Set<number>());
  const [requested, setRequested] = useState<Record<number, boolean>>({});

  /** 이 작업의 전환을 시작한다 — 이미 진행 중이면 아무것도 하지 않는다(중복 클릭 무시). */
  const run = useCallback((id: number, enabled: boolean, request: () => Promise<unknown>) => {
    if (inFlight.current.has(id)) return;
    inFlight.current.add(id);
    setRequested((prev) => ({ ...prev, [id]: enabled }));
    // 행마다 자기 요청의 끝에서 잠금을 푼다 — mutate 콜백은 마지막 호출에만 불려 여러 행을 이어 누르면 앞 행이 잠긴 채 남는다.
    void request().finally(() => {
      inFlight.current.delete(id);
      setRequested((prev) => {
        const next = { ...prev };
        delete next[id];
        return next;
      });
    });
  }, []);

  return {
    run,
    /** 요청 중이면 요청한 값, 아니면 undefined */
    requestedValue: (id: number): boolean | undefined => requested[id],
    isPending: (id: number) => requested[id] !== undefined,
  };
}
