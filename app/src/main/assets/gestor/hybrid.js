/* Shared PWA + Android integration. No administrative key is embedded. */
function nativeAction(action, extra={}) {
  if (!window.RodriguesNative) { toast('Recurso disponível no aplicativo Android.'); return; }
  window.RodriguesNative.postMessage(JSON.stringify({action,...extra}));
}
window.startRodriguesNative = async function(pin, requestedId) {
  try {
    if(!sessionStorage.getItem(SESSION_KEY)) {
      const data = await api('login',{pin});
      sessionStorage.setItem(SESSION_KEY,data.session_token);
    }
    await enterApp();
    if(requestedId) await window.openNativeOrder(requestedId);
  } catch(error) { showLogin(); $('#loginError').textContent=error.message; $('#loginError').classList.remove('hidden'); }
};
window.openNativeOrder = async function(id) {
  if(!state.session)return;
  await loadOrders(); renderCore();
  const order=state.orders.find(o=>o.id===id||o.order_code===id);
  if(order){await openOrder(order.id);nativeAction('silence',{orderId:order.id});}
  else toast('Pedido não localizado. Atualize a lista e confira o histórico.');
};
window.nativeBack = function() {
  for(const [modal,button] of [['reasonModal','reasonClose'],['mapModal','mapBack'],['chatModal','chatBack']]) {
    if(!$('#'+modal).classList.contains('hidden')){$('#'+button).click();return true;}
  }
  if(!$('#actionSheet').classList.contains('hidden')){closeSheet();return true;}
  if(!$('#detailView').classList.contains('hidden')){$('#detailBack').click();return true;}
  if(state.activePage!=='home'){switchPage('home');return true;}
  return false;
};
window.refreshNativeOrders = async function(){if(state.session){await loadOrders();renderCore();renderActivePage();}};
if(window.__RODRIGUES_ANDROID__) {
  const header=document.querySelector('.header-actions');
  const voice=document.createElement('button');voice.className='round-btn';voice.textContent='🎙';voice.setAttribute('aria-label','Comando de voz');voice.onclick=()=>nativeAction('voice');header.prepend(voice);
  const baseMore=renderMore;
  renderMore=function(){baseMore();const button=document.createElement('button');button.className='menu-card wide native-tools';button.innerHTML='<strong>Recursos Android</strong><span>Som, notificações, impressão e painel flutuante ›</span>';button.onclick=()=>nativeAction('tools');$('#morePage').append(button);};
  window.print=()=>nativeAction('print',{orderId:state.selected?.id||''});
  window.open=(url)=>{nativeAction('open_url',{url:String(url)});return null;};
  const baseOpen=openOrder;
  openOrder=async function(id){await baseOpen(id);nativeAction('silence',{orderId:id});};
}
const baseCore=renderCore;
renderCore=function(){baseCore();let banner=$('#connectionError');if(!banner){banner=document.createElement('div');banner.id='connectionError';banner.setAttribute('role','alert');$('#mainContent').prepend(banner);}banner.className=state.lastOrderError?'connection-error':'hidden';banner.textContent=state.lastOrderError?'Sem atualização: '+state.lastOrderError+'. Toque em ↻ para tentar novamente.':'';};
window.addEventListener('online',()=>window.refreshNativeOrders());
if(!window.__RODRIGUES_ANDROID__ && 'serviceWorker' in navigator)navigator.serviceWorker.register('./sw.js').catch(()=>{});
