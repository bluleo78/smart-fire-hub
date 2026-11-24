/**
 * QueryEditorPage 하위 컴포넌트 props 안정성 (WD-1075)
 *
 * SQL 을 입력하면 페이지 상태(sql)가 바뀌어 페이지가 다시 그려진다. SchemaExplorer 가 memo 여도 props 중 하나라도
 * 렌더마다 새 참조(인라인 onRetry, 로딩 중 `?? []` 등)면 memo 가 무력화되므로, 입력 전·후 렌더의 props 가
 * 모두 Object.is 로 같은지 페이지 수준에서 고정한다.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import type { SchemaInfo } from '../../types/analytics';

// SchemaExplorer 스텁 — 렌더마다 받은 props 를 기록한다
const rendered = vi.hoisted(() => [] as Record<string, unknown>[]);
vi.mock('./components/SchemaExplorer', () => ({
  SchemaExplorer: (props: Record<string, unknown>) => {
    rendered.push(props);
    return null;
  },
}));

const getSchema = vi.hoisted(() => vi.fn());
vi.mock('../../api/analytics', () => ({
  analyticsApi: {
    getSchema,
    getFolders: vi.fn().mockResolvedValue({ data: [] }),
    getQuery: vi.fn(),
  },
}));
vi.mock('../../hooks/useAuth', () => ({ useAuth: () => ({ user: { id: 1 } }) }));

import QueryEditorPage from './QueryEditorPage';

const SCHEMA: SchemaInfo = {
  dialect: 'POSTGRESQL',
  schema: 'public',
  tables: [{ tableName: 'orders', columns: [{ columnName: 'id', dataType: 'integer' }] }],
} as unknown as SchemaInfo;

const PROP_KEYS = ['tables', 'onRetry', 'onInsertAtCursor', 'dialect', 'schema', 'error', 'isLoading', 'isFetching'];

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/analytics/queries/new']}>
        <QueryEditorPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function expectStable(before: Record<string, unknown>, after: Record<string, unknown>) {
  for (const key of PROP_KEYS) {
    expect(Object.is(before[key], after[key]), `prop ${key} 가 SQL 입력으로 새 참조가 됐다`).toBe(true);
  }
}

// jsdom 은 Range 의 레이아웃 API 가 없어 CodeMirror 의 측정이 예외를 던진다 — 빈 값으로 채운다
beforeAll(() => {
  const empty = () => [] as unknown as DOMRectList;
  Range.prototype.getClientRects = empty;
  Range.prototype.getBoundingClientRect = () => new DOMRect();
});

/**
 * 마지막 렌더의 props 를 잡고, CodeMirror 본문 DOM 을 바꿔 입력을 흉내 낸 뒤(CodeMirror 가 변경을 읽어 sql 상태를 바꾼다)
 * 페이지가 다시 그려지길 기다려 입력 전·후 props 참조가 같은지 확인한다. 다시 그려지지 않으면 시험이 무의미하므로 그것도 기다려 검증한다.
 */
async function expectStableAfterTyping(container: HTMLElement) {
  const before = rendered.at(-1)!;
  const count = rendered.length;
  const content = container.querySelector('.cm-content') as HTMLElement;
  expect(content).toBeTruthy();
  act(() => {
    content.textContent = 'SELECT 1';
  });
  await waitFor(() => expect(rendered.length).toBeGreaterThan(count));
  expectStable(before, rendered.at(-1)!);
}

describe('QueryEditorPage → SchemaExplorer props 안정성 (WD-1075)', () => {
  beforeEach(() => {
    rendered.length = 0;
    getSchema.mockReset();
  });

  it('스키마 로딩이 끝난 뒤 SQL 을 입력해도 SchemaExplorer props 참조가 그대로다', async () => {
    getSchema.mockResolvedValue({ data: SCHEMA });
    const { container } = renderPage();
    await waitFor(() => expect(rendered.at(-1)?.tables).toHaveLength(1));
    // 스키마 도착 뒤 isFetching 등 후속 렌더가 가라앉을 때까지 기다린다
    await waitFor(() => expect(rendered.at(-1)?.isFetching).toBe(false));
    await expectStableAfterTyping(container);
  });

  it('스키마가 아직 로딩 중일 때도 tables 등 props 참조가 그대로다', async () => {
    getSchema.mockReturnValue(new Promise(() => {}));
    const { container } = renderPage();
    await waitFor(() => expect(rendered.length).toBeGreaterThan(0));
    await expectStableAfterTyping(container);
  });
});
