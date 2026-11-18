const NEW=new Set(['PENDENTE','NOVO','RECEBIDO','ENVIADO','AGUARDANDO_CONFIRMACAO','NOVO_PEDIDO','AGUARDANDO_ACEITE','AGUARDANDO_PAGAMENTO','SENT'])
const DONE=new Set(['CONCLUIDO','CONCLUÍDO','ENTREGUE','CANCELADO','CANCELADA','RETIRADO','FINALIZADO','COMPLETED','DELIVERED','CANCELLED','CANCELED'])
const PREP=new Set(['EM_PREPARO','PREPARANDO'])
const DELIVERY=new Set(['EM_ENTREGA','SAIU_PARA_ENTREGA','SAIU_ENTREGA','A_CAMINHO_CLIENTE','DESPACHADO','ENTREGADOR_NO_LOCAL'])
const money=v=>Number(v||0).toLocaleString('pt-BR',{style:'currency',currency:'BRL'})
const upper=v=>String(v||'').trim().toUpperCase()
const dig=v=>String(v||'').replace(/\D/g,'')
const esc=v=>String(v??'').replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]))

// O backend pode enviar itens resumidos e a montagem completa no raw_payload.
// Reunimos os dois formatos, preservando nomes, quantidades e adicionais de cada item.
const DETAIL_KEYS=['linhasMontagem','linhas','linhasIncluido','linhasIncluidas','cobertura_detalhes','coberturas_detalhes','acompanhamentos_detalhes','adicionais_detalhes','coberturas','cobertura','acompanhamentos','adicionais','extras','complementos','opcoes','opções','options','toppings','frutas','selecoes','seleções','montagem','sabor','baseNome','base_nome','base'];
const asObj=v=>v&&typeof v==='object'&&!Array.isArray(v)?v:{};
const itemName=i=>String(i?.name||i?.nome||i?.titulo||i?.produtoNome||i?.baseNome||i?.produto||'Item');
function extractDetails(...sources){
 const result=[],seen=new Set();
 const add=s=>{
  for(const part of String(s||'').split(/[\n;]+/)){
   const value=part.trim().replace(/^[-+•]\s*/, '');
   const key=value.normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase();
   if(value&&!seen.has(key)){seen.add(key);result.push(value)}
  }
 };
 const category=key=>/^cobertura/i.test(key)?'Cobertura: ':''; 
 const scan=(value,depth=0,prefix='')=>{
  if(value==null||depth>5||value===false)return;
  if(typeof value==='string'){add(prefix+value);return}
  if(Array.isArray(value)){value.forEach(x=>scan(x,depth+1,prefix));return}
  if(typeof value!=='object')return;
  const m=asObj(value);
  const label=m.nome||m.name||m.titulo||m.label||m.texto||m.descricao;
  if(typeof label==='string'&&label.trim()){
   const qty=Number(m.quantidade||m.quantity||m.qtd||1);
   add(prefix+(qty>1&&Number.isFinite(qty)?qty+'× ':'')+label);
  }
  // Somente campos de montagem: não imprime campos técnicos como ids e valores.
  DETAIL_KEYS.forEach(key=>{if(m[key]!=null)scan(m[key],depth+1,category(key)||prefix)});
 };
 sources.forEach(source=>{
  const v=asObj(source);
  if(Array.isArray(source)||typeof source==='string')scan(source);
  else DETAIL_KEYS.forEach(key=>{if(v[key]!=null)scan(v[key],0,category(key))});
 });
 return sortDetails(result);
}
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
  const rawItems=[raw.itens,raw.items,raw.produtos,raw.carrinho].find(x=>Array.isArray(x)&&x.length)||[]
  const enrichedItems=Array.isArray(row?.items)&&row.items.length?row.items:[]
  const items=enrichedItems.length?enrichedItems:rawItems
  const address=row?.delivery_address||raw.endereco||{}
  const type0=upper(row?.fulfillment_type||raw.tipoPedido||raw.tipo_pedido||raw.fulfillment||'ENTREGA');const type=({DELIVERY:'ENTREGA',PICKUP:'RETIRADA',DINE_IN:'BALCAO'})[type0]||type0
  const normalizedItems=items.map((source,index)=>{
    const i=asObj(source);
    // O enriquecimento pode substituir o item, mas omitir sua montagem.
    const rawCandidate=asObj(rawItems[index]);
    const rawItem=enrichedItems.length&&
      rawItems.length===enrichedItems.length&&
      upper(itemName(i))===upper(itemName(rawCandidate))?rawCandidate:{};
    const modifiers=asObj(i.modifiers),details=asObj(i.detalhes);
    const originalModifiers=asObj(rawItem.modifiers),originalDetails=asObj(rawItem.detalhes);
    const name=itemName(i);
    const found=extractDetails(i,modifiers,details,rawItem,originalModifiers,originalDetails,
      Array.isArray(i.detalhes)?i.detalhes:null,Array.isArray(rawItem.detalhes)?rawItem.detalhes:null);
    const detailsList=found.filter(d=>upper(d)!==upper(name));
    return {
      qty:Math.max(1,Number(i.quantity||i.quantidade||i.qtd||rawItem.quantidade||1)||1),
      name,
      price:Number(i.total_price??i.total??i.precoTotal??i.preco??i.valor??rawItem.total??i.unit_price??0)||0,
      notes:String(i.notes||i.observacao||i.observação||rawItem.notes||rawItem.observacao||''),
      details:detailsList
    };
  })
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
    items:normalizedItems,
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
  const items=o.items.map(i=>`<div class="item"><span>${esc(i.qty)}x ${esc(i.name)}</span>${i.price? `<b>${money(i.price)}</b>`:''}</div>${i.details.map(d=>`<div class="detail">+ ${esc(d)}</div>`).join('')}${i.notes?`<div class="detail">Obs: ${esc(i.notes)}</div>`:''}`).join('')
  return `<!doctype html><html><head><meta charset="utf-8"><style>
  @page{margin:0}*{box-sizing:border-box}
  body{font-family:Arial,Helvetica,sans-serif;font-size:14px;font-weight:800;color:#000;background:#fff;margin:0;-webkit-print-color-adjust:exact;print-color-adjust:exact}
  .ticket{width:76mm;max-width:100%;margin:0;padding:2mm 1mm;overflow-wrap:anywhere;text-transform:uppercase;font-weight:800;line-height:1.4}
  h1{text-align:center;font-size:16px;margin:0 0 5px;font-weight:900}
  h2{text-align:center;font-size:22px;margin:0 0 6px;font-weight:900}
  p{margin:5px 0;line-height:1.35}.rule{border-top:1px dashed #000;margin:8px 0}
  .heading{font-size:15px;font-weight:900}.item{display:flex;justify-content:space-between;align-items:flex-start;gap:6px;margin:10px 0 2px;font-size:16px;font-weight:900;line-height:1.4;break-inside:avoid}
  .item span{flex:1;min-width:0}.item b{white-space:nowrap;font-weight:900}.detail{padding-left:9px;font-size:14px;line-height:1.45;font-weight:800;break-inside:avoid}
  .total{font-size:19px;font-weight:900;display:flex;justify-content:space-between;gap:6px;margin:12px 0}
  </style></head><body><section class="ticket">
  <h1>RODRIGUES AÇAÍ E CIA</h1><h2>PEDIDO #${esc(o.number)}</h2>
  <div class="rule"></div><p><b>Cliente:</b> ${esc(o.clientName)}</p>${o.phone?`<p>Telefone: ${esc(o.phone)}</p>`:''}
  <p><b>Tipo:</b> ${esc(o.type)}</p>${o.address?`<p><b>Endereço:</b> ${esc(o.address)}</p>`:''}
  <div class="rule"></div><p class="heading">ITENS DO PEDIDO</p>${items||'<p>Itens ainda não informados pela central.</p>'}
  ${o.observation?`<div class="rule"></div><p><b>OBS:</b> ${esc(o.observation)}</p>`:''}
  <div class="rule"></div>${o.subtotal>0?`<p>Subtotal: ${money(o.subtotal)}</p>`:''}
  ${o.freight>0?`<p>Entrega: ${money(o.freight)}</p>`:''}
  ${o.discount>0?`<p>Desconto: -${money(o.discount)}</p>`:''}
  <div class="total"><span>TOTAL</span><span>${money(o.total)}</span></div>
  <p>Pagamento: ${esc(o.payment||'Não informado')} ${esc(o.paymentStatus||'')}</p>
  </section></body></html>`;
}

export {NEW,DONE,PREP,DELIVERY,money,upper,dig,esc,normalize,ageMin,statusLabel,bucket,sortDetails,acceptanceRemaining,isLate,duplicateIds,timeLabel,receiptHtml};
