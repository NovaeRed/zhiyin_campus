import { createContext, useContext } from 'react';
import type { Account } from '../services/portal';
export const AuthContext = createContext<{ account: Account | null; loading: boolean; error: string; reload: () => Promise<void> }>({ account: null, loading: true, error: '', reload: async () => {} });
export const useAuth = () => useContext(AuthContext);
