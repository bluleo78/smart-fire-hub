import { Settings2 } from 'lucide-react';

import { Badge } from '@/components/ui/badge';
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectSeparator,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { ONTOLOGY_STATUS_LABEL, type OntologySummary } from '@/types/ontology';

interface OntologySelectProps {
  ontologies: OntologySummary[];
  value: number | null;
  onChange: (id: number) => void;
  /** 관리 다이얼로그 진입점. 비-ADMIN에게는 넘기지 않아 항목 자체가 숨는다. */
  onManage?: () => void;
}

// 관리 진입점의 Select value. 실제 온톨로지 id와 겹치지 않는 문자열이어야 한다.
const MANAGE_VALUE = '__manage__';

// 상태별 배지 variant. 라벨 자체는 types/ontology.ts의 ONTOLOGY_STATUS_LABEL을 공유한다.
// active는 기본 상태라 배지를 달지 않는다 — 모든 행에 배지가 붙으면 신호가 죽는다(이 판단은 여기 남긴다).
const STATUS_BADGE_VARIANT: Record<string, 'warning' | 'secondary' | undefined> = {
  draft: 'warning',
  archived: 'secondary',
};

/**
 * 지식 모델 탭의 온톨로지 선택기.
 * 활성/그 외를 그룹으로 나눠 "지금 쓸 수 있는 것"이 위에 오게 한다.
 */
export default function OntologySelect({ ontologies, value, onChange, onManage }: OntologySelectProps) {
  const active = ontologies.filter((o) => o.status === 'active');
  const others = ontologies.filter((o) => o.status !== 'active');

  const renderItem = (o: OntologySummary) => {
    const variant = STATUS_BADGE_VARIANT[o.status];
    return (
      <SelectItem key={o.id} value={String(o.id)}>
        <span className="flex items-center gap-2">
          {o.domain}
          {variant && <Badge variant={variant}>{ONTOLOGY_STATUS_LABEL[o.status]}</Badge>}
        </span>
      </SelectItem>
    );
  };

  return (
    <Select
      value={value == null ? undefined : String(value)}
      onValueChange={(next) => {
        // 관리 항목은 선택이 아니라 액션이다 — 선택 상태를 바꾸지 않고 다이얼로그만 연다.
        if (next === MANAGE_VALUE) {
          onManage?.();
          return;
        }
        onChange(Number(next));
      }}
    >
      <SelectTrigger className="h-8 w-[220px]" aria-label="온톨로지 선택">
        <SelectValue placeholder="온톨로지 선택" />
      </SelectTrigger>
      <SelectContent>
        <SelectGroup>
          <SelectLabel>활성</SelectLabel>
          {active.map(renderItem)}
        </SelectGroup>
        {others.length > 0 && (
          <SelectGroup>
            <SelectLabel>초안 · 은퇴</SelectLabel>
            {others.map(renderItem)}
          </SelectGroup>
        )}
        {onManage && (
          <>
            <SelectSeparator />
            <SelectItem value={MANAGE_VALUE}>
              <span className="flex items-center gap-2">
                <Settings2 className="h-4 w-4" />
                온톨로지 관리…
              </span>
            </SelectItem>
          </>
        )}
      </SelectContent>
    </Select>
  );
}
