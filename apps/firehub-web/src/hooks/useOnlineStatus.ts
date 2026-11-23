import { onlineManager } from '@tanstack/react-query';
import { useSyncExternalStore } from 'react';

/** onlineManager 구독 함수 — 모듈 범위에 고정해 렌더마다 새 함수로 재구독하지 않게 한다(useSyncExternalStore 는 subscribe 참조가 바뀌면 다시 구독한다) */
const subscribeOnline = (onChange: () => void) => onlineManager.subscribe(onChange);

/**
 * 브라우저의 실제 온라인 여부를 구독하는 훅 — TanStack Query 의 onlineManager 를 그대로 따른다.
 * isPaused 는 오프라인뿐 아니라 탭 숨김(focusManager)으로 재시도가 멈춘 경우에도 참이므로,
 * 「연결 없음」 안내는 이 값이 false 일 때만 보이게 하기 위해 쓴다(WD-966).
 * WD-982 리뷰 후속: 데이터셋 목록 전용이던 것을 여러 목록이 함께 쓰도록 공용화했다.
 */
export function useOnlineStatus(): boolean {
  return useSyncExternalStore(
    subscribeOnline,
    () => onlineManager.isOnline(),
    () => true,
  );
}

/** useOfflinePaused 입력 — 목록 쿼리 결과에서 그대로 넘긴다 */
export interface OfflinePausedInput {
  /** 쿼리 data — undefined 면 아직 받은 목록이 없다(첫 조회·캐시 없는 조건 변경) */
  data: unknown;
  /** 쿼리 isPaused — 오프라인뿐 아니라 탭 숨김(focusManager)으로 재시도가 멈춰도 참 */
  isPaused: boolean;
  /** 쿼리 isPlaceholderData — keepPreviousData 로 이전 조건의 행이 남아 있는지. placeholder 를 안 쓰는 목록은 생략 */
  isPlaceholderData?: boolean;
}

/** useOfflinePaused 결과 */
export interface OfflinePausedState {
  /** 실제 오프라인이라 지금 조건의 목록을 못 받는 중 — 목록 자리에 연결 대기 안내(offlineListMessage)를 role=status 로 그린다 */
  offline: boolean;
  /** 온라인인데 받은 목록 없이 멈춤(탭 숨김으로 재시도 멈춤) — 곧 이어질 조회라 스켈레톤으로 그린다 */
  waiting: boolean;
}

/**
 * 목록 조회가 멈춘(paused) 상태를 한 규칙으로 판정하는 공용 훅 (WD-966·WD-982·WD-971).
 * - 지금 조건의 목록이 없는 경우 = 데이터 없음(첫 조회·캐시 없는 조건 변경, WD-971) 또는 placeholder(쪽 이동·검색·필터 변경으로 이전 행만 남음, WD-982).
 *   이때 paused + 실제 오프라인이면 offline — 빈 목록 안내나 흐린 이전 행 대신 연결 대기 안내를 보이게 한다.
 *   데이터 없는 paused 쿼리는 isLoading 도 isError 도 아니라 빈 분기로 떨어지므로, 목록은 이 값을 빈·오류 분기보다 먼저 본다.
 * - 데이터 없이 paused 인데 온라인이면 waiting — 실패도 빈 목록도 아니므로 스켈레톤으로 그린다.
 *   placeholder 가 있는 온라인 paused 는 이전 행(흐림)을 그대로 두므로 waiting 이 아니다.
 * 연결되면 TanStack Query 가 자동 재조회하므로 둘 다 저절로 풀린다.
 */
export function useOfflinePaused({ data, isPaused, isPlaceholderData = false }: OfflinePausedInput): OfflinePausedState {
  const isOnline = useOnlineStatus();
  const noData = data === undefined;
  return {
    offline: (noData || isPlaceholderData) && isPaused && !isOnline,
    waiting: noData && isPaused && isOnline,
  };
}

/** 오프라인 대기 중 목록 자리에 보이는 안내 문구 — 연결되면 TanStack Query 가 자동 재조회한다. label 은 목록 이름(차트·대시보드 등) */
export function offlineListMessage(label: string): string {
  return `인터넷 연결이 없어 ${label} 목록을 불러오지 못하고 있습니다. 연결되면 자동으로 다시 불러옵니다.`;
}
