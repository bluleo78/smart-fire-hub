/**
 * Vitest 공통 설정 — jest-dom matcher 확장, 각 테스트 후 DOM cleanup.
 */
import '@testing-library/jest-dom/vitest';

import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

/**
 * Node 25+ 는 `--localstorage-file` 없이 뜨면 전역 `localStorage` 를 쓸 수 없는 객체로 둔다. 그 전역이 jsdom 의
 * Storage 를 가려 `localStorage.removeItem` 이 TypeError 를 낸다(Node 26 에서 client.refresh 테스트가 그렇게 깨졌다).
 * 쓸 수 없을 때만 메모리 Storage 로 채운다 — 정상 환경(jsdom Storage 가 보이는 Node)에서는 아무것도 바꾸지 않는다.
 */
function installMemoryStorageIfBroken(name: 'localStorage' | 'sessionStorage') {
  const current = (globalThis as Record<string, unknown>)[name] as Storage | undefined;
  if (current && typeof current.removeItem === 'function') return;
  const data = new Map<string, string>();
  const storage: Storage = {
    get length() {
      return data.size;
    },
    clear: () => data.clear(),
    getItem: (key) => (data.has(key) ? data.get(key)! : null),
    key: (index) => Array.from(data.keys())[index] ?? null,
    removeItem: (key) => void data.delete(key),
    setItem: (key, value) => void data.set(key, String(value)),
  };
  Object.defineProperty(globalThis, name, { value: storage, configurable: true, writable: true });
}
installMemoryStorageIfBroken('localStorage');
installMemoryStorageIfBroken('sessionStorage');

afterEach(() => {
  cleanup();
});
