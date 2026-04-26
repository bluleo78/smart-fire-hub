/**
 * 페이지 수준 ErrorBoundary 컴포넌트
 *
 * lazy-loaded 페이지 컴포넌트에서 uncaught 렌더링 에러가 발생했을 때
 * 흰 화면(WSOD) 대신 사용자 친화적인 오류 메시지를 표시한다.
 *
 * WidgetErrorBoundary는 위젯 단위의 작은 오류를 처리하고,
 * 이 컴포넌트는 페이지 전체를 감싸는 최상위 오류 경계 역할을 한다.
 */
import { AlertTriangle, RefreshCw } from 'lucide-react';
import type { ErrorInfo, ReactNode } from 'react';
import { Component } from 'react';

import { Button } from './ui/button';

interface Props {
  children: ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

export class PageErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  /** React가 렌더링 오류를 감지했을 때 state를 에러 상태로 전환 */
  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  /** 오류 상세를 콘솔에 기록 (프로덕션 로깅 서비스 연동 가능) */
  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[PageErrorBoundary]', error, info);
  }

  /** 새로고침을 통해 에러 상태를 초기화하고 재시도 */
  handleRetry = () => {
    this.setState({ hasError: false, error: null });
  };

  render() {
    if (this.state.hasError) {
      return (
        <div className="flex min-h-[60vh] flex-col items-center justify-center gap-4 p-8 text-center">
          <AlertTriangle className="h-12 w-12 text-destructive" />
          <div className="space-y-2">
            <h2 className="text-lg font-semibold">
              페이지를 불러오는 중 문제가 발생했습니다
            </h2>
            <p className="text-sm text-muted-foreground max-w-md">
              일시적인 오류가 발생했습니다. 다시 시도하거나 페이지를 새로고침해 주세요.
            </p>
          </div>
          <Button variant="outline" onClick={this.handleRetry}>
            <RefreshCw className="mr-2 h-4 w-4" />
            다시 시도
          </Button>
        </div>
      );
    }

    return this.props.children;
  }
}
