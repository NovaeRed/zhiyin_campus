import { useEffect, useState } from 'react';
import { Alert, Button, Checkbox, Empty, Modal, Pagination, Spin, message } from 'antd';
import { Link } from 'react-router-dom';
import WorkspaceShell from '../components/WorkspaceShell';
import Icon from '../components/Icon';
import { getSessions, deleteSession, deleteSessions, dateText } from '../services/portal';
import type { Page, SessionRecord } from '../services/portal';
import { errorMessage } from '../services/api';
export default function HistoryPage() {
 const [page, setPage] = useState(1); const [data, setData] = useState<Page<SessionRecord>>(); const [error, setError] = useState(''); const [loading, setLoading] = useState(true); const [retry, setRetry] = useState(0);
 const [selected, setSelected] = useState<number[]>([]); const [deleting, setDeleting] = useState(false);
 const [modal, modalHolder] = Modal.useModal();
 // Request loading flags track the external API lifecycle; cleanup ignores stale responses.
 // eslint-disable-next-line react-hooks/set-state-in-effect
 useEffect(() => { let active = true; setLoading(true); setError(''); getSessions(page).then(result => { if (active) { setData(result); setSelected([]); if (!result.items.length && page > 1) setPage(Math.max(1, Math.ceil(result.total / 12))); } }).catch(e => { if (active) setError(errorMessage(e)); }).finally(() => { if (active) setLoading(false); }); return () => { active = false; }; }, [page, retry]);
 function confirmDelete(ids: number[]) {
  if (!ids.length || deleting) return;
  modal.confirm({ title: ids.length === 1 ? '删除这条对话？' : `删除选中的 ${ids.length} 条对话？`, content: '删除后将无法再查看或继续这些对话。', okText: '确认删除', cancelText: '取消', okButtonProps: { danger: true }, onOk: async () => {
   setDeleting(true);
   try {
    const result = ids.length === 1 ? await deleteSession(ids[0]) : await deleteSessions(ids);
    const nextPage = Math.min(page, Math.max(1, Math.ceil(Math.max(0, (data?.total ?? 0) - result.deletedCount) / 12)));
    setSelected([]); if (nextPage !== page) setPage(nextPage); else setRetry(v => v + 1);
    message.success('对话已删除');
   } catch (e) { message.error(errorMessage(e)); throw e; }
   finally { setDeleting(false); }
  } });
 }
 const allSelected = !!data?.items.length && data.items.every(session => selected.includes(session.id));
 return <WorkspaceShell title="历史对话">{modalHolder}<div className="portal-content"><div className="page-heading"><div><span className="eyebrow">YOUR KNOWLEDGE JOURNEY</span><h1>每一个问题，都留有足迹。</h1><p>找回之前的问答、参考来源，也可以继续探索。</p></div></div>{error ? <Alert type="error" title={error} action={<Button onClick={() => setRetry(v => v + 1)}>重试</Button>} /> : loading ? <Spin /> : data?.items.length ? <>
 <div className="history-toolbar" aria-label="对话管理"><Checkbox checked={allSelected} indeterminate={!!selected.length && !allSelected} disabled={deleting} onChange={event => setSelected(event.target.checked ? data.items.map(session => session.id) : [])}>全选本页</Checkbox><span>已选 {selected.length} 条</span>{!!selected.length && <Button type="text" disabled={deleting} onClick={() => setSelected([])}>取消选择</Button>}<Button danger loading={deleting} disabled={!selected.length} onClick={() => confirmDelete(selected)}>删除所选{selected.length ? `（${selected.length}）` : ''}</Button></div>
 <div className="history-grid">{data.items.map(session => <article className={`history-entry${selected.includes(session.id) ? ' is-selected' : ''}`} key={session.id}><div className="history-card-tools"><Checkbox aria-label={`选择对话：${session.title}`} checked={selected.includes(session.id)} disabled={deleting} onChange={event => setSelected(current => event.target.checked ? [...current, session.id] : current.filter(id => id !== session.id))}>选择</Checkbox><Button type="text" danger size="small" aria-label={`删除对话：${session.title}`} disabled={deleting} onClick={() => confirmDelete([session.id])}>删除</Button></div><Link className="history-card" to={`/?session=${session.id}`}><span className="topic-icon"><Icon name="chat" /></span><h2>{session.title}</h2><p>{session.queryCount} 次提问 · {dateText(session.updatedAt)}</p><span className="history-open">查看并继续对话 <Icon name="arrow" size={17} /></span></Link></article>)}</div><Pagination current={page} pageSize={12} total={data.total} onChange={setPage} disabled={deleting} showSizeChanger={false} /></> : <div className="portal-card"><Empty description="还没有历史对话，从一个校园问题开始吧" /><Link to="/">去提问</Link></div>}</div></WorkspaceShell>;
}
