import { memo } from 'react';
import { Badge } from '../../../components/ui/badge';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../../../components/ui/table';
import type { PipelineStepResponse } from '../../../types/pipeline';

interface PipelineStepsTabProps {
  steps: PipelineStepResponse[];
}

export const PipelineStepsTab = memo(function PipelineStepsTab({ steps }: PipelineStepsTabProps) {
  return (
    <div className="rounded-md border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>이름</TableHead>
            <TableHead>스크립트 타입</TableHead>
            <TableHead>출력 데이터셋</TableHead>
            <TableHead>의존성</TableHead>
            <TableHead>순서</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {steps.map((step) => (
            <TableRow key={step.id}>
              <TableCell className="font-medium">{step.name}</TableCell>
              <TableCell>
                <Badge variant="outline">{step.scriptType}</Badge>
              </TableCell>
              <TableCell>{step.outputDatasetName}</TableCell>
              <TableCell>
                {step.dependsOnStepNames.length > 0
                  ? step.dependsOnStepNames.join(', ')
                  : '-'}
              </TableCell>
              <TableCell>{step.stepOrder}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
});
