import { Check, Pencil, Plus, Trash2, X } from 'lucide-react';
import { useState } from 'react';
import { toast } from 'sonner';

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Textarea } from '@/components/ui/textarea';
import { useUpdateOntologyById } from '@/hooks/queries/useOntology';
import { extractApiError, isConflictError } from '@/lib/api-error';
import type { EntityTypeDef, OntologySchema, Property, Triple, TypeRename } from '@/types/ontology';

interface OntologyEditDialogProps {
  schema: OntologySchema;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /**
   * 편집 대상 온톨로지 id. 항상 id 스코프 PUT(/ontology/{id})을 쓴다 — 유일한 호출부(OntologyPage)가
   * selectedSchema(=useOntologyById(effectiveOntologyId), enabled: id != null)와 함께 마운트하므로
   * 이 컴포넌트가 렌더될 때 id는 항상 존재한다. 전역 PUT(/ontology, id=1 고정)은 API 하위호환을
   * 위해 백엔드에는 남아 있지만 이 화면은 더 이상 쓰지 않는다.
   */
  ontologyId: number;
}

// Neo4j 노드 예약 필드(loader.ts 모델) — 속성명으로 쓰면 적재 시 노드 정체성 필드가 깨진다.
// api OntologyService.RESERVED_PROPERTY_NAMES와 동일한 목록(서비스 경계상 공유 불가, 수동 동기화).
const RESERVED_PROPERTY_NAMES = new Set(['key', 'type', 'name', 'sourceChunkIds', 'schemaVersion']);
const DATA_TYPES: Array<'text' | 'number' | 'date'> = ['text', 'number', 'date'];

// 서버가 renames 검증에 실패했을 때 던지는 내부 용어 메시지의 고정 앞부분(#304).
// ErrorResponse에 에러 코드가 없어 message 부분일치로만 식별할 수 있다 — 이 문구가 그대로
// 사용자에게 노출되면 "어떤 편집을 되돌려야 하는지" 알 수 없으므로 사용자 어휘로 바꿔 준다.
const SERVER_RENAME_REUSE_PREFIX = '타입 리네임의 from이';

/**
 * "이름 재사용/맞바꾸기" 조합을 저장 전에 잡아낸다(#304).
 *
 * 서버는 renames를 (from,to) 이름 쌍으로만 표현하므로 이름 교환(A의 이름을 비운 뒤 그 이름을
 * B에 부여)을 나타낼 수 없고, "각 from이 최종 엔티티 타입 목록에 남아 있으면 400"이라는 규칙으로
 * 거부한다. 여기서 서버와 **동일한 술어**를 그대로 계산해, 서버가 어차피 거부할 조합만 정확히
 * 막는다 — 지금까지 저장되던 편집이 새로 막히는 일은 없다.
 *
 * @returns 재사용된 이름(=차단 사유). 문제 없으면 null.
 */
const findReusedRenamedName = (
  nextRenames: Record<string, string>,
  nextTypes: string[],
): string | null => {
  const finalTypes = new Set(nextTypes);
  for (const from of Object.keys(nextRenames)) {
    if (finalTypes.has(from)) return from;
  }
  return null;
};

/** 이름 재사용 차단 안내 — 어떤 이름이 문제인지와 어떻게 하면 되는지를 함께 알린다. */
const reusedNameMessage = (name: string) =>
  `다른 타입이 쓰던 이름은 같은 저장에서 재사용할 수 없습니다: ${name} — 이름 변경을 먼저 저장한 뒤 다시 시도하세요.`;

/**
 * 지식 모델 편집 다이얼로그.
 * 5-1: domain + 엔티티 타입 description/naming/resolution 편집.
 * 5-2: 관계(트리플) CRUD + 엔티티별 속성(property) CRUD 추가 — 이제 relations/properties도
 * 이 컴포넌트가 소유한 state로 편집되며, 저장 시 원본이 아닌 편집된 값을 PUT payload에 담는다.
 * 5-3: 엔티티 타입 자체의 추가/삭제. 추가는 이름을 진입 전에 확정·검증(non-blank·중복)하므로
 * 기존 카드의 `entity.type`을 key·식별자로 쓰는 불변식이 유지된다. 삭제 시 그 타입을 참조하던
 * 관계도 함께 정리(cascade)한다.
 * 5-5: 엔티티 타입 리네임. 리네임 시점에 (원래이름→현재이름) 쌍을 `renames`에 누적하고 관계의
 * subject/object도 즉시 새 이름으로 갱신(cascade-rewrite)한다 — 안정적인 별도 id 없이 "현재
 * 타입 문자열"만으로 대상을 특정하되, 연쇄 리네임(A→B→C)은 원본 키(A)를 유지한 채 값만 갱신해
 * 1건으로 축약한다. 저장 시 `renames`를 payload에 함께 보내 서버(OntologyRepository)가 entity_type_id를
 * 보존하며 UPDATE할 수 있게 한다(5-6부터 Neo4j는 이 힌트와 무관 — entity_type_id 기반 key라 리네임 영향 없음).
 *
 * 폼 state는 useState 초기값으로만 schema를 반영한다(useEffect로 동기화하지 않음) — 대신
 * 호출부(OntologyPage)가 다이얼로그가 열릴 때마다 이 컴포넌트에 key를 바꿔 리마운트시켜
 * 항상 최신 원본으로 새로 시작하게 한다.
 */
export default function OntologyEditDialog({ schema, open, onOpenChange, ontologyId }: OntologyEditDialogProps) {
  const [domain, setDomain] = useState(schema.domain);
  const [entities, setEntities] = useState<EntityTypeDef[]>(schema.entities);
  const [relations, setRelations] = useState<Triple[]>(schema.relations);
  const [newTypeName, setNewTypeName] = useState('');
  // renames: 원래 타입명 → 현재(마지막 편집 시점) 타입명. 저장 시 서버가 이 쌍으로 entity_type_id를 매칭한다.
  const [renames, setRenames] = useState<Record<string, string>>({});
  const [renamingType, setRenamingType] = useState<string | null>(null);
  const [renameDraft, setRenameDraft] = useState('');
  // baseVersion: 이 폼이 근거로 삼은(= 마지막으로 저장 시도한) 스키마 버전. PUT payload에는 항상
  // 이 값을 담는다 — schema prop은 409 후 재조회로 갱신되므로, prop을 그대로 보내면 "최신 버전 +
  // 낡은 내용"이 짝지어져 남의 변경을 소리 없이 덮어쓰는 lost-update가 된다(#301).
  const [baseVersion, setBaseVersion] = useState(schema.schemaVersion);
  const [hasConflict, setHasConflict] = useState(false);
  // 닫기 가드(#303) 확인 다이얼로그 노출 여부.
  const [confirmingClose, setConfirmingClose] = useState(false);
  const updateById = useUpdateOntologyById();
  const isPending = updateById.isPending;

  // ── 닫기 가드(#303) ──
  // 이 폼은 뷰포트 5배 길이라 편집이 크게 누적된다. 실수로 ESC/오버레이/취소를 눌러 전량이
  // 소리 없이 사라지는 것을 막기 위해, 원본과 달라졌을 때(dirty)만 확인을 거친다.
  //
  // baseline은 **마운트 시점에 한 번 얼려서** 잡는다(useState 지연 초기화). schema prop은 409
  // 이후 재조회분으로 리마운트 없이 교체되므로(#301), 살아 있는 prop과 비교하면 기준선이 서버의
  // 최신 내용으로 슬쩍 옮겨가 "편집했는데 dirty가 아님"이 될 수 있다.
  const [pristine] = useState(() =>
    JSON.stringify({
      domain: schema.domain,
      entities: schema.entities,
      relations: schema.relations,
      renames: {} as Record<string, string>,
    }),
  );
  // 진행 중인 newTypeName/renameDraft 입력값은 dirty에 넣지 않는다 — 확정 전 임시값이다.
  const isDirty = JSON.stringify({ domain, entities, relations, renames }) !== pristine;

  /**
   * 모든 닫기 경로(ESC·오버레이 클릭·X 버튼·푸터 취소)의 단일 진입점.
   * dirty면 닫지 않고 확인 다이얼로그만 띄우고, 아니면 즉시 닫는다.
   */
  const requestClose = () => {
    // 저장 진행 중에는 닫기 요청 자체를 무시한다(푸터 버튼 disabled와 같은 취지 —
    // ESC/오버레이는 disabled의 보호를 받지 못하므로 여기서 막는다).
    if (isPending) return;
    if (isDirty) {
      setConfirmingClose(true);
      return;
    }
    onOpenChange(false);
  };

  // 확인 시: 폼 리셋 코드는 두지 않는다 — OntologyPage가 열 때마다 key로 리마운트하므로
  // 재진입은 이미 원본 상태다(위 JSDoc 참조).
  const confirmDiscardAndClose = () => {
    setConfirmingClose(false);
    onOpenChange(false);
  };

  // 409 후 useUpdateOntologyById가 ['ontology', id]를 무효화 → 재조회분이 (리마운트 없이) prop으로
  // 도착하면 이 값이 baseVersion보다 커진다. 그때서야 "덮어쓰고 저장"이 의미를 가진다.
  const latestVersion = schema.schemaVersion;
  const isLatestLoaded = hasConflict && latestVersion !== baseVersion;

  const entityTypes = entities.map((e) => e.type);

  const updateEntity = (type: string, patch: Partial<EntityTypeDef>) =>
    setEntities((prev) => prev.map((e) => (e.type === type ? { ...e, ...patch } : e)));

  // ── 엔티티 타입 추가/삭제(5-3) ──
  // 추가: 이름을 진입 전에 non-blank·중복 검증 → 통과 시에만 append. 이로써 배열의 모든 타입이
  // 항상 유효·고유해 entity.type을 key/식별자로 쓰는 불변식이 유지된다(리네임(5-5)도 확정 전
  // 동일한 중복 검증을 거치므로 이 불변식을 깨지 않는다).
  const addEntityType = () => {
    const name = newTypeName.trim();
    if (!name) {
      toast.error('타입 이름을 입력하세요.');
      return;
    }
    if (entityTypes.includes(name)) {
      toast.error(`이미 존재하는 타입입니다: ${name}`);
      return;
    }
    // 리네임으로 비워진 이름을 새 타입 이름으로 재사용하는 것도 서버 renames 검증에 걸린다(#304).
    const reused = findReusedRenamedName(renames, [...entityTypes, name]);
    if (reused) {
      toast.error(reusedNameMessage(reused));
      return;
    }
    setEntities((prev) => [
      ...prev,
      { type: name, description: '', naming: '', resolution: 'embedding', properties: [] },
    ]);
    setNewTypeName('');
  };

  // 삭제: 타입을 제거하고, 그 타입을 subject/object로 참조하던 관계도 함께 정리(cascade)한다.
  // 정리하지 않으면 서버 5-2 참조 무결성 검증(400)에 걸리므로 클라이언트가 선제 정리한다.
  // renames(5-5)도 함께 정리 — 이 타입이 리네임 결과(현재값)라면 그 엔트리를 지우지 않으면
  // "to가 최종 엔티티 타입에 없다"는 서버 400으로 새어 나가 단순 삭제가 실패로 보인다.
  const removeEntityType = (type: string) => {
    const pruned = relations.filter((r) => r.subject === type || r.object === type).length;
    setEntities((prev) => prev.filter((e) => e.type !== type));
    setRelations((prev) => prev.filter((r) => r.subject !== type && r.object !== type));
    setRenames((prev) => Object.fromEntries(Object.entries(prev).filter(([, cur]) => cur !== type)));
    toast.success(
      pruned > 0
        ? `타입 '${type}'과(와) 이를 참조하는 관계 ${pruned}개를 제거했습니다.`
        : `타입 '${type}'을(를) 제거했습니다.`,
    );
  };

  // ── 엔티티 타입 리네임(5-5) ──
  const startRename = (type: string) => {
    setRenamingType(type);
    setRenameDraft(type);
  };
  const cancelRename = () => setRenamingType(null);

  const confirmRename = () => {
    const currentType = renamingType;
    if (!currentType) return;
    const newName = renameDraft.trim();
    if (!newName) {
      toast.error('타입 이름을 입력하세요.');
      return;
    }
    if (newName === currentType) {
      setRenamingType(null); // 무변경 — no-op 취급
      return;
    }
    if (entityTypes.includes(newName)) {
      toast.error(`이미 존재하는 타입입니다: ${newName}`);
      return;
    }
    // currentType이 이미 어떤 원본의 리네임 결과라면(연쇄 리네임), 그 원본 키를 유지한 채 값만 갱신.
    const originalKey = Object.entries(renames).find(([, cur]) => cur === currentType)?.[0] ?? currentType;
    const nextRenames =
      originalKey === newName
        ? // 원래 이름으로 되돌아옴 — 엔트리 제거(무변경 취급).
          Object.fromEntries(Object.entries(renames).filter(([k]) => k !== originalKey))
        : { ...renames, [originalKey]: newName };
    // 이름 재사용/맞바꾸기(#304)는 서버가 반드시 400으로 거부하므로 확정 시점에 미리 막는다.
    // 되돌리기(originalKey === newName)는 위에서 엔트리가 제거되므로 여기 걸리지 않는다.
    const reused = findReusedRenamedName(
      nextRenames,
      entities.map((e) => (e.type === currentType ? newName : e.type)),
    );
    if (reused) {
      toast.error(reusedNameMessage(reused));
      return;
    }
    setRenames(nextRenames);
    setEntities((prev) => prev.map((e) => (e.type === currentType ? { ...e, type: newName } : e)));
    setRelations((prev) =>
      prev.map((r) => ({
        ...r,
        subject: r.subject === currentType ? newName : r.subject,
        object: r.object === currentType ? newName : r.object,
      })),
    );
    setRenamingType(null);
  };

  // ── 속성(property) CRUD — 엔티티 카드 내부에서 동작, entities state의 해당 엔티티만 갱신 ──
  const addProperty = (type: string) =>
    updateEntity(type, {
      properties: [
        ...(entities.find((e) => e.type === type)?.properties ?? []),
        { name: '', description: '', dataType: 'text', unit: null },
      ],
    });

  const updateProperty = (type: string, index: number, patch: Partial<Property>) => {
    const entity = entities.find((e) => e.type === type);
    if (!entity) return;
    const next = entity.properties.map((p, i) => (i === index ? { ...p, ...patch } : p));
    updateEntity(type, { properties: next });
  };

  const removeProperty = (type: string, index: number) => {
    const entity = entities.find((e) => e.type === type);
    if (!entity) return;
    updateEntity(type, { properties: entity.properties.filter((_, i) => i !== index) });
  };

  // ── 관계(트리플) CRUD ──
  const addRelation = () =>
    setRelations((prev) => [
      ...prev,
      { subject: entityTypes[0] ?? '', relation: '', object: entityTypes[0] ?? '', description: '' },
    ]);

  const updateRelation = (index: number, patch: Partial<Triple>) =>
    setRelations((prev) => prev.map((r, i) => (i === index ? { ...r, ...patch } : r)));

  const removeRelation = (index: number) =>
    setRelations((prev) => prev.filter((_, i) => i !== index));

  // 서버 왕복 없이 즉시 잡을 수 있는 위반만 로컬 검증한다(빈값/예약어/중복) — 참조 무결성은
  // subject/object가 Select로 현재 엔티티 타입만 선택 가능해 UI 단에서부터 보장된다.
  // blank 검사는 반드시 중복 검사보다 먼저 — 빈 이름 2개는 서로 같은 키라서 중복으로 오진단되고,
  // 메시지에 빈 이름이 끼어들어 "중복된 속성명(Building):" 처럼 문장이 잘린다(#302).
  const validateLocally = (): string | null => {
    for (const e of entities) {
      const names = new Set<string>();
      for (const [i, p] of e.properties.entries()) {
        if (!p.name.trim()) {
          return `이름이 비어 있는 속성이 있습니다(${e.type}, ${i + 1}번째 행)`;
        }
        if (RESERVED_PROPERTY_NAMES.has(p.name)) {
          return `예약어는 속성명으로 쓸 수 없습니다(${e.type}): ${p.name}`;
        }
        if (names.has(p.name)) {
          return `중복된 속성명(${e.type}): ${p.name}`;
        }
        names.add(p.name);
      }
    }
    const seenTriples = new Set<string>();
    for (const r of relations) {
      if (!r.relation.trim()) {
        return `관계명을 입력하세요(${r.subject} → ${r.object})`;
      }
      const key = `${r.subject}|${r.relation}|${r.object}`;
      if (seenTriples.has(key)) {
        return `중복된 관계: ${r.subject} -${r.relation}-> ${r.object}`;
      }
      seenTriples.add(key);
    }
    // 이름 재사용(#304) 최종 안전망 — 확정 시점 가드(confirmRename/addEntityType)를 빠져나온
    // 조합이 남아 있어도, 실제 전송 직전의 payload 그대로 검사해 서버 400 대신 원인을 안내한다.
    const reused = findReusedRenamedName(renames, entities.map((e) => e.type));
    if (reused) {
      return reusedNameMessage(reused);
    }
    return null;
  };

  // 저장 실행 — 어느 버전을 근거로 보낼지는 호출부가 정한다(초기 저장=baseVersion,
  // 충돌 복구=재조회된 최신 버전). 충돌 시 편집 state는 그대로 두고 배너만 띄운다.
  const submit = (version: number) => {
    const localError = validateLocally();
    if (localError) {
      toast.error(localError);
      return;
    }
    const renamesPayload: TypeRename[] = Object.entries(renames).map(([from, to]) => ({ from, to }));
    const payload = { domain, schemaVersion: version, entities, relations, renames: renamesPayload };
    const handlers = {
      onSuccess: () => {
        toast.success('지식 모델이 저장되었습니다.');
        onOpenChange(false);
      },
      onError: (error: unknown) => {
        const message = extractApiError(error, '지식 모델 저장에 실패했습니다.');
        // 서버의 내부 검증 용어(`타입 리네임의 from이 ...`)는 편집 화면에서 의미를 전달하지 못하므로
        // 사용자 어휘로 바꿔 노출한다(#304). 그 외 메시지는 서버 문구를 그대로 살린다.
        if (message.startsWith(SERVER_RENAME_REUSE_PREFIX)) {
          // 서버 문구는 `...남아 있습니다: Incident` 형태지만, 이름 부분이 없더라도 내부 용어가
          // 새어 나가지 않도록 콜론이 있을 때만 이름을 뽑고 없으면 이름 없는 안내로 떨어뜨린다.
          const [, reused] = message.split(/:\s*/, 2);
          toast.error(
            reused
              ? reusedNameMessage(reused)
              : '다른 타입이 쓰던 이름은 같은 저장에서 재사용할 수 없습니다 — 이름 변경을 먼저 저장한 뒤 다시 시도하세요.',
          );
        } else {
          toast.error(message);
        }
        if (isConflictError(error)) {
          // 방금 시도한 버전을 기준선으로 확정 — 이후 재조회분이 도착해야 배너 액션이 열린다.
          setBaseVersion(version);
          setHasConflict(true);
        }
      },
    };
    updateById.mutate({ id: ontologyId, req: payload }, handlers);
  };

  const handleSave = () => submit(baseVersion);
  // 충돌 복구: 편집 내용은 유지한 채 재조회된 최신 버전으로만 갈아끼워 재저장한다.
  const handleOverwrite = () => submit(latestVersion);

  return (
    <>
    {/* onOpenChange: DialogContent 기본 X 버튼처럼 preventDefault할 이벤트가 없는 경로까지
        포함해 모든 닫기를 requestClose로 모은다. 여는 방향(next=true)은 그대로 통과. */}
    <Dialog open={open} onOpenChange={(next) => (next ? onOpenChange(true) : requestClose())}>
      {/* flex 3분할(헤더/스크롤 본문/푸터) — DialogContent에서 overflow-y-auto를 걷어내고 본문만
          스크롤시켜 저장·취소 푸터가 스크롤과 무관하게 항상 보이게 한다(#303).
          shadcn DialogContent의 기본 `grid gap-4`를 덮어야 하므로 flex flex-col을 명시한다
          (선례: ReportPreviewDialog.tsx:62). */}
      <DialogContent
        className="flex max-h-[85vh] max-w-2xl flex-col"
        data-testid="ontology-edit-dialog"
        onEscapeKeyDown={(e) => {
          // 타입 리네임 인라인 입력 중 ESC는 리네임만 취소한다(자체 onKeyDown이 처리) —
          // 여기서 preventDefault만 하고 가드를 띄우지 않는다. Radix가 ESC를 캡처 단계에서
          // 듣더라도 입력의 stopPropagation에 의존하지 않고 우선순위가 지켜진다.
          e.preventDefault();
          if (renamingType !== null) return;
          requestClose();
        }}
        onPointerDownOutside={(e) => {
          e.preventDefault();
          requestClose();
        }}
      >
        <DialogHeader>
          <DialogTitle>지식 모델 편집</DialogTitle>
          <DialogDescription>
            엔티티 타입의 추가·삭제·이름 변경, 각 타입의 설명·명명 규칙·해상도·속성, 엔티티 간 관계를 수정합니다.
          </DialogDescription>
        </DialogHeader>

        {/* 스크롤 영역은 이 본문 div 하나 — 헤더/푸터는 형제로 남아 고정된다(#303). */}
        <div className="flex flex-1 flex-col gap-6 overflow-y-auto py-2 pr-1">
          {/* 충돌 복구 배너(#301) — 409 후 최신 스키마 재조회가 끝나면 "덮어쓰고 저장"으로
              편집 내용을 잃지 않고 재저장할 수 있다. 재조회 전에는 안내만 노출(액션 비활성). */}
          {hasConflict && (
            <div
              className="flex flex-col gap-2 rounded-md border border-destructive/40 bg-destructive/5 p-3"
              role="alert"
              data-testid="ontology-conflict-banner"
            >
              <p className="text-sm font-medium">지식 모델이 다른 사용자에 의해 이미 수정되었습니다.</p>
              <p className="text-xs text-muted-foreground">
                {isLatestLoaded
                  ? `최신 버전(v${latestVersion})을 불러왔습니다. 편집 내용을 유지한 채 저장하면 그 사이 반영된 다른 변경을 덮어씁니다.`
                  : '최신 내용을 불러오는 중입니다...'}
              </p>
              <div>
                <Button
                  size="sm"
                  variant="destructive"
                  onClick={handleOverwrite}
                  disabled={!isLatestLoaded || isPending}
                  data-testid="ontology-conflict-overwrite"
                >
                  덮어쓰고 저장
                </Button>
              </div>
            </div>
          )}

          <div className="flex flex-col gap-2">
            <Label htmlFor="ontology-domain">도메인</Label>
            <Input id="ontology-domain" value={domain} onChange={(e) => setDomain(e.target.value)} />
          </div>

          {entities.map((entity) => (
            <div key={entity.type} className="flex flex-col gap-3 rounded-md border p-4" data-testid={`entity-edit-${entity.type}`}>
              <div className="flex items-center justify-between gap-2">
                {renamingType === entity.type ? (
                  <div className="flex flex-1 items-center gap-1.5" data-testid={`entity-rename-form-${entity.type}`}>
                    <Input
                      autoFocus
                      aria-label={`${entity.type} 타입 이름`}
                      value={renameDraft}
                      onChange={(e) => setRenameDraft(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') {
                          e.preventDefault();
                          confirmRename();
                        } else if (e.key === 'Escape') {
                          // 리네임 중 ESC는 리네임만 취소한다 — 다이얼로그 닫기 가드(#303)까지
                          // 함께 트리거되지 않도록 여기서 이벤트를 소비한다.
                          e.preventDefault();
                          e.stopPropagation();
                          cancelRename();
                        }
                      }}
                      className="h-8 flex-1"
                    />
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-8 w-8 shrink-0"
                      onClick={confirmRename}
                      aria-label={`${entity.type} 타입 이름 확인`}
                    >
                      <Check className="h-3.5 w-3.5" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-8 w-8 shrink-0"
                      onClick={cancelRename}
                      aria-label={`${entity.type} 타입 이름 취소`}
                    >
                      <X className="h-3.5 w-3.5" />
                    </Button>
                  </div>
                ) : (
                  <>
                    <h3 className="text-sm font-semibold">{entity.type}</h3>
                    <div className="flex items-center gap-1">
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-8 w-8 shrink-0"
                        onClick={() => startRename(entity.type)}
                        aria-label={`${entity.type} 타입 이름 변경`}
                        data-testid={`entity-rename-start-${entity.type}`}
                      >
                        <Pencil className="h-3.5 w-3.5" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-8 w-8 shrink-0 text-destructive"
                        onClick={() => removeEntityType(entity.type)}
                        disabled={entityTypes.length <= 1}
                        aria-label={`${entity.type} 타입 삭제`}
                        data-testid={`entity-delete-${entity.type}`}
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </Button>
                    </div>
                  </>
                )}
              </div>

              <div className="flex flex-col gap-2">
                <Label htmlFor={`desc-${entity.type}`}>설명</Label>
                <Textarea
                  id={`desc-${entity.type}`}
                  value={entity.description}
                  onChange={(e) => updateEntity(entity.type, { description: e.target.value })}
                />
              </div>

              <div className="flex flex-col gap-2">
                <Label htmlFor={`naming-${entity.type}`}>명명 규칙</Label>
                <Textarea
                  id={`naming-${entity.type}`}
                  value={entity.naming}
                  onChange={(e) => updateEntity(entity.type, { naming: e.target.value })}
                />
              </div>

              <div className="flex flex-col gap-2">
                <Label htmlFor={`resolution-${entity.type}`}>해상도 정책</Label>
                <Select
                  value={entity.resolution}
                  onValueChange={(v: 'exact' | 'embedding') => updateEntity(entity.type, { resolution: v })}
                >
                  <SelectTrigger id={`resolution-${entity.type}`}>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="exact">정확 매칭</SelectItem>
                    <SelectItem value="embedding">임베딩 해소</SelectItem>
                  </SelectContent>
                </Select>
              </div>

              {/* 속성(property) CRUD — 이름이 예약어(key/type/name/sourceChunkIds/schemaVersion)와 겹치면 즉시 에러 표시 */}
              <div className="flex flex-col gap-2">
                <div className="flex items-center justify-between">
                  <Label>속성</Label>
                  <Button
                    variant="outline"
                    size="sm"
                    className="h-7 gap-1 px-2 text-xs"
                    onClick={() => addProperty(entity.type)}
                    aria-label={`${entity.type} 속성 추가`}
                  >
                    <Plus className="h-3.5 w-3.5" />
                    속성 추가
                  </Button>
                </div>
                {entity.properties.map((prop, i) => {
                  const reserved = RESERVED_PROPERTY_NAMES.has(prop.name);
                  // 빈 속성명은 저장 시 토스트로도 막지만, 어느 행이 문제인지 즉시 보이도록 인라인으로도 알린다(#302).
                  const blankName = !prop.name.trim();
                  // 시각적 근접성만으로는 스크린리더에 오류가 전달되지 않는다 — 입력↔오류 문구를
                  // aria-invalid/aria-describedby로 연결한다(#329, WCAG SC 3.3.1).
                  // 예약어는 항상 non-blank이므로 두 오류는 동시에 뜨지 않아 id 재사용이 안전하다.
                  const nameErrorId = `property-name-error-${entity.type}-${i}`;
                  const nameInvalid = blankName || reserved;
                  return (
                    <div
                      key={i}
                      className="flex flex-col gap-1.5 rounded border p-2"
                      data-testid={`property-row-${entity.type}-${i}`}
                    >
                      <div className="flex items-center gap-1.5">
                        <Input
                          aria-label={`${entity.type} 속성 이름`}
                          aria-invalid={nameInvalid || undefined}
                          aria-describedby={nameInvalid ? nameErrorId : undefined}
                          placeholder="속성명"
                          value={prop.name}
                          onChange={(e) => updateProperty(entity.type, i, { name: e.target.value })}
                          className="flex-1"
                        />
                        <Select
                          value={prop.dataType ?? 'text'}
                          onValueChange={(v: 'text' | 'number' | 'date') => updateProperty(entity.type, i, { dataType: v })}
                        >
                          <SelectTrigger aria-label={`${entity.type} 속성 타입`} className="w-28">
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            {DATA_TYPES.map((dt) => (
                              <SelectItem key={dt} value={dt as string}>
                                {dt}
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                        <Input
                          aria-label={`${entity.type} 속성 단위`}
                          placeholder="단위(선택)"
                          value={prop.unit ?? ''}
                          onChange={(e) => updateProperty(entity.type, i, { unit: e.target.value || null })}
                          className="w-24"
                        />
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-8 w-8 shrink-0"
                          onClick={() => removeProperty(entity.type, i)}
                          aria-label={`${entity.type} 속성 삭제`}
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </Button>
                      </div>
                      <Input
                        aria-label={`${entity.type} 속성 설명`}
                        placeholder="설명(추출·정규화 지침)"
                        value={prop.description}
                        onChange={(e) => updateProperty(entity.type, i, { description: e.target.value })}
                      />
                      {blankName && (
                        <p id={nameErrorId} className="text-xs text-destructive">
                          속성명을 입력하세요
                        </p>
                      )}
                      {reserved && (
                        <p id={nameErrorId} className="text-xs text-destructive">
                          예약어는 속성명으로 쓸 수 없습니다: key, type, name, sourceChunkIds, schemaVersion
                        </p>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>
          ))}

          {/* 엔티티 타입 추가 — 이름을 진입 전에 검증(non-blank·중복)해 append. 이름 변경은 각 카드의 연필 버튼(리네임) 참조 */}
          <div className="flex items-end gap-2 rounded-md border border-dashed p-4" data-testid="add-entity-type-form">
            <div className="flex flex-1 flex-col gap-2">
              <Label htmlFor="new-entity-type-name">새 엔티티 타입</Label>
              <Input
                id="new-entity-type-name"
                data-testid="new-entity-type-name"
                placeholder="타입 이름(예: Sensor)"
                value={newTypeName}
                onChange={(e) => setNewTypeName(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault();
                    addEntityType();
                  }
                }}
              />
            </div>
            <Button
              variant="outline"
              size="sm"
              className="gap-1"
              onClick={addEntityType}
              aria-label="타입 추가"
              data-testid="add-entity-type"
            >
              <Plus className="h-3.5 w-3.5" />
              타입 추가
            </Button>
          </div>

          {/* 관계(트리플) CRUD — 주어/목적어는 현재 엔티티 타입 목록에서만 선택(참조 무결성을 UI에서부터 보장) */}
          <div className="flex flex-col gap-2 rounded-md border p-4" data-testid="relations-editor">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold">관계</h3>
              <Button
                variant="outline"
                size="sm"
                className="h-7 gap-1 px-2 text-xs"
                onClick={addRelation}
                aria-label="관계 추가"
                disabled={entityTypes.length === 0}
              >
                <Plus className="h-3.5 w-3.5" />
                관계 추가
              </Button>
            </div>
            {relations.map((rel, i) => {
              // 속성 행과 동일하게 관계명 입력↔오류 문구를 연결한다(#329).
              const relationBlank = !rel.relation.trim();
              const relationErrorId = `relation-name-error-${i}`;
              return (
              <div key={i} className="flex flex-col gap-1.5 rounded border p-2" data-testid={`relation-row-${i}`}>
                <div className="flex items-center gap-1.5">
                  <Select value={rel.subject} onValueChange={(v) => updateRelation(i, { subject: v })}>
                    <SelectTrigger aria-label="관계 주어 타입" className="flex-1">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {entityTypes.map((t) => (
                        <SelectItem key={t} value={t}>
                          {t}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <Input
                    aria-label="관계명"
                    aria-invalid={relationBlank || undefined}
                    aria-describedby={relationBlank ? relationErrorId : undefined}
                    placeholder="관계명(예: CAUSED_BY)"
                    value={rel.relation}
                    onChange={(e) => updateRelation(i, { relation: e.target.value })}
                    className="flex-1"
                  />
                  <Select value={rel.object} onValueChange={(v) => updateRelation(i, { object: v })}>
                    <SelectTrigger aria-label="관계 목적어 타입" className="flex-1">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {entityTypes.map((t) => (
                        <SelectItem key={t} value={t}>
                          {t}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-8 w-8 shrink-0"
                    onClick={() => removeRelation(i)}
                    aria-label="관계 삭제"
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </Button>
                </div>
                <Input
                  aria-label="관계 설명"
                  placeholder="설명"
                  value={rel.description}
                  onChange={(e) => updateRelation(i, { description: e.target.value })}
                />
                {/* 빈 관계명은 저장 시 토스트로도 막지만, 어느 행이 문제인지 즉시 보이도록 인라인으로도 알린다(#302). */}
                {relationBlank && (
                  <p id={relationErrorId} className="text-xs text-destructive">
                    관계명을 입력하세요
                  </p>
                )}
              </div>
              );
            })}
          </div>
        </div>

        <DialogFooter className="flex flex-col-reverse gap-2 border-t pt-4 sm:flex-row sm:justify-end">
          <Button variant="outline" onClick={requestClose} disabled={isPending}>
            취소
          </Button>
          <Button onClick={handleSave} disabled={isPending}>
            {isPending ? '저장 중...' : '저장'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    {/* 닫기 가드(#303) — DialogContent 안에 중첩하면 부모 Dialog가 닫히는 순간 함께 언마운트되므로
        형제로 둔다. 버튼 카피는 둘 다 결과를 말한다(중립어 '취소/확인'은 이 맥락에서 편집 취소인지
        닫기 취소인지 모호하다). 기본 포커스는 Radix 기본값대로 Cancel(=편집 계속하기)에 간다. */}
    <AlertDialog open={confirmingClose} onOpenChange={setConfirmingClose}>
      <AlertDialogContent data-testid="ontology-close-confirm">
        <AlertDialogHeader>
          <AlertDialogTitle>저장하지 않은 변경이 있습니다</AlertDialogTitle>
          <AlertDialogDescription>
            지금 닫으면 편집한 내용이 모두 사라집니다. 저장하지 않고 닫을까요?
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>편집 계속하기</AlertDialogCancel>
          <AlertDialogAction variant="destructive" onClick={confirmDiscardAndClose}>
            저장하지 않고 닫기
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
    </>
  );
}
