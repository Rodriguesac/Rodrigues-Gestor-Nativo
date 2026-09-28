const groups=new Set(['bases','cardapio_acai','acompanhamentos_gratis','adicionais','coberturas','utensilios']);
const string=(v:any)=>String(v??'').trim();
const num=(v:any,min=0,max=10000)=>{const n=Number(v);if(!Number.isFinite(n)||n<min||n>max)throw Error('Valor inválido.');return n};
const allowedUrl=(v:any)=>!v||/^https:\/\//.test(string(v));
export async function appActions(c:any){
 const {admin,req,body,action,operator,json,readDoc,writeDoc,sha256}=c;
 const now=new Date().toISOString();
 const ok=(data:any={})=>json(req,{ok:true,...data});
 const check=(r:any)=>{if(r.error)throw r.error;return r.data};
 if(action==='me')return ok({operator});
 if(action==='staff_list'){
  const rows=check(await admin.from('app_documents').select('document_id,data').eq('collection_name','security_gestor_app_staff'));
  return ok({staff:rows.map((r:any)=>({id:r.document_id,name:r.data.name,role:r.data.role,active:r.data.active}))});
 }
 if(action==='staff_save'){
  const id=string(body.id).toLowerCase();if(!/^[a-z0-9_-]{3,30}$/.test(id)||id==='owner')throw Error('Use um código de acesso de 3 a 30 letras ou números.');
  const old=await readDoc(admin,'security_gestor_app_staff',id);
  const name=string(body.name).slice(0,100);const role=string(body.role);
  if(!name||!['atendente','montador'].includes(role))throw Error('Nome e perfil obrigatórios.');
  let hash=old?.hash,salt=old?.salt;
  if(body.pin){if(!/^\d{6}$/.test(body.pin))throw Error('O PIN precisa ter 6 números.');salt=crypto.randomUUID();hash=await sha256(salt+body.pin)}
  if(!hash)throw Error('Defina um PIN para a pessoa.');
  await writeDoc(admin,'security_gestor_app_staff',id,{name,role,hash,salt,active:body.active!==false,version:crypto.randomUUID(),updatedAt:now});return ok();
 }
 if(action==='catalog_save'){
  const name=string(body.name).slice(0,160);if(!name)throw Error('Informe o nome.');
  const price=num(body.price);const order=num(body.sort_order??0,0,100000);
  if(!allowedUrl(body.image_url))throw Error('A imagem precisa usar HTTPS.');
  if(body.collection){
   if(!groups.has(body.collection))throw Error('Categoria inválida.');
   const id=string(body.id)||crypto.randomUUID();const old=await readDoc(admin,body.collection,id)||{};
   const data={...old,nome:name,name,preco:price,price,ordem:order,descricao:string(body.description).slice(0,3000),imagem:string(body.image_url),ativo:body.active!==false,disponivel:body.available!==false,pausado:body.available===false,atualizadoEm:now};
   await writeDoc(admin,body.collection,id,data);return ok({id});
  }
  const record={name,description:string(body.description).slice(0,3000),price,compare_at_price:body.compare_at_price?num(body.compare_at_price):null,image_url:string(body.image_url)||null,sort_order:order,active:body.active!==false,available:body.available!==false,updated_at:now};
  const r=body.id?await admin.from('products').update(record).eq('id',body.id).select('*').single():await admin.from('products').insert(record).select('*').single();return ok({product:check(r)});
 }
 if(action==='operation_save'){
  const old=check(await admin.from('store_settings').select('value').eq('id','operacao').maybeSingle());
  const value={...(old?.value||{}),tempoEstimadoMin:num(body.preparation_minutes,1,180),mensagemOperacao:string(body.message).slice(0,500),updatedAt:now,atualizadoEm:now};
  check(await admin.from('store_settings').upsert({id:'operacao',value,updated_at:now},{onConflict:'id'}));return ok({operation:{value}});
 }
 if(action==='marketing_list'){
  if(!['coupons','banners'].includes(body.kind))throw Error('Tipo inválido.');return ok({items:check(await admin.from(body.kind).select('*').order('created_at',{ascending:false}).limit(300))});
 }
 if(action==='marketing_save'){
  const kind=body.kind;if(!['coupons','banners'].includes(kind))throw Error('Tipo inválido.');let record:any;
  if(kind==='coupons'){
   if(!string(body.code)||!['fixed','percent','free_delivery'].includes(body.discount_type))throw Error('Cupom inválido.');
   record={code:string(body.code).toUpperCase().slice(0,40),description:string(body.description).slice(0,1000),discount_type:body.discount_type,discount_value:num(body.discount_value,0,body.discount_type==='percent'?100:10000),min_order_value:num(body.min_order_value),active:body.active!==false,ends_at:body.ends_at||null};
  }else{
   if(!allowedUrl(body.image_url)||!allowedUrl(body.target_url)||!body.image_url)throw Error('Informe uma imagem HTTPS válida.');
   record={title:string(body.title).slice(0,160),image_url:body.image_url,target_url:body.target_url||null,action_type:body.target_url?'url':'image',action_target:body.target_url||null,active:body.active!==false,sort_order:num(body.sort_order||0)};
  }
  record.updated_at=now;
  return ok({item:check(body.id?await admin.from(kind).update(record).eq('id',body.id).select('*').single():await admin.from(kind).insert(record).select('*').single())});
 }
 if(action==='manual_order'){
  const draft=body.order||{};const items=Array.isArray(draft.items)?draft.items:[];
  if(items.length<1||items.length>20)throw Error('Adicione de 1 a 20 itens.');
  const clean=items.map((i:any)=>({name:string(i.name).slice(0,240),quantity:num(i.quantity,1,99),unit_price:num(i.unit_price),total_price:Math.round(num(i.quantity,1,99)*num(i.unit_price)*100)/100,modifiers:{linhasMontagem:string(i.details).split('\n').map(s=>s.trim()).filter(Boolean)},notes:string(i.notes).slice(0,1000)}));
  if(clean.some((i:any)=>!i.name))throw Error('Nome do item obrigatório.');
  if(!/^[a-f0-9-]{36}$/.test(string(draft.submission_id)))throw Error('Identificação do pedido inválida.');
  const subtotal=clean.reduce((s:number,i:any)=>s+i.total_price,0);const delivery=draft.fulfillment==='ENTREGA'?num(draft.delivery_fee):0;
  const result=check(await admin.rpc('submit_whatsapp_builder_order',{p_payload:{customer_name:string(draft.customer_name),phone:string(draft.phone).replace(/\D/g,''),fulfillment:draft.fulfillment==='ENTREGA'?'ENTREGA':'RETIRADA',address:draft.address||{},payment_method:draft.payment_method,payment_details:{forma:draft.payment_method},items:clean,subtotal,delivery_fee:delivery,total:Math.round((subtotal+delivery)*100)/100,customer_note:string(draft.customer_note),submission_id:'manual-'+draft.submission_id}}));
  return ok({result});
 }
 if(action==='report'){
  const from=string(body.from),to=string(body.to);if(!from||!to||!Number.isFinite(Date.parse(from))||!Number.isFinite(Date.parse(to))||Date.parse(to)<=Date.parse(from)||Date.parse(to)-Date.parse(from)>32*86400000)throw Error('Selecione um período de até 31 dias.');
  let rows:any[]=[];for(let offset=0;offset<20000;offset+=1000){const page=check(await admin.from('orders').select('id,status,total,subtotal,discount,delivery_fee,payment_method,created_at').gte('created_at',from).lt('created_at',to).order('created_at').range(offset,offset+999));rows.push(...page);if(page.length<1000)break;if(offset===19000)throw Error('Escolha um período menor.');}
  const done=new Set(['CONCLUIDO','CONCLUÍDO','ENTREGUE','FINALIZADO','RETIRADO','COMPLETED','DELIVERED']);const cancelled=new Set(['CANCELADO','CANCELADA','CANCELLED','CANCELED']);
  const completed=rows.filter(r=>done.has(String(r.status).toUpperCase()));const byPayment:any={};completed.forEach(r=>{const key=r.payment_method||'Não informado';byPayment[key]=(byPayment[key]||0)+Number(r.total)});
  return ok({report:{count:rows.length,completed:completed.length,cancelled:rows.filter(r=>cancelled.has(String(r.status).toUpperCase())).length,total:completed.reduce((s,r)=>s+Number(r.total),0),discount:completed.reduce((s,r)=>s+Number(r.discount),0),delivery:completed.reduce((s,r)=>s+Number(r.delivery_fee),0),byPayment}});
 }
 return null;
}
