import { ArrowLeft, Home } from 'lucide-react';
import { useNavigate } from 'react-router-dom';

import { Button } from '@/components/ui/button';

/**
 * 404 페이지 — 존재하지 않는 라우트 진입 시 표시.
 * AppLayout 내부에서 렌더되어 사이드바·헤더가 함께 노출되므로
 * 사용자는 즉시 다른 메뉴로 이동하거나 이전 페이지로 복귀할 수 있다.
 */
export default function NotFoundPage() {
  const navigate = useNavigate();

  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-4 p-6 text-center min-h-[60vh]">
      <h1 className="text-8xl font-bold text-muted-foreground">404</h1>
      <p className="text-xl font-semibold">페이지를 찾을 수 없습니다</p>
      <p className="text-sm text-muted-foreground">
        요청하신 페이지가 존재하지 않거나 이동되었습니다.
      </p>
      <div className="flex gap-2 mt-2">
        {/* 이전 페이지로 — 사용자가 잘못 클릭한 경우 빠르게 복귀 */}
        <Button variant="outline" onClick={() => navigate(-1)}>
          <ArrowLeft className="h-4 w-4 mr-1" />
          이전 페이지로
        </Button>
        {/* 홈으로 — 컨텍스트를 잃은 경우의 안전한 시작점 */}
        <Button onClick={() => navigate('/')}>
          <Home className="h-4 w-4 mr-1" />
          홈으로 가기
        </Button>
      </div>
    </div>
  );
}
