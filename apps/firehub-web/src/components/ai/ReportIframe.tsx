/**
 * ReportIframe — HTML 리포트를 안전하게 렌더링하는 공통 iframe 컴포넌트.
 *
 * 세 곳에서 재사용: 실행 상세 페이지, 리포트 모달, ReportViewerPage.
 * sandbox="allow-same-origin"으로 스크립트 실행을 차단하되 인쇄 접근은 허용한다.
 *
 * autoHeight가 true이면 iframe load 후 내부 문서 높이에 맞춰 자동 조절한다.
 * false(기본)이면 부모 컨테이너의 h-full을 따른다 (모달, ReportViewerPage 등).
 */
import { forwardRef, useCallback, useImperativeHandle, useRef } from 'react';

import { cn } from '@/lib/utils';

interface ReportIframeProps {
  /** 렌더링할 HTML 문자열 */
  html: string;
  /** 추가 CSS 클래스 */
  className?: string;
  /** true이면 내부 콘텐츠 높이에 맞춰 iframe 높이를 자동 조절한다 */
  autoHeight?: boolean;
}

const ReportIframe = forwardRef<HTMLIFrameElement, ReportIframeProps>(
  ({ html, className, autoHeight = false }, ref) => {
    const innerRef = useRef<HTMLIFrameElement>(null);

    // 외부 ref와 내부 ref를 모두 사용할 수 있도록 연결
    useImperativeHandle(ref, () => innerRef.current as HTMLIFrameElement);

    /** iframe 로드 완료 시 내부 문서 높이를 읽어 iframe 높이를 조절한다 */
    const handleLoad = useCallback(() => {
      if (!autoHeight) return;
      const iframe = innerRef.current;
      if (!iframe?.contentDocument?.body) return;
      // scrollHeight로 전체 콘텐츠 높이를 가져온다
      const contentHeight = iframe.contentDocument.body.scrollHeight;
      iframe.style.height = `${contentHeight + 32}px`;
    }, [autoHeight]);

    return (
      <iframe
        ref={innerRef}
        srcDoc={html}
        sandbox="allow-same-origin"
        title="리포트"
        className={cn('w-full border-0', autoHeight ? '' : 'h-full', className)}
        onLoad={handleLoad}
      />
    );
  },
);

ReportIframe.displayName = 'ReportIframe';

export default ReportIframe;
