import { Sparkles } from 'lucide-react';

import { cn } from '../../lib/utils';
import { useAI } from './AIProvider';

export function AIToggleButton() {
  const { toggleAI, isOpen } = useAI();

  return (
    <button
      onClick={toggleAI}
      title="AI 어시스턴트 (⌘K)"
      aria-label={isOpen ? 'AI 어시스턴트 닫기' : 'AI 어시스턴트 열기'}
      className={cn(
        'fixed right-0 bottom-20 z-50',
        'flex items-center gap-1.5 px-3 py-2.5',
        'rounded-l-lg shadow-lg',
        'text-sm font-medium',
        'transition-all duration-200',
        'bg-primary text-primary-foreground',
        'cursor-pointer',
        isOpen
          ? 'translate-x-full opacity-0 pointer-events-none'
          : 'translate-x-0 opacity-100 hover:px-4',
      )}
    >
      <Sparkles className="h-4 w-4" />
      <span>AI</span>
    </button>
  );
}
