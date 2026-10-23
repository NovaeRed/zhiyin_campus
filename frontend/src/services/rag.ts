import api from './api';
export interface RagSource { chunkId: number; docId: number; parentId?: number; title: string; content: string }
export interface RagAnswer { queryId: number; sessionId: number; answer: string; sources: RagSource[] }
export interface UploadResult { docId: number; title: string; fileType: string; strategy: string; parentChunks: number; childChunks: number; status: string }
const uploadDocument = async (data: FormData): Promise<UploadResult> => { const response = await api.post('/rag/documents', data); return response.data.data; };
const ask = async (data: { question: string; requestId: string; sessionId?: number; targetMajor?: string; targetGrade?: number; targetCohort?: string }): Promise<RagAnswer> => {
 const response = await api.post('/rag/ask', data); return response.data.data;
};
const requestStatus = async (requestId: string): Promise<{ state: string }> => (await api.get(`/rag/requests/${encodeURIComponent(requestId)}`)).data.data;
export default { uploadDocument, ask, requestStatus };
