import { act, render } from '@testing-library/react';
import { useEffect } from 'react';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import { describe, expect, it } from 'vitest';

import { PageTitleOverride, PageTitleSync } from './PageTitleSync';

/**
 * PageTitleSync + PageTitleOverride 단위 테스트 (WD-580).
 * URL 을 바꾸지 않고 다른 화면(워크스페이스 선택·권한 안내)을 그리는 동안 덮어쓴 이름이 경로 이름보다 우선하고,
 * 경로가 바뀌어도 덮어쓰기가 지워지지 않으며, 언마운트되면 경로 기반 제목으로 돌아가는지 확인한다.
 * 브랜드는 기본값(config.js 없음 → Smart Fire Hub)을 쓴다.
 */

/** 테스트에서 라우터 밖으로 navigate 를 꺼내 쓰기 위한 탈출구 */
let navigateRef: ReturnType<typeof useNavigate> | null = null;
function NavigateCapture() {
  const navigate = useNavigate();
  // 렌더 중 외부 변수 대입은 react-hooks/globals 위반이라 effect 에서 꺼낸다
  useEffect(() => {
    navigateRef = navigate;
  }, [navigate]);
  return null;
}

function Harness({ path, override }: { path: string; override?: string }) {
  return (
    <MemoryRouter initialEntries={[path]}>
      <NavigateCapture />
      <PageTitleSync />
      {override && <PageTitleOverride label={override} />}
    </MemoryRouter>
  );
}

describe('PageTitleSync', () => {
  it('경로 기반 제목을 쓰고, 경로가 바뀌면 따라간다', () => {
    render(<Harness path="/data/datasets" />);
    expect(document.title).toBe('데이터셋 · Smart Fire Hub');

    act(() => navigateRef?.('/pipelines'));
    expect(document.title).toBe('파이프라인 · Smart Fire Hub');
  });

  it('덮어쓰기가 마운트돼 있으면 경로 이름보다 우선하고, 경로가 바뀌어도 유지된다', () => {
    render(<Harness path="/admin/users" override="접근 권한 없음" />);
    expect(document.title).toBe('접근 권한 없음 · Smart Fire Hub');

    // PageTitleSync 가 경로 변경으로 다시 돌아도 덮어쓰기를 지우지 않아야 한다(effect 순서 경합 방지)
    act(() => navigateRef?.('/admin/roles'));
    expect(document.title).toBe('접근 권한 없음 · Smart Fire Hub');
  });

  it('덮어쓰기가 언마운트되면 경로 기반 제목으로 돌아간다', () => {
    const { rerender } = render(<Harness path="/" override="워크스페이스 선택" />);
    expect(document.title).toBe('워크스페이스 선택 · Smart Fire Hub');

    rerender(<Harness path="/" />);
    expect(document.title).toBe('Smart Fire Hub');
  });
});
