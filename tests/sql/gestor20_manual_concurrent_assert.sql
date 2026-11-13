DO $check$
DECLARE
 v_order_count integer;
 v_item_count integer;
 v_customer_count integer;
 v_order_id uuid;
 v_total numeric;
BEGIN
 SELECT count(*), max(id)::text::uuid INTO v_order_count, v_order_id
 FROM public.orders
 WHERE source = 'GESTOR20_MANUAL'
   AND client_request_id = 'manual-55555555-5555-4555-8555-555555555555';
 IF v_order_count <> 1 THEN
   RAISE EXCEPTION 'Falha de concorrencia: % pedidos para a mesma chave', v_order_count;
 END IF;
 SELECT count(*) INTO v_item_count FROM public.order_items WHERE order_id=v_order_id;
 IF v_item_count <> 1 THEN
   RAISE EXCEPTION 'Falha de concorrencia: % itens no pedido', v_item_count;
 END IF;
 SELECT total INTO v_total FROM public.orders WHERE id=v_order_id;
 IF v_total <> 9.50 THEN
   RAISE EXCEPTION 'Valor incorreto: %', v_total;
 END IF;
 SELECT count(*) INTO v_customer_count FROM public.customers
 WHERE metadata->>'origin'='GESTOR20_MANUAL';
 IF v_customer_count <> 2 THEN
   RAISE EXCEPTION 'Falha de concorrencia: clientes extras criados: %', v_customer_count;
 END IF;
 RAISE NOTICE 'APROVADO: 12 chamadas simultaneas; 1 pedido, 1 item, 1 cliente.';
END;
$check$;
