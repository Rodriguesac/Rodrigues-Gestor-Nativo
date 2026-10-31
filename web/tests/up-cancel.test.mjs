import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {statusLabel,normalize,bucket} from "../src/domain.js";
const app=readFileSync(new URL("../src/App.jsx",import.meta.url),"utf8");
test("Rodrigues Gestor cancela via API UP quando corrida já foi oferecida ou aceita",()=>{
 assert.match(app,/status==='CANCELADO'&&o\.type==='ENTREGA'&&\(o\.courierId\|\|o\.pendingCourierId\)/);
 assert.match(app,/call\('up_cancel_delivery',\{order_id:o\.id,reason:reason\.trim\(\)\}\)/);
 assert.match(app,/result\.requires_support/);
 assert.match(app,/cancellationInReview/);
 assert.match(app,/!cancellationInReview&&<button className="detail-more-action"/);
});
test("revisão de cancelamento não vira pedido entregue ou cancelado confirmado",()=>{
 const order=normalize({
  id:"test",fulfillment_type:"ENTREGA",status:"CANCELAMENTO_EM_ANALISE",
  courier_id:"driver",tracking_enabled:false,
  raw_payload:{up_state:"CANCELLATION_REVIEW"}
 });
 assert.equal(order.status,"CANCELAMENTO_EM_ANALISE");
 assert.equal(statusLabel(order.status),"Cancelamento em análise");
 assert.notEqual(bucket(order),"done");
 assert.equal(order.trackingEnabled,false);
});
