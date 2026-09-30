const ENDPOINT='https://fdqqwdplprzpqufpgdrm.supabase.co/functions/v1/gestor-app-api';
export async function request(action,data={},token=''){
 const controller=new AbortController();const timeout=setTimeout(()=>controller.abort(),20000);
 try{const r=await fetch(ENDPOINT,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action,...data,session_token:token}),signal:controller.signal,cache:'no-store'});const result=await r.json().catch(()=>({}));if(!r.ok||result.ok===false||result.error)throw Object.assign(new Error(result.message||result.error||'Não foi possível concluir.'),{code:r.status});return result;}
 catch(e){if(e.name==='AbortError')throw new Error('A conexão demorou. Atualize para conferir antes de tentar novamente.');throw e}finally{clearTimeout(timeout)}
}
