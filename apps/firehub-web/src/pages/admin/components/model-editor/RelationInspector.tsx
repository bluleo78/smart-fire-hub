import { Plus, Trash2 } from 'lucide-react';
import { useEffect, useState } from 'react';

import { Button } from '@/components/ui/button';
import { DeleteConfirmDialog } from '@/components/ui/delete-confirm-dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import type { OntologyElementMutations } from '@/hooks/queries/useOntologyElement';
import type { ReportDirty } from '@/hooks/useUnsavedChangesGuard';
import { validateRelationName, validateTripleUniqueness } from '@/lib/ontology-validation';
import type { OntologySchema, Triple } from '@/types/ontology';

import { useAutosaveText } from './useAutosaveText';

interface Props {
  schema: OntologySchema;
  // relation=null이면 "새 관계 만들기" 폼을 보여준다(ModelOutline의 "관계 추가" 버튼이 진입점).
  // 기존 관계 선택은 항상 id를 가진다(ModelOutline이 id 없는 관계를 이미 걸러낸다).
  relation: (Triple & { id: number }) | null;
  // OntologyPage가 한 번만 호출한 useOntologyElementMutations의 반환값 — 여기서 훅을 다시
  // 호출하면 saveState가 갈라져 툴바 칩이 조용히 멈춘다(EntityInspector와 동일한 규칙, Task 4 리뷰 IMP-1).
  mutations: OntologyElementMutations;
  // 생성 성공 시 호출 — 부모가 새로 만들어진 관계를 선택 상태로 전환한다.
  onCreated?: (relationId: number) => void;
  // 생성 폼을 취소했을 때 호출 — 부모가 "생성 중" 상태를 끈다.
  onCancel?: () => void;
  // 삭제 성공 시 호출 — 부모가 선택 상태를 비워 더 이상 존재하지 않는 관계를 계속 지목하지 않게 한다.
  onDeleted?: () => void;
  // 삭제 성공 시 포커스를 돌려받을 대상(I-1, S2 최종 리뷰) — 삭제 트리거(relation-delete-trigger)
  // 자신이 onDeleted로 인스펙터가 갈아치워지며 함께 사라진다. EntityInspector의 타입 삭제가 이미
  // 같은 문제를 겪었고(#328류, restoreFocusRef) 이 컴포넌트만 그 대응이 빠져 있었다 — OntologyPage가
  // ModelOutline의 "타입 추가" 버튼을 그대로 내려준다(타입 삭제와 동일한 대체 대상).
  restoreFocusRef?: React.RefObject<HTMLElement | null>;
  // (#484) 이름/설명 필드 중 하나라도 dirty면 알려준다 — OntologyPage가 useDirtyAggregator로
  // 다른 인스펙터/아웃라인과 OR-합산해 useUnsavedChangesGuard에 연결한다. relation===null(생성 폼)은
  // 자동저장이 아니므로 보고하지 않는다.
  onDirtyChange?: ReportDirty;
}

// 끝점(주어/목적어) 선택 — 생성 폼 전용. 엔티티 타입 목록에서 고른다. id 없는 타입(이론상 없어야
// 하지만 방어적으로)은 대상이 될 수 없으므로 제외한다 — ModelOutline/EntityInspector와 동일한 가드.
// Label을 htmlFor로 SelectTrigger에 연결한다(리뷰 M-1) — 이전에는 연결 없이 aria-label만으로
// 접근성 이름을 이중 선언해, 시각 라벨 클릭으로 포커스가 가지 않았다. htmlFor로 묶으면 aria-label
// 없이도 접근성 이름이 Label 텍스트로 정해져(getByLabel 셀렉터는 그대로 동작) — 다만 "<label for>가
// button(SelectTrigger)을 이름 짓는가"는 브라우저별 검증 없이 남아 있었고(I-3, S2 최종 리뷰), 이제는
// 이 두 컨트롤의 유일한 이름 원천이라 미검증에 기대는 위험이 커졌다. aria-labelledby로 이름을 직접
// 못박아 그 의존을 없앤다 — aria-label 이중 선언(M-1이 지적한 문제)은 다시 만들지 않는다.
function EndpointSelect({
  id,
  label,
  entities,
  value,
  onChange,
}: {
  id: string;
  label: string;
  entities: Array<{ id: number; type: string }>;
  value: number | null;
  onChange: (id: number) => void;
}) {
  const labelId = `${id}-label`;
  return (
    <div className="flex flex-col gap-1.5">
      <Label id={labelId} htmlFor={id}>
        {label}
      </Label>
      <Select value={value != null ? String(value) : undefined} onValueChange={(v) => onChange(Number(v))}>
        <SelectTrigger id={id} aria-labelledby={labelId}>
          <SelectValue placeholder="타입 선택" />
        </SelectTrigger>
        <SelectContent>
          {entities.map((e) => (
            <SelectItem key={e.id} value={String(e.id)}>
              {e.type}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}

// 새 관계 생성 폼 — 끝점(주어/목적어)은 오직 생성 시점에만 정할 수 있다. UpdateRelationRequest가
// 끝점을 아예 받지 않는 이유(수정 폼 쪽 주석 참고)와 대칭인 지점: 이 폼은 CreateRelationRequest가
// 요구하는 subjectTypeId/objectTypeId를 반드시 채워야 제출할 수 있다.
function CreateRelationForm({
  schema,
  mutations,
  onCreated,
  onCancel,
}: {
  schema: OntologySchema;
  mutations: OntologyElementMutations;
  onCreated?: (relationId: number) => void;
  onCancel?: () => void;
}) {
  const entities = schema.entities.filter((e): e is typeof e & { id: number } => e.id != null);
  // 기본값을 entities[0]으로 미리 채우지 않는다(리뷰 M-2) — 그러면 주어=목적어=첫 타입인 자기 루프가
  // 화면에 보이지도 않게(placeholder가 값에 가려짐) 기본 선택되어, 사용자가 목적어 선택을 잊으면
  // 조용히 자기 루프 관계가 만들어진다. null로 시작해 placeholder("타입 선택")를 실제로 보여주고,
  // 아래 handleCreate가 미선택을 명시적으로 막는다.
  const [subjectTypeId, setSubjectTypeId] = useState<number | null>(null);
  const [objectTypeId, setObjectTypeId] = useState<number | null>(null);
  const [relationName, setRelationName] = useState('');
  const [description, setDescription] = useState('');
  // 에러를 둘로 나눈다(리뷰 N-3) — 이전에는 하나의 error state를 관계명 Input의 aria-invalid/
  // aria-describedby에 그대로 실어, "끝점을 선택하세요"처럼 실제 원인이 Select인 에러까지 관계명
  // 입력에 붙는 오귀속이 있었다. formError는 폼 전체(끝점 미선택) 문제, nameError는 관계명 자체
  // (공백/중복) 문제 — nameError만 관계명 Input의 접근성 속성과 연결한다.
  const [formError, setFormError] = useState<string | null>(null);
  const [nameError, setNameError] = useState<string | null>(null);
  // 제출 중 버튼을 잠근다(리뷰 M-5) — 없으면 연타 시 POST가 두 번 나가고 두 번째는 (첫 번째가 만든)
  // 같은 트리플과 충돌해 400이 뜬다.
  const [submitting, setSubmitting] = useState(false);

  const subjectName = entities.find((e) => e.id === subjectTypeId)?.type ?? '';
  const objectName = entities.find((e) => e.id === objectTypeId)?.type ?? '';

  // 끝점을 바꾸면 두 에러 모두 지운다(리뷰 N-2) — 중복 에러는 끝점 조합에 의존하므로, 끝점을 바꿔서
  // 더 이상 중복이 아니게 됐는데도 이전 메시지가 남아있으면(사용자가 관계명 필드를 건드리기 전까지)
  // 이미 해결된 문제를 계속 경고하는 셈이 된다.
  const handleEndpointChange = (setter: (id: number) => void) => (id: number) => {
    setter(id);
    setFormError(null);
    setNameError(null);
  };

  const handleCreate = async () => {
    setFormError(null);
    setNameError(null);
    if (subjectTypeId == null || objectTypeId == null) {
      setFormError('주어/목적어 타입을 선택하세요.');
      return;
    }
    // trim(리뷰 M-7) — EntityInspector의 타입/속성 이름과 동일한 이유: 앞뒤 공백이 있는 이름이
    // 검증은 통과하고 서버(trim하지 않음)엔 공백째 저장되면, 트리플 키가 화면에 보이는 이름과
    // 미묘하게 어긋난다. 검증·중복 비교·전송 모두 같은 trim된 값을 기준으로 한다.
    const trimmedName = relationName.trim();
    const relationError = validateRelationName(trimmedName, subjectName, objectName);
    if (relationError) {
      setNameError(relationError);
      return;
    }
    // 트리플 중복(subject|relation|object)은 이제 여기서 먼저 막는다(리뷰 I-1) — 서버 400 문구
    // (파이프 구분)가 정상 사용 경로에 노출되면 이 파일 전역 제약을 어긴다. 서버 400은 동시 편집
    // 레이스(다른 세션이 먼저 같은 트리플을 만든 경우)에 대한 최종 방어선으로만 남는다.
    const duplicateError = validateTripleUniqueness(schema.relations, subjectName, trimmedName, objectName);
    if (duplicateError) {
      setNameError(duplicateError);
      return;
    }
    setSubmitting(true);
    try {
      const result = await mutations.addRelation({
        subjectTypeId,
        relation: trimmedName,
        objectTypeId,
        description,
      });
      // relation.id는 타입상 optional(Triple)이지만 서버가 방금 생성한 관계라 항상 채워져 온다.
      if (result?.relation.id != null) {
        onCreated?.(result.relation.id);
      }
    } finally {
      setSubmitting(false);
    }
  };

  const errorId = 'new-relation-error';
  const formErrorId = 'new-relation-form-error';

  return (
    <div className="flex flex-col gap-4" data-testid="relation-inspector-create">
      <div className="flex flex-col gap-1.5">
        <p className="text-sm font-medium">새 관계 만들기</p>
        <p className="text-xs text-muted-foreground">
          끝점(주어/목적어)은 생성 후에는 바꿀 수 없습니다 — 다른 끝점이 필요하면 삭제하고 다시 만드세요.
        </p>
      </div>

      {entities.length === 0 ? (
        <p className="text-sm text-muted-foreground">관계를 만들려면 먼저 타입을 하나 이상 정의하세요.</p>
      ) : (
        // <form>으로 감싸 Enter로도 제출된다(리뷰 M-6) — 버튼 클릭만 되던 이전에는 다른 폼들과
        // 상호작용 패턴이 어긋났다.
        <form
          className="flex flex-col gap-4"
          onSubmit={(e) => {
            e.preventDefault();
            void handleCreate();
          }}
        >
          <EndpointSelect
            id="new-relation-subject"
            label="주어 타입"
            entities={entities}
            value={subjectTypeId}
            onChange={handleEndpointChange(setSubjectTypeId)}
          />
          <EndpointSelect
            id="new-relation-object"
            label="목적어 타입"
            entities={entities}
            value={objectTypeId}
            onChange={handleEndpointChange(setObjectTypeId)}
          />

          {/* 폼 전체 에러(끝점 미선택) — 특정 입력 하나의 문제가 아니므로 어느 필드의 aria-invalid/
              aria-describedby에도 묶지 않는다(리뷰 N-3: 이전에는 이 메시지가 관계명 Input에 오귀속됐다). */}
          {formError && (
            <p id={formErrorId} role="alert" className="text-xs text-destructive">
              {formError}
            </p>
          )}

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="new-relation-name">관계명</Label>
            <Input
              id="new-relation-name"
              aria-invalid={nameError ? true : undefined}
              aria-describedby={nameError ? errorId : undefined}
              value={relationName}
              onChange={(e) => {
                setRelationName(e.target.value);
                setNameError(null);
              }}
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="new-relation-description">관계 설명</Label>
            <Input
              id="new-relation-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>

          {nameError && (
            <p id={errorId} className="text-xs text-destructive">
              {nameError}
            </p>
          )}

          <div className="flex gap-1.5">
            <Button type="submit" size="sm" className="gap-1.5" disabled={submitting}>
              <Plus className="h-3.5 w-3.5" />
              관계 만들기
            </Button>
            <Button type="button" variant="outline" size="sm" onClick={onCancel} disabled={submitting}>
              취소
            </Button>
          </div>
        </form>
      )}
    </div>
  );
}

// 편집 모드 우측 인스펙터 — 선택된 관계의 이름/설명을 편집하고 삭제한다. 끝점(주어/목적어)은 이
// 컴포넌트 안에서 절대 편집 가능한 입력으로 렌더되지 않는다 — 서버 계약(UpdateRelationRequest)
// 자체가 끝점을 받지 않는다: "끝점이 바뀌면 다른 관계"이기 때문이다(types/ontology.ts 주석 참고).
// 그래서 여기서는 끝점을 읽기 전용 텍스트로 보여주고, 바꾸고 싶다면 삭제 후 재생성하라고 안내한다.
export default function RelationInspector({
  schema,
  relation,
  mutations,
  onCreated,
  onCancel,
  onDeleted,
  restoreFocusRef,
  onDirtyChange,
}: Props) {
  // relation === null: ModelOutline의 "관계 추가" 버튼으로 진입한 생성 폼.
  if (relation === null) {
    return <CreateRelationForm schema={schema} mutations={mutations} onCreated={onCreated} onCancel={onCancel} />;
  }

  return (
    <EditRelationForm
      schema={schema}
      relation={relation}
      mutations={mutations}
      onDeleted={onDeleted}
      restoreFocusRef={restoreFocusRef}
      onDirtyChange={onDirtyChange}
    />
  );
}

// 기존 관계 편집 폼 — 별도 함수로 분리한 이유: relation이 non-null로 좁혀진 상태에서 훅(useAutosaveText)을
// 호출해야 하는데, 위 RelationInspector 본문에서 조기 return 뒤에 훅을 호출하면 렌더마다 훅 호출 개수가
// 달라져 react-hooks 규칙을 위반한다(생성 폼과 편집 폼은 같은 렌더에서 동시에 존재하지 않는다).
// schema는 트리플 중복 검사(validateTripleUniqueness)에 필요하다(리뷰 I-1) — 관계명을 리네임할 때
// 서버(OntologyElementService)도 PATCH에서 중복을 거부하는데, 로컬에서 막지 않으면 실패한 PATCH가
// useAutosaveText의 committed를 prevServerValue로 되돌리고 draft는 중복 이름 그대로 남아 그 필드가
// 영구 dirty가 된다(매 blur/디바운스마다 같은 PATCH가 재발사).
function EditRelationForm({
  schema,
  relation,
  mutations,
  onDeleted,
  restoreFocusRef,
  onDirtyChange,
}: {
  schema: OntologySchema;
  relation: Triple & { id: number };
  mutations: OntologyElementMutations;
  onDeleted?: () => void;
  restoreFocusRef?: React.RefObject<HTMLElement | null>;
  onDirtyChange?: ReportDirty;
}) {
  const nameField = useAutosaveText(
    relation.relation,
    (value) => mutations.updateRelation(relation.id, { relation: value }),
    // blank 차단 다음에 중복 차단 — 끝점(주어/목적어)은 이 폼에서 고정이므로 트리플 중 관계명만
    // 바뀐다. excludeId로 관계 자기 자신을 목록에서 빼야 이름을 바꾸지 않는 편집(원래 이름 재입력)이
    // 스스로와 충돌해 중복으로 오진단되지 않는다.
    (value) =>
      validateRelationName(value, relation.subject, relation.object) ??
      validateTripleUniqueness(schema.relations, relation.subject, value, relation.object, relation.id),
    true, // trim(리뷰 M-7) — EntityInspector M-1과 동일한 이유: 검증·중복 비교·전송을 같은 정규화된 값으로.
  );
  const descriptionField = useAutosaveText(relation.description, (value) =>
    mutations.updateRelation(relation.id, { description: value }),
  );

  // (#484) 이름/설명 필드 중 하나라도 dirty면 부모에 보고하고, 언마운트 시(다른 관계 선택으로
  // key={relation.id}가 바뀌거나 다른 종류 선택/생성 폼 전환) false로 되돌려 유령 dirty를 막는다
  // (EntityInspector의 EditEntityForm과 동일한 패턴).
  const isAnyDirty = nameField.isDirty || descriptionField.isDirty;
  useEffect(() => {
    onDirtyChange?.(isAnyDirty);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAnyDirty]);
  useEffect(() => () => onDirtyChange?.(false), [onDirtyChange]);

  const nameErrorId = `relation-name-error-${relation.id}`;

  return (
    <div className="flex flex-col gap-4" data-testid="relation-inspector">
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`relation-endpoint-subject-${relation.id}`}>주어 타입</Label>
        {/* 읽기 전용 — Input이 아니라 정적 텍스트로 렌더해 편집 가능하다는 인상을 주지 않는다. */}
        <p
          id={`relation-endpoint-subject-${relation.id}`}
          className="rounded-md border bg-muted px-3 py-2 text-sm"
          data-testid="relation-endpoint-subject"
        >
          {relation.subject}
        </p>
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`relation-endpoint-object-${relation.id}`}>목적어 타입</Label>
        <p
          id={`relation-endpoint-object-${relation.id}`}
          className="rounded-md border bg-muted px-3 py-2 text-sm"
          data-testid="relation-endpoint-object"
        >
          {relation.object}
        </p>
      </div>

      <p className="text-xs text-muted-foreground" data-testid="relation-endpoint-hint">
        끝점(주어/목적어)은 수정할 수 없습니다. 다른 끝점으로 바꾸려면 이 관계를 삭제하고 새로 만드세요.
      </p>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`relation-name-${relation.id}`}>관계명</Label>
        <Input
          id={`relation-name-${relation.id}`}
          aria-invalid={nameField.error ? true : undefined}
          aria-describedby={nameField.error ? nameErrorId : undefined}
          value={nameField.value}
          onChange={(e) => nameField.onChange(e.target.value)}
          onBlur={nameField.onBlur}
        />
        {nameField.error && (
          <p id={nameErrorId} className="text-xs text-destructive">
            {nameField.error}
          </p>
        )}
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`relation-description-${relation.id}`}>설명</Label>
        <Input
          id={`relation-description-${relation.id}`}
          value={descriptionField.value}
          onChange={(e) => descriptionField.onChange(e.target.value)}
          onBlur={descriptionField.onBlur}
        />
      </div>

      {/* FK CASCADE로 함께 사라지는 다른 요소는 없지만(엔티티 타입 삭제와 달리 파급 목록은 없다),
          실수 클릭 시 확인 절차·복구 수단이 전혀 없다는 결함(#419)을 막기 위해 범용
          DeleteConfirmDialog(엔티티 타입 삭제의 DeleteTypeConfirm과 같은 AlertDialog 패턴)를
          적용한다. 커스텀 본문(함께 지워질 목록)이 필요 없으므로 전용 컴포넌트 대신 범용을 그대로 쓴다. */}
      <DeleteConfirmDialog
        entityName="관계"
        itemName={`${relation.subject} → ${relation.relation} → ${relation.object}`}
        onConfirm={() =>
          void mutations.deleteRelation(relation.id).then((result) => {
            // 포커스를 먼저 옮긴 뒤 onDeleted를 호출한다(I-1, S2 최종 리뷰) — EntityInspector의
            // 타입 삭제(#328류, restoreFocusRef)와 동일한 순서. onDeleted가 먼저 실행되면 부모가
            // 선택을 비워 이 버튼 자신이 즉시 언마운트되고, 그 뒤에 focus()를 불러도 대상이 이미
            // 사라져 포커스가 <body>로 떨어진다.
            if (result) {
              restoreFocusRef?.current?.focus();
              onDeleted?.();
            }
          })
        }
        trigger={
          <Button variant="outline" size="sm" className="gap-1.5 text-destructive" data-testid="relation-delete-trigger">
            <Trash2 className="h-3.5 w-3.5" />
            관계 삭제
          </Button>
        }
      />
    </div>
  );
}
