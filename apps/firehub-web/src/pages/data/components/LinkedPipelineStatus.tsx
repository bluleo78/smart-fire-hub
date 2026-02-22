import { Link } from 'react-router-dom';
import { Badge } from '@/components/ui/badge';

interface LinkedPipelineStatusProps {
  pipelines: Array<{ id: number; name: string; isActive: boolean }>;
}

export function LinkedPipelineStatus({ pipelines }: LinkedPipelineStatusProps) {
  if (!pipelines || pipelines.length === 0) return null;

  return (
    <div className="flex items-center gap-1.5 flex-wrap mt-2">
      <span className="text-xs text-muted-foreground shrink-0">연결된 파이프라인:</span>
      {pipelines.map((pipeline) => (
        <Link key={pipeline.id} to={`/pipelines/${pipeline.id}`}>
          <Badge
            variant={pipeline.isActive ? 'default' : 'secondary'}
            className="text-xs cursor-pointer hover:opacity-80 transition-opacity gap-1"
          >
            <span
              className={`inline-block w-1.5 h-1.5 rounded-full ${
                pipeline.isActive ? 'bg-green-400' : 'bg-gray-400'
              }`}
            />
            {pipeline.name}
          </Badge>
        </Link>
      ))}
    </div>
  );
}
