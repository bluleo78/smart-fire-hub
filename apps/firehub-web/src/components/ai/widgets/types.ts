import type { AIMode } from '../../../types/ai';

export interface WidgetProps<T = Record<string, unknown>> {
  input: T;
  /** 페이지 이동 콜백. state를 전달하면 React Router location.state로 전달된다. */
  onNavigate?: (path: string, state?: Record<string, unknown>) => void;
  displayMode: AIMode;
}

export interface WidgetShellProps {
  title: string;
  icon: string;
  subtitle?: string;
  actions?: React.ReactNode;
  navigateTo?: string;
  /** 페이지 이동 콜백. state를 전달하면 React Router location.state로 전달된다. */
  onNavigate?: (path: string, state?: Record<string, unknown>) => void;
  displayMode: AIMode;
  children: React.ReactNode;
}
