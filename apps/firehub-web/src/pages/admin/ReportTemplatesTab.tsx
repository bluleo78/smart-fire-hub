import { FileText, Plus } from 'lucide-react';
import { useNavigate } from 'react-router-dom';

import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card';
import { useProactiveTemplates } from '../../hooks/queries/useProactiveMessages';

function sectionCount(sections: unknown): number {
  return Array.isArray(sections) ? sections.length : 0;
}

export default function ReportTemplatesTab() {
  const { data: templates = [], isLoading } = useProactiveTemplates();
  const navigate = useNavigate();

  const builtins = templates.filter((t) => t.builtin);
  const customs = templates.filter((t) => !t.builtin);

  if (isLoading) {
    return <div className="py-8 text-center text-muted-foreground text-sm">불러오는 중...</div>;
  }

  return (
    <div className="space-y-8">
      {/* Built-in templates */}
      <div className="space-y-4">
        <div>
          <h3 className="text-base font-semibold">기본 템플릿</h3>
          <p className="text-sm text-muted-foreground mt-1">시스템에서 제공하는 기본 리포트 템플릿입니다.</p>
        </div>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {builtins.map((t) => (
            <Card
              key={t.id}
              className="bg-muted/20 border-dashed cursor-pointer hover:bg-muted/30 transition-colors"
              onClick={() => navigate(`/ai-insights/templates/${t.id}`)}
            >
              <CardHeader className="pb-2">
                <div className="flex items-start justify-between gap-2">
                  <CardTitle className="text-sm font-medium">{t.name}</CardTitle>
                  <Badge variant="secondary" className="shrink-0 text-xs">기본</Badge>
                </div>
                {t.description && (
                  <CardDescription className="text-xs line-clamp-2">{t.description}</CardDescription>
                )}
              </CardHeader>
              <CardContent>
                <p className="text-xs text-muted-foreground">섹션 {sectionCount(t.sections)}개</p>
              </CardContent>
            </Card>
          ))}
        </div>
      </div>

      {/* Custom templates */}
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <div>
            <h3 className="text-base font-semibold">커스텀 템플릿</h3>
            <p className="text-sm text-muted-foreground mt-1">직접 만든 리포트 템플릿입니다.</p>
          </div>
          <Button size="sm" onClick={() => navigate('/ai-insights/templates/new')}>
            <Plus className="h-4 w-4" />
            템플릿 추가
          </Button>
        </div>

        {customs.length === 0 ? (
          <div className="rounded-lg border border-dashed flex flex-col items-center justify-center py-12 gap-3 text-center">
            <FileText className="h-10 w-10 text-muted-foreground" />
            <div>
              <p className="text-sm font-medium">커스텀 템플릿 없음</p>
              <p className="text-xs text-muted-foreground mt-1">
                나만의 리포트 구조를 만들어 스마트 작업에 사용하세요.
              </p>
            </div>
            <Button size="sm" variant="outline" onClick={() => navigate('/ai-insights/templates/new')}>
              <Plus className="h-4 w-4" />
              첫 템플릿 만들기
            </Button>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            {customs.map((t) => (
              <Card
                key={t.id}
                className="card-hover cursor-pointer"
                onClick={() => navigate(`/ai-insights/templates/${t.id}`)}
              >
                <CardHeader className="pb-2">
                  <div className="flex items-start justify-between gap-2">
                    <CardTitle className="text-sm font-medium">{t.name}</CardTitle>
                  </div>
                  {t.description && (
                    <CardDescription className="text-xs line-clamp-2">{t.description}</CardDescription>
                  )}
                </CardHeader>
                <CardContent>
                  <p className="text-xs text-muted-foreground">섹션 {sectionCount(t.sections)}개</p>
                </CardContent>
              </Card>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
