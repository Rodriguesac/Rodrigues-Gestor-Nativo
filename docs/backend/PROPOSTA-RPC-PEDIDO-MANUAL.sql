-- PROPOSTA DE BACKEND — não executar em produção sem revisão, backup e testes.
-- A gestor-app-api chama admin.rpc('submit_whatsapp_builder_order'), mas a função
-- ainda não existe no Supabase rodriguesacaiecia2 (verificado em 13/11/2026).
-- O Edge Function já prefixa a chave idempotente com 'manual-'; não repetir prefixo aqui.
-- Fonte: schema real de public.orders, order_items, customers. Este arquivo
-- NÃO é uma migration aplicada; é um candidato que deve passar pelo ciclo
-- supabase migration new / revisão / teste em ambiente controlado.

BEGIN;

CREATE SEQUENCE IF NOT EXISTS public.gestor20_manual_code_seq START WITH 100000;

-- A origem manual é separada das outras origens para não prejudicar checkout.
CREATE UNIQUE INDEX IF NOT EXISTS orders_gestor20_manual_request_uidx
ON public.orders (client_request_id)
WHERE source = 'GESTOR20_MANUAL' AND client_request_id IS NOT NULL;

-- SECURITY INVOKER: somente o usuário service_role da Edge Function executa.
CREATE OR REPLACE FUNCTION public.submit_whatsapp_builder_order(p_payload jsonb)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $SQL$
DECLARE
    v_id uuid;
    v_customer_id uuid;
    v_code text;
    v_request text := coalesce(trim(p_payload->>'submission_id'), '');
    v_existing_request jsonb;
    v_name text := left(trim(coalesce(p_payload->>'customer_name', '')), 140);
    v_phone text := regexp_replace(coalesce(p_payload->>'phone', ''), '[^0-9]', '', 'g');
    v_fulfillment text := upper(trim(coalesce(p_payload->>'fulfillment', '')));
    v_payment text := upper(trim(coalesce(p_payload->>'payment_method', '')));
    v_address jsonb := coalesce(p_payload->'address', '{}'::jsonb);
    v_items jsonb := p_payload->'items';
    v_note text := left(coalesce(p_payload->>'customer_note',''), 2000);
    v_item jsonb;
    v_item_name text;
    v_qty int;
    v_unit numeric;
    v_subtotal numeric := 0;
    v_fee numeric := 0;
    v_total numeric;
    v_now timestamptz := now();
    v_raw_items jsonb := '[]'::jsonb;
BEGIN
    IF current_user <> 'service_role' THEN
        RAISE EXCEPTION 'restricted_to_service_role' USING ERRCODE = '42501';
    END IF;
    IF p_payload IS NULL OR jsonb_typeof(p_payload) <> 'object' THEN
        RAISE EXCEPTION 'invalid_payload';
    END IF;
    IF v_request !~
       '^manual-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
        RAISE EXCEPTION 'invalid_submission_id';
    END IF;

    -- Protege reenvio após timeout: a mesma chave nunca cria outro pedido.
    PERFORM pg_advisory_xact_lock(hashtextextended(v_request, 173));
    SELECT o.id, o.order_code, o.raw_payload->'manual_request'
    INTO v_id, v_code, v_existing_request
    FROM public.orders o
    WHERE o.source = 'GESTOR20_MANUAL' AND o.client_request_id = v_request
    LIMIT 1;
    IF FOUND THEN
        IF v_existing_request IS DISTINCT FROM p_payload THEN
            RAISE EXCEPTION 'idempotency_payload_mismatch';
        END IF;
        RETURN jsonb_build_object('order_id', v_id, 'order_code', v_code,
            'already_exists', true);
    END IF;

    IF char_length(v_name) < 2 OR char_length(v_phone) NOT IN (10,11) THEN
        RAISE EXCEPTION 'invalid_customer';
    END IF;
    IF v_fulfillment NOT IN ('ENTREGA','RETIRADA') THEN
        RAISE EXCEPTION 'invalid_fulfillment';
    END IF;
    IF v_payment NOT IN ('PIX','DINHEIRO','CREDITO','DEBITO') THEN
        RAISE EXCEPTION 'invalid_payment';
    END IF;
    IF v_items IS NULL OR jsonb_typeof(v_items) IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'invalid_items';
    END IF;
    IF jsonb_array_length(v_items) NOT BETWEEN 1 AND 20 THEN
        RAISE EXCEPTION 'invalid_item_count';
    END IF;
    IF v_address IS NULL OR jsonb_typeof(v_address) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'invalid_address';
    END IF;
    IF v_fulfillment = 'ENTREGA' AND
       (nullif(trim(coalesce(v_address->>'rua','')), '') IS NULL OR
        nullif(trim(coalesce(v_address->>'numero','')), '') IS NULL OR
        nullif(trim(coalesce(v_address->>'bairro','')), '') IS NULL) THEN
        RAISE EXCEPTION 'incomplete_address';
    END IF;
    IF coalesce(p_payload->>'delivery_fee','0') !~ '^[0-9]+([.][0-9]{1,2})?$' THEN
        RAISE EXCEPTION 'invalid_delivery_fee';
    END IF;
    IF v_fulfillment = 'ENTREGA' THEN
        v_fee := (p_payload->>'delivery_fee')::numeric;
        IF v_fee < 0 OR v_fee > 10000 THEN
            RAISE EXCEPTION 'invalid_delivery_fee';
        END IF;
    END IF;

    FOR v_item IN SELECT value FROM jsonb_array_elements(v_items)
    LOOP
        IF jsonb_typeof(v_item) <> 'object' THEN
            RAISE EXCEPTION 'invalid_item';
        END IF;
        v_item_name := trim(coalesce(v_item->>'name',''));
        IF char_length(v_item_name) NOT BETWEEN 1 AND 240 THEN
            RAISE EXCEPTION 'invalid_item_name';
        END IF;
        IF coalesce(v_item->>'quantity', '') !~ '^[0-9]{1,2}$' THEN
            RAISE EXCEPTION 'invalid_item_quantity';
        END IF;
        IF coalesce(v_item->>'unit_price','') !~ '^[0-9]+([.][0-9]{1,2})?$' THEN
            RAISE EXCEPTION 'invalid_item_price';
        END IF;
        v_qty := (v_item->>'quantity')::int;
        v_unit := (v_item->>'unit_price')::numeric;
        IF v_qty NOT BETWEEN 1 AND 99 OR v_unit NOT BETWEEN 0 AND 10000 THEN
            RAISE EXCEPTION 'invalid_item_amount';
        END IF;
        v_subtotal := v_subtotal + round(v_qty * v_unit,2);
        IF v_subtotal > 1000000 THEN RAISE EXCEPTION 'order_total_too_large'; END IF;
        v_raw_items := v_raw_items || jsonb_build_array(jsonb_build_object(
            'nome',v_item_name,'quantidade',v_qty,
            'total',round(v_qty*v_unit,2),
            'linhasMontagem',coalesce(v_item->'modifiers'->'linhasMontagem','[]'::jsonb),
            'observacao',left(coalesce(v_item->>'notes',''),1000)
        ));
    END LOOP;

    v_total := round(v_subtotal + v_fee, 2);
    IF v_total > 1000000 THEN RAISE EXCEPTION 'order_total_too_large'; END IF;
    -- Não vincular apenas pelo telefone a uma conta Firebase existente.
    INSERT INTO public.customers(name,phone,address,metadata)
    VALUES(v_name,v_phone,v_address,jsonb_build_object('origin','GESTOR20_MANUAL'))
    RETURNING id INTO v_customer_id;

    v_code := '9' || nextval('public.gestor20_manual_code_seq'::regclass)::text;
    v_id := gen_random_uuid();

    INSERT INTO public.orders(
        id, customer_id, order_code, month_key, status, fulfillment_type,
        subtotal, delivery_fee, discount, total, payment_method, payment_status,
        payment_details, delivery_address, customer_note, source,
        client_request_id, raw_payload, created_at, updated_at
    ) VALUES(
        v_id, v_customer_id, v_code, to_char(v_now at time zone 'America/Campo_Grande','YYYY-MM'),
        'PENDENTE',v_fulfillment,
        v_subtotal,v_fee,0,v_total,v_payment,'PENDENTE',
        jsonb_build_object('forma',v_payment,'status','PENDENTE'),v_address,
        v_note,'GESTOR20_MANUAL',v_request,
        jsonb_build_object(
            'status','PENDENTE','numeroPedido',v_code,
            'tipoPedido',v_fulfillment,
            'cliente',jsonb_build_object('nome',v_name,'telefone',v_phone),
            'pagamento',jsonb_build_object('forma',v_payment,'status','PENDENTE'),
            'endereco',v_address,'itens',v_raw_items,'observacao',v_note,
            'total',v_total,'createdAt',v_now,'submission_id',v_request,
            'manual_request',p_payload
        ),
        v_now,v_now
    );

    FOR v_item IN SELECT value FROM jsonb_array_elements(v_items)
    LOOP
        INSERT INTO public.order_items(
            order_id,name,quantity,unit_price,total_price,modifiers,notes,raw_payload
        ) VALUES(
            v_id,left(trim(v_item->>'name'),240),
            (v_item->>'quantity')::numeric,
            (v_item->>'unit_price')::numeric,
            round((v_item->>'quantity')::numeric * (v_item->>'unit_price')::numeric,2),
            coalesce(v_item->'modifiers','{}'::jsonb),
            left(coalesce(v_item->>'notes',''),1000),v_item
        );
    END LOOP;

    -- Eventos de criação e sinal realtime já são gerados pelos triggers.
    RETURN jsonb_build_object('order_id',v_id,'order_code',v_code,
        'status','PENDENTE','total',v_total,'already_exists',false);
END;
$SQL$;

-- Evita que o RPC possa ser invocado diretamente por clientes públicos.
REVOKE ALL ON FUNCTION public.submit_whatsapp_builder_order(jsonb)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.submit_whatsapp_builder_order(jsonb)
    TO service_role;
REVOKE ALL ON SEQUENCE public.gestor20_manual_code_seq FROM PUBLIC, anon, authenticated;
GRANT USAGE ON SEQUENCE public.gestor20_manual_code_seq TO service_role;

COMMIT;
