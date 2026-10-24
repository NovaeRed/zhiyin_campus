<script setup lang="ts">
import { ref, computed, watch, shallowRef } from 'vue';
import { message, Upload } from 'ant-design-vue';
import WorkspaceShell from '../components/WorkspaceShell.vue';
import DocumentOriginal from '../components/DocumentOriginal.vue';
import DocumentFields from '../components/DocumentFields.vue';
import Icon from '../components/Icon.vue';
import { useAuth } from '../composables/auth';
import ragService from '../services/rag';
import type { UploadResult } from '../services/rag';
import { getDocuments, getDocument, getChunks, setDocumentStatus, deleteDocument, editDocument, metadata, dateText } from '../services/portal';
import type { DocumentRecord, ChunkRecord, Page } from '../services/portal';
import type { DocumentValues } from '../types/documents';
import { errorMessage } from '../services/api';
const { account } = useAuth();
const statuses: Record<string, { label: string; color: string }> = { pending: { label: '待处理', color: 'default' }, processing: { label: '入库中', color: 'processing' }, active: { label: '已启用', color: 'success' }, deprecated: { label: '已停用', color: 'default' }, failed: { label: '入库失败', color: 'error' } };
const data = ref<Page<DocumentRecord>>(), page = ref(1), search = ref(''), status = ref(''), revision = ref(0), loading = ref(true), error = ref('');
const uploadOpen = ref(false), file = shallowRef<File>(), uploading = ref(false), uploadResult = ref<UploadResult>(), uploadError = ref(''), uploadForm = ref<DocumentValues>({ title: '', version: 'v1' });
const detailId = ref<number>(), detail = ref<DocumentRecord>(), chunks = ref<Page<ChunkRecord>>(), chunkPage = ref(1), detailTab = ref('original'), detailLoading = ref(false), detailError = ref('');
const changingId = ref<number>(), deletingId = ref<number>(), editing = ref(false), saving = ref(false), editError = ref(''), editForm = ref<DocumentValues>({ title: '', version: '' });
const columns = [{ title: '文档', key: 'document' }, { title: '状态', key: 'status' }, { title: '切片', key: 'chunks' }, { title: '创建时间', key: 'createdAt' }, { title: '操作', key: 'action', width: 210 }];
const statusOptions = [{ value: '', label: '全部状态' }, ...Object.entries(statuses).map(([value, item]) => ({ value, label: item.label }))];
const files = computed(() => file.value ? [{ uid: 'selected', name: file.value.name, status: 'done' as const }] : []);
const meta = computed(() => detail.value ? metadata(detail.value.metadata) : {});
watch([page, search, status, revision, () => account.value?.canManageDocuments], async (_values, _old, onCleanup) => {
 if (!account.value?.canManageDocuments) return; let active = true; onCleanup(() => { active = false; }); loading.value = true; error.value = '';
 try { const result = await getDocuments({ page: page.value, pageSize: 10, search: search.value, status: status.value }); if (active) data.value = result; }
 catch (e) { if (active) error.value = errorMessage(e); } finally { if (active) loading.value = false; }
}, { immediate: true });
watch([detailId, chunkPage, revision], async (_values, _old, onCleanup) => {
 if (!detailId.value) return; let active = true; onCleanup(() => { active = false; }); detailLoading.value = true; detailError.value = '';
 try { const [doc, list] = await Promise.all([getDocument(detailId.value), getChunks(detailId.value, chunkPage.value)]); if (active) { detail.value = doc; chunks.value = list; } }
 catch (e) { if (active) detailError.value = errorMessage(e); } finally { if (active) detailLoading.value = false; }
});
function searchDocuments(value: string) { search.value = value; page.value = 1; }
function openDetail(id: number) { detail.value = undefined; chunks.value = undefined; detailTab.value = 'original'; detailId.value = id; chunkPage.value = 1; }
function closeDetail() { if (saving.value) return; detailId.value = undefined; detail.value = undefined; chunks.value = undefined; editing.value = false; }
async function changeStatus(doc: DocumentRecord) { changingId.value = doc.id; try { await setDocumentStatus(doc.id, doc.status === 'active' ? 'deprecated' : 'active'); message.success('文档状态已更新'); revision.value++; } catch (e) { message.error(errorMessage(e)); } finally { changingId.value = undefined; } }
async function removeDocument(doc: DocumentRecord) { if (deletingId.value !== undefined) return; deletingId.value = doc.id;
 try { await deleteDocument(doc.id); message.success('文档已删除'); if (detailId.value === doc.id) closeDetail(); if (data.value?.items.length === 1 && page.value > 1) page.value--; revision.value++; }
 catch (e) { message.error(errorMessage(e)); } finally { deletingId.value = undefined; }
}
function beforeUpload(candidate: File) {
 if (!/\.(pdf|docx|xlsx|md|html|txt)$/i.test(candidate.name)) { message.error('暂不支持此文件格式'); return Upload.LIST_IGNORE; }
 if (candidate.size > 20 * 1024 * 1024 || candidate.size === 0) { message.error('文件应大于0字节且不超过20MB'); return Upload.LIST_IGNORE; }
 file.value = candidate; uploadResult.value = undefined; if (!uploadForm.value.title) uploadForm.value.title = candidate.name.replace(/\.[^.]+$/, ''); return false;
}
async function upload() { if (uploading.value) return; if (!file.value) { uploadError.value = '请先选择需要上传的文件'; return; }
 uploading.value = true; uploadError.value = ''; uploadResult.value = undefined;
 const body = new FormData(); body.append('file', file.value); Object.entries(uploadForm.value).forEach(([key, value]) => { if (value !== undefined && value !== null && String(value).trim()) body.append(key, String(value).trim()); });
 try { uploadResult.value = await ragService.uploadDocument(body); file.value = undefined; uploadForm.value = { title: '', version: 'v1' }; revision.value++; page.value = 1; message.success('文档解析、向量化与入库已完成'); }
 catch (e) { uploadError.value = errorMessage(e); } finally { uploading.value = false; }
}
function openEdit() { if (!detail.value) return; const value = meta.value; editForm.value = { title: detail.value.title, version: detail.value.version, targetMajor: value.target_major as string || undefined, targetGrade: value.target_grade as number || undefined, targetCohort: value.target_cohort as string || undefined, department: value.department as string || undefined, effectiveDate: value.effective_date as string || undefined }; editError.value = ''; editing.value = true; }
async function saveProperties() { if (!detail.value || saving.value) return; saving.value = true; editError.value = ''; const values = editForm.value, nullable = (value?: string) => value?.trim() || null;
 try { await editDocument(detail.value.id, { title: values.title.trim(), version: values.version.trim(), metadata: { targetMajor: nullable(values.targetMajor), targetGrade: values.targetGrade ?? null, targetCohort: nullable(values.targetCohort), department: nullable(values.department), effectiveDate: nullable(values.effectiveDate) } }); editing.value = false; detailTab.value = 'properties'; revision.value++; message.success('文档属性已保存，适用范围已同步更新'); }
 catch (e) { editError.value = errorMessage(e); } finally { saving.value = false; }
}
</script>
<template><WorkspaceShell title="知识库管理"><div class="portal-content"><div class="page-heading"><div><span class="eyebrow">KNOWLEDGE WORKSPACE</span><h1>让校园知识，有序生长。</h1><p>维护权威资料，让每一条回答都有可靠的依据。</p></div><a-button type="primary" size="large" @click="uploadOpen = true; uploadResult = undefined; uploadError = ''"><template #icon><Icon name="plus" /></template>添加文档</a-button></div>
 <section class="portal-card"><div class="table-toolbar"><div><h2>知识文档</h2><span>共 {{ data?.total ?? '—' }} 份资料</span></div><a-space wrap><a-input-search aria-label="搜索文档标题" placeholder="搜索文档标题" allow-clear style="width:225px" @search="searchDocuments" /><a-select v-model:value="status" aria-label="文档状态筛选" style="width:125px" :options="statusOptions" @change="page = 1" /><a-button @click="revision++">刷新</a-button></a-space></div>
 <a-alert v-if="error" type="error" show-icon message="无法加载文档" :description="error"><template #action><a-button @click="revision++">重试</a-button></template></a-alert>
 <a-table v-else row-key="id" :columns="columns" :loading="loading" :data-source="data?.items" :scroll="{ x: 830 }" :pagination="{ current: page, pageSize: 10, total: data?.total, showSizeChanger: false, onChange: (value: number) => page = value }">
  <template #emptyText><a-empty description="没有匹配的文档，可以调整筛选或添加新资料" /></template>
  <template #bodyCell="{ column, record: doc }">
   <button v-if="column.key === 'document'" class="document-title" @click="openDetail(doc.id)"><span class="doc-file-icon"><Icon name="file" /></span><span><strong>{{ doc.title }}</strong><small>{{ doc.fileType.toUpperCase() }} · {{ doc.version }} · ID {{ doc.id }}</small></span></button>
   <a-tag v-else-if="column.key === 'status'" :color="statuses[doc.status]?.color">{{ statuses[doc.status]?.label || doc.status }}</a-tag>
   <span v-else-if="column.key === 'chunks'" class="muted-text">{{ doc.childChunks }} 子块 / {{ doc.parentChunks }} 父块</span>
   <span v-else-if="column.key === 'createdAt'" class="muted-text">{{ dateText(doc.createdAt) }}</span>
   <a-space v-else-if="column.key === 'action'"><a-button type="link" size="small" :disabled="deletingId === doc.id" @click="openDetail(doc.id)">详情</a-button>
    <a-popconfirm v-if="['active', 'deprecated'].includes(doc.status)" :title="doc.status === 'active' ? '停用这份文档？' : '重新启用这份文档？'" :description="doc.status === 'active' ? '该文档及其切片将退出后续问答检索。' : '该文档将重新参与问答检索，请确认版本有效。'" @confirm="changeStatus(doc)"><a-button type="link" size="small" :disabled="deletingId === doc.id" :danger="doc.status === 'active'" :loading="changingId === doc.id">{{ doc.status === 'active' ? '停用' : '启用' }}</a-button></a-popconfirm>
    <a-popconfirm title="删除这份文档？" description="删除后将从列表移除并停止参与检索；历史引用和原始文件保留。" ok-text="删除文档" cancel-text="取消" :ok-button-props="{ danger: true }" @confirm="removeDocument(doc)"><a-button type="link" size="small" danger :loading="deletingId === doc.id" :disabled="changingId === doc.id || ['pending', 'processing'].includes(doc.status)">删除</a-button></a-popconfirm>
   </a-space>
  </template>
 </a-table><p class="table-scroll-hint">左右滑动表格，可查看文档状态与操作。</p></section></div>
 <a-drawer title="添加知识文档" :open="uploadOpen" width="min(580px, 100vw)" destroy-on-close :closable="!uploading" :mask-closable="!uploading" :keyboard="!uploading" @close="uploadOpen = false">
  <a-alert v-if="uploadResult" type="success" show-icon message="文档已成功入库" :description="`ID ${uploadResult.docId} · ${uploadResult.title} · ${uploadResult.childChunks} 个子块 / ${uploadResult.parentChunks} 个父块`" style="margin-bottom:20px" />
  <a-alert type="info" show-icon message="请选择可提取正文的文件" description="支持 PDF、DOCX、XLSX、MD、HTML、TXT，单文件不超过20MB。扫描件需先 OCR。提交将调用配置的 Embedding 模型，解析和向量化可能需要一段时间。" style="margin-bottom:22px" />
  <a-form :model="uploadForm" layout="vertical" :disabled="uploading" required-mark="optional" @finish="upload"><a-form-item label="原始文件" required><a-upload-dragger :max-count="1" accept=".pdf,.docx,.xlsx,.md,.html,.txt" :file-list="files" :disabled="uploading" :before-upload="beforeUpload" @remove="file = undefined"><Icon name="upload" :size="28" /><p>点击或拖入文件</p><small>一次添加一份文档</small></a-upload-dragger></a-form-item><DocumentFields v-model="uploadForm" upload /><p class="form-helper">版本号、入学年份与毕业届别分别保存；通用政策可留空专业和年份。</p><a-alert v-if="uploadError" type="error" show-icon message="入库未完成" :description="uploadError" style="margin:15px 0" /><a-alert v-if="uploading" type="info" show-icon message="正在解析并入库，请保持页面开启" style="margin:15px 0" /><a-button type="primary" html-type="submit" block size="large" :loading="uploading">上传并入库</a-button></a-form>
 </a-drawer>
 <a-drawer title="文档详情" width="min(760px, 100vw)" destroy-on-close :open="detailId !== undefined" :closable="!saving" :mask-closable="!saving" :keyboard="!saving" @close="closeDetail">
  <a-spin v-if="detailLoading" /><a-alert v-else-if="detailError" type="error" :message="detailError" />
  <template v-else-if="detail"><div class="document-detail-heading"><h2 class="detail-title">{{ detail.title }}</h2><a-button :disabled="!['active', 'deprecated'].includes(detail.status)" @click="openEdit">编辑属性</a-button></div><a-tabs v-model:active-key="detailTab">
   <a-tab-pane key="original" tab="原始文档"><DocumentOriginal :document="detail" /></a-tab-pane>
   <a-tab-pane key="properties" tab="文档属性"><a-descriptions size="small" :column="2" bordered>
    <a-descriptions-item label="文档 ID">{{ detail.id }}</a-descriptions-item><a-descriptions-item label="状态"><a-tag :color="statuses[detail.status]?.color">{{ statuses[detail.status]?.label || detail.status }}</a-tag></a-descriptions-item><a-descriptions-item label="版本">{{ detail.version }}</a-descriptions-item><a-descriptions-item label="格式">{{ detail.fileType.toUpperCase() }}</a-descriptions-item>
    <a-descriptions-item label="适用专业">{{ meta.target_major || '通用 / 未指定' }}</a-descriptions-item><a-descriptions-item label="入学年份">{{ meta.target_grade || '未指定' }}</a-descriptions-item><a-descriptions-item label="毕业届别">{{ meta.target_cohort || '未指定' }}</a-descriptions-item><a-descriptions-item label="发布部门">{{ meta.department || '未指定' }}</a-descriptions-item><a-descriptions-item label="生效日期">{{ meta.effective_date || '未指定' }}</a-descriptions-item><a-descriptions-item label="上传人">{{ detail.createdByName || '未知' }}</a-descriptions-item><a-descriptions-item label="切片策略">{{ metadata(detail.chunkStrategy).type || '未记录' }}</a-descriptions-item><a-descriptions-item label="切片数量">{{ detail.childChunks }} 子块 / {{ detail.parentChunks }} 父块</a-descriptions-item>
   </a-descriptions></a-tab-pane>
   <a-tab-pane key="chunks" tab="切片正文"><h3 class="chunks-heading">切片正文 <span>共 {{ chunks?.total ?? 0 }} 块</span></h3><details v-for="chunk in chunks?.items" :key="chunk.id" class="chunk-preview"><summary><a-tag>{{ chunk.chunkLevel === 'parent' ? '父块' : '子块' }}</a-tag>#{{ chunk.id }}<span v-if="chunk.parentId"> · 父块 #{{ chunk.parentId }}</span></summary><p>{{ chunk.content }}</p><small>{{ chunk.targetMajor || '通用专业' }} · {{ chunk.targetGrade || '未指定年份' }}</small></details><a-pagination v-model:current="chunkPage" :total="chunks?.total" :page-size="10" :show-size-changer="false" /></a-tab-pane>
  </a-tabs></template>
 </a-drawer>
 <a-modal title="编辑文档属性" :open="editing" :closable="!saving" :mask-closable="!saving" :keyboard="!saving" :footer="null" destroy-on-close @cancel="editing = false"><a-form :model="editForm" layout="vertical" :disabled="saving" required-mark="optional" @finish="saveProperties"><DocumentFields v-model="editForm" /><p class="form-helper">保存后，适用范围会同步用于后续检索。清空字段表示未指定；原始文件和课程正文保持原样。</p><a-alert v-if="editError" type="error" show-icon message="保存失败" :description="editError" style="margin-bottom:16px" /><a-space><a-button type="primary" html-type="submit" :loading="saving">保存属性</a-button><a-button :disabled="saving" @click="editing = false">取消</a-button></a-space></a-form></a-modal>
</WorkspaceShell></template>
