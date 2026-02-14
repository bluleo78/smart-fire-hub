import { useAuth } from '../hooks/useAuth';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';

export function HomePage() {
  const { user, logout } = useAuth();

  return (
    <div className="flex min-h-screen items-center justify-center px-4">
      <Card className="w-full max-w-md">
        <CardHeader className="text-center">
          <CardTitle className="text-2xl">Smart Fire Hub</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="text-center">
            <p className="text-lg font-medium">
              환영합니다, {user?.name}님!
            </p>
            <div className="mt-2 space-y-1 text-sm text-muted-foreground">
              <p>아이디: {user?.username}</p>
              <p>이메일: {user?.email}</p>
            </div>
          </div>
          <Button variant="outline" className="w-full" onClick={logout}>
            로그아웃
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
