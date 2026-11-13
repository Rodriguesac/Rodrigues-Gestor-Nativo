-- Fixture PostgreSQL 16 usada APENAS no GitHub Actions.
-- Campos derivados do schema real; jamais aponta ao Supabase da loja.
DO $roles$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='service_role') THEN
    CREATE ROLE service_role NOLOGIN BYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='anon') THEN
    CREATE ROLE anon NOLOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN
    CREATE ROLE authenticated NOLOGIN;
  END IF;
END;
$roles$;

CREATE TABLE public.customers (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 name text, phone text,
 address jsonb NOT NULL DEFAULT '{}'::jsonb,
 metadata jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE TABLE public.orders (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 customer_id uuid REFERENCES public.customers(id),
 order_code text,
 month_key text,
 status text NOT NULL DEFAULT 'sent',
 fulfillment_type text NOT NULL DEFAULT 'delivery',
 subtotal numeric NOT NULL DEFAULT 0,
 delivery_fee numeric NOT NULL DEFAULT 0,
 discount numeric NOT NULL DEFAULT 0,
 total numeric NOT NULL DEFAULT 0,
 payment_method text,
 payment_status text,
 payment_details jsonb NOT NULL DEFAULT '{}'::jsonb,
 delivery_address jsonb NOT NULL DEFAULT '{}'::jsonb,
 customer_note text,
 source text NOT NULL DEFAULT 'firebase',
 client_request_id text,
 raw_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE public.order_items (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 order_id uuid NOT NULL REFERENCES public.orders(id),
 name text NOT NULL,
 quantity numeric NOT NULL DEFAULT 1,
 unit_price numeric NOT NULL DEFAULT 0,
 total_price numeric NOT NULL DEFAULT 0,
 modifiers jsonb NOT NULL DEFAULT '[]'::jsonb,
 notes text,
 raw_payload jsonb NOT NULL DEFAULT '{}'::jsonb
);

ALTER TABLE public.orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.order_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.customers ENABLE ROW LEVEL SECURITY;
GRANT USAGE ON SCHEMA public TO service_role, anon, authenticated;
GRANT SELECT, INSERT, UPDATE ON public.customers, public.orders, public.order_items TO service_role;
