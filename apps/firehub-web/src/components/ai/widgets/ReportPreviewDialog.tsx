/**
 * ReportPreviewDialog — 리포트 초안 전체를 미리보기하는 다이얼로그.
 *
 * ReportBuilderWidget에서 "미리보기" 버튼을 클릭하면 열린다.
 * 모든 섹션을 SectionContent 컴포넌트로 타입별 렌더링하여
 * 실제 리포트와 유사한 형태로 미리볼 수 있다.
 */

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';

import { SectionContent } from './ReportBuilderWidget';

/** 리포트 미리보기 다이얼로그 props */
interface ReportPreviewDialogProps {
  /** 다이얼로그 열림 상태 */
  open: boolean;
  /** 다이얼로그 닫기 핸들러 */
  onClose: () => void;
  /** 리포트 제목 */
  title: string;
  /** 원본 질문 */
  question: string;
  /** 섹션 메타 정보 목록 */
  sections: Array<{
    key: string;
    label: string;
    type: string;
    required?: boolean;
  }>;
  /** 섹션 키별 내용 맵 */
  sectionContents: Record<string, string>;
}

/**
 * 리포트 전체 미리보기 다이얼로그.
 *
 * 제목과 원본 질문을 헤더에 표시하고,
 * 각 섹션을 타입별 렌더러(SectionContent)로 표시한다.
 * ScrollArea로 긴 내용도 스크롤하여 확인 가능하다.
 */
export default function ReportPreviewDialog({
  open,
  onClose,
  title,
  question,
  sections,
  sectionContents,
}: ReportPreviewDialogProps) {
  return (
    <Dialog open={open} onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="sm:max-w-4xl flex flex-col max-h-[85vh]">
        {/* 헤더 — 리포트 제목과 원본 질문 표시 */}
        <DialogHeader>
          <DialogTitle className="text-base font-semibold">{title}</DialogTitle>
          {/* 원본 질문 — 이탤릭 muted 텍스트로 표시 */}
          <p className="text-xs text-muted-foreground italic mt-1">"{question}"</p>
        </DialogHeader>

        {/* 본문 — 섹션 전체를 스크롤 가능한 영역으로 표시 */}
        <ScrollArea className="flex-1 overflow-auto pr-1">
          <div className="space-y-4 py-2 pr-3">
            {sections.map((section, idx) => {
              const content = sectionContents?.[section.key];
              return (
                <div key={section.key}>
                  {/* 섹션 구분선 — 첫 번째 섹션 전에는 표시하지 않음 */}
                  {idx > 0 && <Separator className="mb-4" />}

                  {/* 섹션 헤더 — 레이블, 타입 뱃지, 필수 여부 */}
                  <div className="flex items-center gap-2 mb-2">
                    <span className="text-sm font-semibold">{section.label}</span>
                    <Badge variant="outline" className="text-[10px]">
                      {section.type}
                    </Badge>
                    {section.required && (
                      <span className="h-1.5 w-1.5 rounded-full bg-warning" />
                    )}
                  </div>

                  {/* 섹션 내용 — 타입별 렌더러 또는 빈 상태 */}
                  {content ? (
                    <SectionContent type={section.type} content={content} />
                  ) : (
                    <p className="text-xs text-muted-foreground">(내용 없음)</p>
                  )}
                </div>
              );
            })}

            {/* 섹션이 하나도 없는 경우 */}
            {sections.length === 0 && (
              <p className="text-sm text-muted-foreground text-center py-8">
                표시할 섹션이 없습니다.
              </p>
            )}
          </div>
        </ScrollArea>

        {/* 푸터 — 닫기 버튼 */}
        <DialogFooter>
          <Button variant="outline" size="sm" onClick={onClose}>
            닫기
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
