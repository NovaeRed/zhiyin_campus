import { useCallback, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { getAccount } from '../services/portal';
import type { Account } from '../services/portal';
import { ApiError, clearTokens, errorMessage } from '../services/api';
import { AuthContext } from './authContext';
export default function AuthProvider({ children }: { children: ReactNode }) {
 const [account, setAccount] = useState<Account | null>(null); const [loading, setLoading] = useState(true); const [error, setError] = useState('');
 const reload = useCallback(async () => { setLoading(true); setError(''); try { setAccount(localStorage.getItem('token') ? await getAccount() : null); } catch (e) { setAccount(null); setError(errorMessage(e)); if (e instanceof ApiError && e.code === 401) clearTokens(); } finally { setLoading(false); } }, []);
 // Request loading flags track the external API lifecycle; cleanup ignores stale responses.
 // eslint-disable-next-line react-hooks/set-state-in-effect
 useEffect(() => { void reload(); const expired = () => { setAccount(null); clearTokens(); }; window.addEventListener('auth-expired', expired); return () => window.removeEventListener('auth-expired', expired); }, [reload]);
 return <AuthContext.Provider value={{ account, loading, error, reload }}>{children}</AuthContext.Provider>;
}
