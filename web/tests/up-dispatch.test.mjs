import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {normalize,bucket,statusLabel} from "../src/domain.js";

test("status de oferta e redistribuição ainda aparecem na fila de prontos",()=>{
 for(const status of ["PRONTO","AGUARDANDO_ENTREGADOR","BUSCANDO_ENTREGADOR","AGUARDANDO_DECISAO_GESTOR"]){
   const row=normalize({id:"order1",status,fulfillment_type:"ENTREGA",raw_payload:{}});
   assert.equal(bucket(row),"ready",status);
 }
 assert.equal(statusLabel("AGUARDANDO_DECISAO_GESTOR"),"Aguardando gestor");
});
test("normalização traz do servidor destinatário da oferta, entregador e mapa habilitado",()=>{
 const row=normalize({id:"order1",fulfillment_type:"DELIVERY",courier_id:"driver1",tracking_enabled:true,
  raw_payload:{up_target_courier_id:"driver2",up_state:"OFFER_PENDING"}});
 assert.equal(row.type,"ENTREGA");
 assert.equal(row.courierId,"driver1");
 assert.equal(row.pendingCourierId,"driver2");
 assert.equal(row.trackingEnabled,true);
 assert.equal(row.upState,"OFFER_PENDING");
});
test("gestor nativo usa ações de oferta; não atribui diretamente nem marca entrega na preparação",()=>{
 const src=readFileSync(new URL("../src/App.jsx",import.meta.url),"utf8");
 assert.match(src,/call\('up_offer_to_courier'/);
 assert.match(src,/call\('up_recall_offer'/);
 assert.match(src,/call\('up_set_tracking'/);
 assert.doesNotMatch(src,/call\('assign_courier'/,"fluxo antigo não deve ser usado pela tela");
 assert.match(src,/const quick=\['ready','delivery'\]\.includes\(b\)&&o\.type==='ENTREGA'\?null/);
 assert.match(src,/const b=bucket\(o\),flow=FLOW\[b\],next=\['ready','delivery'\]\.includes\(b\)&&o\.type==='ENTREGA'\?null/);
 assert.match(src,/Mostrar motoboy no mapa do cliente/);
});
