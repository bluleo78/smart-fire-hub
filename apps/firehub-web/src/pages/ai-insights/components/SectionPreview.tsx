import type { TemplateSection } from '@/api/proactive';
import { Badge } from '@/components/ui/badge';
import { Separator } from '@/components/ui/separator';
import { getSectionTypeDef } from '@/lib/template-section-types';

/** 미리보기에서 변수 치환에 사용할 샘플 값 */
const SAMPLE_VARIABLES: Record<string, string> = {
  date: new Date().toISOString().slice(0, 16).replace('T', ' '),
  jobName: '(작업 이름)',
  author: '(작성자)',
  templateName: '(템플릿 이름)',
  period: '(분석 기간)',
};

/** 템플릿 변수({{변수명}})를 샘플 값으로 치환한다 */
function substituteVariables(content: string): string {
  return content.replace(/\{\{(\w+)\}\}/g, (_, key) => SAMPLE_VARIABLES[key] ?? `{{${key}}}`);
}

/**
 * 섹션 타입별 콘텐츠 플레이스홀더를 렌더링한다.
 * 실제 보고서 레이아웃과 유사한 시각적 스켈레톤을 보여준다.
 */
function renderContentPlaceholder(section: TemplateSection) {
  // 정적(static) 섹션은 실제 콘텐츠를 변수 치환 후 표시
  if (section.static && section.content) {
    return (
      <p className="text-xs text-muted-foreground whitespace-pre-line">
        {substituteVariables(section.content)}
      </p>
    );
  }

  switch (section.type) {
    case 'text':
      // 텍스트 섹션 — 길이가 다른 3줄 스켈레톤
      return (
        <div className="space-y-1.5">
          <div className="h-2 bg-muted rounded w-full" />
          <div className="h-2 bg-muted rounded w-4/5" />
          <div className="h-2 bg-muted rounded w-3/5" />
        </div>
      );

    case 'cards':
      // 카드 섹션 — KPI 카드 3개 플레이스홀더
      return (
        <div className="grid grid-cols-3 gap-2">
          {['지표 1', '지표 2', '지표 3'].map((label) => (
            <div key={label} className="bg-muted rounded p-2 text-center">
              <div className="text-[10px] text-muted-foreground mb-1">{label}</div>
              <div className="text-sm font-semibold text-muted-foreground">--</div>
            </div>
          ))}
        </div>
      );

    case 'list':
      // 리스트 섹션 — 불릿 포인트 3개 스켈레톤
      return (
        <div className="space-y-1.5">
          {[80, 65, 50].map((width) => (
            <div key={width} className="flex items-center gap-2">
              <div className="w-1.5 h-1.5 rounded-full bg-muted-foreground/40 shrink-0" />
              <div className="h-2 bg-muted rounded" style={{ width: `${width}%` }} />
            </div>
          ))}
        </div>
      );

    case 'table':
      // 테이블 섹션 — 3x3 미니 테이블 스켈레톤
      return (
        <div className="border border-border rounded overflow-hidden text-[10px]">
          {/* 헤더 행 */}
          <div className="flex bg-muted">
            {['열 1', '열 2', '열 3'].map((col) => (
              <div key={col} className="flex-1 px-2 py-1 text-muted-foreground font-medium border-r border-border last:border-r-0">
                {col}
              </div>
            ))}
          </div>
          {/* 데이터 행 2개 */}
          {[1, 2].map((row) => (
            <div key={row} className="flex border-t border-border">
              {[1, 2, 3].map((col) => (
                <div key={col} className="flex-1 px-2 py-1 border-r border-border last:border-r-0">
                  <div className="h-2 bg-muted rounded w-3/4" />
                </div>
              ))}
            </div>
          ))}
        </div>
      );

    case 'comparison':
      // 비교 섹션 — 이전 기간 → 현재 기간 나란히 표시
      return (
        <div className="flex items-center gap-2">
          <div className="flex-1 bg-muted rounded p-2 text-center">
            <div className="text-[10px] text-muted-foreground mb-1">이전 기간</div>
            <div className="text-sm font-semibold text-muted-foreground">--</div>
          </div>
          <div className="text-muted-foreground text-sm">→</div>
          <div className="flex-1 bg-muted rounded p-2 text-center">
            <div className="text-[10px] text-muted-foreground mb-1">현재 기간</div>
            <div className="text-sm font-semibold text-muted-foreground">--</div>
          </div>
        </div>
      );

    case 'alert':
      // 알림 섹션 — 앰버 경고 배너 플레이스홀더
      return (
        <div className="bg-amber-500/10 border border-amber-500/30 rounded p-2 flex items-center gap-2">
          <span className="text-amber-500 text-sm">⚠️</span>
          <div className="h-2 bg-amber-500/20 rounded w-3/4" />
        </div>
      );

    case 'timeline':
      // 타임라인 섹션 — 왼쪽 세로선 + 포인트 3개
      return (
        <div className="relative pl-4 space-y-2 border-l-2 border-muted ml-1">
          {[75, 60, 50].map((width, i) => (
            <div key={i} className="relative">
              {/* 타임라인 포인트 */}
              <div className="absolute -left-[21px] top-1 w-2.5 h-2.5 rounded-full bg-muted-foreground/30 border-2 border-background" />
              <div className="space-y-0.5">
                <div className="h-2 bg-muted rounded" style={{ width: `${width}%` }} />
                <div className="h-1.5 bg-muted/60 rounded w-1/3" />
              </div>
            </div>
          ))}
        </div>
      );

    case 'chart':
      // 차트 섹션 — 차트 영역 플레이스홀더 (📈 아이콘 + 막대 모양)
      return (
        <div className="bg-muted/50 rounded p-3 flex flex-col items-center justify-center gap-2 h-16">
          <div className="flex items-end gap-1 h-8">
            {[30, 60, 45, 80, 55, 70, 40].map((h, i) => (
              <div
                key={i}
                className="w-3 bg-muted-foreground/30 rounded-t"
                style={{ height: `${h}%` }}
              />
            ))}
          </div>
          <span className="text-[10px] text-muted-foreground">📈 차트 영역</span>
        </div>
      );

    case 'recommendation':
      // 권고사항 섹션 — 💡 카드 플레이스홀더
      return (
        <div className="bg-emerald-500/10 border border-emerald-500/30 rounded p-2 flex items-start gap-2">
          <span className="text-sm mt-0.5">💡</span>
          <div className="space-y-1.5 flex-1">
            <div className="h-2 bg-emerald-500/20 rounded w-full" />
            <div className="h-2 bg-emerald-500/20 rounded w-4/5" />
          </div>
        </div>
      );

    default:
      // 알 수 없는 타입 — 기본 스켈레톤
      return (
        <div className="h-2 bg-muted rounded w-3/4" />
      );
  }
}

/**
 * 단일 섹션을 타입에 맞는 레이아웃으로 렌더링한다.
 * depth가 증가하면 들여쓰기가 적용된다.
 */
function renderSection(section: TemplateSection, index: number, depth = 0): React.ReactNode {
  const def = getSectionTypeDef(section.type);
  const indent = depth * 12;

  // 구분선 — 단순 Separator
  if (section.type === 'divider') {
    return <Separator key={section.key || index} className="my-3" style={{ marginLeft: indent }} />;
  }

  // 그룹 — 헤더 + 하위 섹션 재귀 렌더링
  if (section.type === 'group') {
    return (
      <div key={section.key || index} style={{ marginLeft: indent }}>
        {/* 그룹 헤더 */}
        <div className={`flex items-center gap-2 py-1.5 px-2 rounded border-l-3 ${def?.color ?? 'border-l-violet-500'} bg-muted/30 mb-2`}>
          <span className="text-sm">{def?.icon}</span>
          <span className="text-sm font-semibold flex-1">{section.label || '그룹'}</span>
          {section.required && <span className="w-1.5 h-1.5 rounded-full bg-amber-500 shrink-0" title="필수 섹션" />}
          <Badge variant="outline" className="text-[10px]">group</Badge>
        </div>
        {/* AI 지시사항 — 이탤릭 안내 텍스트 */}
        {section.instruction && (
          <p className="text-[11px] italic text-muted-foreground px-2 mb-2">
            AI 지시: {section.instruction}
          </p>
        )}
        {/* 하위 섹션 재귀 */}
        <div className="space-y-2">
          {section.children?.map((child, i) => renderSection(child, i, depth + 1))}
        </div>
      </div>
    );
  }

  // 일반 섹션 — 타입별 플레이스홀더 렌더링
  return (
    <div
      key={section.key || index}
      className={`flex flex-col gap-2 py-2 px-2 rounded border-l-3 ${def?.color ?? 'border-l-gray-500'} bg-muted/20`}
      style={{ marginLeft: indent }}
    >
      {/* 섹션 헤더: 아이콘, 레이블, 필수 표시, 타입 뱃지 */}
      <div className="flex items-center gap-2">
        <span className="text-sm">{def?.icon}</span>
        <span className="text-sm flex-1 font-medium">{section.label}</span>
        {section.required && (
          <span className="w-1.5 h-1.5 rounded-full bg-amber-500 shrink-0" title="필수 섹션" />
        )}
        {section.static && (
          <Badge variant="secondary" className="text-[10px]">정적</Badge>
        )}
        <Badge variant="outline" className="text-[10px]">{section.type}</Badge>
      </div>

      {/* 타입별 콘텐츠 플레이스홀더 */}
      {renderContentPlaceholder(section)}

      {/* AI 지시사항 — 이탤릭 안내 텍스트 */}
      {section.instruction && (
        <p className="text-[11px] italic text-muted-foreground">
          AI 지시: {section.instruction}
        </p>
      )}
    </div>
  );
}

interface SectionPreviewProps {
  /** 미리볼 섹션 목록 */
  sections: TemplateSection[];
}

/**
 * 템플릿 섹션 미리보기 컴포넌트.
 * 각 섹션 타입에 맞는 시각적 플레이스홀더를 렌더링하여
 * 실제 보고서 레이아웃을 미리 파악할 수 있도록 한다.
 */
export function SectionPreview({ sections }: SectionPreviewProps) {
  if (sections.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-muted-foreground text-sm">
        <p>섹션이 없습니다.</p>
        <p className="text-xs mt-1">왼쪽 에디터에서 섹션을 추가해보세요.</p>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      {sections.map((section, index) => renderSection(section, index))}
      {/* 섹션 수 요약 */}
      <div className="text-center text-xs text-muted-foreground pt-2">
        {sections.length}개 섹션
      </div>
    </div>
  );
}
