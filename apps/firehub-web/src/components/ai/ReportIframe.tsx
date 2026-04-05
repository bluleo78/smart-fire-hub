/**
 * ReportIframe — HTML 리포트를 안전하게 렌더링하는 공통 iframe 컴포넌트.
 *
 * 세 곳에서 재사용: 실행 상세 페이지, 리포트 모달, ReportViewerPage.
 * sandbox="allow-same-origin"으로 스크립트 실행을 차단하되 인쇄 접근은 허용한다.
 */
import { forwardRef } from 'react';

import { cn } from '@/lib/utils';

interface ReportIframeProps {
  /** 렌더링할 HTML 문자열 */
  html: string;
  /** 추가 CSS 클래스 */
  className?: string;
}

const ReportIframe = forwardRef<HTMLIFrameElement, ReportIframeProps>(
  ({ html, className }, ref) => {
    return (
      <iframe
        ref={ref}
        srcDoc={html}
        sandbox="allow-same-origin"
        title="리포트"
        className={cn('w-full h-full border-0', className)}
      />
    );
  },
);

ReportIframe.displayName = 'ReportIframe';

export default ReportIframe;
