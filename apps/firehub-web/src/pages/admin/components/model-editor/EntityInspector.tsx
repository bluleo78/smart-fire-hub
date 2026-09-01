import { Plus, Trash2 } from 'lucide-react';
import { useRef, useState } from 'react';

import { Button } from '@/components/ui/button';
import { DeleteConfirmDialog } from '@/components/ui/delete-confirm-dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Textarea } from '@/components/ui/textarea';
import type { OntologyElementMutations } from '@/hooks/queries/useOntologyElement';
import {
  affectedRelationsFor,
  DATA_TYPES,
  isLastActiveEntityType,
  RESOLUTIONS,
  validateEntityTypeName,
  validatePropertyName,
} from '@/lib/ontology-validation';
import type { EntityTypeDef, OntologySchema, OntologyStatus, Property } from '@/types/ontology';

import DeleteTypeConfirm from './DeleteTypeConfirm';
import { useAutosaveText } from './useAutosaveText';

interface Props {
  schema: OntologySchema;
  // entity=null이면 "새 타입 만들기" 폼을 보여준다(ModelOutline의 "타입 추가" 버튼이 진입점,
  // RelationInspector의 relation=null 패턴과 대칭). 기존 타입 선택은 ModelOutline이 이미 id 없는
  // 엔티티 타입을 걸러내므로 항상 id를 가진다.
  entity: (EntityTypeDef & { id: number }) | null;
  // OntologyPage가 한 번만 호출한 useOntologyElementMutations의 반환값 — 여기서 훅을 다시
  // 호출하면 saveState가 갈라져 툴바 칩이 조용히 멈춘다(Task 4 리뷰 IMP-1).
  mutations: OntologyElementMutations;
  // 타입 삭제 성공 시 호출 — 부모가 선택 상태를 비워 더 이상 존재하지 않는 타입을 계속 지목하지 않게 한다.
  onDeleted?: () => void;
  // 생성 성공 시 호출 — 부모가 새로 만들어진 타입을 선택 상태로 전환한다(RelationInspector와 대칭).
  onCreated?: (entityTypeId: number) => void;
  // 생성 폼을 취소했을 때 호출 — 부모가 "생성 중" 상태를 끈다.
  onCancel?: () => void;
  // 타입 삭제 확인 다이얼로그를 닫을 때 포커스를 돌려받을 대상(M-2, Task 6 리뷰) — 삭제 트리거
  // 자신이 삭제 성공과 함께 사라지므로 DeleteTypeConfirm.tsx로 그대로 전달한다.
  restoreFocusRef?: React.RefObject<HTMLElement | null>;
  // 선택된 온톨로지의 생명주기 상태(M-4.2, S2 최종 리뷰) — active 온톨로지의 마지막 엔티티 타입은
  // 서버(OntologyElementService.deleteEntityType)가 "엔티티 타입은 최소 1개 이상이어야 합니다."
  // 400으로 거부한다. 로컬에서 이 조건을 몰라 삭제 트리거를 그대로 활성화해 두면 이 파일 전역
  // 제약(서버 문구가 정상 사용에 노출되면 안 된다)을 어긴다. 생성 폼(entity===null)에는 필요 없다.
  status?: OntologyStatus;
}

interface PropertyRowProps {
  entityType: string;
  property: Property & { id: number };
  existingNames: string[];
  // 반환값(성공 시 데이터, 실패 시 undefined)을 그대로 되돌려줘야 한다 — useAutosaveText의 commit()이
  // 이 값을 보고 실패한 편집을 dirty로 되돌린다(Task 4 리뷰 C-1). void로 삼켜버리면 실패가 안 보인다.
  onUpdate: (req: {
    name?: string;
    description?: string;
    dataType?: 'text' | 'number' | 'date';
    unit?: string;
  }) => unknown;
  onDelete: () => void;
}

// 속성 한 행 — 이름/타입/단위/설명을 각각 독립적으로 자동 저장한다. 배열 안에서 훅을 쓰므로
// 반드시 별도 컴포넌트로 분리해야 한다(같은 렌더에서 훅 호출 횟수가 배열 길이에 따라 달라지면
// react-hooks 규칙 위반) — key={property.id}로 속성이 추가/삭제될 때만 마운트/언마운트된다.
function PropertyRow({ entityType, property, existingNames, onUpdate, onDelete }: PropertyRowProps) {
  const nameField = useAutosaveText(
    property.name,
    (name) => onUpdate({ name }),
    (name) => validatePropertyName(name, entityType, existingNames, property.name),
    true, // trim(M-1) — 검증(validatePropertyName)의 예약어/중복 비교가 trim된 값 기준이므로 전송도 맞춘다.
  );
  // 단위는 PATCH 3-값 의미론(types/ontology.ts의 UpdatePropertyRequest 주석 참고: null=변경 없음,
  // ''=지우기)이라 빈 문자열을 그대로 커밋해야 "지우기"가 된다 — 다른 텍스트 필드처럼 값 그대로
  // onUpdate에 실어 보낸다.
  const unitField = useAutosaveText(property.unit ?? '', (unit) => onUpdate({ unit }));
  const descriptionField = useAutosaveText(property.description, (description) => onUpdate({ description }));

  const nameErrorId = `property-name-error-${property.id}`;

  return (
    <div className="flex flex-col gap-1.5 rounded border p-2" data-testid={`property-row-${property.id}`}>
      <div className="flex items-center gap-1.5">
        <Input
          aria-label={`${entityType} 속성 이름`}
          aria-invalid={nameField.error ? true : undefined}
          aria-describedby={nameField.error ? nameErrorId : undefined}
          placeholder="속성명"
          value={nameField.value}
          onChange={(e) => nameField.onChange(e.target.value)}
          onBlur={nameField.onBlur}
          className="flex-1"
        />
        <Select
          value={property.dataType ?? 'text'}
          onValueChange={(v: 'text' | 'number' | 'date') => onUpdate({ dataType: v })}
        >
          <SelectTrigger aria-label={`${entityType} 속성 타입`} className="w-24">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {DATA_TYPES.map((dt) => (
              <SelectItem key={dt} value={dt}>
                {dt}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Input
          aria-label={`${entityType} 속성 단위`}
          placeholder="단위"
          value={unitField.value}
          onChange={(e) => unitField.onChange(e.target.value)}
          onBlur={unitField.onBlur}
          className="w-20"
        />
        {/* 확인 없이 즉시 삭제되던 결함(#419) — 엔티티 타입 삭제(DeleteTypeConfirm)와 동일한
            AlertDialog 패턴을 범용 DeleteConfirmDialog로 적용한다. 속성 삭제는 FK CASCADE 파급이
            없어(커스텀 목록 불필요) 전용 컴포넌트 대신 범용을 그대로 쓴다. */}
        <DeleteConfirmDialog
          entityName="속성"
          itemName={`${entityType}.${property.name}`}
          onConfirm={onDelete}
          trigger={
            <Button
              variant="ghost"
              size="icon"
              className="h-8 w-8 shrink-0"
              aria-label={`${entityType} 속성 삭제`}
            >
              <Trash2 className="h-3.5 w-3.5" />
            </Button>
          }
        />
      </div>
      <Input
        aria-label={`${entityType} 속성 설명`}
        placeholder="설명"
        value={descriptionField.value}
        onChange={(e) => descriptionField.onChange(e.target.value)}
        onBlur={descriptionField.onBlur}
      />
      {nameField.error && (
        <p id={nameErrorId} className="text-xs text-destructive">
          {nameField.error}
        </p>
      )}
    </div>
  );
}

// 새 엔티티 타입 생성 폼 — RelationInspector의 CreateRelationForm과 대칭. 전체 문서 모달이 유일한
// 생성 경로였는데(Task 6에서 제거), 그 자리를 대체한다. 관계와 달리 끝점 개념이 없어 필드는 편집
// 폼과 거의 같다 — 다만 자동 저장 대상이 아니라(아직 서버에 없는 대상을 PATCH할 수 없다) 명시적
// "타입 만들기" 버튼으로만 커밋된다.
function CreateEntityTypeForm({
  schema,
  mutations,
  onCreated,
  onCancel,
}: {
  schema: OntologySchema;
  mutations: OntologyElementMutations;
  onCreated?: (entityTypeId: number) => void;
  onCancel?: () => void;
}) {
  const existingTypeNames = schema.entities.map((e) => e.type);
  const [type, setType] = useState('');
  const [description, setDescription] = useState('');
  const [naming, setNaming] = useState('');
  const [resolution, setResolution] = useState<'embedding' | 'exact'>('embedding');
  const [nameError, setNameError] = useState<string | null>(null);
  // 제출 중 버튼을 잠근다(RelationInspector M-5와 동일한 이유) — 연타 시 같은 이름으로 두 번
  // POST되면 두 번째는 첫 번째가 만든 타입과 이름이 충돌해 400이 뜬다.
  const [submitting, setSubmitting] = useState(false);

  const handleCreate = async () => {
    setNameError(null);
    // trim(EntityInspector M-1과 동일한 이유) — validateEntityTypeName도 trim 기준으로 검증하므로
    // 전송도 맞춘다.
    const trimmedType = type.trim();
    const error = validateEntityTypeName(trimmedType, existingTypeNames);
    if (error) {
      setNameError(error);
      return;
    }
    setSubmitting(true);
    try {
      const result = await mutations.addEntityType({ type: trimmedType, description, naming, resolution });
      if (result?.entityType.id != null) {
        onCreated?.(result.entityType.id);
      }
    } finally {
      setSubmitting(false);
    }
  };

  const errorId = 'new-entity-type-error';

  return (
    <div className="flex flex-col gap-4" data-testid="entity-inspector-create">
      <p className="text-sm font-medium">새 타입 만들기</p>

      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          void handleCreate();
        }}
      >
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="new-entity-type-name">타입 이름</Label>
          <Input
            id="new-entity-type-name"
            aria-invalid={nameError ? true : undefined}
            aria-describedby={nameError ? errorId : undefined}
            value={type}
            onChange={(e) => {
              setType(e.target.value);
              setNameError(null);
            }}
          />
          {nameError && (
            <p id={errorId} className="text-xs text-destructive">
              {nameError}
            </p>
          )}
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="new-entity-description">설명</Label>
          <Textarea id="new-entity-description" value={description} onChange={(e) => setDescription(e.target.value)} />
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="new-entity-naming">명명 규칙</Label>
          <Textarea id="new-entity-naming" value={naming} onChange={(e) => setNaming(e.target.value)} />
        </div>

        <div className="flex flex-col gap-1.5">
          {/* aria-labelledby로 이름을 직접 못박는다(I-3, S2 최종 리뷰) — htmlFor만으로는 "<label
              for>가 button(SelectTrigger)을 이름 짓는가"가 브라우저별 검증 없는 채로 유일한 이름
              원천이 된다(RelationInspector의 EndpointSelect와 동일한 이유). */}
          <Label id="new-entity-resolution-label" htmlFor="new-entity-resolution">
            해상도 정책
          </Label>
          <Select value={resolution} onValueChange={(v: 'embedding' | 'exact') => setResolution(v)}>
            <SelectTrigger id="new-entity-resolution" aria-labelledby="new-entity-resolution-label">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {RESOLUTIONS.map((r) => (
                <SelectItem key={r} value={r}>
                  {r === 'exact' ? '정확 매칭' : '임베딩 해소'}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="flex gap-1.5">
          <Button type="submit" size="sm" className="gap-1.5" disabled={submitting}>
            <Plus className="h-3.5 w-3.5" />
            타입 만들기
          </Button>
          <Button type="button" variant="outline" size="sm" onClick={onCancel} disabled={submitting}>
            취소
          </Button>
        </div>
      </form>
    </div>
  );
}

// 편집 모드 우측 인스펙터 — 선택된 엔티티 타입의 필드와 속성을 편집한다. 필드마다 독립적으로
// 자동 저장하며(문서 전체를 왕복시키던 모달과 달리), 저장 상태는 툴바의 SaveStatusChip이 보여준다.
// entity===null이면 생성 폼(CreateEntityTypeForm)으로 분기한다(RelationInspector와 동일한 패턴) —
// 훅 호출 개수가 렌더마다 달라지면 안 되므로(react-hooks 규칙) 편집 전용 훅은 별도 함수(EditEntityForm)
// 안에서만 호출한다.
export default function EntityInspector({
  schema,
  entity,
  mutations,
  onDeleted,
  onCreated,
  onCancel,
  restoreFocusRef,
  status,
}: Props) {
  if (entity === null) {
    return <CreateEntityTypeForm schema={schema} mutations={mutations} onCreated={onCreated} onCancel={onCancel} />;
  }
  return (
    <EditEntityForm
      schema={schema}
      entity={entity}
      mutations={mutations}
      onDeleted={onDeleted}
      restoreFocusRef={restoreFocusRef}
      status={status}
    />
  );
}

// ontologyId는 prop으로 받지 않는다(Task 4 리뷰 M-4) — mutations가 이미 특정 ontologyId로 바인딩된
// 채로 내려오는데, 별도 ontologyId prop을 함께 두면 둘이 서로 다른 id를 가리켜도(호출부 실수) 타입이
// 이를 걸러내지 못한다. mutations 하나만 진실의 원천으로 둔다.
function EditEntityForm({
  schema,
  entity,
  mutations,
  onDeleted,
  restoreFocusRef,
  status,
}: {
  schema: OntologySchema;
  entity: EntityTypeDef & { id: number };
  mutations: OntologyElementMutations;
  onDeleted?: () => void;
  restoreFocusRef?: React.RefObject<HTMLElement | null>;
  status?: OntologyStatus;
}) {
  const existingTypeNames = schema.entities.map((e) => e.type);

  const typeField = useAutosaveText(
    entity.type,
    (type) => mutations.updateEntityType(entity.id, { type }),
    (type) => validateEntityTypeName(type, existingTypeNames, entity.type),
    true, // trim(M-1) — validateEntityTypeName도 trim 기준으로 검증하므로 전송도 맞춘다.
  );
  const descriptionField = useAutosaveText(entity.description, (description) =>
    mutations.updateEntityType(entity.id, { description }),
  );
  const namingField = useAutosaveText(entity.naming, (naming) => mutations.updateEntityType(entity.id, { naming }));

  // 새 속성 추가 폼 — 자동 저장 대상이 아니라 명시적 버튼 클릭으로만 커밋되는 별도 상태.
  const [newProp, setNewProp] = useState({ name: '', dataType: 'text' as 'text' | 'number' | 'date', unit: '', description: '' });
  const [newPropError, setNewPropError] = useState<string | null>(null);
  // 제출 중 버튼을 잠근다(I-2, S2 최종 리뷰) — CreateEntityTypeForm/CreateRelationForm은 이 가드를
  // 이미 갖고 있는데(리뷰 M-5) 이 폼만 빠져 있었다. 로컬 중복 검사만으로는 연타를 막지 못한다:
  // 두 번째 클릭 시점의 entity.properties는 첫 번째 응답이 아직 캐시를 갱신하기 전이라 새 이름이
  // 목록에 없어 검증을 통과하고, 서버가 "중복된 속성명(...)" 400을 내 이 파일 전역 제약(서버 문구가
  // 정상 사용에 노출되면 안 된다)을 실제로 깬다.
  const [addingProperty, setAddingProperty] = useState(false);
  // 속성 삭제 성공 시 포커스를 옮길 대상(I-1, S2 최종 리뷰) — PropertyRow가 언마운트되면서 삭제
  // 버튼 자신이 사라지는데, 포커스를 옮기는 코드가 전혀 없어 <body>로 떨어졌다. 삭제된 행 대신
  // 이 폼을 계속 대표하는 안정적인 위치(속성 추가 버튼)로 되돌린다.
  const addPropertyButtonRef = useRef<HTMLButtonElement>(null);
  // addPropertyButtonRef의 백업 대상(N-1, S2 최종 리뷰) — I-1(포커스 복귀)과 I-2(이중 제출 가드)가
  // 같은 버튼을 겨냥하면서 생긴 상호작용: 속성 추가가 진행 중(addingProperty)일 때 다른 속성의
  // 삭제가 성공하면 addPropertyButtonRef는 disabled라 focus()가 no-op이 되어 포커스가 다시 <body>로
  // 떨어진다 — I-1이 막으려던 바로 그 회귀다. "속성" 섹션 라벨은 절대 disabled되지 않으므로
  // tabIndex=-1로 프로그램적 포커스만 허용해 안전망으로 둔다.
  const propertiesLabelRef = useRef<HTMLLabelElement>(null);

  const handleAddProperty = async () => {
    const name = newProp.name.trim();
    const error = validatePropertyName(name, entity.type, entity.properties.map((p) => p.name));
    if (error) {
      setNewPropError(error);
      return;
    }
    setNewPropError(null);
    setAddingProperty(true);
    try {
      const result = await mutations.addProperty(entity.id, {
        name,
        description: newProp.description,
        dataType: newProp.dataType,
        unit: newProp.unit.trim() || null,
      });
      if (result) {
        setNewProp({ name: '', dataType: 'text', unit: '', description: '' });
      }
    } finally {
      setAddingProperty(false);
    }
  };

  // 이 타입을 끝점으로 쓰는 관계 — 삭제 확인 다이얼로그가 "함께 지워질 관계"로 보여준다(FK CASCADE).
  // OntologyPage(캔버스 Delete 키 삭제)와 계산이 완전히 같아 ontology-validation.ts로 옮겼다(리뷰 M-3).
  const affectedRelations = affectedRelationsFor(schema, entity.id);

  const typeErrorId = `entity-type-name-error-${entity.id}`;
  // active 온톨로지의 마지막 엔티티 타입인지(M-4.2, S2 최종 리뷰) — 서버가 이 조합을 400으로
  // 거부하므로(OntologyElementService.deleteEntityType) 삭제 트리거를 미리 비활성화해 그 400
  // 문구가 정상 사용 경로에 노출되지 않게 한다. OntologyPage와 조건이 같아 ontology-validation.ts로
  // 옮겼다(리뷰 M-3).
  const isLastActiveType = isLastActiveEntityType(schema, status);

  return (
    <div className="flex flex-col gap-4" data-testid="entity-inspector">
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`entity-type-${entity.id}`}>타입 이름</Label>
        <Input
          id={`entity-type-${entity.id}`}
          aria-invalid={typeField.error ? true : undefined}
          aria-describedby={typeField.error ? typeErrorId : undefined}
          value={typeField.value}
          onChange={(e) => typeField.onChange(e.target.value)}
          onBlur={typeField.onBlur}
        />
        {typeField.error && (
          <p id={typeErrorId} className="text-xs text-destructive">
            {typeField.error}
          </p>
        )}
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`entity-description-${entity.id}`}>설명</Label>
        <Textarea
          id={`entity-description-${entity.id}`}
          value={descriptionField.value}
          onChange={(e) => descriptionField.onChange(e.target.value)}
          onBlur={descriptionField.onBlur}
        />
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`entity-naming-${entity.id}`}>명명 규칙</Label>
        <Textarea
          id={`entity-naming-${entity.id}`}
          value={namingField.value}
          onChange={(e) => namingField.onChange(e.target.value)}
          onBlur={namingField.onBlur}
        />
      </div>

      <div className="flex flex-col gap-1.5">
        {/* aria-labelledby(I-3, S2 최종 리뷰) — new-entity-resolution과 동일한 이유. */}
        <Label id={`entity-resolution-label-${entity.id}`} htmlFor={`entity-resolution-${entity.id}`}>
          해상도 정책
        </Label>
        <Select
          value={entity.resolution}
          onValueChange={(v: 'embedding' | 'exact') => mutations.updateEntityType(entity.id, { resolution: v })}
        >
          <SelectTrigger id={`entity-resolution-${entity.id}`} aria-labelledby={`entity-resolution-label-${entity.id}`}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {RESOLUTIONS.map((r) => (
              <SelectItem key={r} value={r}>
                {r === 'exact' ? '정확 매칭' : '임베딩 해소'}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="flex flex-col gap-2">
        <div className="flex items-center justify-between">
          <Label ref={propertiesLabelRef} tabIndex={-1}>
            속성
          </Label>
          <Button
            ref={addPropertyButtonRef}
            variant="outline"
            size="sm"
            className="h-7 gap-1 px-2 text-xs"
            onClick={handleAddProperty}
            disabled={addingProperty}
            aria-label={`${entity.type} 속성 추가`}
          >
            <Plus className="h-3.5 w-3.5" />
            속성 추가
          </Button>
        </div>

        {entity.properties
          .filter((p): p is Property & { id: number } => p.id != null)
          .map((prop) => (
            <PropertyRow
              key={prop.id}
              entityType={entity.type}
              property={prop}
              existingNames={entity.properties.map((p) => p.name)}
              onUpdate={(req) => mutations.updateProperty(entity.id, prop.id, req)}
              onDelete={() =>
                void mutations.deleteProperty(entity.id, prop.id).then((result) => {
                  // 삭제 성공 시에만 옮긴다 — 실패하면 행이 그대로 남아 있으므로 포커스를 옮길
                  // 이유가 없다(I-1, S2 최종 리뷰).
                  if (!result) return;
                  // 속성 추가가 진행 중이면 addPropertyButtonRef는 disabled라 focus()가 no-op이다
                  // (N-1) — 그 경우 항상 활성인 propertiesLabelRef로 대신 옮긴다.
                  if (addPropertyButtonRef.current && !addPropertyButtonRef.current.disabled) {
                    addPropertyButtonRef.current.focus();
                  } else {
                    propertiesLabelRef.current?.focus();
                  }
                })
              }
            />
          ))}

        {/* 새 속성 추가 폼 — 예약어/중복은 API 호출 없이 여기서 막는다(#329 이식: aria-invalid/describedby). */}
        <div className="flex flex-col gap-1.5 rounded border border-dashed p-2" data-testid="add-property-form">
          <div className="flex items-center gap-1.5">
            <Input
              aria-label="새 속성 이름"
              aria-invalid={newPropError ? true : undefined}
              aria-describedby={newPropError ? 'new-property-error' : undefined}
              placeholder="속성명"
              value={newProp.name}
              onChange={(e) => {
                setNewProp((p) => ({ ...p, name: e.target.value }));
                // 이전 검증 실패 표시가 이름을 고친 뒤에도 다음 "속성 추가" 클릭까지 남아있지
                // 않도록 입력이 바뀌는 즉시 지운다(Task 4 리뷰 M-2).
                setNewPropError(null);
              }}
              className="flex-1"
            />
            <Select
              value={newProp.dataType}
              onValueChange={(v: 'text' | 'number' | 'date') => setNewProp((p) => ({ ...p, dataType: v }))}
            >
              <SelectTrigger aria-label="새 속성 타입" className="w-24">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {DATA_TYPES.map((dt) => (
                  <SelectItem key={dt} value={dt}>
                    {dt}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Input
              aria-label="새 속성 단위"
              placeholder="단위(선택)"
              value={newProp.unit}
              onChange={(e) => setNewProp((p) => ({ ...p, unit: e.target.value }))}
              className="w-20"
            />
          </div>
          <Input
            aria-label="새 속성 설명"
            placeholder="설명(선택)"
            value={newProp.description}
            onChange={(e) => setNewProp((p) => ({ ...p, description: e.target.value }))}
          />
          {newPropError && (
            <p id="new-property-error" className="text-xs text-destructive">
              {newPropError}
            </p>
          )}
        </div>
      </div>

      <DeleteTypeConfirm
        entity={entity}
        affectedRelations={affectedRelations}
        restoreFocusRef={restoreFocusRef}
        onConfirm={() => {
          void mutations.deleteEntityType(entity.id).then((result) => {
            if (result) {
              // 포커스를 여기서 명시적으로 옮긴다(NEW-1, Task 6 리뷰 라운드2) — 응답이 빠르면
              // (AlertDialogContent의 exit 애니메이션이 끝나기 전에 뮤테이션이 이미 성공하면),
              // 다이얼로그 close 시점의 자동 복귀가 트리거로 성공한 직후 트리거 자신이 onDeleted로
              // 곧장 사라져 포커스가 <body>로 떨어진다(#328류) — restoreFocusRef 안전망(M-2)이
              // 결국 스스로 복구하긴 하지만(관측상 최대 ~1초 지연), 그 사이의 <body> 유실 구간
              // 자체가 회귀다. 뮤테이션이 실제로 끝난 시점에 맞춰 여기서 곧바로 포커스를 옮기면
              // 응답 속도와 무관하게 그 구간 자체가 생기지 않는다. restoreFocusRef는 안전망으로
              // 그대로 둔다(예: onDeleted가 없어 인스펙터가 안 바뀌는 등 이 경로 밖의 케이스).
              restoreFocusRef?.current?.focus();
              onDeleted?.();
            }
          });
        }}
        trigger={
          <Button
            variant="outline"
            size="sm"
            className="gap-1.5 text-destructive"
            data-testid="entity-delete-trigger"
            disabled={isLastActiveType}
          >
            <Trash2 className="h-3.5 w-3.5" />
            타입 삭제
          </Button>
        }
      />
      {/* disabled 버튼은 pointer-events-none이라(components/ui/button.tsx) title 툴팁이 hover로도
          안 뜨고, disabled 요소는 포커스도 못 받아 키보드/스크린리더 사용자도 이유를 알 방법이
          없었다(N-2, S2 최종 리뷰) — title 대신 눈에 보이는 문구로 옮긴다. */}
      {isLastActiveType && (
        <p className="text-xs text-muted-foreground" data-testid="entity-delete-disabled-reason">
          활성 온톨로지는 엔티티 타입이 최소 1개 있어야 합니다.
        </p>
      )}
    </div>
  );
}
