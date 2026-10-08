import { useEffect, useState } from 'react';

import { useAuth } from './useAuth';

export interface RecentDataset {
  id: number;
  name: string;
  tableName: string;
  accessedAt: string;
}

/**
 * 예전 전역 키 — 사용자·테넌트 구분 없이 한 브라우저의 모든 이력이 섞여 쌓였다.
 * 어느 테넌트의 이력인지 가릴 수 없으므로 옮기지 않고 지운다.
 */
const LEGACY_STORAGE_KEY = 'sfh-recent-datasets';
const MAX_STORED = 10;
const MAX_SHOWN = 5;

/**
 * 최근 접근 이력 저장 키 — 사용자 + 테넌트 단위로 나눈다.
 * 같은 브라우저에서 테넌트를 전환하거나 다른 사용자로 로그인해도 남의 이력이 보이지 않게 하기 위함이다.
 * 사용자 또는 테넌트가 정해지지 않았으면 null — 읽지도 쓰지도 않는다.
 */
export function recentDatasetsStorageKey(userId: number | null | undefined, tenantId: number | null): string | null {
  if (userId == null || tenantId == null) return null;
  return `${LEGACY_STORAGE_KEY}:${userId}:${tenantId}`;
}

function removeLegacyKey(): void {
  try {
    localStorage.removeItem(LEGACY_STORAGE_KEY);
  } catch {
    // ignore storage errors
  }
}

function readFromStorage(key: string | null): RecentDataset[] {
  if (!key) return [];
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return [];
    return JSON.parse(raw) as RecentDataset[];
  } catch {
    return [];
  }
}

function writeToStorage(key: string | null, items: RecentDataset[]): void {
  if (!key) return;
  try {
    localStorage.setItem(key, JSON.stringify(items));
  } catch {
    // ignore storage errors
  }
}

/** 현재 사용자·테넌트의 최근 접근 데이터셋 목록을 다룬다. */
export function useRecentDatasets() {
  const { user, activeTenantId } = useAuth();
  const key = recentDatasetsStorageKey(user?.id, activeTenantId);
  const [recents, setRecents] = useState<RecentDataset[]>(() =>
    readFromStorage(key).slice(0, MAX_SHOWN)
  );

  // 키가 바뀌면(사용자 로딩 완료·테넌트 전환) 그 키의 목록으로 갈아끼운다. 전역 키 잔재도 함께 지운다.
  useEffect(() => {
    removeLegacyKey();
    setRecents(readFromStorage(key).slice(0, MAX_SHOWN));
  }, [key]);

  const addRecent = (dataset: RecentDataset) => {
    const existing = readFromStorage(key);
    const filtered = existing.filter((d) => d.id !== dataset.id);
    const updated = [dataset, ...filtered].slice(0, MAX_STORED);
    writeToStorage(key, updated);
    setRecents(updated.slice(0, MAX_SHOWN));
  };

  /** 특정 데이터셋을 최근 목록에서 뺀다 — 접근을 잃은(숨겨진) 데이터셋이 바로가기로 남지 않게. */
  const removeRecent = (id: number) => {
    const updated = readFromStorage(key).filter((d) => d.id !== id);
    writeToStorage(key, updated);
    setRecents(updated.slice(0, MAX_SHOWN));
  };

  const clearRecents = () => {
    writeToStorage(key, []);
    setRecents([]);
  };

  return { recents, addRecent, removeRecent, clearRecents };
}
