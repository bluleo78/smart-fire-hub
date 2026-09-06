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
import { groupOntologiesByStatus } from '@/lib/ontology-grouping';
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
  // groupOntologiesByStatus는 활성 → 초안 → 은퇴 순으로 묶는다 — 이 컴포넌트는 활성만 별도 그룹으로
  // 떼어내고 나머지(초안·은퇴)는 그 순서 그대로 하나로 합쳐, 관리 다이얼로그(OntologyManageDialog)와
  // 같은 그룹핑 소스를 공유한다(#417).
  const groups = groupOntologiesByStatus(ontologies);
  const active = groups.find((g) => g.status === 'active')?.items ?? [];
  const others = groups.filter((g) => g.status !== 'active').flatMap((g) => g.items);

  // 닫힌 SelectTrigger에 표시할 현재 선택 온톨로지(#504). Radix SelectValue는 children이 없으면
  // 매칭되는 SelectItem의 children(아래 renderItem의 max-w-[280px] 래퍼)을 그대로 재사용하는데,
  // 그 래퍼는 드롭다운 목록 폭(280px) 기준이라 트리거 폭(220px)에서는 잘리지 않고, SelectValue
  // 자신도 트리거의 flex item이라 min-w-0 없이는 줄어들지 않아 도메인명이 넘친다(#409는 펼쳐진
  // 목록만 고쳤다). 트리거 전용 children으로 교체해 별도로 truncate를 강제한다.
  const selected = [...active, ...others].find((o) => String(o.id) === String(value));

  const renderItem = (o: OntologySummary) => {
    const variant = STATUS_BADGE_VARIANT[o.status];
    return (
      <SelectItem key={o.id} value={String(o.id)}>
        {/* 도메인명 길이 상한이 없어(#409) 긴 이름이 그대로 줄바꿈되면 드롭다운 레이아웃이 무너진다 —
            max-width + truncate 로 한 줄 말줄임 처리하고, 잘린 전체 이름은 title로 노출한다. */}
        <span className="flex items-center gap-2 max-w-[280px]">
          <span className="min-w-0 truncate" title={o.domain}>
            {o.domain}
          </span>
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
        {/* Radix SelectValue는 className/style prop을 받아도 렌더링에 반영하지 않는다(내부에서
            구조 분해로 버림) — 그래서 폭 제약은 children 쪽에서 직접 만든다. renderItem이 쓰는
            max-w-[280px] 래퍼 대신 단순 truncate span을 쓰면, 이 span에는 title(#504)이 있어 잘린
            전체 이름을 hover로 볼 수 있고, block+truncate가 부모(SelectValue, select.tsx가 이미
            overflow-hidden을 강제해 flex item의 자동 최소폭이 0이 되는 요소)의 실제 렌더 폭 안에서
            말줄임된다. */}
        <SelectValue placeholder="온톨로지 선택">
          {selected && (
            <span className="block truncate" title={selected.domain}>
              {selected.domain}
            </span>
          )}
        </SelectValue>
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
