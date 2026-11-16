/**
 * DatasetWidget 제목 옆 보조 라벨 회귀 테스트 (WD-760)
 *
 * 예전 보조 라벨은 `DOCUMENT ? 문서 : 출처 라벨` 이라 파일(FILE) 데이터셋이 「원본」 같은 출처 라벨로 보여
 * 테이블과 구분되지 않았다. 이제 테이블이 아니면 저장 방식 라벨(문서·파일), 테이블이면 출처 라벨을 보인다.
 * 데이터셋 상세·데이터 API 는 모킹해 라벨 판정만 격리 검증한다.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

// vi.mock 팩토리가 끌어올려지므로 목 객체도 vi.hoisted 로 올린다
const datasets = vi.hoisted(() => ({ getDatasetById: vi.fn(), getDatasetData: vi.fn() }));
vi.mock('../../../api/datasets', () => ({ datasetsApi: datasets }));

import DatasetWidget from './DatasetWidget';

/** 상세 응답 모킹 — 라벨 판정에 쓰는 저장 방식·출처만 바꿔 가며 쓴다 */
function mockDetail(storageType: string, originType: string) {
  datasets.getDatasetById.mockResolvedValue({
    data: {
      id: 7,
      name: '라벨 확인 데이터셋',
      storageType,
      originType,
      columns: [],
      rowCount: 0,
      updatedAt: '2026-11-16T00:00:00',
      exportAllowed: false,
    },
  });
}

function renderWidget() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return render(<DatasetWidget displayMode="side" input={{ datasetId: 7 }} />, { wrapper });
}

/** 제목(데이터셋 이름)과 같은 헤더 줄에 있는 보조 라벨 텍스트 */
async function headerText(): Promise<string> {
  const title = await screen.findByText('라벨 확인 데이터셋');
  return title.parentElement?.textContent ?? '';
}

beforeEach(() => {
  datasets.getDatasetById.mockReset();
  datasets.getDatasetData.mockReset().mockResolvedValue({
    data: { columns: [], rows: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
  });
});

describe('DatasetWidget 보조 라벨 (WD-760)', () => {
  it('FILE 데이터셋은 출처 라벨이 아니라 「파일」로 보인다', async () => {
    mockDetail('FILE', 'SOURCE');
    renderWidget();
    const text = await headerText();
    expect(text).toContain('파일');
    expect(text).not.toContain('원본');
  });

  it('DOCUMENT 데이터셋은 「문서」로 보인다', async () => {
    mockDetail('DOCUMENT', 'SOURCE');
    renderWidget();
    const text = await headerText();
    expect(text).toContain('문서');
    expect(text).not.toContain('원본');
  });

  it('TABLE 데이터셋은 지금처럼 출처 라벨(임시)로 보인다', async () => {
    mockDetail('TABLE', 'TEMP');
    renderWidget();
    const text = await headerText();
    expect(text).toContain('임시');
    expect(text).not.toContain('테이블');
  });
});
