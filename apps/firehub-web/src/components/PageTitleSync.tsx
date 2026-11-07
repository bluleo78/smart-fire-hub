import { useEffect, useSyncExternalStore } from 'react';
import { useLocation } from 'react-router-dom';

import { useBranding } from '@/hooks/useBranding';
import { composePageTitle, resolvePageLabel } from '@/lib/page-title';
import {
  getPageTitleOverride,
  pushPageTitleOverride,
  subscribePageTitleOverride,
} from '@/lib/page-title-override';

/**
 * 경로가 바뀔 때마다 탭 제목(document.title)을 「화면 이름 · 브랜드명」으로 맞춘다 (WD-580, WCAG 2.4.2).
 *
 * BrowserRouter 바로 안에 한 번만 마운트한다 — 로그인·회원가입 등 인증 화면은 AppLayout 밖에 있어
 * 레이아웃에 두면 놓치므로, 모든 라우트를 덮는 라우터 최상단이 유일한 단일 지점이다.
 * 브랜드명은 런타임 브랜딩(config.js → useBranding)에서 읽어 사이트별 화이트라벨 이름을 그대로 쓴다.
 * URL 과 다른 화면을 그 자리에서 그리는 경우(워크스페이스 선택·권한 안내)는 PageTitleOverride 가 건 이름이 경로 이름보다 우선한다.
 * 세 값을 한 렌더에서 함께 읽어 계산하므로 어느 하나가 바뀌어도 나머지를 덮어 지우지 않고, 상태를 만들지 않아 재렌더 루프가 없다.
 */
export function PageTitleSync() {
  const { pathname } = useLocation();
  const { brandName } = useBranding();
  const override = useSyncExternalStore(subscribePageTitleOverride, getPageTitleOverride);

  useEffect(() => {
    document.title = composePageTitle(override ?? resolvePageLabel(pathname), brandName);
  }, [pathname, brandName, override]);

  return null;
}

/**
 * 마운트돼 있는 동안 탭 제목의 화면 이름을 label 로 덮어쓴다. 언마운트되면 경로 기반 제목으로 돌아간다.
 * 조건부 분기(early return) 안에서도 쓸 수 있게 훅이 아니라 아무것도 그리지 않는 컴포넌트로 둔다.
 */
export function PageTitleOverride({ label }: { label: string }) {
  useEffect(() => pushPageTitleOverride(label), [label]);
  return null;
}
