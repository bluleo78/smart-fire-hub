import { Trash2 } from 'lucide-react';

import { Badge } from '../../../components/ui/badge';
import { Button } from '../../../components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../../../components/ui/table';
import type { DocumentFileResponse, DocumentStatus } from '../../../types/document';

interface DocumentListProps {
  documents: DocumentFileResponse[];
  onDelete: (documentId: number) => void;
  deletingId?: number | null;
}

/** 상태 → 한글 라벨 + 배지 variant 매핑. */
const STATUS_META: Record<DocumentStatus, { label: string; variant: 'default' | 'secondary' | 'destructive' }> = {
  PENDING: { label: '대기', variant: 'secondary' },
  PARSING: { label: '추출 중', variant: 'secondary' },
  EMBEDDING: { label: '임베딩 중', variant: 'secondary' },
  COMPLETED: { label: '완료', variant: 'default' },
  FAILED: { label: '실패', variant: 'destructive' },
};

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** 문서 목록 테이블. 상태 배지·페이지/청크 수·삭제 버튼 제공. */
export function DocumentList({ documents, onDelete, deletingId }: DocumentListProps) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>파일명</TableHead>
          <TableHead>상태</TableHead>
          <TableHead>크기</TableHead>
          <TableHead>페이지</TableHead>
          <TableHead>청크</TableHead>
          <TableHead className="w-12" />
        </TableRow>
      </TableHeader>
      <TableBody>
        {documents.map((d) => {
          const meta = STATUS_META[d.status];
          return (
            <TableRow key={d.id}>
              <TableCell className="font-medium">{d.originalName}</TableCell>
              <TableCell>
                <Badge variant={meta.variant} title={d.errorDetail ?? undefined}>{meta.label}</Badge>
              </TableCell>
              <TableCell>{formatSize(d.fileSize)}</TableCell>
              <TableCell>{d.pageCount ?? '-'}</TableCell>
              <TableCell>{d.chunkCount ?? '-'}</TableCell>
              <TableCell>
                <Button
                  size="icon"
                  variant="ghost"
                  aria-label={`${d.originalName} 삭제`}
                  disabled={deletingId === d.id}
                  onClick={() => onDelete(d.id)}
                >
                  <Trash2 className="h-4 w-4" />
                </Button>
              </TableCell>
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
  );
}
