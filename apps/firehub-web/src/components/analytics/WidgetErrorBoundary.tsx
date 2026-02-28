import { Component } from 'react';
import type { ReactNode, ErrorInfo } from 'react';
import { AlertTriangle, RefreshCw } from 'lucide-react';
import { Button } from '../ui/button';

interface Props {
  children: ReactNode;
  widgetName?: string;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

export class WidgetErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[WidgetErrorBoundary]', error, info);
  }

  handleRetry = () => {
    this.setState({ hasError: false, error: null });
  };

  render() {
    if (this.state.hasError) {
      return (
        <div className="flex flex-col items-center justify-center gap-3 h-full text-center p-4">
          <AlertTriangle className="h-8 w-8 text-destructive shrink-0" />
          <div>
            <p className="text-sm font-medium text-destructive">
              {this.props.widgetName ? `"${this.props.widgetName}" 위젯 오류` : '위젯 오류'}
            </p>
            <p className="text-xs text-muted-foreground mt-1 max-w-[200px]">
              {this.state.error?.message ?? '알 수 없는 오류가 발생했습니다.'}
            </p>
          </div>
          <Button size="sm" variant="outline" onClick={this.handleRetry}>
            <RefreshCw className="h-3.5 w-3.5 mr-1.5" />
            다시 시도
          </Button>
        </div>
      );
    }

    return this.props.children;
  }
}
