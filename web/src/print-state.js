/* Confirmed print queue. Never repeat an unconfirmed receipt automatically. */
const PREFIX="gestor-print-v2-",LEGACY="gestor-print-";
const active=new Map();
const store=()=>{try{return globalThis.localStorage}catch{return null}};
const storageKey=(id,mode)=>PREFIX+mode+"-"+id;
const save=(key,state,message="",storage=store())=>{
 try{storage?.setItem(key,JSON.stringify({state,at:Date.now(),message:String(message).slice(0,280)}));return true}
 catch{return false}
};
const parse=raw=>{try{return raw?JSON.parse(raw):null}catch{return null}};
export function automaticPrintOnce(order,mode,bridge,receipt,storage=store()){
 const id=String(order?.id||"").trim(),prefs=bridge.printerSettings?.();
 if(!id||!["accept","arrival"].includes(mode)||!prefs?.configured||prefs.mode!==mode||!storage)return false;
 const key=storageKey(id,mode);
 try{if(storage.getItem(LEGACY+mode+"-"+id)||storage.getItem(key))return false}
 catch{return false}
 if(!save(key,"pending","",storage))return false;
 active.set(id,{key,storage});
 try{if(bridge.printDirect(receipt(order),id))return true}
 catch(e){save(key,"failed",e.message,storage);active.delete(id);return false}
 save(key,"failed","Não foi possível enviar. Reimprima manualmente.",storage);
 active.delete(id);return false;
}
export function confirmPrintResult(detail,storage=store()){
 const id=String(detail?.id||"").trim();
 if(!id)return false;
 const seen=active.get(id);
 let key=seen?.key;
 if(!key){
  for(const mode of ["arrival","accept"]){
   const candidate=storageKey(id,mode);
   let value=null;
   try{value=parse(storage?.getItem(candidate))}catch{}
   if(value?.state==="pending"){key=candidate;break}
  }
 }
 if(!key)return false;
 save(key,detail.ok===true?"confirmed":"failed",detail.message||"",seen?.storage||storage);
 active.delete(id);
 return true;
}
export function printState(id,mode,storage=store()){
 try{return parse(storage?.getItem(storageKey(id,mode)))?.state||""}catch{return ""}
}
if(typeof globalThis.addEventListener==="function")
 globalThis.addEventListener("gestor:print-result",event=>confirmPrintResult(event.detail));
