import type { AIMode } from '../../../types/ai';

export interface WidgetProps<T = Record<string, unknown>> {
  input: T;
  onNavigate?: (path: string) => void;
  displayMode: AIMode;
}

export interface WidgetShellProps {
  title: string;
  icon: string;
  subtitle?: string;
  actions?: React.ReactNode;
  navigateTo?: string;
  onNavigate?: (path: string) => void;
  displayMode: AIMode;
  children: React.ReactNode;
}
