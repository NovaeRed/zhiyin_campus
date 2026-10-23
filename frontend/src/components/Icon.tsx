export type IconName = 'book' | 'chat' | 'plus' | 'upload' | 'arrow' | 'cap' | 'award' | 'chart' | 'logout' | 'file';
const paths: Record<IconName, string> = {
 book: 'M12 6c-3-2-6-2-9-1v14c3-1 6-1 9 1m0-14c3-2 6-2 9-1v14c-3-1-6-1-9 1V6',
 chat: 'M21 11a9 9 0 0 1-9 9H4l-2 2V11a9 9 0 0 1 19 0ZM7 9h10M7 13h7',
 plus: 'M12 5v14M5 12h14', upload: 'M12 16V3m-5 5 5-5 5 5M4 15v6h16v-6',
 arrow: 'M5 12h14m-6-6 6 6-6 6', cap: 'm2 9 10-5 10 5-10 5L2 9Zm4 2v6c4 3 8 3 12 0v-6m4-2v8',
 award: 'M8 14 6 22l6-3 6 3-2-8M19 9A7 7 0 1 1 5 9a7 7 0 0 1 14 0Z', chart: 'M4 3v18h17M8 16v-5m5 5V6m5 10v-8',
 logout: 'M9 4H4v16h5m6-13 5 5-5 5m-6-5h11', file: 'M14 2H5v20h14V7l-5-5Zm0 0v6h5M8 12h8m-8 4h6',
};
export default function Icon({ name, size = 20 }: { name: IconName; size?: number }) { return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[name]} /></svg>; }
