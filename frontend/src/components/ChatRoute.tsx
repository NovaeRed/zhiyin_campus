import { useLocation } from 'react-router-dom';
import ChatPage from '../pages/Chat';
export default function ChatRoute() { const location = useLocation(); return <ChatPage key={location.search} />; }
