import { useRef, useState } from 'react';
import { toast } from 'sonner';

import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Badge } from '@/components/ui/badge';
import { Button, buttonVariants } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  useApproveReviewItem, useRejectReviewItem, useReviewItemEvidence, useReviewItemsPending,
} from '@/hooks/queries/useReviewItems';
import { handleApiError } from '@/lib/api-error';
import { validatePropertyCorrection } from '@/lib/property-correction';
import { cn } from '@/lib/utils';
import type {
  EntityPayload, PropertyPayload, RelationPayload, ReviewItemResponse, ReviewItemType, SynonymPayload,
} from '@/types/reviewItem';

// signal_type(DB enum) → 검수자용 한국어 레이블(#314).
// 신호 컬럼은 종류마다 표기 수준이 달라선 안 되므로, 점수가 없는 신호도 반드시 이 표를 거쳐 한국어로 나간다.
const SIGNAL_LABEL: Record<string, string> = {
  similarity: '유사도',
  low_confidence: '신뢰도',
  normalization_failure: '정규화 실패',
};

/**
 * 신호 셀 문구를 만든다. 점수가 있으면 `{레이블} {점수}`, 없으면 레이블만.
 * 미지의 signalType은 원시 enum을 그대로 노출하지 않고 '기타'로 폴백해, 신호 종류가 늘어나도
 * 영문 스네이크케이스가 한국어 UI에 새는 것을 구조적으로 막는다(#314).
 */
function formatSignal(signalType: string | null, signalScore: number | null): string {
  if (signalType == null) return '-';
  const label = SIGNAL_LABEL[signalType] ?? '기타';
  if (signalScore == null) return label;
  // 유사도는 소수 3자리, 그 외 신뢰도류는 2자리 — 기존 표기 관례 유지.
  return `${label} ${signalScore.toFixed(signalType === 'similarity' ? 3 : 2)}`;
}

/** 확인 다이얼로그가 제어하는 대상 — 어떤 행을, 어떤 조치로, (속성이면) 어떤 정정값으로 확정할지. */
type ConfirmTarget = {
  row: ReviewItemResponse;
  action: 'approve' | 'reject';
  /** 다이얼로그를 연 시점의 정정값 스냅샷 — 사용자가 그래프에 무엇을 쓰는지 그대로 에코하고, 확정 시에도 이 값을 보낸다. */
  correctedValue?: string;
};

/** 행에서 누른 동사를 확인 버튼 라벨로 그대로 재사용한다(중립어 '확인' 금지 — 오클릭 방지 어포던스). */
function actionVerb(row: ReviewItemResponse, action: 'approve' | 'reject'): string {
  if (action === 'reject') return '거부';
  if (row.itemType === 'property_normalization') return '정정 적용';
  if (row.itemType === 'entity_extraction' || row.itemType === 'relation_extraction') return '적재';
  return '승인';
}

/** 거부 다이얼로그의 대상 요약 한 줄 — item_type별 표기는 승인 카피의 대상 표기를 재사용한다. */
function rejectSummary(row: ReviewItemResponse): string {
  switch (row.itemType) {
    case 'synonym_merge': {
      const p = row.payload as SynonymPayload;
      return `대상: “${p.nameA}” ↔ “${p.nameB}” (${p.entityType})`;
    }
    case 'property_normalization': {
      const p = row.payload as PropertyPayload;
      return `대상: ${p.entityType}.${p.propertyName} — 원문 “${p.rawText}”`;
    }
    case 'entity_extraction': {
      const p = row.payload as EntityPayload;
      return `대상: “${p.name}” (${p.entityType})`;
    }
    case 'relation_extraction': {
      const p = row.payload as RelationPayload;
      return `대상: “${p.subjectName}” → ${p.relType} → “${p.objectName}”`;
    }
    default:
      return '';
  }
}

/**
 * 확인 다이얼로그 카피를 만든다. 다이얼로그는 페이지에 1개만 두고 item_type × action으로 문구만 스위칭한다.
 * destructive는 되돌리기 난이도가 실제로 높은 경우(동의어 병합 승인 / 모든 거부)에만 준다 —
 * 전부 빨강이면 경고가 무의미해지기 때문.
 */
function buildConfirmCopy(target: ConfirmTarget): {
  title: string; body: string; summary: string | null; confirmLabel: string; destructive: boolean;
} {
  const { row, action } = target;
  const confirmLabel = actionVerb(row, action);

  if (action === 'reject') {
    return {
      title: '검수를 거부합니다',
      body: '이 항목을 거부 처리하면 그래프에 반영되지 않고 검수 목록에서 사라집니다. 현재 화면에서 다시 조회하거나 되돌릴 수 없습니다.',
      summary: rejectSummary(row),
      confirmLabel,
      destructive: true,
    };
  }

  switch (row.itemType) {
    case 'synonym_merge': {
      const p = row.payload as SynonymPayload;
      return {
        title: '동의어를 병합합니다',
        body: `“${p.nameA}”와 “${p.nameB}”를 같은 ${p.entityType}로 병합합니다. 그래프의 두 노드가 하나로 합쳐지며, 이 작업은 되돌릴 수 없습니다.`,
        summary: null, confirmLabel, destructive: true,
      };
    }
    case 'property_normalization': {
      const p = row.payload as PropertyPayload;
      return {
        title: '속성 정정값을 반영합니다',
        body: `${p.entityType}.${p.propertyName} 속성을 원문 “${p.rawText}” 에서 “${target.correctedValue ?? ''}” 로 정정해 그래프에 기록합니다.`,
        summary: null, confirmLabel, destructive: false,
      };
    }
    case 'entity_extraction': {
      const p = row.payload as EntityPayload;
      const relCount = p.relations?.length ?? 0;
      const base = `“${p.name}”(${p.entityType}) 엔티티를 그래프에 적재합니다.`;
      return {
        title: '엔티티를 그래프에 적재합니다',
        body: relCount > 0 ? `${base} 이 엔티티에 연결된 관계 ${relCount}건도 함께 적재됩니다.` : base,
        summary: null, confirmLabel, destructive: false,
      };
    }
    case 'relation_extraction': {
      const p = row.payload as RelationPayload;
      return {
        title: '관계를 그래프에 적재합니다',
        body: `“${p.subjectName}” → ${p.relType} → “${p.objectName}” 관계를 그래프 엣지로 적재합니다.`,
        summary: null, confirmLabel, destructive: false,
      };
    }
    default:
      return { title: '검수를 확정합니다', body: '이 항목을 확정합니다.', summary: null, confirmLabel, destructive: false };
  }
}

// AI가 수행한 불확실한 작업(동의어 병합·속성 정규화)을 사람이 원문 근거와 함께 검수·판단하는 인박스.
export default function ReviewInboxPage() {
  // 탭 필터 — undefined면 전체, 아니면 해당 item_type만.
  const [filter, setFilter] = useState<ReviewItemType | undefined>(undefined);
  const { data: pending, isLoading } = useReviewItemsPending(filter);
  const approve = useApproveReviewItem();
  const reject = useRejectReviewItem();
  const [processingId, setProcessingId] = useState<number | null>(null);
  // 확인 다이얼로그는 페이지에 1개만 둔다(행마다 렌더하면 DOM이 부풀고, 확정 후 행이 사라질 때 언마운트 레이스가 생긴다).
  const [confirmTarget, setConfirmTarget] = useState<ConfirmTarget | null>(null);
  // 확정 시 대상 행(=트리거 버튼)이 목록에서 사라지므로 포커스가 돌아갈 곳이 없다.
  // 표를 대체 복귀 지점으로 넘겨 키보드 사용자가 페이지 맨 위로 튕기지 않게 한다 (#337).
  const tableRef = useRef<HTMLTableElement>(null);

  /**
   * 확정 후 목록이 갱신되어 대상 행이 사라지면, 그 행의 트리거 버튼을 잡고 있던 포커스가
   * <body>로 떨어진다. 다이얼로그가 닫히는 시점에는 행이 아직 살아 있어
   * 복귀 훅의 restoreFocusRef 폴백이 타지 않으므로 여기서 직접 표로 되돌린다 (#337).
   * 실패(409 등)로 행이 남은 경우엔 포커스가 트리거에 그대로 있으므로 아무 것도 하지 않는다.
   */
  const restoreFocusIfRowGone = () => {
    // 목록 재조회 후 React 리렌더가 언제 flush 될지는 프레임 단위로 보장되지 않는다.
    // 첫 프레임에 아직 행이 살아 있으면 다음 프레임에 다시 확인한다(최대 3프레임).
    // 그래도 포커스가 살아 있으면 실패로 행이 잔존한 경우이므로 손대지 않는다.
    let frames = 0;
    const check = () => {
      const active = document.activeElement;
      if (active && active !== document.body && active.isConnected) {
        if (frames++ < 2) requestAnimationFrame(check);
        return;
      }
      tableRef.current?.focus();
    };
    requestAnimationFrame(check);
  };

  const doApprove = async (id: number, correctedValue?: string) => {
    setProcessingId(id);
    try {
      await approve.mutateAsync({ id, correctedValue });
      toast.success('검수를 승인했습니다.');
      restoreFocusIfRowGone();
    } catch (err) { handleApiError(err, '승인 처리에 실패했습니다.'); }
    finally { setProcessingId(null); }
  };
  const doReject = async (id: number) => {
    setProcessingId(id);
    try {
      await reject.mutateAsync(id);
      toast.success('검수를 거부했습니다.');
      restoreFocusIfRowGone();
    } catch (err) { handleApiError(err, '거부 처리에 실패했습니다.'); }
    finally { setProcessingId(null); }
  };

  // 확인 클릭 → 다이얼로그를 먼저 닫고 → 기존 mutation 경로를 그대로 호출한다.
  // 닫기를 먼저 하는 이유: 실패(409) 시 사유 토스트와 잔존 행이 오버레이 뒤에 가려지면 안 된다(#310 동작 보존).
  const handleConfirm = () => {
    const target = confirmTarget;
    if (!target) return;
    setConfirmTarget(null);
    if (target.action === 'approve') void doApprove(target.row.id, target.correctedValue);
    else void doReject(target.row.id);
  };

  const copy = confirmTarget ? buildConfirmCopy(confirmTarget) : null;

  return (
    // 좁은 폭에서는 페이지 패딩을 줄여 320px 리플로우 여유를 확보한다(#345).
    <div className="space-y-4 p-4 sm:p-6">
      <div>
        <h1 className="text-2xl font-semibold">AI 검수</h1>
        <p className="text-muted-foreground text-sm">
          AI가 수행한 불확실한 작업을 원문 근거와 함께 검수합니다. 동의어 병합은 승인 시 그래프에 병합되고,
          속성 정규화는 정정값을 입력해 반영합니다. 저신뢰 엔티티는 승인 시 관계와 함께 그래프에 적재됩니다.
          저신뢰 관계는 승인 시 그래프 엣지로 적재됩니다.
        </p>
      </div>

      <Tabs value={filter ?? 'all'} onValueChange={(v) => setFilter(v === 'all' ? undefined : (v as ReviewItemType))}>
        <TabsList>
          <TabsTrigger value="all">전체</TabsTrigger>
          <TabsTrigger value="synonym_merge">동의어</TabsTrigger>
          <TabsTrigger value="property_normalization">속성</TabsTrigger>
          <TabsTrigger value="entity_extraction">엔티티</TabsTrigger>
          <TabsTrigger value="relation_extraction">관계</TabsTrigger>
        </TabsList>
      </Tabs>

      {/* tabIndex=-1: Tab 순서는 건드리지 않으면서 프로그램적으로 포커스를 받을 수 있게 한다(#337 복귀 지점).
          포커스 링은 지우지 않는다 — 복귀 지점이 보이지 않으면 SC 2.4.7 위반으로 결함을 바꿔 심는 셈이다. */}
      <Table ref={tableRef} tabIndex={-1} data-testid="review-inbox-table">
        <TableHeader>
          <TableRow>
            <TableHead>종류</TableHead>
            <TableHead>내용</TableHead>
            <TableHead>신호</TableHead>
            <TableHead className="text-right">조치</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading && (
            <TableRow><TableCell colSpan={4} className="text-muted-foreground text-center">불러오는 중...</TableCell></TableRow>
          )}
          {!isLoading && (pending?.length ?? 0) === 0 && (
            <TableRow><TableCell colSpan={4} className="text-muted-foreground text-center">검수 대기 중인 항목이 없습니다.</TableCell></TableRow>
          )}
          {pending?.map((row) => (
            <ReviewRow key={row.id} row={row} processing={processingId === row.id} onRequestConfirm={setConfirmTarget} />
          ))}
        </TableBody>
      </Table>

      {/* 4개 조치(승인·적재·정정 적용·거부) 전부가 거치는 단일 확인 게이트. 그래프 변경/목록 소멸이 모두 비가역이라 조건부 스킵은 두지 않는다. */}
      <AlertDialog open={confirmTarget !== null} onOpenChange={(open) => !open && setConfirmTarget(null)}>
        <AlertDialogContent
          restoreFocusRef={tableRef}
          data-testid="review-decide-confirm"
          data-action={confirmTarget?.action}
          data-item-type={confirmTarget?.row.itemType}
        >
          <AlertDialogHeader>
            <AlertDialogTitle>{copy?.title}</AlertDialogTitle>
            <AlertDialogDescription>{copy?.body}</AlertDialogDescription>
            {copy?.summary && <div className="text-muted-foreground text-sm">{copy.summary}</div>}
          </AlertDialogHeader>
          <AlertDialogFooter>
            {/* 취소가 좌측·기본 포커스 — 비가역 조치에서 Enter 오입력이 곧바로 확정되지 않게 한다.
                기본 포커스는 Radix AlertDialog 가 onOpenAutoFocus 에서 Cancel 로 옮겨 주므로 별도 지정이 불필요하다.
                여기에 `autoFocus` 를 두면 React 가 commit 단계에서 먼저 포커스를 옮겨
                포커스 복귀 훅이 트리거 대신 이 버튼을 캡처한다 (이슈 #337). */}
            <AlertDialogCancel data-testid="review-decide-confirm-cancel">취소</AlertDialogCancel>
            <AlertDialogAction
              data-testid="review-decide-confirm-action"
              className={cn(copy?.destructive && buttonVariants({ variant: 'destructive' }))}
              onClick={handleConfirm}
            >
              {copy?.confirmLabel}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

// 항목 1행 — item_type별 내용/조치 렌더러 + 확장형 근거 패널.
function ReviewRow(props: {
  row: ReviewItemResponse;
  processing: boolean;
  /** 즉시 확정하지 않고 페이지 레벨 확인 다이얼로그를 연다(정정값은 이 시점 스냅샷). */
  onRequestConfirm: (target: ConfirmTarget) => void;
}) {
  const { row, processing, onRequestConfirm } = props;
  const [showEvidence, setShowEvidence] = useState(false);
  const [correctedValue, setCorrectedValue] = useState('');
  const isProperty = row.itemType === 'property_normalization';
  // 정정값 형식 검증(#311) — 위반이면 [정정 적용]을 막고 사유를 인라인으로 알린다.
  // 서버 왕복 후 실패 토스트로 알던 것을 입력 시점으로 당긴다. 아직 아무것도 입력하지 않은 상태에서는
  // 사유를 띄우지 않는다(빈 값은 오류가 아니라 미입력) — 대신 버튼은 그대로 비활성이다.
  const propertyDataType = isProperty ? (row.payload as PropertyPayload).dataType : null;
  const correctionError = propertyDataType ? validatePropertyCorrection(propertyDataType, correctedValue) : null;
  const isSynonym = row.itemType === 'synonym_merge';
  const isEntity = row.itemType === 'entity_extraction';
  const isRelation = row.itemType === 'relation_extraction';

  // 행마다 반복되는 컨트롤(근거 토글·조치 버튼)의 접근 가능한 이름에 붙일 대상 요약(#338, #342).
  // 시각 라벨은 표 밀도를 위해 짧게 두고 aria-label로만 대상을 덧붙인다. rejectSummary가 이미
  // item_type별 대상 표기를 만들고 있으므로 '대상: ' 접두만 떼어 재사용한다(카피 중복 방지).
  const targetSummary = rejectSummary(row).replace(/^대상: /, '');
  // 근거 패널은 다음 <tr>에 삽입되므로 토글과 aria-controls로 명시적으로 묶는다. id는 행 단위로 유일해야 한다.
  const evidencePanelId = `review-evidence-${row.id}`;
  const approveVerb = actionVerb(row, 'approve');

  return (
    <>
      <TableRow>
        <TableCell>
          <Badge variant="secondary">{isSynonym ? '동의어' : isProperty ? '속성' : isEntity ? '엔티티' : isRelation ? '관계' : row.itemType}</Badge>
        </TableCell>
        <TableCell className="max-w-md">
          {isSynonym && <SynonymContent payload={row.payload as SynonymPayload} rationale={row.reason} />}
          {isProperty && (
            <PropertyContent
              itemId={row.id}
              payload={row.payload as PropertyPayload} reason={row.reason}
              value={correctedValue} onChange={setCorrectedValue}
              error={correctedValue.trim() === '' ? null : correctionError}
            />
          )}
          {isEntity && <EntityContent payload={row.payload as EntityPayload} reason={row.reason} />}
          {isRelation && <RelationContent payload={row.payload as RelationPayload} reason={row.reason} />}
        </TableCell>
        <TableCell className="text-sm text-muted-foreground">
          {formatSignal(row.signalType, row.signalScore)}
          <div>
            {/* aria-expanded/aria-controls로 접힘·펼침 상태와 대상 패널을 노출한다(#338, SC 4.1.2).
                시각 라벨은 행마다 같지만 aria-label로 대상을 붙여 버튼 목록에서 구분되게 한다(SC 2.4.6).
                aria-controls는 패널이 실제로 있을 때만 붙인다 — 없는 id를 가리키면 무효 참조가 된다. */}
            <button
              type="button"
              className="text-primary underline text-xs"
              aria-expanded={showEvidence}
              aria-controls={showEvidence ? evidencePanelId : undefined}
              aria-label={`${targetSummary} — ${showEvidence ? '근거 숨기기' : '원문 근거 보기'}`}
              onClick={() => setShowEvidence((s) => !s)}
            >
              {showEvidence ? '근거 숨기기' : '원문 근거 보기'}
            </button>
          </div>
        </TableCell>
        <TableCell className="text-right space-x-2 whitespace-nowrap">
          {/* #311 회귀 가드 — 정정값이 비었거나 형식 위반이면 트리거 자체가 비활성이라 다이얼로그도 열리지 않는다. */}
          {/* 조치는 전부 비가역이므로 접근 가능한 이름이 어떤 행인지 식별해야 한다(#342, SC 2.4.6/4.1.2).
              시각 라벨(승인/적재/정정 적용/거부)은 유지하고 aria-label로만 대상 요약을 덧붙인다. */}
          <Button size="sm" disabled={processing || (isProperty && correctionError !== null)}
            aria-label={`${targetSummary} — ${approveVerb}`}
            onClick={() => onRequestConfirm({ row, action: 'approve', correctedValue: isProperty ? correctedValue : undefined })}>
            {approveVerb}
          </Button>
          <Button size="sm" variant="outline" disabled={processing}
            aria-label={`${targetSummary} — 거부`}
            onClick={() => onRequestConfirm({ row, action: 'reject' })}>거부</Button>
        </TableCell>
      </TableRow>
      {showEvidence && (
        <TableRow>
          {/* 토글이 aria-controls로 가리키는 패널 컨테이너. 로딩 → 결과 전환이 비동기로 조용히 일어나므로
              aria-live/aria-busy로 고지한다(#338, SC 4.1.3). */}
          <TableCell colSpan={4} className="bg-muted/40">
            {/* id/live region은 <td>가 아니라 내부 div에 둔다 — td에 role을 덮으면 표 셀 시맨틱이 깨진다. */}
            <div id={evidencePanelId} aria-label={`${targetSummary} 원문 근거`} role="group">
              <EvidencePanel id={row.id} />
            </div>
          </TableCell>
        </TableRow>
      )}
    </>
  );
}

function SynonymContent({ payload, rationale }: { payload: SynonymPayload; rationale: string | null }) {
  return (
    <div className="space-y-1">
      <div><span className="font-medium">{payload.nameA}</span> ↔ <span className="font-medium">{payload.nameB}</span> <Badge variant="outline">{payload.entityType}</Badge></div>
      {rationale && <div className="text-xs text-muted-foreground">{rationale}</div>}
    </div>
  );
}

function PropertyContent(props: {
  /** 오류 노드 id를 행 단위로 유일화하기 위해 필요 — 같은 표에 여러 행이 동시에 렌더된다(#340). */
  itemId: number;
  payload: PropertyPayload; reason: string | null; value: string; onChange: (v: string) => void; error: string | null;
}) {
  const { itemId, payload, reason, value, onChange, error } = props;
  // aria-invalid만으로는 "유효하지 않음"까지만 낭독된다. 사유 노드에 id를 주고 aria-describedby로 묶어야
  // 무엇이 잘못됐는지가 전달된다(#340, SC 3.3.1/1.3.1). #300 매핑 다이얼로그와 같은 배선 패턴.
  const errorId = `review-correction-error-${itemId}`;
  return (
    <div className="space-y-1">
      <div><Badge variant="outline">{payload.entityType}.{payload.propertyName}</Badge> 원문: <span className="font-medium">“{payload.rawText}”</span></div>
      {reason && <div className="text-xs text-muted-foreground">{reason}</div>}
      <Input
        className="max-w-xs" placeholder={payload.dataType === 'number' ? '정정 숫자(예: 30000000)' : payload.dataType === 'date' ? 'YYYY-MM-DD' : '정정 텍스트'}
        value={value} onChange={(e) => onChange(e.target.value)}
        aria-invalid={error !== null} aria-label={`${payload.propertyName} 정정값`}
        aria-describedby={error ? errorId : undefined}
      />
      {/* role="alert": 입력 도중 뒤늦게 등장하는 오류라 포커스를 옮기지 않고도 즉시 고지되어야 한다. */}
      {error && <div id={errorId} role="alert" className="text-xs text-destructive">{error}</div>}
    </div>
  );
}

// 저신뢰 엔티티 — 이름·타입·연결관계수·사유 표시(정정 입력 없음, 승인 시 as-extracted 그대로 적재).
function EntityContent({ payload, reason }: { payload: EntityPayload; reason: string | null }) {
  const relCount = payload.relations?.length ?? 0;
  return (
    <div className="space-y-1">
      <div><span className="font-medium">{payload.name}</span> <Badge variant="outline">{payload.entityType}</Badge></div>
      {relCount > 0 && <div className="text-xs text-muted-foreground">연결 관계 {relCount}건 (승인 시 함께 적재)</div>}
      {reason && <div className="text-xs text-muted-foreground">{reason}</div>}
    </div>
  );
}

// 저신뢰 관계 — 주어→[관계]→목적어·사유 표시(정정 입력 없음, 승인 시 엣지 적재).
function RelationContent({ payload, reason }: { payload: RelationPayload; reason: string | null }) {
  return (
    <div className="space-y-1">
      <div>
        <span className="font-medium">{payload.subjectName}</span>
        {' '}<Badge variant="outline">{payload.relType}</Badge>{' → '}
        <span className="font-medium">{payload.objectName}</span>
      </div>
      {reason && <div className="text-xs text-muted-foreground">{reason}</div>}
    </div>
  );
}

// 원문 근거 패널 — 펼칠 때만 조회(enabled). 스니펫 없으면 안내.
// 로딩 → 결과(또는 '근거 없음') 전환이 시각적으로만 일어나던 것을 aria-live/aria-busy로 고지한다(#338, SC 4.1.3).
function EvidencePanel({ id }: { id: number }) {
  const { data, isLoading } = useReviewItemEvidence(id, true);
  // live region은 콘텐츠가 바뀔 때 이미 DOM에 있어야 낭독된다 — 분기마다 새로 만들지 않고 래퍼를 항상 유지한다.
  const body = isLoading ? (
    <span className="text-muted-foreground text-sm">근거 불러오는 중...</span>
  ) : !data || data.length === 0 ? (
    <span className="text-muted-foreground text-sm">이 항목에는 연결된 원문 근거가 없습니다.</span>
  ) : (
    <div className="space-y-2">
      {data.map((c) => (
        <div key={c.chunkId} className="text-sm">
          <span className="text-muted-foreground text-xs">청크 #{c.chunkId}</span>
          <p className="whitespace-pre-wrap">{c.content}</p>
        </div>
      ))}
    </div>
  );
  return (
    <div aria-live="polite" aria-busy={isLoading} data-testid={`review-evidence-live-${id}`}>
      {body}
    </div>
  );
}
