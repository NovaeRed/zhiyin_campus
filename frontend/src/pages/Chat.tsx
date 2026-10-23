import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Input, Spin, message } from 'antd';
import { Link, useSearchParams } from 'react-router-dom';
import Icon from '../components/Icon';
import type { IconName } from '../components/Icon';
import ragService from '../services/rag';
import type { RagSource } from '../services/rag';
import WorkspaceShell from '../components/WorkspaceShell';
import { getHistory } from '../services/portal';
import { errorMessage } from '../services/api';
import '../styles/Chat.css';
interface ChatMessage { id: number; text: string; sender: 'user' | 'bot'; sources?: RagSource[] }
const topics: { icon: IconName; title: string; description: string; question: string }[] = [
 { icon: 'cap', title: '培养方案', description: '课程安排，提前心中有数', question: '我想查询培养方案，需要提供哪些信息？' },
 { icon: 'chart', title: '学分与课程', description: '学分、学期，一问就明白', question: '2024级计算机科学与技术专业的大三有哪些课程？' },
 { icon: 'award', title: '奖助学金', description: '了解申请条件与评定规则', question: '奖学金的申请条件是什么？' },
];
export default function ChatPage() {
 const [params, setParams] = useSearchParams();
 const historyId = params.get('session');
 const [historyLoading, setHistoryLoading] = useState(false);
 const [historyError, setHistoryError] = useState('');
 const [historyRetry, setHistoryRetry] = useState(0);
 const [cohort, setCohort] = useState('');
 const [messages, setMessages] = useState<ChatMessage[]>([]);
 const [input, setInput] = useState('');
 const [loading, setLoading] = useState(false);

 const [sessionId, setSessionId] = useState<number>();
 const [major, setMajor] = useState('');
 const [grade, setGrade] = useState('');
 const end = useRef<HTMLDivElement>(null);
 const sending = useRef(false);
 useEffect(() => {
  if (!historyId) return;
  let active = true;
  // Loading state mirrors the external history request; cleanup ignores stale responses.
  // eslint-disable-next-line react-hooks/set-state-in-effect
  setHistoryLoading(true); setHistoryError('');
  async function load() {
   const id = Number(historyId); if (!Number.isSafeInteger(id) || id < 1) throw new Error('会话编号不合法');
   const restored: ChatMessage[] = []; let page = 1; let total: number;
   do {
    const result = await getHistory(id, page); total = result.total;
    for (const query of result.items) {
     restored.push({ id: query.queryId * 2, text: query.question, sender: 'user' });
     let sources: RagSource[] = []; try { const parsed = JSON.parse(query.sourcesJson || '[]'); if (Array.isArray(parsed)) sources = parsed; } catch { /* Older traces may have no source snapshot. */ }
     restored.push({ id: query.queryId * 2 + 1, text: query.answer || (query.state === 'completed' ? '该历史记录没有保存回答正文。' : '该次问答未完成，可重新提问。'), sender: 'bot', sources: query.answer ? sources : [] });
    }
    page++;
   } while ((page - 1) * 50 < total && active);
   if (active) { setMessages(restored); setSessionId(id); }
  }
  load().catch(e => { if (active) setHistoryError(errorMessage(e)); }).finally(() => { if (active) setHistoryLoading(false); });
  return () => { active = false; };
 }, [historyId, historyRetry]);
 useEffect(() => { end.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }); }, [messages, loading]);
 async function send() {
  if (!input.trim() || sending.current || loading || historyLoading || historyError || (grade && !/^\d{4}$/.test(grade))) return;
  sending.current = true;
  const question = input.trim();
  const userMessageId = Date.now();
  setMessages(prev => [...prev, { id: userMessageId, text: question, sender: 'user' }]); setInput(''); setLoading(true);
  try { const response = await ragService.ask({ question, sessionId, targetMajor: major.trim() || undefined, targetGrade: grade ? Number(grade) : undefined, targetCohort: cohort.trim() || undefined }); setSessionId(response.sessionId); setMessages(prev => [...prev, { id: Date.now(), text: response.answer, sender: 'bot', sources: response.sources }]); }
  catch (e) {
   message.error(errorMessage(e));
   setMessages(prev => prev.filter(item => item.id !== userMessageId));
   setInput(current => current || question);
  }
  finally { sending.current = false; setLoading(false); }
 }
 function newChat() { setParams({}); setMessages([]); setSessionId(undefined); setInput(''); setHistoryError(''); setHistoryLoading(false); }
 return <WorkspaceShell title="校园问答" onNew={newChat} busy={loading || historyLoading}>
   <div className="content-wrap">
    {historyId && <div className="session-heading"><span>历史会话 #{historyId} · 查询范围需按本次问题填写</span><Link to="/history">返回历史列表</Link></div>}
    {historyLoading ? <div className="history-load"><Spin description="正在读取历史对话…" /></div> : historyError ? <Alert type="error" title="无法读取会话" description={historyError} action={<Button onClick={() => setHistoryRetry(v => v + 1)}>重试</Button>} /> : messages.length === 0 ? <><section className="welcome-banner"><img src="/campus-garden.png" alt="绿树环绕的校园图书馆与书本雕塑" /><div className="banner-copy"><span className="eyebrow">YOUR CAMPUS COMPANION</span><h1>校园里的疑问，<br />在这里找到答案<span>。</span></h1><p>从一门课程，到一份成长计划。<br />让复杂的信息，成为清晰的下一步。</p><span className="banner-label"><Icon name="book" size={16} />有来源的解答，更安心的探索</span></div></section>
    <section className="explore"><div className="section-heading"><h2>从你关心的事开始</h2><span>选择一个方向，开启探索</span></div><div className="topic-grid">{topics.map((topic, i) => <button className={`topic-card topic-${i}`} key={topic.title} onClick={() => setInput(topic.question)}><span className="topic-icon"><Icon name={topic.icon} size={24} /></span><h3>{topic.title}</h3><p>{topic.description}</p><span className="topic-link">试着问一问 <Icon name="arrow" size={17} /></span></button>)}</div></section></> :
    <section className="conversation" aria-label="问答记录" aria-live="polite">{messages.map(item => <article className={`chat-message ${item.sender}`} key={item.id}><div className="message-avatar">{item.sender === 'bot' ? <Icon name="book" /> : '我'}</div><div className="message-body"><h3>{item.sender === 'bot' ? '知引' : '你'}</h3><div className="answer-text">{item.text}</div>{!!item.sources?.length && <div className="sources"><span>参考资料 · {item.sources.length}</span>{item.sources.map((source, i) => <details key={`${source.chunkId}-${i}`}><summary><Icon name="file" size={16} />[{i + 1}] {source.title}</summary><p>{source.content}</p></details>)}</div>}</div></article>)}{loading && <div className="thinking" role="status">正在查阅校园资料，请稍候…</div>}<div ref={end} /></section>}
    <section className="composer-area"><div className="scope-row"><span>查询范围（可选）</span><Input aria-label="专业" placeholder="专业，如计算机科学与技术" value={major} onChange={e => setMajor(e.target.value)} disabled={loading} /><Input aria-label="入学年份" placeholder="入学年份，如2024" maxLength={4} value={grade} onChange={e => setGrade(e.target.value.replace(/\D/g, ''))} disabled={loading} /><Input className="cohort-field" aria-label="毕业届别" placeholder="毕业届别，如2028届" value={cohort} onChange={e => setCohort(e.target.value)} disabled={loading} /></div><div className="composer"><Input.TextArea aria-label="你的校园问题" placeholder="有什么校园问题想了解？" value={input} onChange={e => setInput(e.target.value)} autoSize={{ minRows: 2, maxRows: 6 }} onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); void send(); } }} /><div className="composer-actions"><span className="attach-button">专业、入学年份与毕业届别可分别指定</span><span className="enter-hint">Enter 发送 · Shift + Enter 换行</span><Button type="primary" aria-label="发送问题" loading={loading} disabled={historyLoading || !!historyError || !input.trim() || (!!grade && !/^\d{4}$/.test(grade))} onClick={() => void send()}><Icon name="arrow" /></Button></div></div><p className="disclaimer">回答基于已有校园资料生成，请结合引用原文核实；具体事项以学校最新通知为准。</p></section>
   </div>
 </WorkspaceShell>;
}
