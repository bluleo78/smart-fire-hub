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

/**
 * 오프라인으로 쪽 이동 조회가 멈춘 상태인지 (WD-982 리뷰 후속).
 * isPaused 단독으로는 오프라인뿐 아니라 탭 숨김(focusManager)으로 재시도가 멈춘 경우에도 참이라, 실제 오프라인(onlineManager)일 때만 참으로 좁힌다.
 * isPlaceholderData 와 함께 보는 이유: 쪽을 옮겨 placeholder 로 이전 행이 남은 경우에만 「흐린 행 대신 안내」로 바꾸기 위해서다.
 */
export function useOfflinePaused(isPaused: boolean, isPlaceholderData: boolean): boolean {
  const isOnline = useOnlineStatus();
  return isPlaceholderData && isPaused && !isOnline;
}

/** 오프라인 대기 중 목록 자리에 보이는 안내 문구 — 연결되면 TanStack Query 가 자동 재조회한다. label 은 목록 이름(차트·대시보드 등) */
export function offlineListMessage(label: string): string {
  return `인터넷 연결이 없어 ${label} 목록을 불러오지 못하고 있습니다. 연결되면 자동으로 다시 불러옵니다.`;
}
