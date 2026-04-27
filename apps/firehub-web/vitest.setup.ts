/**
 * Vitest 공통 설정 — jest-dom matcher 확장, 각 테스트 후 DOM cleanup.
 */
import '@testing-library/jest-dom/vitest';

import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

afterEach(() => {
  cleanup();
});
