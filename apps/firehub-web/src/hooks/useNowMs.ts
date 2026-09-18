import { useEffect, useState } from 'react';

/**
 * "지금"(epoch ms)을 일정 간격으로 갱신해 돌려주는 훅 (#691).
 *
 * 실행 중인 작업의 경과 시간을 그리려면 렌더마다 현재 시각이 필요한데, 컴포넌트 본문에서
 * `Date.now()` 를 직접 부르면 (1) 값이 변해도 리렌더가 일어나지 않아 시간이 멈춘 것처럼 보이고
 * (2) 렌더가 순수하지 않아진다(`react-hooks/purity`). 그래서 상태로 들고 주기적으로 갱신한다.
 *
 * `active` 가 false 면 타이머를 걸지 않는다 — 완료된 목록에서 1초마다 리렌더할 이유가 없다.
 * 이때도 마운트 시점의 값은 돌려주므로 호출부는 분기 없이 쓸 수 있다.
 */
export function useNowMs(active: boolean, intervalMs = 1000): number {
  const [nowMs, setNowMs] = useState<number>(() => Date.now());

  useEffect(() => {
    if (!active) return;
    const update = () => setNowMs(Date.now());
    update();
    const id = setInterval(update, intervalMs);
    return () => clearInterval(id);
  }, [active, intervalMs]);

  return nowMs;
}
