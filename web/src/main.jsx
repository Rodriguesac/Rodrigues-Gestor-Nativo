import React from 'react';
import {createRoot} from 'react-dom/client';
import App from './App.jsx';
import './styles.css';
class StartupBoundary extends React.Component{
 constructor(props){super(props);this.state={failed:false}}
 static getDerivedStateFromError(){return {failed:true}}
 componentDidCatch(error){console.error('Falha na interface do Gestor',error)}
 render(){if(this.state.failed)return <div className="gate"><div className="login-card"><h1>Vamos reconectar?</h1><p>Não foi possível abrir esta tela. Seus pedidos continuam salvos na loja.</p><button className="primary" onClick={()=>location.reload()}>Tentar novamente</button></div></div>;return this.props.children}
}
if(!globalThis.AndroidGestor&&'serviceWorker' in navigator)addEventListener('load',()=>navigator.serviceWorker.register('./sw.js').catch(()=>{}));
createRoot(document.getElementById('root')).render(<StartupBoundary><App/></StartupBoundary>);
