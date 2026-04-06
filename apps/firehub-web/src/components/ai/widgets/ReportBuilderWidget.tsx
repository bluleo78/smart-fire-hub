/**
 * ReportBuilderWidget — AI가 생성한 리포트 초안을 카드 형태로 표시하는 위젯.
 *
 * 섹션 타입(cards, recommendation, list, comparison, 기본 마크다운)에 따라
 * 적절한 렌더러를 선택하여 시각적으로 구분된 레이아웃을 제공한다.
 * 미리보기 다이얼로그를 통해 전체 리포트를 확인할 수 있다.
 */

import { Eye, Save } from 'lucide-react';
import { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';

import ReportPreviewDialog from './ReportPreviewDialog';
import type { WidgetProps } from './types';
import { WidgetShell } from './WidgetShell';

/** 리포트 섹션 메타 정보 */
interface ReportSection {
  key: string;
  label: string;
  type: string;
  instruction?: string;
  required?: boolean;
}

/** ReportBuilderWidget 입력 데이터 구조 */
interface ReportBuilderInput {
  title: string;
  question: string;
  templateStructure: {
    sections: ReportSection[];
    output_format?: string;
  };
  sectionContents: Record<string, string>;
  style?: string;
}

// ─────────────────────────────────────────────
// 섹션 타입별 렌더러들
// ─────────────────────────────────────────────

/**
 * cards 타입 렌더러 — content에서 ```json [...] ``` 블록을 추출하여 카드 그리드로 표시.
 * JSON 블록 앞의 텍스트는 마크다운으로 렌더링하고,
 * [{title, value, description}] 형태의 JSON은 2열 카드 그리드로 표시한다.
 * JSON 파싱 실패 시 마크다운 폴백으로 전체 content를 렌더링한다.
 */
function CardsSectionRenderer({ content }: { content: string }) {
  // ```json ... ``` 블록 추출
  const jsonBlockMatch = content.match(/```json\s*([\s\S]*?)```/);

  if (!jsonBlockMatch) {
    // JSON 블록이 없으면 마크다운으로 폴백
    return (
      <div className="prose prose-sm dark:prose-invert max-w-none text-sm">
        <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
      </div>
    );
  }

  // JSON 블록 앞의 텍스트 (설명 텍스트)
  const beforeJson = content.slice(0, content.indexOf('```json')).trim();

  let cards: Array<{ title?: string; value?: string; description?: string }> = [];
  let parseFailed = false;

  try {
    const parsed = JSON.parse(jsonBlockMatch[1].trim());
    if (Array.isArray(parsed)) {
      cards = parsed;
    } else {
      parseFailed = true;
    }
  } catch {
    parseFailed = true;
  }

  // 파싱 실패 시 전체 마크다운 폴백
  if (parseFailed) {
    return (
      <div className="prose prose-sm dark:prose-invert max-w-none text-sm">
        <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      {/* JSON 블록 앞의 설명 텍스트 마크다운 렌더링 */}
      {beforeJson && (
        <div className="prose prose-sm dark:prose-invert max-w-none text-sm">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{beforeJson}</ReactMarkdown>
        </div>
      )}
      {/* 카드 2열 그리드 */}
      <div className="grid grid-cols-2 gap-2">
        {cards.map((card, idx) => (
          <div
            key={idx}
            className="rounded-lg border bg-card p-3 space-y-0.5"
          >
            {/* 카드 제목 */}
            {card.title && (
              <p className="text-xs text-muted-foreground">{card.title}</p>
            )}
            {/* 카드 값 — 시각적으로 강조 */}
            {card.value && (
              <p className="text-lg font-semibold leading-tight">{card.value}</p>
            )}
            {/* 카드 설명 */}
            {card.description && (
              <p className="text-xs text-muted-foreground">{card.description}</p>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

/**
 * recommendation 타입 렌더러 — 마크다운을 렌더링하되 번호 목록에 primary 색상 강조.
 * prose marker:text-primary로 리스트 마커를 강조하여 권고 사항임을 시각적으로 표현한다.
 */
function RecommendationSectionRenderer({ content }: { content: string }) {
  return (
    <div className="prose prose-sm dark:prose-invert max-w-none text-sm [&_ol]:marker:text-primary [&_ul]:marker:text-primary">
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
    </div>
  );
}

/**
 * list 타입 렌더러 — 마크다운을 렌더링하되 리스트 마커를 amber 색상으로 강조.
 * 중요도 목록임을 amber 색상 마커로 시각적으로 구분한다.
 */
function ListSectionRenderer({ content }: { content: string }) {
  return (
    <div className="prose prose-sm dark:prose-invert max-w-none text-sm [&_ul]:marker:text-amber-500 [&_ol]:marker:text-amber-500">
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
    </div>
  );
}

/**
 * comparison 타입 렌더러 — bg-muted/50 배경으로 비교 영역을 시각적으로 구분.
 * 비교 데이터임을 배경색으로 강조하여 다른 섹션과 명확히 구분한다.
 */
function ComparisonSectionRenderer({ content }: { content: string }) {
  return (
    <div className="bg-muted/50 rounded-lg p-3">
      <div className="prose prose-sm dark:prose-invert max-w-none text-sm">
        <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
      </div>
    </div>
  );
}

/**
 * 섹션 타입에 따라 적절한 렌더러를 선택하는 컴포넌트.
 * export하여 ReportPreviewDialog에서도 동일한 렌더러를 재사용한다.
 */
export function SectionContent({ type, content }: { type: string; content: string }) {
  switch (type) {
    case 'cards':
      return <CardsSectionRenderer content={content} />;
    case 'recommendation':
      return <RecommendationSectionRenderer content={content} />;
    case 'list':
      return <ListSectionRenderer content={content} />;
    case 'comparison':
      return <ComparisonSectionRenderer content={content} />;
    default:
      // 알 수 없는 타입은 기본 마크다운으로 렌더링
      return (
        <div className="prose prose-sm dark:prose-invert max-w-none text-sm">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
        </div>
      );
  }
}

// ─────────────────────────────────────────────
// 메인 위젯 컴포넌트
// ─────────────────────────────────────────────

/**
 * AI가 생성한 리포트 초안을 표시하는 위젯.
 * 섹션별 타입에 맞는 렌더러로 표시하고, 미리보기 다이얼로그를 제공한다.
 */
export default function ReportBuilderWidget({
  input,
  onNavigate,
  displayMode,
}: WidgetProps<ReportBuilderInput>) {
  const { title, question, templateStructure, sectionContents } = input;
  const sections = templateStructure?.sections ?? [];

  /** 미리보기 다이얼로그 열림 상태 */
  const [previewOpen, setPreviewOpen] = useState(false);

  const maxH =
    displayMode === 'fullscreen' || displayMode === 'native'
      ? 'max-h-[450px]'
      : 'max-h-[250px]';

  return (
    <WidgetShell
      title={title}
      icon="📝"
      subtitle="AI 리포트"
      displayMode={displayMode}
      onNavigate={onNavigate}
    >
      <div className="flex flex-col gap-2 p-3">
        {/* 원본 질문 표시 */}
        <p className="text-xs text-muted-foreground italic">"{question}"</p>

        <Separator />

        {/* 섹션 목록 — 타입별 렌더러로 표시 */}
        <ScrollArea className={maxH}>
          <div className="space-y-3 pr-2">
            {sections.map((section) => {
              const content = sectionContents?.[section.key];
              return (
                <div key={section.key} className="space-y-1">
                  {/* 섹션 헤더 — 레이블, 타입 뱃지, 필수 여부 표시 */}
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-medium">{section.label}</span>
                    <Badge variant="outline" className="text-[10px]">
                      {section.type}
                    </Badge>
                    {section.required && (
                      <span className="h-1.5 w-1.5 rounded-full bg-amber-500" />
                    )}
                  </div>
                  {/* 섹션 내용 — 타입별 렌더러 또는 빈 상태 */}
                  {content ? (
                    <SectionContent type={section.type} content={content} />
                  ) : (
                    <p className="text-xs text-muted-foreground">
                      (내용 없음)
                    </p>
                  )}
                </div>
              );
            })}
          </div>
        </ScrollArea>

        <Separator />

        {/* 액션 버튼 — 미리보기, 저장 */}
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            className="flex-1"
            onClick={() => setPreviewOpen(true)}
          >
            <Eye className="mr-1 h-3.5 w-3.5" />
            미리보기
          </Button>
          <Button
            variant="default"
            size="sm"
            className="flex-1"
            onClick={() => onNavigate?.('/ai-insights/jobs/new')}
          >
            <Save className="mr-1 h-3.5 w-3.5" />
            저장
          </Button>
        </div>
      </div>

      {/* 미리보기 다이얼로그 — 전체 섹션을 타입별 렌더러로 표시 */}
      <ReportPreviewDialog
        open={previewOpen}
        onClose={() => setPreviewOpen(false)}
        title={title}
        question={question}
        sections={sections}
        sectionContents={sectionContents}
      />
    </WidgetShell>
  );
}
