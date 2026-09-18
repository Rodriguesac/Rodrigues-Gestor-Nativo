/* Rodrigues Gestor Web - completo, mobile-first, PIN + Supabase protegido */
const API_URL='https://jgjmntezfjuyuxhcnvhd.supabase.co/functions/v1/gestor20rac-api';
const API_KEY='sb_publishable_fsub-d0wToVGTbfDoATS7A_NWaimMvX';
const SESSION_KEY='gestor20rac_session_token';
const ACCEPT_LIMIT_MS=8*60*1000;
const AUTO_CANCEL_REASON='O tempo para aceite acabou, pedido cancelado automaticamente';

const $=s=>document.querySelector(s);
const $$=s=>[...document.querySelectorAll(s)];
const state={
  session:null,staff:null,orders:[],customers:new Map(),selected:null,
  products:[],productsLoaded:false,reviews:[],reviewOrders:new Map(),reviewCustomers:new Map(),reviewsLoaded:false,
  operation:{open:true,raw:{}},orderTab:'open',catalogQuery:'',activePage:'home',
  orderPoll:null,messagePoll:null,ordersBusy:false,autoExpiring:new Set(),conversation:null,
  store:{lat:-20.43131,lng:-54.55412,name:'Rodrigues Açaí & Cia'},maps:{mini:null,full:null}
};

async function api(action,payload={}){
  const sessionToken=sessionStorage.getItem(SESSION_KEY)||'';
  let response;
  try{
    response=await fetch(API_URL,{method:'POST',headers:{'Content-Type':'application/json','apikey':API_KEY},body:JSON.stringify({action,session_token:sessionToken,...payload})});
  }catch{throw new Error('Sem conexão com o Gestor.');}
  let data={};try{data=await response.json()}catch{}
  if(!response.ok){
    if(response.status===401&&action!=='login'){
      sessionStorage.removeItem(SESSION_KEY);state.session=null;setTimeout(showLogin,0);
    }
    const error=new Error(data.message||'Não foi possível concluir esta ação.');error.code=data.error||'request_failed';throw error;
  }
  return data;
}

const money=v=>Number(v||0).toLocaleString('pt-BR',{style:'currency',currency:'BRL'});
const fmtTime=d=>d?new Date(d).toLocaleTimeString('pt-BR',{hour:'2-digit',minute:'2-digit'}):'--:--';
const fmtDate=d=>d?new Date(d).toLocaleDateString('pt-BR'):'--/--/----';
const fmtDateTime=d=>d?`${fmtDate(d)} às ${fmtTime(d)}`:'--';
const esc=(v='')=>String(v).replace(/[&<>'"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]));
const minsSince=d=>Math.max(0,Math.floor((Date.now()-new Date(d).getTime())/60000));
const acceptRemainingMs=o=>Math.max(0,ACCEPT_LIMIT_MS-(Date.now()-new Date(o.created_at).getTime()));
const acceptExpired=o=>acceptRemainingMs(o)<=0;
const acceptCountdown=o=>{const sec=Math.max(0,Math.ceil(acceptRemainingMs(o)/1000));return `${Math.floor(sec/60)}:${String(sec%60).padStart(2,'0')}`;};

function toast(msg){const el=$('#toast');if(!el)return;el.textContent=msg;el.classList.remove('hidden');clearTimeout(window.__toast);window.__toast=setTimeout(()=>el.classList.add('hidden'),2800);}
function statusKind(raw){
  const s=String(raw||'').trim().toUpperCase();
  if(['CANCELADO','CANCELADA','CANCELED','CANCELLED','REJECTED','REJEITADO'].includes(s))return'cancelled';
  if(['CONCLUIDO','CONCLUÍDO','FINALIZADO','COMPLETED','DELIVERED','ENTREGUE','RETIRADO'].includes(s))return'done';
  if(['EM_ENTREGA','DESPACHADO','DISPATCHED','OUT_FOR_DELIVERY','SAIU_ENTREGA','SAIU_PARA_ENTREGA','A_CAMINHO_CLIENTE','BUSCANDO_ENTREGADOR','AGUARDANDO_ENTREGADOR','AGUARDANDO_DECISAO_GESTOR','A_CAMINHO_LOJA','ENTREGADOR_A_CAMINHO_LOJA','COLETANDO','ENTREGADOR_CHEGOU_LOJA','ENTREGADOR_NO_LOCAL'].includes(s))return'delivery';
  if(['PRONTO','READY'].includes(s))return'ready';
  if(['EM_PREPARO','PREPARING','PREPARO','PREPARANDO'].includes(s))return'preparing';
  if(['CONFIRMADO','ACEITO','ACCEPTED','CONFIRMED','FILA'].includes(s))return'confirmed';
  if(['AGUARDANDO_PAGAMENTO','PAYMENT_PENDING'].includes(s))return'payment';
  if(['AGUARDANDO_CONFIRMACAO','RECEBIDO','PENDENTE','NOVO','NOVO_PEDIDO','PENDING','NEW','RECEIVED','AGUARDANDO_ACEITE'].includes(s))return'new';
  return'other';
}
function statusLabel(raw){return({new:'Novo pedido',payment:'Aguardando pagamento',confirmed:'Confirmado',preparing:'Em preparo',ready:'Pronto',delivery:'Em entrega',done:'Concluído',cancelled:'Cancelado',other:'Em acompanhamento'})[statusKind(raw)];}
function nextAction(order){
  const k=statusKind(order.status);
  if(k==='new')return{label:'aceitar pedido',status:'CONFIRMADO',tone:'success'};
  if(k==='confirmed')return{label:'iniciar preparo',status:'EM_PREPARO',tone:'dark'};
  if(k==='preparing')return{label:'marcar como pronto',status:'PRONTO',tone:'success'};
  if(k==='ready')return{label:'despachar pedido',status:'EM_ENTREGA',tone:'dark'};
  if(k==='delivery')return{label:'finalizar entrega',status:'CONCLUIDO',tone:'success'};
  return null;
}
function customerFor(o){return state.customers.get(o.customer_id)||{};}
function orderName(o){const c=customerFor(o);return c.name||o.raw_payload?.cliente?.nome||'Cliente';}
function orderPhone(o){const c=customerFor(o);return c.phone||o.raw_payload?.cliente?.telefone||'';}
function cancellationReason(o){return String(o.cancellation_reason||o.raw_payload?.motivoCancelamento||'').trim();}
function isFazerMeuPedido(o){const src=String(o?.source||o?.raw_payload?.origemCheckout||'').toUpperCase();return src.includes('FAZER_MEU_PEDIDO_ACAI')||src.includes('FAZER_MEU_PEDIDO');}
function sourceLabel(o){return isFazerMeuPedido(o)?'Fazer Meu Pedido de Açaí':'Site cliente';}
function receiptUrl(o){const token=String(o?.receipt_token||o?.raw_payload?.receiptToken||'').trim();return token?`https://fazermeupedidodeacai.netlify.app/c/${encodeURIComponent(token)}`:'';}
function beep(){if(window.__RODRIGUES_ANDROID__)return;try{const C=window.AudioContext||window.webkitAudioContext,ctx=new C(),osc=ctx.createOscillator(),gain=ctx.createGain();osc.connect(gain);gain.connect(ctx.destination);osc.frequency.value=880;gain.gain.value=.07;osc.start();setTimeout(()=>{osc.stop();ctx.close()},420);}catch{}}

function updateHeader(){
  const open=state.operation.open;
  $('#openLabel').textContent=open?'Loja aberta':'Loja fechada';
  $('#storeDot').classList.toggle('closed',!open);
  const openOrders=state.orders.filter(o=>!['done','cancelled'].includes(statusKind(o.status))).length;
  const badge=$('#ordersBadge');badge.textContent=String(openOrders);badge.classList.toggle('hidden',openOrders===0);
}

async function init(){
  const token=sessionStorage.getItem(SESSION_KEY);
  if(token){try{await enterApp();return}catch{sessionStorage.removeItem(SESSION_KEY)}}
  showLogin();
}
function showLogin(){
  state.session=null;if(state.orderPoll){clearInterval(state.orderPoll);state.orderPoll=null}if(state.messagePoll){clearInterval(state.messagePoll);state.messagePoll=null}
  $('#appView').classList.add('hidden');$('#detailView').classList.add('hidden');$('#loginView').classList.remove('hidden');$('#loginPin')?.focus();
}
async function enterApp(){
  state.session={user:{id:'gestor20rac-pin'}};state.staff={role:'owner',name:'Gestor 20 RAC',active:true};
  $('#loginView').classList.add('hidden');$('#appView').classList.remove('hidden');
  await Promise.all([loadStore(),loadOrders()]);subscribeOrders();renderCore();await switchPage('home');
}
$('#loginForm').addEventListener('submit',async e=>{
  e.preventDefault();const btn=$('#loginBtn');btn.disabled=true;btn.textContent='Entrando…';$('#loginError').classList.add('hidden');
  try{const data=await api('login',{pin:$('#loginPin').value.replace(/\D/g,'')});sessionStorage.setItem(SESSION_KEY,data.session_token);if(window.__RODRIGUES_ANDROID__)window.RodriguesNative?.postMessage(JSON.stringify({action:'authenticated',pin:$('#loginPin').value.replace(/\D/g,'')}));$('#loginPin').value='';await enterApp();}
  catch(error){$('#loginError').textContent=error.message||'Código incorreto.';$('#loginError').classList.remove('hidden');}
  finally{btn.disabled=false;btn.textContent='Entrar';}
});

async function loadStore(){
  try{
    const data=await api('store');const logistics=data.store?.value||{},operation=data.operation?.value||{};
    if(logistics.lojaLat)state.store={lat:Number(logistics.lojaLat),lng:Number(logistics.lojaLng),name:logistics.lojaNome||state.store.name};
    state.operation={open:operation.lojaAberta!==false&&operation.aberta!==false&&operation.aceitarPedidos!==false,raw:operation};updateHeader();
  }catch(error){console.error(error)}
}
async function loadOrders({notify=false}={}){
  if(state.ordersBusy)return;state.ordersBusy=true;const previous=new Map(state.orders.map(o=>[o.id,o]));
  try{
    const data=await api('orders');if(!Array.isArray(data.orders))throw new Error('Resposta inválida de pedidos');state.orders=data.orders;state.lastOrderError='';state.customers.clear();(data.customers||[]).forEach(c=>state.customers.set(c.id,c));
    if(notify){const fresh=state.orders.find(o=>!previous.has(o.id)&&['new','payment'].includes(statusKind(o.status)));if(fresh){beep();toast(`Novo pedido #${fresh.order_code||''}`)}}
    updateHeader();
  }catch(error){console.error(error);state.lastOrderError=error.message||'Erro ao carregar pedidos';if(error.code!=='session_expired')toast(state.lastOrderError);}
  finally{state.ordersBusy=false}
}
async function loadProducts(force=false){
  if(state.productsLoaded&&!force)return;const data=await api('products');state.products=data.products||[];state.productsLoaded=true;
}
async function loadReviews(force=false){
  if(state.reviewsLoaded&&!force)return;const data=await api('reviews');state.reviews=data.reviews||[];state.reviewOrders=new Map((data.orders||[]).map(o=>[o.id,o]));state.reviewCustomers=new Map((data.customers||[]).map(c=>[c.id,c]));state.reviewsLoaded=true;
}
function subscribeOrders(){
  if(state.orderPoll)clearInterval(state.orderPoll);
  state.orderPoll=setInterval(async()=>{
    if(!state.session||document.visibilityState==='hidden')return;
    const selectedBefore=state.selected?.updated_at||'';await loadOrders({notify:true});renderCore();renderActivePage();
    if(state.selected){const fresh=state.orders.find(o=>o.id===state.selected.id);if(fresh){state.selected=fresh;if(selectedBefore!==fresh.updated_at&&!$('#detailView').classList.contains('hidden'))await renderDetail();}}
  },4000);
}

function renderCore(){renderHome();renderOperation();renderMore();updateHeader();}
function renderActivePage(){
  if(state.activePage==='home')renderHome();
  else if(state.activePage==='operation')renderOperation();
  else if(state.activePage==='reports')renderReports();
  else if(state.activePage==='more')renderMore();
}
function todayOrders(){const d=new Date();d.setHours(0,0,0,0);return state.orders.filter(o=>new Date(o.created_at)>=d);}
function renderHome(){
  const open=state.orders.filter(o=>!['done','cancelled'].includes(statusKind(o.status)));const today=todayOrders();
  const revenue=today.filter(o=>statusKind(o.status)!=='cancelled').reduce((s,o)=>s+Number(o.total||0),0);
  const newOrders=open.filter(o=>['new','payment'].includes(statusKind(o.status))).slice(0,3);
  $('#homePage').innerHTML=`
    <div class="page-title-row"><div><h1>Início</h1><p class="page-sub">Operação em tempo quase real</p></div><span class="live-pill"><i></i>Atualizado</span></div>
    <button class="store-banner ${state.operation.open?'open':'closed'}" id="homeStoreStatus"><div><strong>${state.operation.open?'Loja aberta':'Loja fechada'}</strong><span>${state.operation.open?'Recebendo novos pedidos':'Novos pedidos bloqueados'}</span></div><b>›</b></button>
    <div class="summary-grid">
      <div class="summary-card"><strong>${open.length}</strong><span>pedidos abertos</span></div>
      <div class="summary-card"><strong>${today.length}</strong><span>pedidos hoje</span></div>
      <div class="summary-card"><strong>${money(revenue)}</strong><span>vendas hoje</span></div>
      <div class="summary-card"><strong>${state.orders.filter(o=>statusKind(o.status)==='ready').length}</strong><span>prontos</span></div>
    </div>
    <div class="section-heading"><h2>Precisam de atenção</h2><button class="link-btn" id="seeAllOrders">Ver todos</button></div>
    ${newOrders.length?newOrders.map(orderCard).join(''):'<div class="empty compact">Nenhum pedido aguardando aceite.</div>'}
  `;
  $('#homeStoreStatus').onclick=()=>switchPage('store');$('#seeAllOrders').onclick=()=>{state.orderTab='open';switchPage('operation')};
  $$('#homePage .order-card').forEach(el=>el.onclick=()=>openOrder(el.dataset.order));
}
function orderCard(o){
  const k=statusKind(o.status),age=minsSince(o.created_at),expired=['new','payment'].includes(k)&&acceptExpired(o);const reason=cancellationReason(o);
  const chipClass=k==='ready'?'green':k==='cancelled'?'red':k==='payment'?'amber':k==='done'?'green':'';
  return `<article class="order-card ${k==='new'||k==='payment'?'attention':''}" data-order="${o.id}">
    <div class="order-card-head"><div><h3>#${esc(o.order_code||o.id.slice(0,6))} · ${esc(orderName(o))}</h3><p>${esc(sourceLabel(o))}</p></div><div class="age-bubble ${expired?'expired':''}">${age}m</div></div>
    <div class="order-datetime">${fmtDateTime(o.created_at)}</div>
    ${['new','payment'].includes(k)?`<div class="deadline-line ${expired?'expired':''}">${expired?'Prazo encerrado — cancelamento automático':'Tempo para aceitar'} <strong data-deadline="${o.id}">${expired?'00:00':acceptCountdown(o)}</strong></div>`:''}
    ${k==='cancelled'&&reason?`<div class="cancel-reason"><b>Motivo:</b> ${esc(reason)}</div>`:''}
    <div class="order-card-foot"><span class="chip ${chipClass}">${statusLabel(o.status)}</span><strong>${money(o.total)}</strong></div>
  </article>`;
}
function renderOperation(){
  const isHistory=state.orderTab==='history';const rows=state.orders.filter(o=>isHistory?['done','cancelled'].includes(statusKind(o.status)):!['done','cancelled'].includes(statusKind(o.status)));
  const groups=isHistory?`<div class="history-list">${rows.map(orderCard).join('')||'<div class="empty">Nenhum pedido no histórico.</div>'}</div>`:
    groupOrders('Novos',rows.filter(o=>['new','payment'].includes(statusKind(o.status))))+
    groupOrders('Em preparo',rows.filter(o=>['confirmed','preparing'].includes(statusKind(o.status))))+
    groupOrders('Prontos e entrega',rows.filter(o=>['ready','delivery'].includes(statusKind(o.status))));
  $('#operationPage').innerHTML=`
    <div class="page-title-row"><div><h1>Pedidos</h1><p class="page-sub">${rows.length} ${isHistory?'no histórico':'em aberto'}</p></div></div>
    <div class="segmented"><button class="seg-btn ${!isHistory?'active':''}" data-tab="open">Abertos</button><button class="seg-btn ${isHistory?'active':''}" data-tab="history">Histórico</button></div>
    ${groups||'<div class="empty">Nenhum pedido aberto.</div>'}`;
  $$('#operationPage [data-tab]').forEach(b=>b.onclick=()=>{state.orderTab=b.dataset.tab;renderOperation()});
  $$('#operationPage .order-card').forEach(el=>el.onclick=()=>openOrder(el.dataset.order));
}
function groupOrders(title,arr){if(!arr.length)return'';return`<div class="section-heading"><h2>${title}</h2><span>${arr.length}</span></div>${arr.map(orderCard).join('')}`;}

async function renderCatalog(){
  const q=state.catalogQuery.trim().toLowerCase();const list=state.products.filter(p=>!q||String(p.name||'').toLowerCase().includes(q));
  $('#catalogPage').innerHTML=`
    <div class="page-title-row"><div><h1>Cardápio</h1><p class="page-sub">Pause ou reative itens sem editar código</p></div><span class="count">${state.products.length} itens</span></div>
    <div class="search-box"><span>⌕</span><input id="catalogSearch" placeholder="Buscar produto" value="${esc(state.catalogQuery)}"></div>
    <div class="catalog-list">${list.map(p=>{const on=p.available!==false&&p.active!==false;return`<div class="catalog-row"><div class="catalog-main"><strong>${esc(p.name||'Produto')}</strong><span>${money(p.price)}</span></div><button class="toggle-switch ${on?'on':''}" data-product="${p.id}" data-on="${on?'1':'0'}" aria-label="${on?'Pausar':'Reativar'} ${esc(p.name||'produto')}"><i></i><b>${on?'Ativo':'Pausado'}</b></button></div>`}).join('')||'<div class="empty">Nenhum produto encontrado.</div>'}</div>`;
  $('#catalogSearch').oninput=e=>{state.catalogQuery=e.target.value;renderCatalog();setTimeout(()=>$('#catalogSearch')?.focus(),0)};
  $$('#catalogPage [data-product]').forEach(btn=>btn.onclick=async()=>{
    const id=btn.dataset.product,on=btn.dataset.on==='1';btn.disabled=true;
    try{const result=await api('update_product',{product_id:id,available:!on});const idx=state.products.findIndex(p=>p.id===id);if(idx>=0)state.products[idx]=result.product;renderCatalog();toast(!on?'Produto reativado':'Produto pausado');}
    catch(error){toast(error.message||'Não foi possível alterar o produto');btn.disabled=false}
  });
}

async function renderReviews(){
  const avg=state.reviews.length?state.reviews.reduce((s,r)=>s+Number(r.rating||0),0)/state.reviews.length:0;
  $('#reviewsPage').innerHTML=`
    <div class="page-title-row"><div><h1>Avaliações</h1><p class="page-sub">Notas e comentários dos clientes</p></div></div>
    <div class="rating-summary"><strong>${state.reviews.length?avg.toFixed(1).replace('.',','):'—'}</strong><div><div class="stars big">${stars(avg)}</div><span>${state.reviews.length} ${state.reviews.length===1?'avaliação':'avaliações'}</span></div></div>
    <div class="review-list">${state.reviews.map(r=>{const o=state.reviewOrders.get(r.order_id)||{},c=state.reviewCustomers.get(r.customer_id)||{};return`<article class="review-card"><div class="review-top"><div><strong>${esc(c.name||'Cliente')}</strong><span>Pedido #${esc(o.order_code||'')}</span></div><div class="stars">${stars(r.rating)}</div></div>${r.comment?`<p>${esc(r.comment)}</p>`:'<p class="muted">Sem comentário.</p>'}<small>${fmtDateTime(r.created_at)}</small></article>`}).join('')||'<div class="empty"><strong>Ainda não há avaliações.</strong><p>Quando o cliente avaliar um pedido concluído, a nota aparecerá aqui.</p></div>'}</div>`;
}
function stars(n){const x=Math.round(Number(n||0));return[1,2,3,4,5].map(i=>`<span class="${i<=x?'on':''}">★</span>`).join('');}

function renderMore(){
  $('#morePage').innerHTML=`
    <div class="page-title-row"><div><h1>Mais</h1><p class="page-sub">Gestão e configurações</p></div></div>
    <div class="menu-list">
      <button class="menu-row" data-more="store"><span class="menu-icon">◉</span><div><strong>Loja e funcionamento</strong><small>${state.operation.open?'Aberta e recebendo pedidos':'Fechada'}</small></div><b>›</b></button>
      <button class="menu-row" data-more="history"><span class="menu-icon">◷</span><div><strong>Histórico de pedidos</strong><small>Concluídos e cancelados</small></div><b>›</b></button>
      <button class="menu-row" data-more="reports"><span class="menu-icon">▥</span><div><strong>Relatórios</strong><small>Vendas, ticket e cancelamentos</small></div><b>›</b></button>
      <button class="menu-row" data-more="chat"><span class="menu-icon">◌</span><div><strong>Conversas</strong><small>Chat com clientes</small></div><b>›</b></button>
      <button class="menu-row" data-more="refresh"><span class="menu-icon">↻</span><div><strong>Atualizar dados</strong><small>Pedidos, loja e cardápio</small></div><b>›</b></button>
    </div>
    <button class="logout-button" id="logoutBtn">Sair do Gestor</button>`;
  $$('[data-more]').forEach(b=>b.onclick=async()=>{
    const a=b.dataset.more;if(a==='store')switchPage('store');if(a==='history'){state.orderTab='history';switchPage('operation')}
    if(a==='reports')switchPage('reports');if(a==='chat')switchPage('chat');if(a==='refresh')refreshEverything();
  });
  $('#logoutBtn').onclick=async()=>{try{await api('logout')}catch{}sessionStorage.removeItem(SESSION_KEY);if(window.__RODRIGUES_ANDROID__)nativeAction('logout');showLogin()};
}
function renderReports(){
  const today=todayOrders(),valid=today.filter(o=>statusKind(o.status)!=='cancelled'),cancelled=today.filter(o=>statusKind(o.status)==='cancelled');const revenue=valid.reduce((s,o)=>s+Number(o.total||0),0);const ticket=valid.length?revenue/valid.length:0;
  const weekStart=new Date();weekStart.setHours(0,0,0,0);weekStart.setDate(weekStart.getDate()-6);const week=state.orders.filter(o=>new Date(o.created_at)>=weekStart&&statusKind(o.status)!=='cancelled');const weekRevenue=week.reduce((s,o)=>s+Number(o.total||0),0);
  $('#reportsPage').innerHTML=`${subHeader('Relatórios')}<div class="summary-grid report-grid"><div class="summary-card"><strong>${money(revenue)}</strong><span>vendas hoje</span></div><div class="summary-card"><strong>${valid.length}</strong><span>pedidos válidos hoje</span></div><div class="summary-card"><strong>${money(ticket)}</strong><span>ticket médio hoje</span></div><div class="summary-card"><strong>${cancelled.length}</strong><span>cancelados hoje</span></div></div><div class="report-panel"><h2>Últimos 7 dias</h2><div class="report-line"><span>Pedidos válidos</span><strong>${week.length}</strong></div><div class="report-line"><span>Faturamento</span><strong>${money(weekRevenue)}</strong></div></div><p class="info-note">Relatório calculado com os pedidos disponíveis no Supabase.</p>`;
  bindSubBack();
}
function renderStorePage(){
  const open=state.operation.open;$('#storePage').innerHTML=`${subHeader('Loja e funcionamento')}<div class="store-control-card ${open?'open':'closed'}"><span>${open?'● ABERTA':'● FECHADA'}</span><h2>${open?'Recebendo pedidos':'Novos pedidos bloqueados'}</h2><p>${open?'O cliente pode enviar novos pedidos normalmente.':'Abra a loja quando estiver pronta para receber pedidos.'}</p></div><div class="settings-card"><div><strong>Prazo para aceitar</strong><span>8 minutos</span></div><p>Pedido não aceito nesse prazo é cancelado automaticamente com motivo informado ao cliente.</p></div><div id="storeSwipeMount"></div>`;
  bindSubBack();$('#storeSwipeMount').innerHTML=swipeMarkup('swipeStore',open?'Arraste para fechar a loja':'Arraste para abrir a loja',open?'danger':'success');
  bindSwipe('swipeStore',async()=>{try{const result=await api('set_operation',{open:!open});state.operation={open:result.operation?.value?.lojaAberta!==false,raw:result.operation?.value||{}};updateHeader();renderStorePage();renderHome();renderMore();toast(state.operation.open?'Loja aberta':'Loja fechada');return true}catch(error){toast(error.message);return false}});
}
function subHeader(title){return`<div class="subpage-head"><button class="back-inline" data-subback>‹</button><div><h1>${esc(title)}</h1><p>Rodrigues Açaí & Cia</p></div></div>`;}
function bindSubBack(){$$('[data-subback]').forEach(b=>b.onclick=()=>switchPage('more'));}

async function renderChatPage(){
  let data=[];try{const result=await api('conversations');data=result.conversations||[]}catch(error){console.error(error)}
  $('#chatPage').innerHTML=`${subHeader('Conversas')}<div class="chat-list">${data.map(c=>{const o=state.orders.find(x=>x.id===c.order_id);return`<article class="order-card" data-chatorder="${c.order_id}"><div class="order-card-head"><div><h3>#${esc(o?.order_code||'')} · ${esc(o?orderName(o):'Cliente')}</h3><p>${o?fmtDateTime(o.created_at):''}</p></div><span class="chip green">Abrir</span></div></article>`}).join('')||'<div class="empty">Nenhuma conversa.</div>'}</div>`;
  bindSubBack();$$('[data-chatorder]').forEach(el=>el.onclick=()=>{const o=state.orders.find(x=>x.id===el.dataset.chatorder);if(o)openChat(o)});
}

async function switchPage(page){
  state.activePage=page;$$('.page').forEach(p=>p.classList.add('hidden'));const target=$(`#${page}Page`);if(target)target.classList.remove('hidden');
  const mainPages=['home','operation','catalog','reviews','more'];const navPage=mainPages.includes(page)?page:'more';$$('.nav-item').forEach(b=>b.classList.toggle('active',b.dataset.page===navPage));
  if(page==='home')renderHome();
  if(page==='operation')renderOperation();
  if(page==='catalog'){try{await loadProducts();renderCatalog()}catch(error){target.innerHTML='<div class="empty">Não foi possível carregar o cardápio.</div>';toast(error.message)}}
  if(page==='reviews'){try{await loadReviews();renderReviews()}catch(error){target.innerHTML='<div class="empty">Não foi possível carregar as avaliações.</div>';toast(error.message)}}
  if(page==='more')renderMore();if(page==='reports')renderReports();if(page==='store')renderStorePage();if(page==='chat')await renderChatPage();
  $('#mainContent').scrollTop=0;window.scrollTo({top:0,behavior:'instant'});
}
$$('.nav-item').forEach(b=>b.addEventListener('click',()=>switchPage(b.dataset.page)));
$('#storeHeaderButton').onclick=()=>switchPage('store');
$('#refreshBtn').onclick=()=>refreshEverything();
async function refreshEverything(){
  const btn=$('#refreshBtn');btn.disabled=true;btn.classList.add('spinning');
  try{await Promise.all([loadStore(),loadOrders()]);if(state.productsLoaded)await loadProducts(true);if(state.reviewsLoaded)await loadReviews(true);renderCore();await switchPage(state.activePage);toast('Dados atualizados');}
  catch(error){toast(error.message||'Falha ao atualizar')}
  finally{btn.disabled=false;btn.classList.remove('spinning')}
}

function swipeMarkup(id,label,tone='dark'){return`<div class="swipe-control ${tone}" id="${id}"><div class="swipe-text">${esc(label)}</div><div class="swipe-thumb">››</div></div>`;}
function resetSwipe(el){if(!el)return;const thumb=el.querySelector('.swipe-thumb');el.classList.remove('busy','done');if(thumb)thumb.style.transform='translateX(0px)';}
function bindSwipe(id,onComplete){
  const el=$('#'+id);if(!el)return;const thumb=el.querySelector('.swipe-thumb');let dragging=false,start=0,x=0,max=0,pid=null;
  const moveTo=v=>{x=Math.max(0,Math.min(max,v));thumb.style.transform=`translateX(${x}px)`;el.style.setProperty('--swipe-progress',max?String(x/max):'0')};
  thumb.onpointerdown=e=>{if(el.classList.contains('busy'))return;dragging=true;pid=e.pointerId;start=e.clientX;max=Math.max(0,el.clientWidth-thumb.offsetWidth-8);thumb.setPointerCapture(pid);el.classList.add('dragging')};
  thumb.onpointermove=e=>{if(!dragging||e.pointerId!==pid)return;moveTo(e.clientX-start)};
  const finish=async e=>{if(!dragging||e.pointerId!==pid)return;dragging=false;el.classList.remove('dragging');try{thumb.releasePointerCapture(pid)}catch{}if(max&&x>=max*.72){moveTo(max);el.classList.add('busy','done');let ok=false;try{ok=(await onComplete(el))!==false}catch(error){console.error(error);toast(error.message||'Não foi possível concluir')}if(!ok&&document.body.contains(el))resetSwipe(el)}else{resetSwipe(el)}};
  thumb.onpointerup=finish;thumb.onpointercancel=()=>{dragging=false;el.classList.remove('dragging');resetSwipe(el);};
}

async function openOrder(id){const o=state.orders.find(x=>x.id===id);if(!o)return;state.selected=o;$('#detailView').classList.remove('hidden');await renderDetail();}
$('#detailBack').onclick=()=>{$('#detailView').classList.add('hidden');destroyMiniMap()};
$('#detailMore').onclick=()=>openSheet();
function progressStep(k){return({new:1,payment:1,confirmed:2,preparing:2,ready:3,delivery:4,done:5,cancelled:1})[k]||1;}
async function renderDetail(){
  const o=state.selected;if(!o)return;let dbItems=[];try{dbItems=(await api('order_items',{order_id:o.id})).items||[]}catch(error){console.error(error)}
  const c=customerFor(o),raw=o.raw_payload||{},pay=o.payment_details||raw.pagamento||{},addr=getAddress(o),k=statusKind(o.status),act=nextAction(o),reason=cancellationReason(o);
  const fallbackItems=Array.isArray(raw.itens)?raw.itens.map((it,i)=>({id:it.id||`raw-${i}`,name:it.nome||it.titulo||it.baseNome||'Item',quantity:Number(it.quantidade||it.qtd||1),total_price:Number(it.total??it.preco??it.precoUnitario??0)*Number(it.quantidade||it.qtd||1),modifiers:it.detalhes||{linhas:it.linhas||it.linhasMontagem||[]},notes:it.observacao||''})):[];
  const items=dbItems.length?dbItems:fallbackItems;const modifiers=it=>{const m=it.modifiers||{};let lines=m.linhasIncluído||m.linhasIncluido||m.linhasMontagem||m.linhas||[];if(!Array.isArray(lines)||!lines.length){const r=it.raw_payload||{},cfg=r.configuracaoMonte||{};lines=[...(r.linhas||r.linhasMontagem||[])];if(!lines.length)lines=[...(cfg.acompanhamentos||[]),...(cfg.adicionais||[]),...(cfg.utensilios||[])].map(x=>typeof x==='string'?{nome:x}:x)}return Array.isArray(lines)?lines.map(x=>esc(x?.nome||x)).filter(Boolean).join(', '):''};
  const cpf=c.tax_id||raw.cliente?.cpf||'',changeValue=Number(pay.valorTroco||pay.troco||0),changeTo=Number(pay.valorTrocoPara||pay.trocoPara||0),paymentName=String(o.payment_method||pay.forma||pay.metodo||'Não informado').replaceAll('_',' ');
  $('#detailTitle').textContent=`Pedido #${o.order_code||''}`;$('#detailSubtitle').textContent=`${statusLabel(o.status)} · ${fmtDateTime(o.created_at)}`;
  $('#detailScroll').innerHTML=`
    <section class="detail-section order-hero"><div><span class="eyebrow">${esc(sourceLabel(o))}</span><h1>${esc(orderName(o))}</h1><p>Pedido #${esc(o.order_code||'')} · ${fmtDateTime(o.created_at)}</p></div><div class="age-bubble large ${['new','payment'].includes(k)&&acceptExpired(o)?'expired':''}">${minsSince(o.created_at)}m</div></section>
    ${['new','payment'].includes(k)?`<section class="deadline-card ${acceptExpired(o)?'expired':''}"><strong>${acceptExpired(o)?'Prazo de aceite encerrado':'Prazo para aceitar'}</strong><span data-detail-deadline>${acceptExpired(o)?'00:00':acceptCountdown(o)}</span><small>${acceptExpired(o)?'O sistema está cancelando automaticamente.':'Limite máximo: 8 minutos.'}</small></section>`:''}
    ${k==='cancelled'?`<section class="cancel-banner"><strong>Pedido cancelado</strong><p>${esc(reason||'Sem motivo informado.')}</p>${o.cancelled_at?`<small>${fmtDateTime(o.cancelled_at)}</small>`:''}</section>`:''}
    <section class="detail-section"><div class="contact-row"><button id="detailChat" class="outline-pill">💬 Chat</button><button id="detailCall" class="outline-pill">☎ Ligar</button>${receiptUrl(o)?'<button id="detailReceipt" class="outline-pill">🧾 Comprovante</button>':''}</div><div class="status-label">Status: <strong>${statusLabel(o.status)}</strong></div><div class="progress">${[1,2,3,4,5].map((_,i)=>`<i class="${i<progressStep(k)?'on':''}"></i>`).join('')}</div></section>
    <section class="detail-section"><div class="section-heading compact-head"><h2>Itens do pedido</h2><span>${items.length}</span></div>${items.map(it=>`<div class="order-item"><div class="qty-box">${Number(it.quantity||1)}</div><div><div class="item-name">${esc(it.name)}</div><div class="item-lines">${modifiers(it)}</div>${it.notes?`<div class="item-lines"><b>Obs.:</b> ${esc(it.notes)}</div>`:''}</div><div class="price">${money(it.total_price)}</div></div>`).join('')||'<p class="muted">Itens ainda não carregados.</p>'}</section>
    <section class="detail-section"><div class="summary-line"><span>Subtotal</span><span>${money(o.subtotal)}</span></div><div class="summary-line"><span>Taxa de entrega</span><span>${money(o.delivery_fee)}</span></div>${Number(o.discount)>0?`<div class="summary-line"><span>Desconto</span><span>− ${money(o.discount)}</span></div>`:''}<div class="summary-line total"><strong>Total</strong><strong>${money(o.total)}</strong></div></section>
    <section class="detail-section"><h2>Entrega</h2><button id="mapPreview" class="map-preview"><div id="miniMap"></div></button><h3 class="subheading">Endereço do cliente</h3><p class="address-text muted">${esc(addr.text||'Endereço não informado')}</p><div class="contact-row"><button id="copyAddress" class="outline-pill">▣ Copiar</button><button id="shareAddress" class="outline-pill">⇧ Compartilhar</button></div></section>
    <section class="detail-section"><h2>Pagamento</h2><div class="detail-info-row"><div class="icon">▣</div><div><h3>${esc(paymentName)}</h3><p>Valor: <strong>${money(o.total)}</strong>${pay.precisaMaquininha?' · levar maquininha':''}</p></div></div>${pay.precisaTroco||changeValue>0||changeTo>0?`<div class="detail-info-row"><div class="icon">ⓢ</div><div><h3>Troco</h3><p>${changeValue>0?'Leve '+money(changeValue)+' de troco':changeTo>0?'Troco para '+money(changeTo):'Cliente informou troco'}</p></div></div>`:''}${cpf?`<div class="detail-info-row"><div class="icon">▤</div><div><h3>CPF na nota</h3><p>${esc(cpf)}</p></div></div>`:''}</section>
    <section class="detail-section"><button class="secondary-full" id="printDetail">🖨 Imprimir pedido</button></section>`;
  renderDetailActions(o,k,act);
  $('#detailCall').onclick=()=>{const ph=orderPhone(o).replace(/\D/g,'');if(ph)location.href='tel:+'+ph;else toast('Cliente sem telefone cadastrado')};
  $('#detailChat').onclick=()=>openChat(o);$('#detailReceipt')?.addEventListener('click',()=>window.open(receiptUrl(o),'_blank','noopener'));$('#printDetail').onclick=()=>window.print();
  $('#copyAddress').onclick=()=>navigator.clipboard?.writeText(addr.text||'').then(()=>toast('Endereço copiado'));$('#shareAddress').onclick=async()=>{if(navigator.share)try{await navigator.share({title:`Endereço do pedido ${o.order_code||''}`,text:addr.text})}catch{}else navigator.clipboard?.writeText(addr.text||'')};$('#mapPreview').onclick=()=>openMap(addr);initMiniMap(addr);
}
function renderDetailActions(o,k,act){
  const footer=$('#detailActions');footer.classList.remove('stacked');
  if(['done','cancelled'].includes(k)){footer.innerHTML=`<button class="secondary-full" id="historyPrint">Imprimir pedido</button>`;$('#historyPrint').onclick=()=>window.print();return;}
  if(['new','payment'].includes(k)){
    if(acceptExpired(o)){footer.innerHTML='<div class="auto-cancel-footer"><strong>Prazo de aceite encerrado</strong><span>Cancelamento automático em andamento</span></div>';autoExpireOrder(o);return;}
    footer.classList.add('stacked');footer.innerHTML=`${swipeMarkup('swipeAccept',`Arraste para aceitar · ${acceptCountdown(o)}`,'success')}${swipeMarkup('swipeReject','Arraste para rejeitar','danger')}`;
    bindSwipe('swipeAccept',()=>changeStatus(o,'CONFIRMADO',{silent:false}));
    bindSwipe('swipeReject',async()=>{const reason=await askCancelReason('reject');if(!reason)return false;return changeStatus(o,'CANCELADO',{reason,mode:'reject'})});return;
  }
  if(act){footer.classList.add('stacked');footer.innerHTML=`${swipeMarkup('swipeNext',`Arraste para ${act.label}`,act.tone)}<button id="cancelFromDetail" class="cancel-link">Cancelar pedido</button>`;bindSwipe('swipeNext',()=>changeStatus(o,act.status,{}));$('#cancelFromDetail').onclick=async()=>{const reason=await askCancelReason('cancel');if(reason)await changeStatus(o,'CANCELADO',{reason,mode:'cancel'})};return;}
  footer.innerHTML='';
}
async function changeStatus(order,newStatus,options={}){
  if(!order||!newStatus)return false;if(newStatus==='CONFIRMADO'&&acceptExpired(order)){toast('O prazo de 8 minutos para aceitar expirou.');return false}
  const now=new Date().toISOString(),raw=structuredClone(order.raw_payload||{}),hist=Array.isArray(raw.historicoStatus)?raw.historicoStatus:[];hist.push({data:now,origem:options.mode==='expired-auto'?'AUTO_ACCEPT_TIMEOUT':'RODRIGUES_GESTOR_WEB',status:newStatus,titulo:statusLabel(newStatus)});
  Object.assign(raw,{status:newStatus,statusLoja:newStatus,statusPedido:newStatus,statusAtualizadoEm:now,updatedAt:now,historicoStatus:hist});if(newStatus!=='PENDENTE'&&newStatus!=='AGUARDANDO_PAGAMENTO')raw.pendenteGestor=false;
  if(newStatus==='CONFIRMADO'){raw.aceitoEm=now;raw.acceptedAt=now}if(newStatus==='EM_PREPARO')raw.preparoIniciadoEm=now;if(newStatus==='PRONTO')raw.prontoEm=now;
  if(newStatus==='CANCELADO'){raw.motivoCancelamento=options.reason||'';raw.rejeitadoPelaLoja=options.mode==='reject';raw.cancelamentoTipo=options.mode==='expired-auto'?'PRAZO_ACEITE_EXPIRADO':options.mode==='reject'?'REJEITADO_LOJA':'CANCELADO_LOJA';raw.canceladoAutomaticamente=options.mode==='expired-auto'}
  const patch={status:newStatus,updated_at:now,raw_payload:raw};if(newStatus==='CANCELADO'){patch.cancellation_reason=options.reason||'';patch.cancelled_at=now}if(newStatus==='CONFIRMADO')patch.accepted_at=now;if(newStatus==='EM_PREPARO')patch.preparing_at=now;if(newStatus==='EM_ENTREGA')patch.dispatched_at=now;if(newStatus==='CONCLUIDO')patch.completed_at=now;
  try{const result=await api('update_order',{order_id:order.id,patch});state.selected=result.order;await loadOrders();renderCore();if(!$('#detailView').classList.contains('hidden'))await renderDetail();if(!options.silent)toast(newStatus==='CANCELADO'?'Pedido cancelado':'Status atualizado');return true}
  catch(error){if(options.mode==='expired-auto'&&['already_cancelled','order_not_pending'].includes(error.code)){await loadOrders();return true}toast(error.message||'Não foi possível alterar o pedido');return false}
}
async function autoExpireOrder(order){/* Expiration is enforced by the server, even when this app is closed. */}

function askCancelReason(mode='cancel'){
  return new Promise(resolve=>{
    const modal=$('#reasonModal'),title=$('#reasonTitle'),help=$('#reasonHelp'),select=$('#reasonSelect'),other=$('#reasonOther'),mount=$('#reasonSwipeMount'),close=$('#reasonClose');
    title.textContent=mode==='reject'?'Rejeitar pedido':'Cancelar pedido';help.textContent=mode==='reject'?'Escolha o motivo da rejeição. O cliente verá essa informação.':'Escolha o motivo do cancelamento. O cliente verá essa informação.';select.value='';other.value='';modal.classList.remove('hidden');
    mount.innerHTML=swipeMarkup('swipeReason',mode==='reject'?'Arraste para confirmar rejeição':'Arraste para confirmar cancelamento','danger');let settled=false;
    const finish=value=>{if(settled)return;settled=true;modal.classList.add('hidden');close.onclick=null;resolve(value||'')};close.onclick=()=>finish('');
    bindSwipe('swipeReason',async()=>{const base=select.value,extra=other.value.trim(),reason=base==='Outro'?extra:(extra&&base?`${base} — ${extra}`:base);if(!reason||reason.trim().length<3){toast('Escolha ou informe o motivo');return false}finish(reason.trim());return true});
  });
}
function openSheet(){const k=statusKind(state.selected?.status);$('#actionSheet [data-sheet="cancel"]').classList.toggle('hidden',['done','cancelled'].includes(k));$('#sheetBackdrop').classList.remove('hidden');$('#actionSheet').classList.remove('hidden')}
function closeSheet(){$('#sheetBackdrop').classList.add('hidden');$('#actionSheet').classList.add('hidden')}
$('#sheetBackdrop').onclick=closeSheet;$('#actionSheet').addEventListener('click',async e=>{const a=e.target.dataset.sheet;if(!a)return;if(a==='print')window.print();if(a==='cancel'&&state.selected){closeSheet();const reason=await askCancelReason('cancel');if(reason)await changeStatus(state.selected,'CANCELADO',{reason,mode:'cancel'})}if(a==='close')closeSheet()});

function getAddress(o){const a=o.delivery_address||o.raw_payload?.endereco||{};const lat=Number(a.lat??a.latitude??a.latlng?.lat??a.coords?.lat??0),lng=Number(a.lng??a.longitude??a.latlng?.lng??a.coords?.lng??0);const street=a.street||a.rua||a.logradouro||'',num=a.number||a.numero||'',district=a.district||a.bairro||'',comp=a.complement||a.complemento||'',city=a.city||a.cidade||'',uf=a.state||a.uf||'',cep=a.postal_code||a.cep||'';return{lat,lng,text:[`${street}${num?', '+num:''}`,comp,district,`${city}${uf?'/'+uf:''}`,cep?`CEP ${cep}`:''].filter(Boolean).join(' - ')}}
function destroyMiniMap(){if(state.maps.mini){state.maps.mini.remove();state.maps.mini=null}}
function pin(color,label){return L.divIcon({className:'',html:`<div style="width:38px;height:38px;border-radius:50% 50% 50% 8px;transform:rotate(-45deg);background:${color};display:grid;place-items:center;border:3px solid #fff;box-shadow:0 2px 8px #777"><span style="transform:rotate(45deg);color:#fff;font-weight:800">${label}</span></div>`,iconSize:[42,42],iconAnchor:[21,38]})}
function initMiniMap(addr){destroyMiniMap();if(!window.L||!addr.lat||!addr.lng)return;state.maps.mini=L.map('miniMap',{zoomControl:false,attributionControl:false,dragging:false,scrollWheelZoom:false,doubleClickZoom:false});L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19}).addTo(state.maps.mini);L.marker([addr.lat,addr.lng],{icon:pin('#e9003b','●')}).addTo(state.maps.mini);L.marker([state.store.lat,state.store.lng],{icon:pin('#111','▣')}).addTo(state.maps.mini);state.maps.mini.fitBounds(L.latLngBounds([[addr.lat,addr.lng],[state.store.lat,state.store.lng]]).pad(.25))}
function openMap(addr){if(!addr.lat||!addr.lng){toast('Pedido sem coordenadas');return}$('#mapModal').classList.remove('hidden');$('#mapCaption').textContent=addr.text;setTimeout(()=>{if(state.maps.full){state.maps.full.remove();state.maps.full=null}state.maps.full=L.map('fullMap',{zoomControl:false,attributionControl:false});L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19}).addTo(state.maps.full);L.marker([addr.lat,addr.lng],{icon:pin('#e9003b','●')}).addTo(state.maps.full);L.marker([state.store.lat,state.store.lng],{icon:pin('#111','▣')}).addTo(state.maps.full);state.maps.full.fitBounds(L.latLngBounds([[addr.lat,addr.lng],[state.store.lat,state.store.lng]]).pad(.18))},80)}
$('#mapBack').onclick=()=>{$('#mapModal').classList.add('hidden');if(state.maps.full){state.maps.full.remove();state.maps.full=null}};

async function openChat(o){$('#chatModal').classList.remove('hidden');$('#chatTitle').textContent=`Chat · #${o.order_code||''}`;$('#chatSub').textContent=orderName(o);try{state.conversation=(await api('conversation_for_order',{order_id:o.id,customer_id:o.customer_id})).conversation;await loadMessages();subscribeMessages()}catch(error){toast(error.message||'Não foi possível abrir o chat')}}
async function loadMessages(){if(!state.conversation)return;try{const data=(await api('messages',{conversation_id:state.conversation.id})).messages||[];$('#messagesList').innerHTML=data.map(m=>`<div class="message ${m.sender_type==='staff'?'mine':''}">${esc(m.body)}<small>${fmtTime(m.created_at)}</small></div>`).join('')||'<div class="empty">Nenhuma mensagem ainda.</div>';$('#messagesList').scrollTop=$('#messagesList').scrollHeight}catch(error){console.error(error)}}
function subscribeMessages(){if(state.messagePoll)clearInterval(state.messagePoll);if(!state.conversation)return;state.messagePoll=setInterval(()=>{if(state.conversation&&!$('#chatModal').classList.contains('hidden'))loadMessages()},3000)}
$('#chatBack').onclick=()=>{$('#chatModal').classList.add('hidden');if(state.messagePoll){clearInterval(state.messagePoll);state.messagePoll=null}state.conversation=null};
$('#messageForm').addEventListener('submit',async e=>{e.preventDefault();const v=$('#messageInput').value.trim();if(!v||!state.conversation)return;$('#messageInput').value='';try{await api('send_message',{conversation_id:state.conversation.id,body:v});await loadMessages()}catch(error){toast(error.message||'Erro ao enviar mensagem')}});

setInterval(()=>{
  if(!state.session)return;
  $$('[data-deadline]').forEach(el=>{const o=state.orders.find(x=>x.id===el.dataset.deadline);if(o){el.textContent=acceptExpired(o)?'00:00':acceptCountdown(o);if(acceptExpired(o)&&['new','payment'].includes(statusKind(o.status)))autoExpireOrder(o)}});
  if(state.selected&&!$('#detailView').classList.contains('hidden')&&['new','payment'].includes(statusKind(state.selected.status))){const d=$('[data-detail-deadline]');if(d)d.textContent=acceptExpired(state.selected)?'00:00':acceptCountdown(state.selected);const swipe=$('#swipeAccept .swipe-text');if(swipe&&!acceptExpired(state.selected))swipe.textContent=`Arraste para aceitar · ${acceptCountdown(state.selected)}`;if(acceptExpired(state.selected))autoExpireOrder(state.selected)}
},1000);

document.documentElement.dataset.gestorVersion='gestor20rac-completo-2026.09.18-v4';
if(!window.__RODRIGUES_ANDROID__)init();
