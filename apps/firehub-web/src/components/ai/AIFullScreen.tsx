import { AIChatPanel } from './AIChatPanel';
import { AISessionSidebar } from './AISessionSidebar';
import { useAI } from './AIProvider';

export function AIFullScreen() {
  const { isOpen } = useAI();

  if (!isOpen) return null;

  return (
    <div className="flex-1 flex h-full w-full">
      <AISessionSidebar />
      <div className="flex-1 h-full max-w-3xl mx-auto w-full">
        <AIChatPanel showSessionSwitcher={false} />
      </div>
    </div>
  );
}
