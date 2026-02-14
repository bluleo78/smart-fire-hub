import { useAuth } from '../hooks/useAuth';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';

export function HomePage() {
  const { user } = useAuth();

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">대시보드</h1>
      <Card>
        <CardHeader>
          <CardTitle>환영합니다, {user?.name}님!</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="space-y-1 text-sm text-muted-foreground">
            <p>아이디: {user?.username}</p>
            <p>이메일: {user?.email ?? '-'}</p>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
