import { ref } from 'vue';
import { getAccount } from '../services/portal';
import type { Account } from '../services/portal';
import { ApiError, clearTokens, errorMessage } from '../services/api';
const account = ref<Account | null>(null), loading = ref(false), error = ref('');
let pending: Promise<void> | null = null;
async function reload() {
 if (pending) return pending;
 pending = (async () => { loading.value = true; error.value = ''; try { account.value = localStorage.getItem('token') ? await getAccount() : null; }
 catch (e) { account.value = null; error.value = errorMessage(e); if (e instanceof ApiError && e.code === 401) clearTokens(); }
 finally { loading.value = false; } })().finally(() => { pending = null; });
 return pending;
}
window.addEventListener('auth-expired', () => { account.value = null; clearTokens(); window.dispatchEvent(new Event('account-expired')); });
export function useAuth() { return { account, loading, error, reload }; }
