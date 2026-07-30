import { useState } from 'react';
import { toast } from 'sonner';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
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

// AI가 수행한 불확실한 작업(동의어 병합·속성 정규화)을 사람이 원문 근거와 함께 검수·판단하는 인박스.
export default function ReviewInboxPage() {
  // 탭 필터 — undefined면 전체, 아니면 해당 item_type만.
  const [filter, setFilter] = useState<ReviewItemType | undefined>(undefined);
  const { data: pending, isLoading } = useReviewItemsPending(filter);
  const approve = useApproveReviewItem();
  const reject = useRejectReviewItem();
  const [processingId, setProcessingId] = useState<number | null>(null);

  const doApprove = async (id: number, correctedValue?: string) => {
    setProcessingId(id);
    try {
      await approve.mutateAsync({ id, correctedValue });
      toast.success('검수를 승인했습니다.');
    } catch (err) { handleApiError(err, '승인 처리에 실패했습니다.'); }
    finally { setProcessingId(null); }
  };
  const doReject = async (id: number) => {
    setProcessingId(id);
    try {
      await reject.mutateAsync(id);
      toast.success('검수를 거부했습니다.');
    } catch (err) { handleApiError(err, '거부 처리에 실패했습니다.'); }
    finally { setProcessingId(null); }
  };

  return (
    <div className="space-y-4 p-6">
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

      <Table>
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
            <ReviewRow key={row.id} row={row} processing={processingId === row.id} onApprove={doApprove} onReject={doReject} />
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

// 항목 1행 — item_type별 내용/조치 렌더러 + 확장형 근거 패널.
function ReviewRow(props: {
  row: ReviewItemResponse;
  processing: boolean;
  onApprove: (id: number, correctedValue?: string) => void;
  onReject: (id: number) => void;
}) {
  const { row, processing, onApprove, onReject } = props;
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
            <button type="button" className="text-primary underline text-xs" onClick={() => setShowEvidence((s) => !s)}>
              {showEvidence ? '근거 숨기기' : '원문 근거 보기'}
            </button>
          </div>
        </TableCell>
        <TableCell className="text-right space-x-2 whitespace-nowrap">
          <Button size="sm" disabled={processing || (isProperty && correctionError !== null)}
            onClick={() => onApprove(row.id, isProperty ? correctedValue : undefined)}>
            {isProperty ? '정정 적용' : isEntity || isRelation ? '적재' : '승인'}
          </Button>
          <Button size="sm" variant="outline" disabled={processing} onClick={() => onReject(row.id)}>거부</Button>
        </TableCell>
      </TableRow>
      {showEvidence && (
        <TableRow>
          <TableCell colSpan={4} className="bg-muted/40">
            <EvidencePanel id={row.id} />
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
  payload: PropertyPayload; reason: string | null; value: string; onChange: (v: string) => void; error: string | null;
}) {
  const { payload, reason, value, onChange, error } = props;
  return (
    <div className="space-y-1">
      <div><Badge variant="outline">{payload.entityType}.{payload.propertyName}</Badge> 원문: <span className="font-medium">“{payload.rawText}”</span></div>
      {reason && <div className="text-xs text-muted-foreground">{reason}</div>}
      <Input
        className="max-w-xs" placeholder={payload.dataType === 'number' ? '정정 숫자(예: 30000000)' : payload.dataType === 'date' ? 'YYYY-MM-DD' : '정정 텍스트'}
        value={value} onChange={(e) => onChange(e.target.value)}
        aria-invalid={error !== null} aria-label={`${payload.propertyName} 정정값`}
      />
      {error && <div className="text-xs text-destructive">{error}</div>}
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
function EvidencePanel({ id }: { id: number }) {
  const { data, isLoading } = useReviewItemEvidence(id, true);
  if (isLoading) return <span className="text-muted-foreground text-sm">근거 불러오는 중...</span>;
  if (!data || data.length === 0) return <span className="text-muted-foreground text-sm">이 항목에는 연결된 원문 근거가 없습니다.</span>;
  return (
    <div className="space-y-2">
      {data.map((c) => (
        <div key={c.chunkId} className="text-sm">
          <span className="text-muted-foreground text-xs">청크 #{c.chunkId}</span>
          <p className="whitespace-pre-wrap">{c.content}</p>
        </div>
      ))}
    </div>
  );
}
