import { useState } from 'react';
import { toast } from 'sonner';

import { Button } from '../../../components/ui/button';
import { Label } from '../../../components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../../../components/ui/select';
import { useBindOntology, useOntologyList } from '../../../hooks/queries/useMapping';
import { handleApiError } from '../../../lib/api-error';

interface OntologyBindingCardProps {
  datasetId: number;
}

/**
 * 미바인딩 데이터셋에 온톨로지를 연결한다.
 * 바인딩이 없으면 백엔드가 매핑 저장·활성화를 거부하므로, 매핑 편집의 선행 단계다.
 */
export function OntologyBindingCard({ datasetId }: OntologyBindingCardProps) {
  const { data: ontologies, isLoading } = useOntologyList();
  const bindOntology = useBindOntology(datasetId);
  // Select는 문자열 값만 다루므로 ID를 문자열로 보관하고 전송 직전에 숫자로 바꾼다.
  const [selected, setSelected] = useState('');

  const handleBind = async () => {
    if (!selected) return;
    try {
      await bindOntology.mutateAsync(Number(selected));
      toast.success('온톨로지를 연결했습니다.');
    } catch (error) {
      handleApiError(error, '온톨로지 연결에 실패했습니다.');
    }
  };

  return (
    <div className="rounded-md border p-6 space-y-4" data-testid="ontology-binding-card">
      <div>
        <h2 className="text-xl leading-7 font-semibold">온톨로지 연결</h2>
        <p className="mt-1 text-sm text-muted-foreground">
          이 데이터셋을 지식그래프로 투영하려면 기준이 될 온톨로지를 먼저 연결해야 합니다.
        </p>
      </div>

      <div className="space-y-2 max-w-sm">
        <Label htmlFor="ontology-select">온톨로지 *</Label>
        <Select value={selected} onValueChange={setSelected}>
          <SelectTrigger id="ontology-select" data-testid="ontology-select">
            <SelectValue placeholder="온톨로지를 선택하세요" />
          </SelectTrigger>
          <SelectContent>
            {(ontologies ?? []).map((o) => (
              <SelectItem key={o.id} value={String(o.id)}>
                {o.domain} (v{o.schemaVersion})
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <Button onClick={handleBind} disabled={!selected || isLoading || bindOntology.isPending}>
        {bindOntology.isPending ? '연결 중...' : '연결'}
      </Button>
    </div>
  );
}
