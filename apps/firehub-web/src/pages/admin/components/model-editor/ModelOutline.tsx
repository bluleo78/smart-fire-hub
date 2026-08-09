import { Plus } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import type { OntologyElementMutations } from '@/hooks/queries/useOntologyElement';
import { validateDomain } from '@/lib/ontology-validation';
import { cn } from '@/lib/utils';
import type { OntologySchema } from '@/types/ontology';

import type { SchemaGraphSelection } from '../SchemaGraph';
import { useAutosaveText } from './useAutosaveText';

interface Props {
  schema: OntologySchema;
  selected: SchemaGraphSelection | null;
  onSelect: (selection: SchemaGraphSelection) => void;
  // OntologyPage가 한 번만 호출한 useOntologyElementMutations의 반환값(EntityInspector/
  // RelationInspector와 동일한 규칙, Task 4 리뷰 IMP-1) — 도메인명 자동 저장(I-1, Task 6 리뷰)에 쓴다.
  mutations: OntologyElementMutations;
  // 관계 섹션 헤더의 "관계 추가" 버튼(Task 5) — 새 관계는 끝점(주어/목적어)을 생성 시점에 정해야 해서
  // (RelationInspector.tsx 주석 참고) 기존 요소 선택과는 다른 진입점이 필요하다. 미전달 시 버튼을
  // 숨긴다(현재는 항상 showEditor에서만 렌더돼 항상 전달되지만, 선택 방어를 남겨 둔다).
  onAddRelation?: () => void;
  // 타입 섹션 헤더의 "타입 추가" 버튼(S2 Task 6 백로그, Task 5 리뷰 M-8 이관) — 전체 문서 모달이
  // 유일한 엔티티 타입 생성 경로였는데, 모달을 지우면서 이 진입점이 새 편집기에 하나도 남지 않게
  // 되는 것을 막는다. onAddRelation과 대칭 — 생성 폼은 기존 요소 선택과 별개의 상태(모달 없음,
  // id 없음)이므로 같은 패턴으로 부모가 소유한다.
  onAddEntityType?: () => void;
  // 타입 삭제 확인 다이얼로그(DeleteTypeConfirm)의 포커스 복귀 대상(M-2, Task 6 리뷰) — 삭제
  // 트리거가 삭제 성공과 함께 사라지므로, 선택과 무관하게 항상 남아 있는 이 버튼이 대신 받는다
  // (DatasetMappingTab의 entityAddRef와 같은 패턴). OntologyPage가 소유하고 여기서는 ref만 붙인다.
  addEntityTypeButtonRef?: React.RefObject<HTMLButtonElement | null>;
}

// 편집 모드 좌측 아웃라인 — 타입/관계를 목록으로 보여주고 클릭 시 선택 상태를 갱신한다.
// OntologyPage가 이 selected를 SchemaGraph에도 그대로 내려줘 캔버스 선택과 양방향으로 동기화된다.
// 요소 단위 편집 API가 id로 대화하므로(이름 지목은 리네임과 경합), id가 없는 요소(이론상 없어야 하지만
// 방어적으로)는 목록에서 제외한다 — 클릭해도 어느 요소를 저장해야 할지 알 수 없기 때문이다.
export default function ModelOutline({
  schema,
  selected,
  onSelect,
  mutations,
  onAddRelation,
  onAddEntityType,
  addEntityTypeButtonRef,
}: Props) {
  const entities = schema.entities.filter((e): e is typeof e & { id: number } => e.id != null);
  const relations = schema.relations.filter((r): r is typeof r & { id: number } => r.id != null);

  // 도메인명 자동 저장(I-1, Task 6 리뷰) — 전체 문서 모달이 유일한 도메인 편집 경로였는데, 모달을
  // 지우면서 편집 수단이 통째로 사라졌었다(patchDomain 뮤테이션·API는 있었지만 부르는 UI가 없었음).
  // 다른 필드(타입 이름 등)와 동일하게 useAutosaveText로 배선한다 — trim=true는 EntityInspector의
  // 타입 이름과 같은 이유(validateDomain도 trim 기준으로 검증하므로 전송도 맞춘다).
  const domainField = useAutosaveText(
    schema.domain,
    (domain) => mutations.patchDomain({ domain }),
    validateDomain,
    true,
  );

  const isSelected = (kind: SchemaGraphSelection['kind'], id: number) =>
    selected?.kind === kind && selected.id === id;

  return (
    // (리뷰 IMP-4) sm 미만에서는 숨긴다 — 아웃라인(w-64)+캔버스+인스펙터(w-80)를 모두 고정폭으로 두면
    // 320px 뷰포트에서 캔버스가 폭 0으로 밀려나고 인스펙터가 화면 밖으로 잘려 나간다. <main> 자체는
    // 조상 노드의 overflow-hidden에 가려 스크롤로 드러나지 않아 SC 1.4.10 게이트를 통과하면서도 실사용은
    // 불가능한 상태였다 — 좁은 화면에서는 캔버스에 집중하고 넓은 화면(sm 이상)에서만 3-pane을 보여준다.
    <div className="hidden w-64 shrink-0 flex-col overflow-hidden border-r sm:flex" data-testid="model-outline">
      <div className="flex flex-col gap-1.5 px-4 pt-4 pb-2">
        <span className="text-xs font-medium uppercase text-muted-foreground">지식 모델</span>
        <Label htmlFor="model-outline-domain" className="sr-only">
          도메인
        </Label>
        <Input
          id="model-outline-domain"
          className="h-8 text-sm font-medium"
          aria-invalid={domainField.error ? true : undefined}
          aria-describedby={domainField.error ? 'model-outline-domain-error' : undefined}
          value={domainField.value}
          onChange={(e) => domainField.onChange(e.target.value)}
          onBlur={domainField.onBlur}
        />
        {domainField.error && (
          <p id="model-outline-domain-error" className="text-xs text-destructive">
            {domainField.error}
          </p>
        )}
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-4">
        {/* 타입 섹션 */}
        <div className="mb-3">
          <div className="flex items-center justify-between px-2 py-1">
            <span className="text-xs font-medium uppercase text-muted-foreground">타입 {entities.length}개</span>
            {onAddEntityType && (
              <Button
                ref={addEntityTypeButtonRef}
                variant="ghost"
                size="icon"
                className="h-6 w-6"
                onClick={onAddEntityType}
                aria-label="타입 추가"
              >
                <Plus className="h-3.5 w-3.5" />
              </Button>
            )}
          </div>
          <div className="space-y-0.5" data-testid="model-outline-entities">
            {entities.map((e) => {
              const active = isSelected('entity', e.id);
              return (
                <button
                  key={e.id}
                  type="button"
                  onClick={() => onSelect({ kind: 'entity', id: e.id })}
                  aria-current={active ? 'true' : undefined}
                  data-testid={`outline-entity-${e.id}`}
                  className={cn(
                    'flex w-full items-center rounded px-2 py-1.5 text-left text-sm transition-colors hover:bg-muted',
                    active && 'bg-accent text-accent-foreground',
                  )}
                >
                  <span className="min-w-0 flex-1 truncate">{e.type}</span>
                </button>
              );
            })}
          </div>
        </div>

        {/* 관계 섹션 */}
        <div>
          <div className="flex items-center justify-between px-2 py-1">
            <span className="text-xs font-medium uppercase text-muted-foreground">관계 {relations.length}개</span>
            {onAddRelation && (
              <Button
                variant="ghost"
                size="icon"
                className="h-6 w-6"
                onClick={onAddRelation}
                aria-label="관계 추가"
              >
                <Plus className="h-3.5 w-3.5" />
              </Button>
            )}
          </div>
          <div className="space-y-0.5" data-testid="model-outline-relations">
            {relations.map((r) => {
              const active = isSelected('relation', r.id);
              return (
                <button
                  key={r.id}
                  type="button"
                  onClick={() => onSelect({ kind: 'relation', id: r.id })}
                  aria-current={active ? 'true' : undefined}
                  data-testid={`outline-relation-${r.id}`}
                  className={cn(
                    'flex w-full items-center rounded px-2 py-1.5 text-left text-xs transition-colors hover:bg-muted',
                    active && 'bg-accent text-accent-foreground',
                  )}
                >
                  <span className="min-w-0 flex-1 truncate">
                    {r.subject} → {r.relation} → {r.object}
                  </span>
                </button>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}
