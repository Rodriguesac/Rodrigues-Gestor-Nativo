const NAMESPACE = 'urn:x-cast:com.rodrigues.cozinha';
const context = cast.framework.CastReceiverContext.getInstance();
let orders = [];
let attentionTimer = null;

const el = (id) => document.getElementById(id);

function stageOf(status='') {
  const s = String(status).toUpperCase();
  if (['AGUARDANDO_CONFIRMACAO','RECEBIDO','PENDENTE','NOVO','NOVO_PEDIDO'].includes(s)) return 'NEW';
  if (['CONFIRMADO','FILA','ACEITO'].includes(s)) return 'CONFIRMED';
  if (['EM_PREPARO','PREPARANDO'].includes(s)) return 'PREPARING';
  if (s === 'PRONTO') return 'READY';
  return null;
}

function safe(v) {
  return String(v ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'}[ch]));
}

function ageMinutes(createdMillis) {
  const n = Number(createdMillis || 0);
  if (!n) return 0;
  return Math.max(0, Math.floor((Date.now() - n) / 60000));
}

function money(value) {
  return Number(value || 0).toLocaleString('pt-BR',{style:'currency',currency:'BRL'});
}

function renderCard(order) {
  const min = ageMinutes(order.createdMillis);
  const minClass = min >= 30 ? 'late' : min >= 20 ? 'warn' : '';
  const items = (order.items || []).slice(0,7).map(item => {
    const details = (item.details || []).slice(0,8).map(d => '<div class="detail">• '+safe(d)+'</div>').join('');
    return '<div class="item">'+safe(item.quantity)+'x '+safe(item.name)+'</div>'+details;
  }).join('');
  const more = (order.items || []).length > 7 ? '<div class="detail">+ '+((order.items||[]).length-7)+' item(ns)</div>' : '';
  const obs = order.observation ? '<div class="obs">OBS: '+safe(order.observation)+'</div>' : '';
  const address = order.pickup ? 'RETIRADA NO BALCÃO' : safe([order.address,order.neighborhood].filter(Boolean).join(' • '));
  return '<article class="card" data-order-id="'+safe(order.id)+'">'+
      '<div class="card-head"><div><div class="order-no">#'+safe(order.number)+'</div><div class="client">'+safe(order.clientName)+'</div></div>'+
      '<div class="minutes '+minClass+'">'+min+' min</div></div>'+
      '<div class="meta">'+(order.pickup ? 'RETIRADA' : 'ENTREGA')+' • '+money(order.total)+'</div>'+
      items+more+obs+'<div class="address">'+address+'</div></article>';
}

function render() {
  const groups = {NEW:[],CONFIRMED:[],PREPARING:[],READY:[]};
  orders.forEach(o => {
    const stage = stageOf(o.status);
    if (stage) groups[stage].push(o);
  });

  const mapping = {
    NEW:['colNew','badgeNew'],
    CONFIRMED:['colConfirmed','badgeConfirmed'],
    PREPARING:['colPreparing','badgePreparing'],
    READY:['colReady','badgeReady']
  };

  Object.entries(mapping).forEach(([stage,[col,badge]]) => {
    el(col).innerHTML = groups[stage].map(renderCard).join('');
    el(badge).textContent = groups[stage].length;
  });

  el('countTotal').textContent = orders.length;
  el('countNew').textContent = groups.NEW.length;
  el('countPrep').textContent = groups.PREPARING.length;
  el('countReady').textContent = groups.READY.length;

  const empty = orders.length === 0;
  el('board').style.display = empty ? 'none' : 'grid';
  el('emptyState').classList.toggle('show', empty);
}

function showAttention(message) {
  el('attentionOrder').textContent = '#'+safe(message.number || '---');
  el('attentionClient').textContent = message.clientName || 'Pedido';
  el('attention').classList.add('show');
  clearTimeout(attentionTimer);
  attentionTimer = setTimeout(() => el('attention').classList.remove('show'), 5000);
  setTimeout(() => {
    const card = document.querySelector('[data-order-id="'+CSS.escape(String(message.orderId||''))+'"]');
    if (card) card.classList.add('flash');
  }, 100);
}

context.addCustomMessageListener(NAMESPACE, (event) => {
  let message = event.data;
  if (typeof message === 'string') {
    try { message = JSON.parse(message); } catch { return; }
  }
  if (!message || typeof message !== 'object') return;

  if (message.type === 'orders_snapshot') {
    orders = Array.isArray(message.orders) ? message.orders : [];
    el('connection').textContent = 'Celular conectado • atualizado agora';
    render();
  } else if (message.type === 'attention') {
    showAttention(message);
  }
});

context.addEventListener(cast.framework.system.EventType.SENDER_CONNECTED, () => {
  el('connection').textContent = 'Celular conectado';
});

context.addEventListener(cast.framework.system.EventType.SENDER_DISCONNECTED, () => {
  if (context.getSenders().length === 0) el('connection').textContent = 'Aguardando celular';
});

setInterval(() => {
  el('clock').textContent = new Date().toLocaleTimeString('pt-BR',{hour:'2-digit',minute:'2-digit'});
  document.querySelectorAll('.card').forEach(card => {});
  render();
}, 10000);

el('clock').textContent = new Date().toLocaleTimeString('pt-BR',{hour:'2-digit',minute:'2-digit'});
render();

const options = new cast.framework.CastReceiverOptions();
options.disableIdleTimeout = true;
options.statusText = 'Rodrigues Cozinha';
options.customNamespaces = {};
options.customNamespaces[NAMESPACE] = cast.framework.system.MessageType.JSON;
context.start(options);
