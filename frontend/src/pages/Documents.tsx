import { useEffect, useState } from 'react';
import { Alert, Button, Descriptions, Drawer, Empty, Form, Input, InputNumber, Modal, Pagination, Popconfirm, Select, Space, Spin, Table, Tabs, Tag, Upload, message } from 'antd';
import type { UploadFile } from 'antd';
import WorkspaceShell from '../components/WorkspaceShell';
import Icon from '../components/Icon';
import DocumentOriginal from '../components/DocumentOriginal';
import ragService from '../services/rag';
import type { UploadResult } from '../services/rag';
import { getDocuments, getDocument, getChunks, setDocumentStatus, editDocument, metadata, dateText } from '../services/portal';
import type { DocumentRecord, ChunkRecord, Page } from '../services/portal';
import { errorMessage } from '../services/api';
const statuses: Record<string, { label: string; color: string }> = { pending: { label: '待处理', color: 'default' }, processing: { label: '入库中', color: 'processing' }, active: { label: '已启用', color: 'success' }, deprecated: { label: '已停用', color: 'default' }, failed: { label: '入库失败', color: 'error' } };
function statusTag(status: string) { return <Tag color={statuses[status]?.color}>{statuses[status]?.label || status}</Tag>; }
interface UploadValues { title: string; version: string; strategy?: string; targetMajor?: string; targetGrade?: number; targetCohort?: string; department?: string; effectiveDate?: string }
export default function DocumentsPage() {
 const [form] = Form.useForm<UploadValues>();
 const [editForm] = Form.useForm<UploadValues>();
 const [editing, setEditing] = useState(false), [saving, setSaving] = useState(false), [editError, setEditError] = useState('');
 const [data, setData] = useState<Page<DocumentRecord>>(); const [page, setPage] = useState(1); const [search, setSearch] = useState(''); const [status, setStatus] = useState(''); const [revision, setRevision] = useState(0);
 const [loading, setLoading] = useState(true); const [error, setError] = useState(''); const [uploadOpen, setUploadOpen] = useState(false); const [file, setFile] = useState<UploadFile>(); const [uploading, setUploading] = useState(false); const [uploadResult, setUploadResult] = useState<UploadResult>(); const [uploadError, setUploadError] = useState('');
 const [detailId, setDetailId] = useState<number>(); const [detail, setDetail] = useState<DocumentRecord>(); const [chunks, setChunks] = useState<Page<ChunkRecord>>(); const [chunkPage, setChunkPage] = useState(1); const [detailError, setDetailError] = useState(''); const [detailLoading, setDetailLoading] = useState(false); const [changingId, setChangingId] = useState<number>();
 // Request loading flags track the external API lifecycle; cleanup ignores stale responses.
 // eslint-disable-next-line react-hooks/set-state-in-effect
 useEffect(() => { let active = true; setLoading(true); setError(''); getDocuments({ page, pageSize: 10, search, status }).then(result => { if (active) setData(result); }).catch(e => { if (active) setError(errorMessage(e)); }).finally(() => { if (active) setLoading(false); }); return () => { active = false; }; }, [page, search, status, revision]);
 // Request loading flags track the external API lifecycle; cleanup ignores stale responses.
 // eslint-disable-next-line react-hooks/set-state-in-effect
 useEffect(() => { if (!detailId) return; let active = true; setDetailLoading(true); setDetailError(''); Promise.all([getDocument(detailId), getChunks(detailId, chunkPage)]).then(([doc, list]) => { if (active) { setDetail(doc); setChunks(list); } }).catch(e => { if (active) setDetailError(errorMessage(e)); }).finally(() => { if (active) setDetailLoading(false); }); return () => { active = false; }; }, [detailId, chunkPage, revision]);
 async function upload(values: UploadValues) {
  if (uploading) return;
  if (!file?.originFileObj) { setUploadError('请先选择需要上传的文件'); return; }
  setUploading(true); setUploadError(''); setUploadResult(undefined);
  const body = new FormData(); body.append('file', file.originFileObj);
  Object.entries(values).forEach(([key, value]) => { if (value !== undefined && value !== null && String(value).trim()) body.append(key, String(value).trim()); });
  try { const result = await ragService.uploadDocument(body); setUploadResult(result); setFile(undefined); form.resetFields(); setRevision(v => v + 1); setPage(1); message.success('文档解析、向量化与入库已完成'); }
  catch (e) { setUploadError(errorMessage(e)); }
  finally { setUploading(false); }
 }
 async function changeStatus(doc: DocumentRecord) { setChangingId(doc.id); try { await setDocumentStatus(doc.id, doc.status === 'active' ? 'deprecated' : 'active'); message.success('文档状态已更新'); setRevision(v => v + 1); } catch (e) { message.error(errorMessage(e)); } finally { setChangingId(undefined); } }
 function openEdit() {
  if (!detail) return;
  const values = metadata(detail.metadata);
  editForm.resetFields(); editForm.setFieldsValue({ title: detail.title, version: detail.version,
   targetMajor: values.target_major as string || undefined, targetGrade: values.target_grade as number || undefined,
   targetCohort: values.target_cohort as string || undefined, department: values.department as string || undefined, effectiveDate: values.effective_date as string || undefined });
  setEditError(''); setEditing(true);
 }
 async function saveProperties(values: UploadValues) {
  if (!detail || saving) return;
  setSaving(true); setEditError('');
  const nullable = (value?: string) => value?.trim() || null;
  try { await editDocument(detail.id, { title: values.title.trim(), version: values.version.trim(), metadata: {
   targetMajor: nullable(values.targetMajor), targetGrade: values.targetGrade ?? null, targetCohort: nullable(values.targetCohort), department: nullable(values.department), effectiveDate: nullable(values.effectiveDate) } });
   setEditing(false); setRevision(v => v + 1); message.success('文档属性已保存，适用范围已同步更新');
  } catch (e) { setEditError(errorMessage(e)); } finally { setSaving(false); }
 }
 const meta = detail ? metadata(detail.metadata) : {};
 return <WorkspaceShell title="知识库管理"><div className="portal-content"><div className="page-heading"><div><span className="eyebrow">KNOWLEDGE WORKSPACE</span><h1>让校园知识，有序生长。</h1><p>维护权威资料，让每一条回答都有可靠的依据。</p></div><Button type="primary" size="large" icon={<Icon name="plus" />} onClick={() => { setUploadOpen(true); setUploadResult(undefined); setUploadError(''); }}>添加文档</Button></div>
 <section className="portal-card"><div className="table-toolbar"><div><h2>知识文档</h2><span>共 {data?.total ?? '—'} 份资料</span></div><Space wrap><Input.Search aria-label="搜索文档标题" placeholder="搜索文档标题" allowClear onSearch={value => { setSearch(value); setPage(1); }} style={{ width: 225 }} /><Select aria-label="文档状态筛选" value={status} style={{ width: 125 }} onChange={value => { setStatus(value); setPage(1); }} options={[{ value: '', label: '全部状态' }, ...Object.entries(statuses).map(([value, item]) => ({ value, label: item.label }))]} /><Button onClick={() => setRevision(v => v + 1)}>刷新</Button></Space></div>
 {error ? <Alert type="error" showIcon title="无法加载文档" description={error} action={<Button onClick={() => setRevision(v => v + 1)}>重试</Button>} /> : <Table rowKey="id" loading={loading} dataSource={data?.items} scroll={{ x: 830 }} locale={{ emptyText: <Empty description="没有匹配的文档，可以调整筛选或添加新资料" /> }} pagination={{ current: page, pageSize: 10, total: data?.total, showSizeChanger: false, onChange: setPage }} columns={[
 { title: '文档', dataIndex: 'title', render: (_, doc: DocumentRecord) => <button className="document-title" onClick={() => { setDetailId(doc.id); setChunkPage(1); }}><span className="doc-file-icon"><Icon name="file" /></span><span><strong>{doc.title}</strong><small>{doc.fileType.toUpperCase()} · {doc.version} · ID {doc.id}</small></span></button> },
 { title: '状态', dataIndex: 'status', render: statusTag },
 { title: '切片', render: (_, doc: DocumentRecord) => <span className="muted-text">{doc.childChunks} 子块 / {doc.parentChunks} 父块</span> },
 { title: '创建时间', dataIndex: 'createdAt', render: value => <span className="muted-text">{dateText(value)}</span> },
 { title: '操作', width: 145, render: (_, doc: DocumentRecord) => <Space><Button type="link" size="small" onClick={() => { setDetailId(doc.id); setChunkPage(1); }}>详情</Button>{['active', 'deprecated'].includes(doc.status) && <Popconfirm title={doc.status === 'active' ? '停用这份文档？' : '重新启用这份文档？'} description={doc.status === 'active' ? '该文档及其切片将退出后续问答检索。' : '该文档将重新参与问答检索，请确认版本有效。'} onConfirm={() => changeStatus(doc)}><Button type="link" size="small" danger={doc.status === 'active'} loading={changingId === doc.id}>{doc.status === 'active' ? '停用' : '启用'}</Button></Popconfirm>}</Space> },
 ]} /> }<p className="table-scroll-hint">左右滑动表格，可查看文档状态与操作。</p></section></div>
 <Drawer title="添加知识文档" open={uploadOpen} width="min(580px, 100vw)" destroyOnHidden onClose={() => { if (!uploading) setUploadOpen(false); }} closable={!uploading} maskClosable={!uploading} keyboard={!uploading}>
 {uploadResult && <Alert type="success" showIcon title="文档已成功入库" description={`ID ${uploadResult.docId} · ${uploadResult.title} · ${uploadResult.childChunks} 个子块 / ${uploadResult.parentChunks} 个父块`} style={{ marginBottom: 20 }} />}
 <Alert type="info" showIcon title="请选择可提取正文的文件" description="支持 PDF、DOCX、XLSX、MD、HTML、TXT，单文件不超过20MB。扫描件需先 OCR。提交将调用配置的 Embedding 模型，解析和向量化可能需要一段时间。" style={{ marginBottom: 22 }} />
 <Form form={form} layout="vertical" onFinish={upload} initialValues={{ version: 'v1' }} disabled={uploading} requiredMark="optional">
 <Form.Item label="原始文件" required><Upload.Dragger maxCount={1} accept=".pdf,.docx,.xlsx,.md,.html,.txt" fileList={file ? [file] : []} disabled={uploading} beforeUpload={candidate => { if (!/\.(pdf|docx|xlsx|md|html|txt)$/i.test(candidate.name)) { message.error('暂不支持此文件格式'); return Upload.LIST_IGNORE; } if (candidate.size > 20 * 1024 * 1024 || candidate.size === 0) { message.error('文件应大于0字节且不超过20MB'); return Upload.LIST_IGNORE; } setFile({ uid: candidate.uid, name: candidate.name, originFileObj: candidate }); setUploadResult(undefined); if (!form.getFieldValue('title')) form.setFieldValue('title', candidate.name.replace(/\.[^.]+$/, '')); return false; }} onRemove={() => { setFile(undefined); return true; }}><Icon name="upload" size={28} /><p>点击或拖入文件</p><small>一次添加一份文档</small></Upload.Dragger></Form.Item>
 <Form.Item name="title" label="文档标题" rules={[{ required: true, whitespace: true, message: '请输入文档标题' }, { max: 255 }]}><Input placeholder="如：2024级计算机科学与技术培养方案" maxLength={255} /></Form.Item>
 <div className="form-grid"><Form.Item name="version" label="文档版本" rules={[{ required: true, whitespace: true }, { max: 32 }]}><Input maxLength={32} placeholder="v1" /></Form.Item><Form.Item name="strategy" label="切片策略"><Select allowClear placeholder="使用后端默认策略" options={[{ value: 'recursive', label: '递归切片' }, { value: 'parent_child', label: '父子切片' }]} /></Form.Item></div>
 <div className="form-grid"><Form.Item name="targetMajor" label="适用专业"><Input maxLength={100} placeholder="留空可按正文推断" /></Form.Item><Form.Item name="targetGrade" label="入学年份"><InputNumber min={1900} max={2200} precision={0} placeholder="如2024，留空可推断" style={{ width: '100%' }} /></Form.Item></div>
 <div className="form-grid"><Form.Item name="targetCohort" label="毕业届别"><Input maxLength={32} placeholder="如2028届，非入学年份" /></Form.Item><Form.Item name="department" label="发布部门"><Input maxLength={100} placeholder="如教务处" /></Form.Item></div>
 <Form.Item name="effectiveDate" label="生效日期"><Input type="date" /></Form.Item>
 <p className="form-helper">版本号、入学年份与毕业届别分别保存；通用政策可留空专业和年份。</p>
 {uploadError && <Alert type="error" showIcon title="入库未完成" description={uploadError} style={{ margin: '15px 0' }} />}
 {uploading && <Alert type="info" showIcon title="正在解析并入库，请保持页面开启" style={{ margin: '15px 0' }} />}
 <Button type="primary" htmlType="submit" block size="large" loading={uploading}>上传并入库</Button></Form></Drawer>
 <Drawer title="文档详情" width="min(760px, 100vw)" destroyOnHidden open={detailId !== undefined} onClose={() => { if (saving) return; setEditing(false); setDetailId(undefined); setDetail(undefined); setChunks(undefined); }}>
 {detailLoading ? <Spin /> : detailError ? <Alert type="error" title={detailError} /> : detail && <>
  <div className="document-detail-heading"><h2 className="detail-title">{detail.title}</h2><Button onClick={openEdit} disabled={!['active', 'deprecated'].includes(detail.status)}>编辑属性</Button></div>
  <Tabs defaultActiveKey="original" items={[
   { key: 'original', label: '原始文档', children: <DocumentOriginal document={detail} /> },
   { key: 'properties', label: '文档属性', children: <Descriptions size="small" column={2} bordered items={[
    { key: 'id', label: '文档 ID', children: detail.id }, { key: 'status', label: '状态', children: statusTag(detail.status) },
    { key: 'version', label: '版本', children: detail.version }, { key: 'format', label: '格式', children: detail.fileType.toUpperCase() },
    { key: 'major', label: '适用专业', children: String(meta.target_major || '通用 / 未指定') }, { key: 'grade', label: '入学年份', children: String(meta.target_grade || '未指定') },
    { key: 'cohort', label: '毕业届别', children: String(meta.target_cohort || '未指定') }, { key: 'dept', label: '发布部门', children: String(meta.department || '未指定') },
    { key: 'effective', label: '生效日期', children: String(meta.effective_date || '未指定') }, { key: 'creator', label: '上传人', children: detail.createdByName || '未知' },
    { key: 'strategy', label: '切片策略', children: String(metadata(detail.chunkStrategy).type || '未记录') }, { key: 'counts', label: '切片数量', children: `${detail.childChunks} 子块 / ${detail.parentChunks} 父块` },
   ]} /> },
   { key: 'chunks', label: '切片正文', children: <><h3 className="chunks-heading">切片正文 <span>共 {chunks?.total ?? 0} 块</span></h3>{chunks?.items.map(chunk => <details className="chunk-preview" key={chunk.id}><summary><Tag>{chunk.chunkLevel === 'parent' ? '父块' : '子块'}</Tag>#{chunk.id}{chunk.parentId && <span> · 父块 #{chunk.parentId}</span>}</summary><p>{chunk.content}</p><small>{chunk.targetMajor || '通用专业'} · {chunk.targetGrade || '未指定年份'}</small></details>)}<Pagination current={chunkPage} total={chunks?.total} pageSize={10} onChange={setChunkPage} showSizeChanger={false} /></> },
  ]} />
 </>}
 </Drawer>
 <Modal title="编辑文档属性" open={editing} onCancel={() => { if (!saving) setEditing(false); }} closable={!saving} maskClosable={!saving} keyboard={!saving} footer={null} destroyOnHidden>
  <Form form={editForm} layout="vertical" onFinish={saveProperties} disabled={saving} requiredMark="optional">
   <Form.Item name="title" label="文档标题" rules={[{ required: true, whitespace: true, message: '请输入文档标题' }, { max: 255 }]}><Input maxLength={255} /></Form.Item>
   <Form.Item name="version" label="文档版本" rules={[{ required: true, whitespace: true }, { max: 32 }]}><Input maxLength={32} /></Form.Item>
   <div className="form-grid"><Form.Item name="targetMajor" label="适用专业"><Input maxLength={100} placeholder="通用资料可留空" /></Form.Item><Form.Item name="targetGrade" label="入学年份"><InputNumber min={1900} max={2200} precision={0} style={{ width: '100%' }} /></Form.Item></div>
   <div className="form-grid"><Form.Item name="targetCohort" label="毕业届别" rules={[{ pattern: /^20\d{2}届$/, message: '请输入如2028届，或留空' }]}><Input maxLength={32} placeholder="如2028届" /></Form.Item><Form.Item name="department" label="发布部门"><Input maxLength={100} /></Form.Item></div>
   <Form.Item name="effectiveDate" label="生效日期"><Input type="date" /></Form.Item>
   <p className="form-helper">保存后，适用范围会同步用于后续检索。清空字段表示未指定；原始文件和课程正文保持原样。</p>
   {editError && <Alert type="error" showIcon title="保存失败" description={editError} style={{ marginBottom: 16 }} />}
   <Space><Button type="primary" htmlType="submit" loading={saving}>保存属性</Button><Button disabled={saving} onClick={() => setEditing(false)}>取消</Button></Space>
  </Form>
 </Modal></WorkspaceShell>;
}
