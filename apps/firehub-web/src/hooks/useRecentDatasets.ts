import { useState } from 'react';

export interface RecentDataset {
  id: number;
  name: string;
  tableName: string;
  accessedAt: string;
}

const STORAGE_KEY = 'sfh-recent-datasets';
const MAX_STORED = 10;
const MAX_SHOWN = 5;

function readFromStorage(): RecentDataset[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    return JSON.parse(raw) as RecentDataset[];
  } catch {
    return [];
  }
}

function writeToStorage(items: RecentDataset[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(items));
  } catch {
    // ignore storage errors
  }
}

export function useRecentDatasets() {
  const [recents, setRecents] = useState<RecentDataset[]>(() =>
    readFromStorage().slice(0, MAX_SHOWN)
  );

  const addRecent = (dataset: RecentDataset) => {
    const existing = readFromStorage();
    const filtered = existing.filter((d) => d.id !== dataset.id);
    const updated = [dataset, ...filtered].slice(0, MAX_STORED);
    writeToStorage(updated);
    setRecents(updated.slice(0, MAX_SHOWN));
  };

  const clearRecents = () => {
    writeToStorage([]);
    setRecents([]);
  };

  return { recents, addRecent, clearRecents };
}
