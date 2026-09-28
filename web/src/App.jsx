import React, { useEffect, useMemo, useRef, useState } from 'react'
import { hybrid } from './hybrid.js'

const API='https://jgjmntezfjuyuxhcnvhd.supabase.co/functions/v1/gestor-orders'
const NEW=new Set(['PENDENTE','NOVO','RECEBIDO','ENVIADO'])
const DONE=new Set(['CONCLUIDO','ENTREGUE','CANCELADO'])
const PREP=new Set(['EM_PREPARO','PREPARANDO'])
const DELIVERY=new Set(['EM_ENTREGA','SAIU_PARA_ENTREGA','SAIU_ENTREGA','A_CAMINHO_CLIENTE'])
const money=v=>Number(v||0).toLocaleString('pt-BR',{style:'currency',currency:'BRL'})
const upper=v=>String(v||'').trim().toUpperCase()
const dig=v=>String(v||'').replace(/\D/g,'')
const esc=v=>String(v??'').replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]))

function normalize(row){
  const raw=row?.raw&&typeof row.raw==='object'?row.raw:row||{}
  const client=raw.cliente||raw.customer||{}
  const values=raw.valores||{}
  const pay=raw.pagamento||{}
  const status=upper(row.status||raw.status||raw.statusPedido||'PENDENTE')
  const created=row.created_at||raw.createdAt||raw.criadoEm||raw.created_at||new Date().toISOString()
  const items=Array.isArray(raw.itens)?raw.itens:Array.isArray(raw.items)?raw.items:[]
  const address=raw.endereco||{}
  const type=upper(raw.tipoPedido||raw.tipo_pedido||raw.fulfillment||'ENTREGA')
  return {
    id:String(row.id||raw.id||''),
    number:String(row.order_code||raw.codigoPedido||raw.numeroPedido||raw.numero||row.id||'').replace(/^#/,'').slice(-12),
    status,
    created,
    clientName:String(client.nome||client.name||raw.nomeCliente||raw.clienteNome||'Cliente'),
    phone:String(client.telefone||client.phone||raw.telefoneCliente||''),
    type,
    total:Number(row.total??raw.total??values.total??0),
    subtotal:Number(raw.subtotal??values.subtotal??0),
    freight:Number(raw.frete??values.taxa??0),
    discount:Number(raw.desconto??values.desconto??0),
    payment:String(pay.forma||pay.metodo||raw.formaPagamento||raw.pagamentoMetodo||''),
    paymentStatus:upper(pay.status||raw.statusPagamento||''),
    observation:String(raw.observacao||raw.observação||''),
    address:[address.rua||address.street,address.numero||address.number,address.bairro||address.district].filter(Boolean).join(', '),
    items:items.map(i=>({
      qty:Number(i.quantidade||i.qtd||i.quantity||1),
      name:String(i.nome||i.titulo||i.produtoNome||i.baseNome||i.produto||'Item'),
      price:Number(i.total||i.preco||i.valor||0),
      details:Array.isArray(i.linhasMontagem)?i.linhasMontagem.map(x=>x?.nome||x).filter(Boolean):
        Array.isArray(i.detalhes)?i.detalhes.map(x=>typeof x==='string'?x:x?.nome).filter(Boolean):[]
    })),
    raw
  }
}
function ageMin(o){return Math.max(0,Math.floor((Date.now()-new Date(o.created).getTime())/60000))}
function statusLabel(s){
  return ({PENDENTE:'Novo',NOVO:'Novo',RECEBIDO:'Novo',ENVIADO:'Novo',CONFIRMADO:'Confirmado',ACEITO:'Confirmado',EM_PREPARO:'Em preparo',PREPARANDO:'Em preparo',PRONTO:'Pronto',EM_ENTREGA:'Em entrega',SAIU_PARA_ENTREGA:'Em entrega',SAIU_ENTREGA:'Em entrega',CONCLUIDO:'Concluído',ENTREGUE:'Concluído',CANCELADO:'Cancelado'})[s]||s.replaceAll('_',' ')
}
function bucket(o){if(NEW.has(o.status))return'new';if(PREP.has(o.status))return'prep';if(o.status==='CONFIRMADO'||o.status==='ACEITO')return'confirmed';if(o.status==='PRONTO')return'ready';if(DELIVERY.has(o.status))return'delivery';return DONE.has(o.status)?'done':'other'}

async function api(pin,payload){
  const r=await fetch(API,{method:'POST',headers:{'Content-Type':'application/json','x-gestor-pin':pin},body:JSON.stringify(payload),cache:'no-store'})
  const d=await r.json().catch(()=>({}))
  if(r.status===401)throw Object.assign(new Error('PIN inválido.'),{code:401})
  if(!r.ok||d.ok===false)throw new Error(d.error||'Falha ao acessar o Gestor.')
  return d
}

function Icon({name}){
  const paths={
    orders:'M5 4h14v16H5z M8 8h8 M8 12h8 M8 16h5',
    history:'M12 8v5l3 2 M4.9 4.9A10 10 0 1 1 2 12 M2 4v6h6',
    products:'M4 7h16 M6 7l1 13h10l1-13 M9 7V4h6v3',
    store:'M3 10l2-6h14l2 6 M5 10v10h14V10 M9 20v-6h6v6',
    more:'M5 12h.01 M12 12h.01 M19 12h.01',
    bell:'M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9 M10 21h4',
    search:'M21 21l-4.3-4.3 M11 18a7 7 0 1 1 0-14 7 7 0 0 1 0 14',
    chevron:'M9 6l6 6-6 6',
    print:'M6 9V3h12v6 M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2 M6 14h12v7H6z'
  }
  return <svg viewBox="0 0 24 24" aria-hidden="true"><path d={paths[name]||paths.more}/></svg>
}

function PinGate({onReady}){
  const [pin,setPin]=useState('')
  const [error,setError]=useState('')
  useEffect(()=>{
    const n=hybrid.getNativePin()
    const saved=n||localStorage.getItem('rodrigues_gestor_pin')||''
    if(/^\d{6}$/.test(saved))onReady(saved)
  },[onReady])
  const submit=e=>{e.preventDefault();const p=dig(pin).slice(0,6);if(p.length!==6){setError('Digite os 6 números.');return}localStorage.setItem('rodrigues_gestor_pin',p);onReady(p)}
  return <div className="gate"><form className="gate-card" onSubmit={submit}>
    <div className="gate-logo">R</div><small>RODRIGUES GESTOR</small><h1>Acesso operacional</h1><p>Digite o PIN do Gestor para abrir os pedidos.</p>
    <input autoFocus inputMode="numeric" maxLength={6} value={pin} onChange={e=>setPin(dig(e.target.value).slice(0,6))} placeholder="••••••"/>
    {error&&<div className="error">{error}</div>}<button>Entrar</button>
  </form></div>
}

function OrderCard({o,onOpen}){
  const mins=ageMin(o),b=bucket(o),late=!DONE.has(o.status)&&mins>=20
  return <button className={"order-card "+(late?'late ':'')+b} onClick={()=>onOpen(o)}>
    <div className="order-top"><div><h3>PEDIDO #{o.number||o.id.slice(-6)}</h3><p>{o.clientName} {o.phone&&<>• {o.phone}</>}</p></div><span className="state">{statusLabel(o.status)}</span></div>
    <div className="order-meta"><span className={late?'time late-time':'time'}>{mins<1?'agora':mins+' min'}</span><b>{o.type==='RETIRADA'?'RETIRADA':'ENTREGA'}</b>{o.payment&&<span>{o.payment}</span>}</div>
    <div className="order-foot"><span>{o.items.length?o.items.slice(0,2).map(i=>i.qty+'× '+i.name).join(' • '):'Pedido sem itens detalhados'}</span><strong>{money(o.total)}</strong></div>
  </button>
}

function receiptHtml(o){
  const items=o.items.map(i=>`<div class="item"><b>${i.qty}x</b><span>${esc(i.name)}</span><b>${i.price?money(i.price):''}</b></div>${i.details.map(d=>`<div class="detail">• ${esc(d)}</div>`).join('')}`).join('')
  return `<!doctype html><html><head><meta charset="utf-8"><style>@page{margin:2mm}body{font-family:monospace;color:#000;margin:0}.ticket{width:80mm;max-width:100%;padding:2mm;box-sizing:border-box}h1,h2{text-align:center;margin:4px}.rule{border-top:1px dashed #000;margin:9px 0}.item{display:grid;grid-template-columns:auto 1fr auto;gap:8px;margin:7px 0}.detail{padding-left:26px}.total{font-size:1.35em;display:flex;justify-content:space-between;margin:12px 0}</style></head><body><section class="ticket"><h1>RODRIGUES AÇAÍ E CIA</h1><h2>PEDIDO #${esc(o.number)}</h2><div class="rule"></div><p><b>Cliente:</b> ${esc(o.clientName)}</p><p><b>Tipo:</b> ${esc(o.type)}</p>${o.address?`<p><b>Endereço:</b> ${esc(o.address)}</p>`:''}<div class="rule"></div>${items}${o.observation?`<div class="rule"></div><p><b>OBS:</b> ${esc(o.observation)}</p>`:''}<div class="rule"></div><div class="total"><span>TOTAL</span><b>${money(o.total)}</b></div><p><b>Pagamento:</b> ${esc(o.payment)}</p></section></body></html>`
}

function OrderSheet({o,onClose,onAction,busy}){
  const actions=bucket(o)==='new'?[['CONFIRMADO','Aceitar'],['CANCELADO','Rejeitar']]:
    bucket(o)==='confirmed'?[['EM_PREPARO','Iniciar preparo'],['CANCELADO','Cancelar']]:
    bucket(o)==='prep'?[['PRONTO','Pedido pronto'],['CANCELADO','Cancelar']]:
    bucket(o)==='ready'?[['EM_ENTREGA',o.type==='RETIRADA'?'Finalizar retirada':'Despachar']]:
    bucket(o)==='delivery'?[['CONCLUIDO','Finalizar entrega']]:[]
  return <div className="sheet-backdrop" onClick={e=>e.target===e.currentTarget&&onClose()}>
    <section className="sheet"><div className="handle"/><header><div><small>PEDIDO</small><h2>#{o.number}</h2></div><button className="close" onClick={onClose}>×</button></header>
      <div className="sheet-chips"><span>{statusLabel(o.status)}</span><span>{o.type}</span><span>{ageMin(o)} min</span></div>
      <div className="info"><b>{o.clientName}</b>{o.phone&&<small>{o.phone}</small>}{o.address&&<small>{o.address}</small>}</div>
      <div className="items">{o.items.map((i,k)=><div className="sheet-item" key={k}><div><b>{i.qty}× {i.name}</b>{i.details.map((d,j)=><small key={j}>• {d}</small>)}</div><strong>{i.price?money(i.price):''}</strong></div>)}</div>
      {o.observation&&<div className="obs"><b>Observação</b><p>{o.observation}</p></div>}
      <div className="sheet-total"><span>Total</span><b>{money(o.total)}</b></div>
      <div className="sheet-actions">{actions.map(([s,l])=><button disabled={busy} className={s==='CANCELADO'?'danger':''} key={s} onClick={()=>onAction(s)}>{l}</button>)}</div>
      <button className="secondary" onClick={()=>hybrid.printHtml(receiptHtml(o))}><Icon name="print"/> Imprimir comanda</button>
    </section>
  </div>
}

export default function App(){
  const [pin,setPin]=useState('')
  const [orders,setOrders]=useState([])
  const [loading,setLoading]=useState(false)
  const [error,setError]=useState('')
  const [filter,setFilter]=useState('Todos')
  const [query,setQuery]=useState('')
  const [tab,setTab]=useState('orders')
  const [selected,setSelected]=useState(null)
  const [busy,setBusy]=useState(false)
  const prevIds=useRef(new Set())

  const load=async(silent=false)=>{
    if(!pin)return
    if(!silent)setLoading(true)
    try{
      const d=await api(pin,{action:'list',limit:120})
      const rows=(d.orders||[]).map(normalize).sort((a,b)=>new Date(b.created)-new Date(a.created))
      setOrders(rows);setError('')
      const current=new Set(rows.filter(o=>NEW.has(o.status)).map(o=>o.id))
      if(prevIds.current.size){
        const fresh=[...current].filter(id=>!prevIds.current.has(id))
        if(fresh.length&&'Notification' in window&&Notification.permission==='granted'&&!hybrid.isNative()){
          new Notification('Novo pedido',{body:'Chegou '+fresh.length+' novo pedido no Rodrigues Gestor.'})
        }
      }
      prevIds.current=current
    }catch(e){
      setError(e.message||'Falha ao atualizar.')
      if(e.code===401){localStorage.removeItem('rodrigues_gestor_pin');setPin('')}
    }finally{if(!silent)setLoading(false)}
  }
  useEffect(()=>{if(pin){load();const t=setInterval(()=>load(true),3500);return()=>clearInterval(t)}},[pin])
  useEffect(()=>{hybrid.keepAwake(true);return()=>hybrid.keepAwake(false)},[])\n  useEffect(()=>{\n    const open=e=>setRequestedId(String(e?.detail?.id||''))\n    addEventListener('native:open-order',open)\n    return()=>removeEventListener('native:open-order',open)\n  },[])\n  useEffect(()=>{\n    if(!requestedId)return\n    const found=orders.find(o=>o.id===requestedId||o.number===requestedId)\n    if(found){setSelected(found);setRequestedId('')}\n  },[requestedId,orders])

  const active=orders.filter(o=>!DONE.has(o.status))
  const counts={
    new:active.filter(o=>bucket(o)==='new').length,
    confirmed:active.filter(o=>bucket(o)==='confirmed').length,
    prep:active.filter(o=>bucket(o)==='prep').length,
    ready:active.filter(o=>bucket(o)==='ready').length
  }
  const filtered=useMemo(()=>{
    let rows=tab==='history'?orders.filter(o=>DONE.has(o.status)):orders.filter(o=>!DONE.has(o.status))
    if(filter==='Atrasados')rows=rows.filter(o=>ageMin(o)>=20)
    if(filter==='Entrega')rows=rows.filter(o=>o.type!=='RETIRADA')
    if(filter==='Retirada')rows=rows.filter(o=>o.type==='RETIRADA')
    if(filter==='Pagamento')rows=rows.filter(o=>o.paymentStatus&& !['PAGO','PAID','APROVADO'].includes(o.paymentStatus))
    const q=query.trim().toLowerCase()
    if(q)rows=rows.filter(o=>(o.number+' '+o.clientName+' '+o.items.map(i=>i.name).join(' ')).toLowerCase().includes(q))
    return rows
  },[orders,filter,query,tab])

  const action=async status=>{
    if(!selected)return
    let reason=''
    if(status==='CANCELADO'){reason=prompt('Motivo do cancelamento:','')||'';if(!reason.trim())return}
    setBusy(true)
    try{
      await api(pin,status==='CANCELADO'?{action:'cancel',orderId:selected.id,reason}:{action:'status',orderId:selected.id,status})
      hybrid.vibrate(70);hybrid.stopRing();await load(true);setSelected(null)
    }catch(e){alert(e.message||'Não foi possível alterar o pedido.')}finally{setBusy(false)}
  }

  if(!pin)return <PinGate onReady={setPin}/>

  return <div className="app">
    <header className="hero">
      <div className="hero-line"><div><h1>Bom dia <span>👋</span></h1><p>Central de pedidos ao vivo</p></div><button className="bell" onClick={()=>{if(hybrid.isNative())hybrid.notificationSettings();else if('Notification' in window) Notification.requestPermission?.()}}><Icon name="bell"/></button></div>
      <div className="hero-pills"><span><i/> Loja aberta</span><span><em/> Preparo ~25 min</span></div>
      <div className="presence"><i/> {hybrid.isNative()?'Modo nativo ativo':'PWA ativo'} • {active.length} pedidos em andamento</div>
    </header>

    {tab==='orders'||tab==='history'?<main>
      <section className="stats">
        <button className="stat red" onClick={()=>{setTab('orders');setFilter('Todos')}}><b>{counts.new}</b><span>Novos</span></button>
        <button className="stat purple"><b>{counts.confirmed}</b><span>Confirmado</span></button>
        <button className="stat amber"><b>{counts.prep}</b><span>Em preparo</span></button>
        <button className="stat green"><b>{counts.ready}</b><span>Prontos</span></button>
      </section>
      <label className="search"><Icon name="search"/><input value={query} onChange={e=>setQuery(e.target.value)} placeholder="Pedido, cliente ou item"/></label>
      <div className="filters">{['Todos','Atrasados','Entrega','Retirada','Pagamento'].map(f=><button key={f} className={filter===f?'on':''} onClick={()=>setFilter(f)}>{f==='Pagamento'?'Pagamento pendente':f}</button>)}</div>
      {error&&<div className="error-bar">{error}<button onClick={()=>load()}>Tentar novamente</button></div>}
      <section className="orders">{loading&&!orders.length?<div className="loading">Carregando pedidos…</div>:filtered.length?filtered.map(o=><OrderCard key={o.id} o={o} onOpen={setSelected}/>):<div className="empty">Nenhum pedido neste filtro.</div>}</section>
    </main>:<main className="module">
      <div className="module-card"><div className="module-icon"><Icon name={tab==='products'?'products':tab==='store'?'store':'more'}/></div>
      <h2>{tab==='products'?'Produtos':tab==='store'?'Loja':'Mais'}</h2>
      <p>{tab==='products'?'O catálogo continua sendo lido do backend. Esta área será a próxima a receber os controles de pausar, reativar e editar.':tab==='store'?'Os controles operacionais da loja ficam disponíveis no mesmo React, com funções extras quando aberto no APK.':'Preferências híbridas do Gestor.'}</p>
      {tab==='more'&&<div className="module-actions"><button onClick={()=>hybrid.keepAwake(true)}>Manter tela ligada</button><button onClick={()=>hybrid.notificationSettings()}>Notificações do aparelho</button><button onClick={()=>{localStorage.removeItem('rodrigues_gestor_pin');setPin('')}}>Trocar PIN</button></div>}
      </div>
    </main>}

    <nav className="bottom">
      {[['orders','orders','Pedidos'],['history','history','Histórico'],['products','products','Produtos'],['store','store','Loja'],['more','more','Mais']].map(([id,ic,label])=><button key={id} className={tab===id?'on':''} onClick={()=>{setTab(id);setFilter('Todos')}}><Icon name={ic}/><span>{label}</span></button>)}
    </nav>
    {selected&&<OrderSheet o={selected} onClose={()=>setSelected(null)} onAction={action} busy={busy}/>}
  </div>
}
