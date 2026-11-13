-- Conjunto de assertions com erro fatal se alguma condição não corresponder.
-- Banco/usuários descartáveis no GitHub Actions.
\set ON_ERROR_STOP on

DO $test$
BEGIN
 IF has_function_privilege('anon','public.submit_whatsapp_builder_order(jsonb)','EXECUTE') THEN
    RAISE EXCEPTION 'Anon pode executar a função!';
 END IF;
 IF has_function_privilege('authenticated','public.submit_whatsapp_builder_order(jsonb)','EXECUTE') THEN
    RAISE EXCEPTION 'Authenticated pode executar a função!';
 END IF;
 IF NOT has_function_privilege('service_role','public.submit_whatsapp_builder_order(jsonb)','EXECUTE') THEN
    RAISE EXCEPTION 'Service role sem acesso ao RPC.';
 END IF;
END;
$test$;

SET ROLE service_role;

DO $test$
DECLARE
 payload jsonb := jsonb_build_object(
  'submission_id','manual-11111111-1111-4111-8111-111111111111',
  'customer_name','Cliente de validação',
  'phone','67999998888',
  'fulfillment','ENTREGA',
  'payment_method','PIX',
  'address',jsonb_build_object('rua','Rua das Flores','numero','25',
    'bairro','Centro','complemento','','referencia',''),
  'delivery_fee',5.50,
  'customer_note','Sem amendoim',
  'items',jsonb_build_array(
    jsonb_build_object('name','Açaí 500ml','quantity',2,'unit_price',14.50,
     'modifiers',jsonb_build_object('linhasMontagem',jsonb_build_array('Leite ninho','Morango')),'notes','Sem açúcar'),
    jsonb_build_object('name','Água','quantity',1,'unit_price',3.00,
     'modifiers',jsonb_build_object('linhasMontagem',jsonb_build_array()),'notes','')
  ),
  'subtotal',32.00,
  'total',37.50
 );
 first_answer jsonb;
 second_answer jsonb;
 found_count integer;
 stored_total numeric;
 stored_fee numeric;
 stored_subtotal numeric;
 item_count integer;
BEGIN
 first_answer := public.submit_whatsapp_builder_order(payload);
 second_answer := public.submit_whatsapp_builder_order(payload);
 IF first_answer->>'order_id' IS NULL THEN
   RAISE EXCEPTION 'Pedido sem ID após inserção';
 END IF;
 IF first_answer->>'order_id' IS DISTINCT FROM second_answer->>'order_id' THEN
   RAISE EXCEPTION 'Tentativa duplicada criou outro pedido';
 END IF;
 IF coalesce((second_answer->>'already_exists')::boolean, false) IS NOT TRUE THEN
   RAISE EXCEPTION 'Idempotência não identificou repetição';
 END IF;
 SELECT count(*),max(total),max(subtotal),max(delivery_fee)
 INTO found_count,stored_total,stored_subtotal,stored_fee FROM public.orders
 WHERE client_request_id = payload->>'submission_id' AND source='GESTOR20_MANUAL';
 IF found_count <> 1 OR stored_total <> 37.50 OR stored_subtotal <> 32.00
     OR stored_fee <> 5.50 THEN
   RAISE EXCEPTION 'Pedido incorreto: %, %, %, %',
    found_count,stored_total,stored_subtotal,stored_fee;
 END IF;
 SELECT count(*) INTO item_count FROM public.order_items
 WHERE order_id = (first_answer->>'order_id')::uuid;
 IF item_count <> 2 THEN
   RAISE EXCEPTION 'Itens duplicados ou ausentes: %',item_count;
 END IF;

 -- Erro de conexão seguido de mudança no conteúdo não deve reaproveitar
 -- silenciosamente um pedido diferente sob a mesma chave.
 BEGIN
   PERFORM public.submit_whatsapp_builder_order(
     jsonb_set(payload,'{customer_note}','"Alteração após envio"'::jsonb));
   RAISE EXCEPTION 'ERRO: alteração com mesma chave deveria falhar';
 EXCEPTION WHEN OTHERS THEN
   IF SQLERRM NOT LIKE '%idempotency_payload_mismatch%' THEN RAISE; END IF;
 END;

 -- A validação ocorre ANTES de criar os registros.
 BEGIN
   PERFORM public.submit_whatsapp_builder_order(
     jsonb_set(jsonb_set(payload,'{submission_id}',
       '"manual-22222222-2222-4222-8222-222222222222"'::jsonb),
       '{items}','[]'::jsonb));
   RAISE EXCEPTION 'ERRO: carrinho vazio aceito';
 EXCEPTION WHEN OTHERS THEN
   IF SQLERRM NOT LIKE '%invalid_item_count%' THEN RAISE; END IF;
 END;

 BEGIN
   PERFORM public.submit_whatsapp_builder_order(
     jsonb_set(jsonb_set(payload,'{submission_id}',
       '"manual-33333333-3333-4333-8333-333333333333"'::jsonb),
       '{address}','{"rua":"","numero":"","bairro":""}'::jsonb));
   RAISE EXCEPTION 'ERRO: endereço vazio aceito';
 EXCEPTION WHEN OTHERS THEN
   IF SQLERRM NOT LIKE '%incomplete_address%' THEN RAISE; END IF;
 END;

 BEGIN
   PERFORM public.submit_whatsapp_builder_order(
     jsonb_set(payload,'{submission_id}','"44444444-4444-4444-8444-444444444444"'::jsonb));
   RAISE EXCEPTION 'ERRO: submission_id sem prefixo aceito';
 EXCEPTION WHEN OTHERS THEN
   IF SQLERRM NOT LIKE '%invalid_submission_id%' THEN RAISE; END IF;
 END;

 IF (SELECT count(*) FROM public.orders) <> 1 THEN
   RAISE EXCEPTION 'Tentativas inválidas criaram pedidos';
 END IF;
 IF (SELECT count(*) FROM public.customers) <> 1 THEN
   RAISE EXCEPTION 'Tentativas inválidas criaram clientes';
 END IF;
 RAISE NOTICE 'PASSOU: idempotencia, itens, total, endereco, valores, sem duplicados';
END;
$test$;

RESET ROLE;
