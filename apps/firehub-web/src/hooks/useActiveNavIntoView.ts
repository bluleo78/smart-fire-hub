import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';

/**
 * 사이드바 스크롤 컨테이너에서 활성 메뉴 항목을 보이는 영역으로 끌어온다.
 *
 * 무엇: 라우트가 바뀌면 `aria-current="page"`인 링크를 `scrollIntoView({block:'nearest'})`로 노출한다.
 * 왜:   nav 항목이 뷰포트를 넘어 항상 일부가 잘려 있는데(실측 856px 콘텐츠 / 614px 가시,
 *       242px 상시 잘림) 잘린 쪽 메뉴로 진입해도 nav는 스크롤되지 않았다. `/admin/settings`에서
 *       활성 항목 "설정"이 top=872px, nav 하단이 670px — 즉 현재 위치 표시가 화면 밖에 있었다.
 *
 * `block: 'nearest'`인 이유: 이미 보이는 항목은 건드리지 않는다. 'center'로 하면 홈처럼 위쪽에
 * 있는 메뉴를 눌러도 nav가 덜컥 움직여서, 잘림을 고치려다 멀쩡한 경우를 망친다.
 */
export function useActiveNavIntoView(ref: React.RefObject<HTMLElement | null>) {
  const { pathname } = useLocation();

  useEffect(() => {
    const nav = ref.current;
    if (!nav) return;

    const active = nav.querySelector<HTMLElement>('[aria-current="page"]');
    if (!active) return;

    // 접힌 섹션이 펼쳐지는 등 레이아웃이 한 프레임 늦게 잡히는 경우가 있어 페인트 뒤에 실행한다.
    const raf = requestAnimationFrame(() => {
      active.scrollIntoView({ block: 'nearest', behavior: 'auto' });
    });
    return () => cancelAnimationFrame(raf);
  }, [ref, pathname]);
}
