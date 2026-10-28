import api from './api';
export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}
export interface Account {
  id: number;
  studentNo: string;
  username: string;
  role: string;
  canManageDocuments: boolean;
}
export interface DocumentRecord {
  id: number;
  title: string;
  fileType: string;
  version: string;
  status: string;
  metadata: string;
  chunkStrategy: string;
  createdByName?: string;
  createdAt: string;
  updatedAt: string;
  parentChunks: number;
  childChunks: number;
}
export interface ChunkRecord {
  id: number;
  parentId?: number;
  chunkLevel: string;
  content: string;
  metadata: string;
  targetMajor?: string;
  targetGrade?: number;
  docStatus: string;
}
export interface SessionRecord {
  id: number;
  title: string;
  queryCount: number;
  updatedAt: string;
}
export interface HistoryQuery {
  queryId: number;
  question: string;
  state: string;
  answer?: string;
  sourcesJson?: string;
  createdAt: string;
}
export const getAccount = async (): Promise<Account> => (await api.get('/account/me')).data.data;
export const getDocuments = async (params: {
  page: number;
  pageSize: number;
  search: string;
  status: string;
}): Promise<Page<DocumentRecord>> => (await api.get('/admin/documents', { params })).data.data;
export const getDocument = async (id: number): Promise<DocumentRecord> =>
  (await api.get(`/admin/documents/${id}`)).data.data;
export const getChunks = async (id: number, page = 1): Promise<Page<ChunkRecord>> =>
  (await api.get(`/admin/documents/${id}/chunks`, { params: { page, pageSize: 10 } })).data.data;
export const setDocumentStatus = async (id: number, status: string) =>
  api.put(`/admin/documents/${id}/status`, { status });
export const deleteDocument = async (id: number) => api.delete(`/admin/documents/${id}`);
export interface DocumentEdit {
  title: string;
  version: string;
  metadata: {
    targetMajor: string | null;
    targetGrade: number | null;
    targetCohort: string | null;
    department: string | null;
    effectiveDate: string | null;
  };
}
export const editDocument = async (id: number, body: DocumentEdit) =>
  api.put(`/admin/documents/${id}`, body);
export const getDocumentOriginal = async (id: number) =>
  api.get<Blob>(`/admin/documents/${id}/original`, { responseType: 'blob' });
export const getSessions = async (page = 1): Promise<Page<SessionRecord>> =>
  (await api.get('/rag/sessions', { params: { page, pageSize: 12 } })).data.data;
export const deleteSession = async (id: number): Promise<{ deletedCount: number }> =>
  (await api.delete(`/rag/sessions/${id}`)).data.data;
export const deleteSessions = async (ids: number[]): Promise<{ deletedCount: number }> =>
  (await api.post('/rag/sessions/batch-delete', { ids })).data.data;
export const getHistory = async (id: number, page = 1): Promise<Page<HistoryQuery>> =>
  (await api.get(`/rag/sessions/${id}/queries`, { params: { page, pageSize: 50 } })).data.data;
export function metadata(value: string): Record<string, unknown> {
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
  } catch {
    return {};
  }
}
export function dateText(value: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—';
}
