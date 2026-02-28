import { Loader2 } from 'lucide-react';
import { useEffect, useState } from 'react';

const thinkingTexts = [
  '생각하는 중',
  '데이터를 분석하는 중',
  '도구를 사용하는 중',
  '답변을 준비하는 중',
];

export function ThinkingIndicator() {
  const [textIndex, setTextIndex] = useState(0);
  const [dots, setDots] = useState('');

  useEffect(() => {
    const dotInterval = setInterval(() => {
      setDots(prev => prev.length >= 3 ? '' : prev + '.');
    }, 500);

    const textInterval = setInterval(() => {
      setTextIndex(prev => (prev + 1) % thinkingTexts.length);
      setDots('');
    }, 4000);

    return () => {
      clearInterval(dotInterval);
      clearInterval(textInterval);
    };
  }, []);

  return (
    <div className="flex w-full justify-start">
      <div className="max-w-[85%] rounded-lg bg-muted px-3 py-2">
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="h-3.5 w-3.5 animate-spin" />
          <span>{thinkingTexts[textIndex]}{dots}</span>
        </div>
      </div>
    </div>
  );
}
