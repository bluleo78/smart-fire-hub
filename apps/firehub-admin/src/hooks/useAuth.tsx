import { useContext } from 'react';

import type { AuthContextValue } from './auth-context-value';
import { AuthContext } from './auth-context-value';

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
