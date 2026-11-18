import test from 'node:test';import assert from 'node:assert/strict';
import {normalize,bucket,sortDetails,acceptanceRemaining,duplicateIds,receiptHtml} from '../src/domain.js';
test('normalizes raw items when API enrichment is empty',()=>{const o=normalize({id:'1',items:[],raw_payload:{itens:[{nome:'Açaí',quantidade:2,linhasMontagem:['Uva','Leite em pó','Leite condensado','Calda']}],tipoPedido:'RETIRADA'},status:'PENDENTE'});assert.equal(o.items.length,1);assert.deepEqual(o.items[0].details,['Calda','Leite condensado','Leite em pó','Uva']);assert.equal(o.type,'RETIRADA')});
test('terminal and pickup statuses leave operational queue',()=>{for(const status of ['FINALIZADO','RETIRADO','CANCELADA','CONCLUÍDO','CANCELED'])assert.equal(bucket(normalize({id:status,status})),'done')});
test('acceptance expires at five minutes without altering status',()=>{const o={created:'2026-09-28T12:00:00Z',status:'PENDENTE'};assert.equal(acceptanceRemaining(o,Date.parse('2026-09-28T12:04:59Z')),1);assert.equal(acceptanceRemaining(o,Date.parse('2026-09-28T12:05:01Z')),0);assert.equal(o.status,'PENDENTE')});
test('duplicates require phone, items, value and five minute window',()=>{const a={id:'a',phone:'67999999999',total:13,created:'2026-09-28T12:00:00Z',items:[{name:'Açaí',qty:1,details:[]}]};assert.deepEqual([...duplicateIds([a,{...a,id:'b',created:'2026-09-28T12:03:00Z'}])],['a','b']);assert.equal(duplicateIds([a,{...a,id:'b',total:14}]).size,0)});
test('receipt escapes customer-controlled fields',()=>{const o=normalize({id:'x',customer:{name:'<img onerror=alert(1)>'},items:[{name:'<script>x</script>'}]});const html=receiptHtml(o);assert.ok(!html.includes('<script>x</script>'));assert.ok(html.includes('&lt;img'));});

test('açaí origem already named in product is not repeated as topping',()=>{
 const o=normalize({id:'test',raw_payload:{itens:[{nome:'Copo 300ml - Açaí Origem',detalhes:{linhasMontagem:['Leite condensado','Açaí Origem','Ovomaltine','Abacaxi']}}]}})
 assert.deepEqual(o.items[0].details,['Leite condensado','Ovomaltine','Abacaxi'])
 assert.equal((receiptHtml(o).match(/AÇAÍ ORIGEM/g)||[]).length,1)
})
test('açaí origem remains visible when it is the only base identification',()=>{
 const o=normalize({id:'test',raw_payload:{itens:[{nome:'Copo 300ml',detalhes:{linhasMontagem:['Açaí Origem','Banana']}}]}})
 assert.ok(o.items[0].details.includes('Açaí Origem'))
})

test('80mm receipt highlights delivery address, complete items and totals',()=>{
 const o=normalize({id:'523',raw_payload:{numeroPedido:'523',tipoPedido:'ENTREGA',endereco:{rua:'Rua Simões',numero:'88',bairro:'Estrela Dalva'},itens:[{nome:'Copo 300ml - Açaí Origem',preco:13,detalhes:{linhasMontagem:['Açaí Origem','Leite condensado','Ovomaltine','Abacaxi']}}],total:13,pagamento:{forma:'debito'}}})
 const html=receiptHtml(o)
 assert.ok(html.includes('ENDEREÇO DE ENTREGA'))
 assert.ok(html.includes('RUA SIMÕES, 88, ESTRELA DALVA'))
 assert.ok(html.includes('LEITE CONDENSADO'))
 assert.equal((html.match(/AÇAÍ ORIGEM/g)||[]).length,1)
 assert.ok(html.includes('@page{size:80mm'))
 assert.ok(html.includes('TOTAL'))
})
