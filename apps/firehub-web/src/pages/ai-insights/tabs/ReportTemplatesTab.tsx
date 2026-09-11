import { FileText, Plus } from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { SearchInput } from '@/components/ui/search-input';
import { useProactiveTemplates } from '@/hooks/queries/useProactiveMessages';

export default function ReportTemplatesTab() {
  const { data: templates = [], isLoading } = useProactiveTemplates();
  const navigate = useNavigate();
  // 커스텀 템플릿 이름 검색어. 템플릿이 늘어날수록(#547) 스크롤만으로는
  // 원하는 항목을 찾기 어려워 클라이언트 사이드 필터를 둔다.
  const [customSearch, setCustomSearch] = useState('');

  const builtins = templates.filter((t) => t.builtin);
  const customs = templates.filter((t) => !t.builtin);
  // 이름 기준 대소문자 무시 부분 일치 필터링. 검색어가 없으면 전체 표시.
  // (목록 규모가 작아 useMemo 없이 매 렌더 재계산해도 비용이 미미함 — React Compiler와의
  //  수동 메모이제이션 충돌을 피하기 위해 단순 계산으로 유지)
  const searchKeyword = customSearch.trim().toLowerCase();
  const filteredCustoms = searchKeyword
    ? customs.filter((t) => t.name.toLowerCase().includes(searchKeyword))
    : customs;

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
        {/* 동일 행의 카드 높이를 균일하게 맞추기 위해 items-stretch(기본값) 유지 */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {builtins.map((t) => (
            <Card
              key={t.id}
              className="bg-muted/20 border-dashed cursor-pointer hover:bg-muted/30 transition-colors flex flex-col"
              onClick={() => navigate(`/ai-insights/templates/${t.id}`)}
            >
              <CardHeader className="pb-2 flex-shrink-0">
                <div className="flex items-start justify-between gap-2">
                  <CardTitle className="text-sm font-medium">{t.name}</CardTitle>
                  <Badge variant="secondary" className="shrink-0 text-xs">기본</Badge>
                </div>
                {t.description && (
                  <CardDescription className="text-xs line-clamp-2">{t.description}</CardDescription>
                )}
              </CardHeader>
              {/* flex-1로 설정하여 카드 높이가 늘어날 때 콘텐츠 영역이 확장되도록 함 */}
              <CardContent className="flex-1">
                <p className="text-xs text-muted-foreground">섹션 {t.sectionCount}개</p>
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
          <>
            <SearchInput
              placeholder="템플릿 이름으로 검색..."
              value={customSearch}
              onChange={setCustomSearch}
            />
            {filteredCustoms.length === 0 ? (
              <div className="rounded-lg border border-dashed flex flex-col items-center justify-center py-12 gap-2 text-center">
                <FileText className="h-8 w-8 text-muted-foreground" />
                <p className="text-sm font-medium">검색 결과 없음</p>
                <p className="text-xs text-muted-foreground">
                  &apos;{customSearch}&apos;와(과) 일치하는 커스텀 템플릿이 없습니다.
                </p>
              </div>
            ) : (
              /* 동일 행의 카드 높이를 균일하게 맞추기 위해 items-stretch(기본값) 유지 */
              <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                {filteredCustoms.map((t) => (
                  <Card
                    key={t.id}
                    className="card-hover cursor-pointer flex flex-col"
                    onClick={() => navigate(`/ai-insights/templates/${t.id}`)}
                  >
                    <CardHeader className="pb-2 flex-shrink-0">
                      <div className="flex items-start justify-between gap-2">
                        <CardTitle className="text-sm font-medium">{t.name}</CardTitle>
                      </div>
                      {t.description && (
                        <CardDescription className="text-xs line-clamp-2">{t.description}</CardDescription>
                      )}
                    </CardHeader>
                    {/* flex-1로 설정하여 카드 높이가 늘어날 때 콘텐츠 영역이 확장되도록 함 */}
                    <CardContent className="flex-1">
                      <p className="text-xs text-muted-foreground">섹션 {t.sectionCount}개</p>
                    </CardContent>
                  </Card>
                ))}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
