/**
 * SchemaExplorer memo 재렌더 단위 테스트 (WD-1075)
 *
 * 내부 Input 을 렌더 횟수 스파이로 바꿔야 해서 WD-912 시험(SchemaExplorer.test.tsx, 실제 Input 사용)과 파일을 나눈다.
 */
import { fireEvent, render, screen } from '@testing-library/react';
import { type ComponentProps, useState } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { SchemaExplorer } from './SchemaExplorer';

type Props = ComponentProps<typeof SchemaExplorer>;

// WD-1075: SchemaExplorer 가 실제로 다시 그려졌는지 보려고 내부 Input 의 렌더 횟수를 센다.
// (Profiler 는 부모가 새로 만든 Profiler 요소 자체가 렌더돼 memo 가 막은 경우에도 onRender 가 불려 쓸 수 없다)
const inputRenders = vi.hoisted(() => vi.fn());
vi.mock('../../../components/ui/input', () => ({
  Input: (props: Record<string, unknown>) => {
    inputRenders();
    return <input {...props} />;
  },
}));

describe('SchemaExplorer 재렌더 (WD-1075)', () => {
  it('부모의 무관한 상태가 바뀌어도 같은 props 면 다시 그리지 않는다', () => {
    inputRenders.mockClear();
    // 안정된 props — 테스트 본문에서 한 번만 만들어 두어 부모가 다시 그려져도 참조가 바뀌지 않는다
    const stableTables: Props['tables'] = [
      { tableName: 'orders', columns: [{ columnName: 'id', dataType: 'integer' }] } as Props['tables'][number],
    ];
    const stableInsert = () => {};
    const stableRetry = () => {};

    function Parent() {
      const [n, setN] = useState(0);
      return (
        <div>
          <button onClick={() => setN(n + 1)}>입력 {n}</button>
          <SchemaExplorer tables={stableTables} onInsertAtCursor={stableInsert} onRetry={stableRetry} />
        </div>
      );
    }

    render(<Parent />);
    expect(inputRenders).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: /입력/ }));
    fireEvent.click(screen.getByRole('button', { name: /입력/ }));
    // 마운트 1회만 — memo 가 없으면 부모 상태 변경마다 다시 그려 3회가 된다
    expect(inputRenders).toHaveBeenCalledTimes(1);
  });
});
