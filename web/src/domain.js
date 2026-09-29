const NEW=new Set(['PENDENTE','NOVO','RECEBIDO','ENVIADO','AGUARDANDO_CONFIRMACAO','NOVO_PEDIDO','AGUARDANDO_ACEITE','AGUARDANDO_PAGAMENTO','SENT'])
const DONE=new Set(['CONCLUIDO','CONCLUÍDO','ENTREGUE','CANCELADO','CANCELADA','RETIRADO','FINALIZADO','COMPLETED','DELIVERED','CANCELLED','CANCELED'])
const PREP=new Set(['EM_PREPARO','PREPARANDO'])
const DELIVERY=new Set(['EM_ENTREGA','SAIU_PARA_ENTREGA','SAIU_ENTREGA','A_CAMINHO_CLIENTE','DESPACHADO','ENTREGADOR_NO_LOCAL'])
const money=v=>Number(v||0).toLocaleString('pt-BR',{style:'currency',currency:'BRL'})
const upper=v=>String(v||'').trim().toUpperCase()
const dig=v=>String(v||'').replace(/\D/g,'')
const esc=v=>String(v??'').replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]))

function normalize(row){
  const raw=row?.raw_payload&&typeof row.raw_payload==='object'
    ? row.raw_payload
    : row?.raw&&typeof row.raw==='object'
      ? row.raw
      : row||{}
  const client=row?.customer||raw.cliente||raw.customer||{}
  const values=raw.valores||{}
  const pay=row?.payment_details||raw.pagamento||{}
  const status=upper(row.status||raw.status||raw.statusPedido||'PENDENTE')
  const created=row.created_at||raw.createdAt||raw.criadoEm||raw.created_at||''
  const items=Array.isArray(row?.items)&&row.items.length?row.items:Array.isArray(raw.itens)?raw.itens:Array.isArray(raw.items)?raw.items:[]
  const address=row?.delivery_address||raw.endereco||{}
  const type0=upper(row?.fulfillment_type||raw.tipoPedido||raw.tipo_pedido||raw.fulfillment||'ENTREGA');const type=({DELIVERY:'ENTREGA',PICKUP:'RETIRADA',DINE_IN:'BALCAO'})[type0]||type0
  return {
    sourceRow:row,
    cancellationReason:row.cancellation_reason||raw.motivoCancelamento||'',
    pickupCode:row.pickup_code||raw.codigoRetirada||'',
    id:String(row.id||raw.id||''),
    number:String(row.order_code||raw.codigoPedido||raw.numeroPedido||raw.numero||row.id||'').replace(/^#/,'').slice(-12),
    status,
    created,
    clientName:String(client.name||client.nome||raw.nomeCliente||raw.clienteNome||'Cliente'),
    phone:String(client.phone||client.telefone||raw.telefoneCliente||''),
    type,
    total:Number(row.total??raw.total??values.total??0),
    subtotal:Number(row.subtotal??raw.subtotal??values.subtotal??0),
    freight:Number(row.delivery_fee??raw.frete??values.taxa??0),
    discount:Number(row.discount??raw.desconto??values.desconto??0),
    payment:String(row.payment_method||pay.forma||pay.metodo||raw.formaPagamento||raw.pagamentoMetodo||''),
    paymentStatus:upper(row.payment_status||pay.status||raw.statusPagamento||''),
    observation:String(row.customer_note||raw.observacao||raw.observação||''),
    address:[address.rua||address.street,address.numero||address.number,address.bairro||address.district].filter(Boolean).join(', '),
    items:items.map(i=>{
      const modifiers=i.modifiers||i.detalhes||{}
      const lines=Array.isArray(modifiers.linhasMontagem)?modifiers.linhasMontagem:
        Array.isArray(modifiers.linhas)?modifiers.linhas:
        Array.isArray(i.linhasMontagem)?i.linhasMontagem:
        Array.isArray(i.detalhes)?i.detalhes:[]
      return {
        qty:Number(i.quantity||i.quantidade||i.qtd||1),
        name:String(i.name||i.nome||i.titulo||i.produtoNome||i.baseNome||i.produto||'Item'),
        price:Number(i.total_price||i.total||i.preco||i.valor||i.unit_price||0),
        notes:String(i.notes||i.observacao||''),
        details:sortDetails(lines.map(x=>typeof x==='string'?x:x?.nome).filter(Boolean))
      }
    }),
    raw
  }
}
function ageMin(o){const ms=Date.parse(o.created);return Number.isFinite(ms)?Math.max(0,Math.floor((Date.now()-ms)/60000)):0}
function statusLabel(s){
  return ({PENDENTE:'Novo',NOVO:'Novo',RECEBIDO:'Novo',ENVIADO:'Novo',CONFIRMADO:'Confirmado',ACEITO:'Confirmado',EM_PREPARO:'Em preparo',PREPARANDO:'Em preparo',PRONTO:'Pronto',EM_ENTREGA:'Em entrega',SAIU_PARA_ENTREGA:'Em entrega',SAIU_ENTREGA:'Em entrega',CONCLUIDO:'Concluído',ENTREGUE:'Concluído',CANCELADO:'Cancelado'})[s]||s.replaceAll('_',' ')
}
function bucket(o){if(NEW.has(o.status))return'new';if(PREP.has(o.status))return'prep';if(['CONFIRMADO','ACEITO','FILA'].includes(o.status))return'confirmed';if(o.status==='PRONTO')return'ready';if(DELIVERY.has(o.status))return'delivery';return DONE.has(o.status)?'done':'other'}


function sortDetails(lines){
 const rank=s=>{s=s.normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase();if(/cobertura|calda/.test(s))return 0;if(/leite condensado/.test(s))return 1;if(/leite (em po|ninho)/.test(s))return 2;if(/morango|banana|uva|kiwi|manga|abacaxi|fruta/.test(s))return 9;return 3};
 return lines.map((s,i)=>({s,i,r:rank(s)})).sort((a,b)=>a.r-b.r||a.i-b.i).map(x=>x.s)
}
function acceptanceRemaining(o,now=Date.now()){return Math.max(0,300-Math.floor((now-Date.parse(o.created))/1000))}
function isLate(o){return !DONE.has(o.status)&&ageMin(o)>=(NEW.has(o.status)?5:30)}
function duplicateIds(orders){const ids=new Set();for(let i=0;i<orders.length;i++)for(let j=i+1;j<orders.length;j++){const a=orders[i],b=orders[j];if(!a.phone||a.phone!==b.phone||a.total!==b.total||Math.abs(Date.parse(a.created)-Date.parse(b.created))>300000)continue;const items=o=>JSON.stringify(o.items.map(i=>[i.name,i.qty,i.details]));if(items(a)===items(b)){ids.add(a.id);ids.add(b.id)}}return ids}
const timeLabel=v=>new Date(v).toLocaleString('pt-BR',{timeZone:'America/Campo_Grande',day:'2-digit',month:'2-digit',hour:'2-digit',minute:'2-digit'});
function receiptHtml(o){
  const items=o.items.map(i=>`<div class="item"><b>${i.qty}x</b><span>${esc(i.name)}</span><b>${i.price?money(i.price):''}</b></div>${i.notes?`<div class="detail">Obs: ${esc(i.notes)}</div>`:''}${i.details.map(d=>`<div class="detail">• ${esc(d)}</div>`).join('')}`).join('')
  return `<!doctype html><html><head><meta charset="utf-8"><style>@page{margin:2mm}body{font-family:monospace;color:#000;margin:0}.ticket{width:80mm;max-width:100%;padding:2mm;box-sizing:border-box}h1,h2{text-align:center;margin:4px}.rule{border-top:1px dashed #000;margin:9px 0}.item{display:grid;grid-template-columns:auto 1fr auto;gap:8px;margin:7px 0}.detail{padding-left:26px}.total{font-size:1.35em;display:flex;justify-content:space-between;margin:12px 0}</style></head><body><section class="ticket"><h1>RODRIGUES AÇAÍ E CIA</h1><h2>PEDIDO #${esc(o.number)}</h2><div class="rule"></div><p><b>Cliente:</b> ${esc(o.clientName)}</p><p><b>Tipo:</b> ${esc(o.type)}</p>${o.address?`<p><b>Endereço:</b> ${esc(o.address)}</p>`:''}<div class="rule"></div>${items}${o.observation?`<div class="rule"></div><p><b>OBS:</b> ${esc(o.observation)}</p>`:''}<div class="rule"></div><div class="total"><span>TOTAL</span><b>${money(o.total)}</b></div><p><b>Pagamento:</b> ${esc(o.payment)}</p></section></body></html>`
}


export {NEW,DONE,PREP,DELIVERY,money,upper,dig,esc,normalize,ageMin,statusLabel,bucket,sortDetails,acceptanceRemaining,isLate,duplicateIds,timeLabel,receiptHtml};
