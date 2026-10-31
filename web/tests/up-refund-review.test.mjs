import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
const app=fs.readFileSync(new URL("../src/App.jsx",import.meta.url),"utf8");
test("apenas dono pode informar estorno já feito na Conta Web sem liberar pedido",()=>{
 assert.match(app,/owner&&paymentIsInfinitepay&&paymentIsPaid/);
 assert.match(app,/call\('up_refund_report',\{/);
 assert.match(app,/amount_cents:Math\.round\(Number\(o\.total\|\|0\)\*100\)/);
 assert.match(app,/call\('up_refund_status',\{order_id:o\.id\}\)/);
 assert.match(app,/outra credencial deverá conferir na Conta Web/);
 assert.match(app,/Pedido permanece em análise/);
});
test("formulário explicita que a API não devolve dinheiro",()=>{
 assert.match(app,/Estornos são feitos no App ou na Conta Web InfinitePay/);
 assert.match(app,/Informar estorno realizado/);
 assert.match(app,/Não.*automático|não.*automático|uma segunda credencial deve conferir/);
});

test("pedido InfinitePay com checkout pendente não oferece devolver sem conciliação",()=>{
 assert.match(app,/const paymentIsInfinitepay=String\(o\.payment\|\|''\)\.toUpperCase\(\)==='INFINITEPAY'/);
 assert.match(app,/const financialReview=paymentIsInfinitepay\|\|paymentIsPaid/);
 assert.match(app,/owner&&!financialReview&&<form/);
 assert.match(app,/paymentIsInfinitepay&&!paymentIsPaid&&<p/);
});
