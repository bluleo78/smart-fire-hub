import { Button } from '../ui/button';
import { Sparkles } from 'lucide-react';
import { useAI } from './AIProvider';

export function AIToggleButton() {
  const { toggleAI, isOpen } = useAI();

  return (
    <Button
      variant={isOpen ? 'secondary' : 'ghost'}
      size="sm"
      className="gap-1.5"
      onClick={toggleAI}
      title="AI 어시스턴트 (⌘K)"
    >
      <Sparkles className="h-4 w-4" />
      <span className="hidden sm:inline text-sm">AI</span>
    </Button>
  );
}
