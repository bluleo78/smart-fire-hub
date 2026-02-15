import { useQuery } from '@tanstack/react-query';
import { usersApi } from '../../api/users';

export function useUsers(params: { search?: string; page?: number; size?: number }) {
  return useQuery({
    queryKey: ['users', params],
    queryFn: () => usersApi.getUsers(params).then(r => r.data),
  });
}
