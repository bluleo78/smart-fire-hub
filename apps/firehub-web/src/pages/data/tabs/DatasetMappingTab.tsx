import { useEffect, useState } from 'react';
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
} from '../../../components/ui/alert-dialog';
import { Badge } from '../../../components/ui/badge';
import { Button } from '../../../components/ui/button';
import {
  useActivateMapping,
  useBinding,
  useMapping,
  useOntologyById,
  useSaveMapping,
} from '../../../hooks/queries/useMapping';
import { handleApiError } from '../../../lib/api-error';
import type { DraftEntity, DraftRelation, MappingDraft } from '../../../lib/mapping-spec';
import { countRelationsReferencing, emptyDraft, nextDraftId, removeEntity, toDraft, toSpec } from '../../../lib/mapping-spec';
import type { EntityMappingFormData, RelationMappingFormData } from '../../../lib/validations/mapping';
import type { DatasetDetailResponse } from '../../../types/dataset';
import { EntityMappingDialog } from '../components/EntityMappingDialog';
import { EntityMappingTable } from '../components/EntityMappingTable';
import { OntologyBindingCard } from '../components/OntologyBindingCard';
import { RelationMappingDialog } from '../components/RelationMappingDialog';
import { RelationMappingTable } from '../components/RelationMappingTable';

interface DatasetMappingTabProps {
  dataset: DatasetDetailResponse;
  datasetId: number;
}

/**
 * 표 데이터셋의 컬럼 → 온톨로지 매핑 편집 탭.
 * 상태는 (바인딩 여부 × 매핑 존재 여부)로 갈린다:
 *  1) 미바인딩 → 온톨로지 연결 카드만
 *  2) 바인딩 + 매핑 없음(404) → 빈 편집 상태 (에러 아님)
 *  3) 바인딩 + 매핑 있음 → draft/active 배지와 함께 편집
 * 편집은 전부 로컬 draft(안정 ID 모델)에서 하고, 저장 시에만 인덱스 기반 spec으로 직렬화한다.
 */
export function DatasetMappingTab({ dataset, datasetId }: DatasetMappingTabProps) {
  const { data: binding, isLoading: bindingLoading } = useBinding(datasetId);
  const ontologyId = binding?.ontologyId ?? null;
  const { data: ontology } = useOntologyById(ontologyId);
  const { data: mapping, isLoading: mappingLoading } = useMapping(datasetId);
  const saveMapping = useSaveMapping(datasetId);
  const activateMapping = useActivateMapping(datasetId);

  const [draft, setDraft] = useState<MappingDraft>(emptyDraft);
  // 로컬 편집이 서버 저장본과 달라진 상태. 활성화를 막는 근거가 된다.
  const [dirty, setDirty] = useState(false);

  // 엔티티 다이얼로그 상태. editingEntity가 null이면 "추가", 값이 있으면 "수정".
  const [entityDialogOpen, setEntityDialogOpen] = useState(false);
  const [editingEntity, setEditingEntity] = useState<DraftEntity | null>(null);
  // 삭제 확인 대상. 참조 관계 수를 고지해야 하므로 별도 상태로 들고 있는다.
  const [deletingEntity, setDeletingEntity] = useState<DraftEntity | null>(null);
  // 활성 매핑을 초안으로 되돌리는 저장 확인 대기 상태.
  const [confirmingDemote, setConfirmingDemote] = useState(false);

  const handleEntityAdd = () => {
    setEditingEntity(null);
    setEntityDialogOpen(true);
  };

  const handleEntityEdit = (entity: DraftEntity) => {
    setEditingEntity(entity);
    setEntityDialogOpen(true);
  };

  /**
   * draft를 갱신하는 유일한 통로. 갱신과 dirty 표시를 한 곳에 묶어 둔다 —
   * 따로 두면 새 핸들러가 setDirty를 빠뜨려 "미저장 변경" 가드(활성화 차단)가 조용히 무력화된다.
   */
  const updateDraft = (fn: (prev: MappingDraft) => MappingDraft) => {
    setDraft(fn);
    setDirty(true);
  };

  const handleEntitySubmit = (data: EntityMappingFormData) => {
    updateDraft((prev) =>
      editingEntity
        ? { ...prev, entities: prev.entities.map((e) => (e.id === editingEntity.id ? { ...e, ...data } : e)) }
        : { ...prev, entities: [...prev.entities, { id: nextDraftId('e'), ...data }] },
    );
  };

  // 삭제 확인 문구에 쓸 참조 관계 수. 조건 판단과 표시에 같은 값을 쓰므로 한 번만 계산한다.
  const deletingRelationCount = deletingEntity ? countRelationsReferencing(draft, deletingEntity.id) : 0;

  // 엔티티를 지우면 이를 참조하는 관계도 함께 사라진다 — 확인 다이얼로그에서 미리 고지한다.
  const handleEntityDeleteConfirm = () => {
    if (!deletingEntity) return;
    updateDraft((prev) => removeEntity(prev, deletingEntity.id));
    setDeletingEntity(null);
  };

  // 서버 매핑이 로드되거나 저장으로 갱신되면 로컬 draft를 서버 기준으로 되돌린다.
  useEffect(() => {
    if (mappingLoading) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- 서버 응답(mapping)으로 로컬 draft를 시드/재동기화
    setDraft(mapping ? toDraft(mapping.spec) : emptyDraft());
    setDirty(false);
  }, [mapping, mappingLoading]);

  const [relationDialogOpen, setRelationDialogOpen] = useState(false);
  const [editingRelation, setEditingRelation] = useState<DraftRelation | null>(null);

  const handleRelationAdd = () => {
    setEditingRelation(null);
    setRelationDialogOpen(true);
  };

  const handleRelationEdit = (relation: DraftRelation) => {
    setEditingRelation(relation);
    setRelationDialogOpen(true);
  };

  const handleRelationSubmit = (data: RelationMappingFormData) => {
    updateDraft((prev) =>
      editingRelation
        ? { ...prev, relations: prev.relations.map((r) => (r.id === editingRelation.id ? { ...r, ...data } : r)) }
        : { ...prev, relations: [...prev.relations, { id: nextDraftId('r'), ...data }] },
    );
  };

  const handleRelationDelete = (relation: DraftRelation) => {
    updateDraft((prev) => ({ ...prev, relations: prev.relations.filter((r) => r.id !== relation.id) }));
  };

  /**
   * 활성화. 서버는 **저장된** spec만 활성화하므로, 미저장 변경이 있으면 화면과 실제 활성 내용이
   * 어긋난다 — 먼저 저장하도록 막는다.
   */
  const handleActivate = async () => {
    if (dirty) {
      toast.error('저장되지 않은 변경이 있습니다. 먼저 저장하세요.');
      return;
    }
    try {
      await activateMapping.mutateAsync();
      toast.success('매핑을 활성화했습니다.');
    } catch (error) {
      handleApiError(error, '매핑 활성화에 실패했습니다.');
    }
  };

  const persistDraft = async () => {
    try {
      await saveMapping.mutateAsync(toSpec(draft));
      setDirty(false);
      toast.success('매핑을 초안으로 저장했습니다.');
    } catch (error) {
      // 컨포먼스 위반(400)은 백엔드가 한국어로 정확한 사유를 준다 — 그대로 노출한다.
      handleApiError(error, '매핑 저장에 실패했습니다.');
    }
  };

  /**
   * 저장은 항상 status를 draft로 되돌리므로, 활성 매핑을 저장하면 그래프 투영이 조용히 멈춘다.
   * 활성 상태에서만 확인 절차를 끼워 넣는다 — 초안 상태 저장은 부작용이 없어 즉시 진행한다.
   */
  const handleSave = async () => {
    if (mapping?.status === 'active') {
      setConfirmingDemote(true);
      return;
    }
    await persistDraft();
  };

  const handleDemoteConfirm = async () => {
    setConfirmingDemote(false);
    await persistDraft();
  };

  if (bindingLoading || mappingLoading) {
    return <div className="text-sm text-muted-foreground">불러오는 중...</div>;
  }

  // 상태 1 — 미바인딩.
  if (ontologyId == null) {
    return <OntologyBindingCard datasetId={datasetId} />;
  }

  return (
    <div className="space-y-4" data-testid="mapping-tab">
      <div className="flex items-center justify-between">
        <h2 className="text-xl leading-7 font-semibold">
          지식그래프 매핑
          <span className="ml-2 text-sm font-normal text-muted-foreground">
            온톨로지: {ontology?.domain ?? `#${ontologyId}`} · 테이블: {dataset.tableName}
          </span>
        </h2>
        <div className="flex items-center gap-2">
          <Badge variant={mapping?.status === 'active' ? 'default' : 'outline'} data-testid="mapping-status">
            {mapping ? (mapping.status === 'active' ? '활성' : '초안') : '없음'}
          </Badge>
          {dirty && (
            <span className="text-sm text-warning" data-testid="mapping-dirty">
              저장되지 않은 변경
            </span>
          )}
          <Button
            onClick={handleSave}
            // 미변경 상태의 재저장은 status를 draft로 되돌리는 부작용만 남기므로 막는다.
            disabled={!dirty || saveMapping.isPending}
            data-testid="mapping-save-button"
          >
            {saveMapping.isPending ? '저장 중...' : '초안 저장'}
          </Button>
          <Button
            variant="outline"
            onClick={handleActivate}
            // 저장된 매핑이 없으면 활성화할 대상 자체가 없다.
            disabled={!mapping || activateMapping.isPending}
            data-testid="mapping-activate-button"
          >
            {activateMapping.isPending ? '활성화 중...' : '활성화'}
          </Button>
        </div>
      </div>

      <p className="text-sm text-muted-foreground" data-testid="mapping-summary">
        엔티티 {draft.entities.length}개 · 관계 {draft.relations.length}개
      </p>

      {/*
        빈 상태는 서버 저장본(mapping) 유무가 아니라 화면에 실제로 보이는 로컬 draft를 기준으로 판정한다.
        서버 저장 전에 추가한 매핑도 요약·표에는 이미 반영되므로, mapping 기준으로 두면
        "엔티티 1개"와 "아직 매핑이 없습니다"가 동시에 보인다. 저장 여부는 상태 배지가 따로 전달한다. (#298)
      */}
      {draft.entities.length === 0 && draft.relations.length === 0 && (
        <p className="text-sm text-muted-foreground" data-testid="mapping-empty">
          아직 매핑이 없습니다. 엔티티 매핑을 추가해 직접 만들거나, AI 채팅으로 자동 추론을 요청할 수 있습니다.
        </p>
      )}

      <EntityMappingTable
        entities={draft.entities}
        onAdd={handleEntityAdd}
        onEdit={handleEntityEdit}
        onDelete={setDeletingEntity}
      />

      {ontology && (
        <EntityMappingDialog
          open={entityDialogOpen}
          onOpenChange={setEntityDialogOpen}
          ontology={ontology}
          columns={dataset.columns}
          initial={editingEntity}
          onSubmit={handleEntitySubmit}
        />
      )}

      <RelationMappingTable
        relations={draft.relations}
        entities={draft.entities}
        onAdd={handleRelationAdd}
        onEdit={handleRelationEdit}
        onDelete={handleRelationDelete}
      />

      {ontology && (
        <RelationMappingDialog
          open={relationDialogOpen}
          onOpenChange={setRelationDialogOpen}
          ontology={ontology}
          entities={draft.entities}
          initial={editingRelation}
          onSubmit={handleRelationSubmit}
        />
      )}

      <AlertDialog open={deletingEntity !== null} onOpenChange={(open) => !open && setDeletingEntity(null)}>
        <AlertDialogContent data-testid="entity-delete-confirm">
          <AlertDialogHeader>
            <AlertDialogTitle>엔티티 매핑 삭제</AlertDialogTitle>
            <AlertDialogDescription>
              {deletingEntity?.entityType} 매핑을 삭제합니다.
              {deletingRelationCount > 0 && (
                <> 이 엔티티를 참조하는 관계 {deletingRelationCount}건도 함께 삭제됩니다.</>
              )}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction onClick={handleEntityDeleteConfirm}>삭제</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog open={confirmingDemote} onOpenChange={(open) => !open && setConfirmingDemote(false)}>
        <AlertDialogContent data-testid="mapping-demote-confirm">
          <AlertDialogHeader>
            <AlertDialogTitle>활성 매핑을 초안으로 되돌립니다</AlertDialogTitle>
            <AlertDialogDescription>
              저장하면 매핑 상태가 활성에서 초안으로 바뀌고, 이 데이터셋의 지식그래프 투영이 중단됩니다. 편집을 반영한
              뒤 다시 활성화해야 투영이 재개됩니다.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction onClick={handleDemoteConfirm}>초안으로 저장</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
