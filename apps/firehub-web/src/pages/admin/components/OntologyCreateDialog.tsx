import { useState } from 'react';

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
import { useCreateOntology } from '@/hooks/queries/useOntology';
import { extractApiError } from '@/lib/api-error';

interface OntologyCreateDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** 생성 성공 시 새 온톨로지 id를 넘긴다 — 호출부가 그것을 선택 상태로 만든다. */
  onCreated: (id: number) => void;
}

/**
 * 신규 온톨로지 생성 — 도메인명 하나만 받는다.
 * 엔티티·관계는 여기서 받지 않는다. 편집기(OntologyEditDialog)에 이미 있는 폼을 중복 구현하지 않고,
 * 빈 draft를 만든 뒤 빈 상태 CTA로 편집기에 넘긴다.
 */
export default function OntologyCreateDialog({ open, onOpenChange, onCreated }: OntologyCreateDialogProps) {
  const [domain, setDomain] = useState('');
  const [error, setError] = useState<string | null>(null);
  const createOntology = useCreateOntology();

  const submit = () => {
    const trimmed = domain.trim();
    if (!trimmed) {
      setError('도메인명을 입력하세요.');
      return;
    }
    setError(null);
    createOntology.mutate(
      // 항상 draft로 만든다 — 사람이 내용을 채우고 활성화하기 전까지 바인딩·적재에서 격리된다.
      { domain: trimmed, entities: [], relations: [], status: 'draft' },
      {
        onSuccess: (id) => {
          setDomain('');
          onOpenChange(false);
          onCreated(id);
        },
        // 도메인 중복(409)은 다이얼로그를 닫지 않고 필드 옆에 보여준다 — 이름을 고쳐 재시도해야 하기 때문.
        onError: (err) => setError(extractApiError(err, '온톨로지 생성에 실패했습니다.')),
      },
    );
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md" data-testid="ontology-create-dialog">
        <DialogHeader>
          <DialogTitle>새 온톨로지</DialogTitle>
          <DialogDescription>
            도메인명을 정하면 빈 초안이 만들어집니다. 엔티티와 관계는 다음 단계에서 채웁니다.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-2">
          <Label htmlFor="ontology-domain">도메인</Label>
          <Input
            id="ontology-domain"
            value={domain}
            onChange={(e) => setDomain(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && submit()}
            aria-invalid={error != null}
            placeholder="예: 건축물 안전점검"
          />
          {error && <p className="text-[0.8rem] font-medium text-destructive">{error}</p>}
        </div>
        <DialogFooter className="gap-2 sm:justify-end">
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={createOntology.isPending}>
            취소
          </Button>
          <Button onClick={submit} disabled={createOntology.isPending}>
            {createOntology.isPending ? '만드는 중...' : '만들기'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
