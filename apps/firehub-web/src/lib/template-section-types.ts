import type { SectionType, TemplateSection } from '../api/proactive';

export interface SectionTypeDefinition {
  type: SectionType;
  icon: string;
  label: string;
  description: string;
  color: string; // Tailwind border color class
  snippet: {
    key: string;
    type: SectionType;
    label: string;
    description: string;
  };
}

export const SECTION_TYPES: SectionTypeDefinition[] = [
  {
    type: 'text',
    icon: '📝',
    label: 'Text',
    description: '마크다운 서술형 텍스트. 요약, 분석, 인사이트 설명.',
    color: 'border-l-blue-500',
    snippet: { key: 'new_text', type: 'text', label: '새 텍스트 섹션', description: '이 섹션에 대한 설명을 입력하세요' },
  },
  {
    type: 'cards',
    icon: '📊',
    label: 'Cards',
    description: '핵심 수치 카드. KPI, 통계 요약, 전주/전월 비교.',
    color: 'border-l-amber-500',
    snippet: { key: 'new_cards', type: 'cards', label: '핵심 지표', description: '주요 KPI 수치를 카드로 표시' },
  },
  {
    type: 'list',
    icon: '📋',
    label: 'List',
    description: '항목 나열. 주요 이슈, 변경사항, 권고사항.',
    color: 'border-l-slate-500',
    snippet: { key: 'new_list', type: 'list', label: '주요 항목', description: '나열할 항목 목록' },
  },
  {
    type: 'table',
    icon: '📑',
    label: 'Table',
    description: '행/열 구조 데이터. 순위표, 비교표, 상세 통계.',
    color: 'border-l-indigo-500',
    snippet: { key: 'new_table', type: 'table', label: '데이터 테이블', description: '표 형식 데이터' },
  },
  {
    type: 'comparison',
    icon: '🔄',
    label: 'Comparison',
    description: '기간 비교. 전주/전월/전년 대비 변화율 표시.',
    color: 'border-l-purple-500',
    snippet: { key: 'new_comparison', type: 'comparison', label: '기간 비교', description: '이전 기간 대비 변화를 비교' },
  },
  {
    type: 'alert',
    icon: '⚠️',
    label: 'Alert',
    description: '경고/알림. 이상치, 임계값 초과, 긴급 권고.',
    color: 'border-l-red-500',
    snippet: { key: 'new_alert', type: 'alert', label: '주요 경고', description: '주의가 필요한 항목' },
  },
  {
    type: 'timeline',
    icon: '🕐',
    label: 'Timeline',
    description: '시간순 이벤트 나열. 사건 경과, 작업 히스토리.',
    color: 'border-l-cyan-500',
    snippet: { key: 'new_timeline', type: 'timeline', label: '타임라인', description: '시간순 이벤트 나열' },
  },
  {
    type: 'chart',
    icon: '📈',
    label: 'Chart',
    description: '차트/그래프 설명. 데이터 시각화 결과를 서술적으로 설명.',
    color: 'border-l-green-500',
    snippet: { key: 'new_chart', type: 'chart', label: '차트 분석', description: '데이터 시각화 결과 설명' },
  },
  {
    type: 'recommendation',
    icon: '💡',
    label: 'Recommendation',
    description: 'AI 권고사항. 분석 결과를 바탕으로 제안하는 조치/개선 사항.',
    color: 'border-l-emerald-500',
    snippet: { key: 'new_recommendation', type: 'recommendation', label: '권고사항', description: 'AI가 제안하는 조치 사항' },
  },
  {
    type: 'group',
    icon: '📁',
    label: 'Group',
    description: '섹션 그룹/챕터. 하위 섹션을 묶는 컨테이너.',
    color: 'border-l-violet-500',
    snippet: { key: 'new_group', type: 'group', label: '새 그룹', description: '관련 섹션을 그룹으로 묶습니다' },
  },
  {
    type: 'divider',
    icon: '➖',
    label: 'Divider',
    description: '구분선. 섹션 간 시각적 구분.',
    color: 'border-l-gray-500',
    snippet: { key: 'new_divider', type: 'divider', label: '구분선', description: '' },
  },
];

export function getSectionTypeDef(type: string): SectionTypeDefinition | undefined {
  return SECTION_TYPES.find((s) => s.type === type);
}

/** Parse JSON string into TemplateSection[]. Returns null on invalid JSON, [] on valid but empty. */
export function parseTemplateSections(json: string): TemplateSection[] | null {
  try {
    const parsed = JSON.parse(json);
    return Array.isArray(parsed?.sections) ? (parsed.sections as TemplateSection[]) : [];
  } catch {
    return null;
  }
}

/** Validate that section nesting depth does not exceed maxDepth (default 3). */
export function validateSectionDepth(
  sections: TemplateSection[],
  maxDepth = 3,
  currentDepth = 1,
): boolean {
  for (const section of sections) {
    if (currentDepth > maxDepth) return false;
    if (section.children && section.children.length > 0) {
      if (section.type !== 'group') return false;
      if (!validateSectionDepth(section.children, maxDepth, currentDepth + 1)) return false;
    }
  }
  return true;
}

/** Flatten nested sections into a flat array (for counting, iterating). */
export function flattenSections(sections: TemplateSection[]): TemplateSection[] {
  const result: TemplateSection[] = [];
  for (const section of sections) {
    result.push(section);
    if (section.children) {
      result.push(...flattenSections(section.children));
    }
  }
  return result;
}
