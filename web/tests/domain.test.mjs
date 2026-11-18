import test from 'node:test';import assert from 'node:assert/strict';
import {normalize,bucket,sortDetails,acceptanceRemaining,duplicateIds,receiptHtml} from '../src/domain.js';
test('normalizes raw items when API enrichment is empty',()=>{const o=normalize({id:'1',items:[],raw_payload:{itens:[{nome:'Açaí',quantidade:2,linhasMontagem:['Uva','Leite em pó','Leite condensado','Calda']}],tipoPedido:'RETIRADA'},status:'PENDENTE'});assert.equal(o.items.length,1);assert.deepEqual(o.items[0].details,['Calda','Leite condensado','Leite em pó','Uva']);assert.equal(o.type,'RETIRADA')});
test('terminal and pickup statuses leave operational queue',()=>{for(const status of ['FINALIZADO','RETIRADO','CANCELADA','CONCLUÍDO','CANCELED'])assert.equal(bucket(normalize({id:status,status})),'done')});
test('acceptance expires at five minutes without altering status',()=>{const o={created:'2026-09-28T12:00:00Z',status:'PENDENTE'};assert.equal(acceptanceRemaining(o,Date.parse('2026-09-28T12:04:59Z')),1);assert.equal(acceptanceRemaining(o,Date.parse('2026-09-28T12:05:01Z')),0);assert.equal(o.status,'PENDENTE')});
test('duplicates require phone, items, value and five minute window',()=>{const a={id:'a',phone:'67999999999',total:13,created:'2026-09-28T12:00:00Z',items:[{name:'Açaí',qty:1,details:[]}]};assert.deepEqual([...duplicateIds([a,{...a,id:'b',created:'2026-09-28T12:03:00Z'}])],['a','b']);assert.equal(duplicateIds([a,{...a,id:'b',total:14}]).size,0)});
test('receipt escapes customer-controlled fields',()=>{const o=normalize({id:'x',customer:{name:'<img onerror=alert(1)>'},items:[{name:'<script>x</script>'}]});const html=receiptHtml(o);assert.ok(!html.includes('<script>x</script>'));assert.ok(html.includes('&lt;img'));});

test('exibe montagem salva no payload mesmo quando a API retorna itens resumidos',()=>{
 const o=normalize({id:'441014',items:[{name:'Copo 500ml',quantity:2,total_price:46}],raw_payload:{itens:[{nome:'Copo 500ml',quantidade:2,detalhes:{baseNome:'Açaí Origem',cobertura_detalhes:[{nome:'Chocolate'}],acompanhamentos_detalhes:[{nome:'Leite condensado'},{nome:'Morango'}],adicionais_detalhes:[{nome:'Nutella',quantidade:2}]}}]}});
 assert.deepEqual(o.items[0].details,['Cobertura: Chocolate','Leite condensado','2× Nutella','Açaí Origem','Morango']);
 const html=receiptHtml(o);for(const x of o.items[0].details)assert.ok(html.includes(x),'Comanda deve incluir '+x);
});
test('não mistura complementos de produtos de nomes diferentes',()=>{
 const o=normalize({id:'2',items:[{name:'Copo 700ml',quantity:1}],raw_payload:{itens:[{nome:'Copo 500ml',detalhes:{adicionais:['Nutella']}}]}});
 assert.deepEqual(o.items[0].details,[]);
});
test('montagem presente em array de detalhes aparece na tela e impressão',()=>{
 const o=normalize({id:'3',items:[{name:'Açaí',detalhes:[{nome:'Ovomaltine'},{nome:'Banana'}]}]});
 assert.deepEqual(o.items[0].details,['Ovomaltine','Banana']);assert.ok(receiptHtml(o).includes('Ovomaltine'));
});

test('comanda térmica usa caixa alta visual e fontes reforçadas',()=>{
 const o=normalize({id:'l',order_code:'123',customer:{name:'Ana'},items:[{name:'Copo 500ml',quantity:1,detalhes:{adicionais:['Ovomaltine','Banana']}}]});
 const html=receiptHtml(o);
 assert.match(html,/text-transform:uppercase/);
 assert.match(html,/font-size:16px;font-weight:900/);
 assert.match(html,/font-size:14px;line-height:1\.45;font-weight:800/);
 assert.ok(html.includes('Ovomaltine'));
 assert.ok(html.includes('Banana'));
});
