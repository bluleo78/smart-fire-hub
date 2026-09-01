import { ONTOLOGY_STATUS_LABEL, type OntologyStatus, type OntologySummary } from '@/types/ontology';

// 상태별 표시 순서 — "지금 쓸 수 있는 것"(활성)이 위, 그다음 검토 대상(초안), 마지막 운영 종료(은퇴).
// OntologySelect(헤더 콤보박스)가 이미 활성을 최상단에 두는 그룹핑을 쓰고 있어 그 우선순위를 그대로 따른다.
const STATUS_ORDER: OntologyStatus[] = ['active', 'draft', 'archived'];

export interface OntologyStatusGroup {
  status: OntologyStatus;
  label: string;
  items: OntologySummary[];
}

/**
 * 온톨로지 목록을 상태별로 그룹핑한다(활성 → 초안 → 은퇴).
 * 같은 데이터를 다루는 OntologySelect(선택기)와 OntologyManageDialog(관리 테이블)가
 * 서로 다른 순서로 보이던 불일치(#417)를 없애기 위해 그룹핑 기준을 여기 한 곳에 둔다.
 * 항목이 없는 그룹은 결과에서 제외한다.
 */
export function groupOntologiesByStatus(ontologies: OntologySummary[]): OntologyStatusGroup[] {
  return STATUS_ORDER.map((status) => ({
    status,
    label: ONTOLOGY_STATUS_LABEL[status],
    items: ontologies.filter((o) => o.status === status),
  })).filter((group) => group.items.length > 0);
}
