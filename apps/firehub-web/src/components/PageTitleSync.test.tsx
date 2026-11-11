import { act, render } from '@testing-library/react';
import { useEffect } from 'react';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import { describe, expect, it } from 'vitest';

import { DetailPageTitle, PageTitleOverride, PageTitleSync } from './PageTitleSync';

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

/**
 * DetailPageTitle 단위 테스트 (WD-534).
 * 상세 화면이 불러온 리소스 이름을 「리소스 이름 · 섹션 이름 · 브랜드명」으로 걸고, 이름이 없거나 공백뿐이면 섹션 제목에 머물며,
 * 이름이 바뀌면 따라가고(저장 후 refetch·다른 id), 언마운트(목록으로 이동)하면 섹션/목록 제목으로 돌아가는지 확인한다.
 */
function DetailHarness({ path, name, mounted = true }: { path: string; name?: string | null; mounted?: boolean }) {
  return (
    <MemoryRouter initialEntries={[path]}>
      <NavigateCapture />
      <PageTitleSync />
      {mounted && <DetailPageTitle name={name} />}
    </MemoryRouter>
  );
}

describe('DetailPageTitle', () => {
  it('이름이 있으면 「리소스 이름 · 섹션 이름 · 브랜드명」', () => {
    render(<DetailHarness path="/data/datasets/1" name="화재 출동 기록" />);
    expect(document.title).toBe('화재 출동 기록 · 데이터셋 · Smart Fire Hub');
  });

  it.each([[undefined], [null], [''], ['   ']])('이름이 %j 이면 섹션 제목에 머문다', (name) => {
    render(<DetailHarness path="/pipelines/7" name={name} />);
    expect(document.title).toBe('파이프라인 · Smart Fire Hub');
  });

  it('이름을 불러오면 섹션 제목에서 리소스 제목으로 바뀌고, 이름이 바뀌면 따라간다', () => {
    const { rerender } = render(<DetailHarness path="/admin/users/2" name={undefined} />);
    expect(document.title).toBe('사용자 관리 · Smart Fire Hub');

    rerender(<DetailHarness path="/admin/users/2" name="홍길동" />);
    expect(document.title).toBe('홍길동 · 사용자 관리 · Smart Fire Hub');

    // 저장으로 이름이 바뀌거나 다른 id 로 옮겨 가면 이전 이름이 남지 않아야 한다
    rerender(<DetailHarness path="/admin/users/2" name="김철수" />);
    expect(document.title).toBe('김철수 · 사용자 관리 · Smart Fire Hub');
  });

  it('이름이 여러 번 바뀐 뒤 언마운트돼도 이전 이름이 남지 않는다', () => {
    // 이름이 바뀔 때마다 이전 덮어쓰기가 풀리지 않으면 저장소에 쌓여, 언마운트 뒤에도 옛 이름이 제목에 남는다
    const { rerender } = render(<DetailHarness path="/admin/users/2" name="홍길동" />);
    rerender(<DetailHarness path="/admin/users/2" name="김철수" />);
    rerender(<DetailHarness path="/admin/users/2" name="이영희" />);
    expect(document.title).toBe('이영희 · 사용자 관리 · Smart Fire Hub');

    rerender(<DetailHarness path="/admin/users/2" name="이영희" mounted={false} />);
    expect(document.title).toBe('사용자 관리 · Smart Fire Hub');
  });

  it('라우트 표에 없는 경로에서는 리소스 이름을 붙이지 않는다', () => {
    render(<DetailHarness path="/no-such-route/1" name="화재 출동 기록" />);
    expect(document.title).toBe('페이지를 찾을 수 없음 · Smart Fire Hub');
  });

  it('언마운트되면(목록으로 이동) 경로 기반 제목으로 돌아간다', () => {
    const { rerender } = render(<DetailHarness path="/data/datasets/1" name="화재 출동 기록" />);
    expect(document.title).toBe('화재 출동 기록 · 데이터셋 · Smart Fire Hub');

    act(() => navigateRef?.('/data/datasets'));
    rerender(<DetailHarness path="/data/datasets/1" name="화재 출동 기록" mounted={false} />);
    expect(document.title).toBe('데이터셋 · Smart Fire Hub');
  });
});
