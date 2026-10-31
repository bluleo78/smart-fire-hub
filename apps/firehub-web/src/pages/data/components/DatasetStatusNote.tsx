import { useCallback, useEffect, useRef, useState } from 'react';

import { Button } from '../../../components/ui/button';

/**
 * 데이터셋 상세 머리(제목 아래)의 상태 노트 — 두 줄까지만 보이고, 넘치면 「전체 보기」로 펼친다(WD-381).
 *
 * 예전에는 노트 전체를 줄임 없이 그려, 긴 노트(3천 자면 데스크톱 30줄, 10만 자면 휴대폰에서 6만 px)가
 * 탭·본문을 화면 아래로 밀어냈다. 넘침 여부는 글자 수가 아니라 실제로 잘렸는지(scrollHeight)로 판단해,
 * 넓은 화면에서 한 줄에 들어가는 노트에는 버튼을 보이지 않는다. 화면 폭이 바뀌면 다시 잰다.
 */
export function DatasetStatusNote({ note }: { note: string }) {
  const [expanded, setExpanded] = useState(false);
  const [clamped, setClamped] = useState(false);
  const textRef = useRef<HTMLParagraphElement | null>(null);

  // 접힌 상태에서 잘렸는지 잰다 — 펼친 동안에는 「접기」를 보여야 하므로 마지막 측정값을 유지한다.
  const measure = useCallback(() => {
    const el = textRef.current;
    if (!el || expanded) return;
    setClamped(el.scrollHeight > el.clientHeight + 1);
  }, [expanded]);

  useEffect(() => {
    measure();
    const el = textRef.current;
    // ResizeObserver 가 없는 환경(jsdom·오래된 웹뷰)에서는 첫 측정만 하고 넘어간다
    if (!el || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [measure, note]);

  return (
    <div className="mt-0.5">
      <p
        ref={textRef}
        data-testid="dataset-status-note"
        className={`text-xs text-muted-foreground italic break-words ${expanded ? '' : 'line-clamp-2'}`}
      >
        {note}
      </p>
      {(clamped || expanded) && (
        <Button
          variant="link"
          size="sm"
          className="h-auto p-0 text-xs"
          aria-expanded={expanded}
          onClick={() => setExpanded((v) => !v)}
        >
          {expanded ? '접기' : '전체 보기'}
        </Button>
      )}
    </div>
  );
}
