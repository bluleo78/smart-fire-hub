import type { ReactNode } from 'react';
import { Component } from 'react';

interface State { hasError: boolean }

export class WidgetErrorBoundary extends Component<{ children: ReactNode }, State> {
  constructor(props: { children: ReactNode }) {
    super(props);
    this.state = { hasError: false };
  }
  static getDerivedStateFromError(): State { return { hasError: true }; }
  render() {
    if (this.state.hasError) {
      return (
        <div className="my-1 flex h-20 items-center justify-center rounded-lg border border-border bg-muted text-sm text-muted-foreground">
          위젯을 표시할 수 없습니다.
        </div>
      );
    }
    return this.props.children;
  }
}
