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
    address:typeof address==='string'?address:[address.completo||address.texto||address.rua||address.street,address.completo||address.texto?'':address.numero||address.number,address.completo||address.texto?'':address.bairro||address.district].filter(Boolean).join(', ')||String(raw.enderecoEntrega||raw.enderecoCliente||''),
    items:items.map(i=>{
      const modifiers=i.modifiers||i.detalhes||{}
      const lines=Array.isArray(modifiers.linhasMontagem)?modifiers.linhasMontagem:
        Array.isArray(modifiers.linhas)?modifiers.linhas:
        Array.isArray(i.linhasMontagem)?i.linhasMontagem:
        Array.isArray(i.detalhes)?i.detalhes:[]
      const name=String(i.name||i.nome||i.titulo||i.produtoNome||i.baseNome||i.produto||'Item')
      return {
        qty:Number(i.quantity||i.quantidade||i.qtd||1),
        name,
        price:Number(i.total_price||i.total||i.preco||i.valor||i.unit_price||0),
        notes:String(i.notes||i.observacao||''),
        details:sortDetails(stripRepeatedBase(name,lines.map(x=>typeof x==='string'?x:x?.nome).filter(Boolean)))
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


// A base já descrita no produto não deve aparecer novamente como complemento.
function stripRepeatedBase(name,details){
 const key=value=>String(value||'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').toUpperCase().replace(/[^A-Z0-9]+/g,' ').trim()
 const product=key(name)
 return details.filter(detail=>{
   const base=key(detail).replace(/^(BASE|TIPO DE BASE|SABOR) /,'')
   return !(base==='ACAI ORIGEM' && product.includes('ACAI ORIGEM'))
 })
}

function sortDetails(lines){
 const rank=s=>{s=s.normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase();if(/cobertura|calda/.test(s))return 0;if(/leite condensado/.test(s))return 1;if(/leite (em po|ninho)/.test(s))return 2;if(/morango|banana|uva|kiwi|manga|abacaxi|fruta/.test(s))return 9;return 3};
 return lines.map((s,i)=>({s,i,r:rank(s)})).sort((a,b)=>a.r-b.r||a.i-b.i).map(x=>x.s)
}
function acceptanceRemaining(o,now=Date.now()){return Math.max(0,300-Math.floor((now-Date.parse(o.created))/1000))}
function isLate(o){return !DONE.has(o.status)&&ageMin(o)>=(NEW.has(o.status)?5:30)}
function duplicateIds(orders){const ids=new Set();for(let i=0;i<orders.length;i++)for(let j=i+1;j<orders.length;j++){const a=orders[i],b=orders[j];if(!a.phone||a.phone!==b.phone||a.total!==b.total||Math.abs(Date.parse(a.created)-Date.parse(b.created))>300000)continue;const items=o=>JSON.stringify(o.items.map(i=>[i.name,i.qty,i.details]));if(items(a)===items(b)){ids.add(a.id);ids.add(b.id)}}return ids}
const timeLabel=v=>new Date(v).toLocaleString('pt-BR',{timeZone:'America/Campo_Grande',day:'2-digit',month:'2-digit',hour:'2-digit',minute:'2-digit'});
function receiptHtml(o){
 const label=value=>esc(upper(value))
 const meta=(key,value)=>'<div class="meta"><b>'+key+'</b> '+label(value)+'</div>'
 const items=(o.items||[]).map(i=>{
  const details=(i.details||[]).map(x=>'<div class="detail">+ '+label(x)+'</div>').join('')
  return '<section class="item"><div class="item-title">'+label(i.qty)+'X '+label(i.name)+'</div>'+
   (i.price>0?'<div class="item-price">'+money(i.price)+'</div>':'')+
   details+(i.notes?'<div class="obs">OBS: '+label(i.notes)+'</div>':'')+'</section>'
 }).join('')
 const moneyRow=(text,value)=>'<div class="money-row"><span>'+text+'</span><b>'+money(value)+'</b></div>'
 const address=o.type==='RETIRADA'?'RETIRADA NA LOJA':o.address||'ENDEREÇO NÃO INFORMADO'
 return '<!doctype html><html><head><meta charset="utf-8"><style>'+
  '@page{margin:0}*{box-sizing:border-box}html,body{margin:0;padding:0;background:#fff;color:#000;font:700 13px/1.28 Arial,Helvetica,sans-serif}'+
  '.ticket{width:76mm;margin:0 auto;padding:2mm 1mm 3mm}h1{font-size:16px;margin:0;text-align:center;font-weight:900}h2{font-size:21px;margin:3px 0 5px;text-align:center;font-weight:900}'+
  '.rule{border-top:1px dashed #000;margin:7px 0}.meta{margin:3px 0;overflow-wrap:anywhere}.address{border:1px solid #000;padding:5px;margin:6px 0;overflow-wrap:anywhere}.address b{display:block;margin-bottom:2px}'+
  '.heading{font-size:14px;margin:0 0 5px;font-weight:900}.item{margin:7px 0 9px;break-inside:avoid;page-break-inside:avoid}.item-title{font-size:16px;line-height:1.25;font-weight:900;overflow-wrap:anywhere}.item-price{text-align:right;margin:1px 0 4px;font-size:14px}.detail{font-size:14px;line-height:1.3;margin:2px 0 2px 5px;overflow-wrap:anywhere}.obs{border:1px solid #000;margin:5px 0;padding:5px;overflow-wrap:anywhere}'+
  '.money-row{display:flex;justify-content:space-between;gap:5px;margin:3px 0}.total{border-top:2px solid #000;padding-top:6px;font-size:18px;font-weight:900;margin:7px 0}.payment{margin:8px 0 0;overflow-wrap:anywhere}'+
  '</style></head><body><main class="ticket"><h1>RODRIGUES AÇAÍ E CIA</h1><h2>PEDIDO #'+label(o.number)+'</h2>'+
  '<div class="rule"></div>'+meta('CLIENTE:',o.clientName)+(o.phone?meta('TELEFONE:',o.phone):'')+meta('TIPO:',o.type)+
  '<div class="address"><b>'+(o.type==='RETIRADA'?'RETIRADA':'ENDEREÇO DE ENTREGA')+'</b>'+label(address)+'</div>'+
  '<div class="rule"></div><h3 class="heading">ITENS DO PEDIDO</h3>'+items+
  (o.observation?'<div class="obs">OBS DO PEDIDO: '+label(o.observation)+'</div>':'')+
  '<div class="rule"></div>'+
  (o.subtotal>0&&o.subtotal!==o.total?moneyRow('SUBTOTAL',o.subtotal):'')+
  (o.freight>0?moneyRow('ENTREGA',o.freight):'')+
  (o.discount>0?moneyRow('DESCONTO',-o.discount):'')+
  '<div class="money-row total"><span>TOTAL</span><b>'+money(o.total)+'</b></div>'+
  '<div class="payment">PAGAMENTO: '+label(o.payment||'NÃO INFORMADO')+(o.paymentStatus?' - '+label(o.paymentStatus):'')+'</div>'+
  '</main></body></html>'
}


export {NEW,DONE,PREP,DELIVERY,money,upper,dig,esc,normalize,ageMin,statusLabel,bucket,sortDetails,acceptanceRemaining,isLate,duplicateIds,timeLabel,receiptHtml};
