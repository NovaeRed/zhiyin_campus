import { useEffect, useState } from 'react';
import { Alert, Button, Empty, Pagination, Spin } from 'antd';
import { Link } from 'react-router-dom';
import WorkspaceShell from '../components/WorkspaceShell';
import Icon from '../components/Icon';
import { getSessions, dateText } from '../services/portal';
import type { Page, SessionRecord } from '../services/portal';
import { errorMessage } from '../services/api';
export default function HistoryPage() {
 const [page, setPage] = useState(1); const [data, setData] = useState<Page<SessionRecord>>(); const [error, setError] = useState(''); const [loading, setLoading] = useState(true); const [retry, setRetry] = useState(0);
 // Request loading flags track the external API lifecycle; cleanup ignores stale responses.
 // eslint-disable-next-line react-hooks/set-state-in-effect
 useEffect(() => { let active = true; setLoading(true); setError(''); getSessions(page).then(result => { if (active) setData(result); }).catch(e => { if (active) setError(errorMessage(e)); }).finally(() => { if (active) setLoading(false); }); return () => { active = false; }; }, [page, retry]);
 return <WorkspaceShell title="历史对话"><div className="portal-content"><div className="page-heading"><div><span className="eyebrow">YOUR KNOWLEDGE JOURNEY</span><h1>每一个问题，都留有足迹。</h1><p>找回之前的问答、参考来源，也可以继续探索。</p></div><Link className="history-new" to="/">开启新对话 <Icon name="arrow" size={18} /></Link></div>{error ? <Alert type="error" title={error} action={<Button onClick={() => setRetry(v => v + 1)}>重试</Button>} /> : loading ? <Spin /> : data?.items.length ? <><div className="history-grid">{data.items.map(session => <Link className="history-card" key={session.id} to={`/?session=${session.id}`}><span className="topic-icon"><Icon name="chat" /></span><h2>{session.title}</h2><p>{session.queryCount} 次提问 · {dateText(session.updatedAt)}</p><span className="history-open">查看并继续对话 <Icon name="arrow" size={17} /></span></Link>)}</div><Pagination current={page} pageSize={12} total={data.total} onChange={setPage} showSizeChanger={false} /></> : <div className="portal-card"><Empty description="还没有历史对话，从一个校园问题开始吧" /><Link to="/">去提问</Link></div>}</div></WorkspaceShell>;
}
