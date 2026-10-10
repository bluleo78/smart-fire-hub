import { useCallback, useEffect, useState } from 'react';

/** 가로 스크롤 컨테이너의 양 끝 잘림 상태. */
export interface HorizontalOverflow {
  /** 왼쪽으로 더 스크롤할 내용이 있다(이미 오른쪽으로 밀렸다). */
  canScrollLeft: boolean;
  /** 오른쪽에 잘린 내용이 있다. */
  canScrollRight: boolean;
}

/**
 * 가로 스크롤 컨테이너의 좌/우 잘림 여부를 추적한다 — 페이드 그라데이션·화살표 힌트를 "스크롤 가능할 때만" 보이게 하는 데 쓴다.
 *
 * 무엇: 스크롤·컨테이너 크기 변경·자식 크기 변경·자식 추가/삭제 때마다 scrollLeft/scrollWidth/clientWidth 로 다시 계산한다.
 * 왜:   표(Table)에만 있던 계산을 데이터셋 상세 탭 바도 함께 쓰게 꺼냈다. 탭 바는 데이터셋 종류(표·문서·파일·지도 유무)에 따라
 *       탭 개수가 바뀌는데, 컨테이너 크기가 그대로면 컨테이너 ResizeObserver 가 발화하지 않는다 — 그래서 자식까지 관찰하고
 *       MutationObserver 로 자식 목록 변화도 잡는다.
 *
 * ref 객체가 아니라 엘리먼트를 받는 이유: 탭 바는 데이터셋 로드 뒤에야 마운트되는데, ref 객체는 바뀌어도 effect 가 다시 돌지
 * 않아 관찰이 영영 붙지 않는다. 호출부는 콜백 ref(useState 의 setter)로 엘리먼트를 넘긴다.
 */
export function useHorizontalOverflow(el: HTMLElement | null): HorizontalOverflow {
  const [overflow, setOverflow] = useState<HorizontalOverflow>({
    canScrollLeft: false,
    canScrollRight: false,
  });

  const update = useCallback(() => {
    if (!el) return;
    const { scrollLeft, scrollWidth, clientWidth } = el;
    const next = {
      canScrollLeft: scrollLeft > 0,
      // 소수점 반올림 차이로 끝까지 밀어도 1px 남는 경우를 잘림으로 보지 않는다.
      canScrollRight: scrollLeft + clientWidth < scrollWidth - 1,
    };
    // 값이 같으면 같은 객체를 유지해 스크롤마다 불필요한 리렌더를 막는다.
    setOverflow((prev) =>
      prev.canScrollLeft === next.canScrollLeft && prev.canScrollRight === next.canScrollRight ? prev : next,
    );
  }, [el]);

  useEffect(() => {
    if (!el) return;
    // 첫 계산은 따로 부르지 않는다 — ResizeObserver 는 observe() 직후 한 번 콜백을 부르므로 그때 계산된다.
    el.addEventListener('scroll', update, { passive: true });
    const ro = new ResizeObserver(update);
    const observeAll = () => {
      ro.disconnect();
      ro.observe(el);
      for (const child of Array.from(el.children)) ro.observe(child);
    };
    observeAll();
    // 자식이 추가·삭제되면(탭 개수 변화 등) 새 자식을 관찰 대상에 넣고 다시 계산한다.
    const mo = new MutationObserver(() => {
      observeAll();
      update();
    });
    mo.observe(el, { childList: true });
    return () => {
      el.removeEventListener('scroll', update);
      ro.disconnect();
      mo.disconnect();
    };
  }, [el, update]);

  return overflow;
}
