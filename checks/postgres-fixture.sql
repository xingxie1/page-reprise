create role anon;
create role authenticated;
create schema auth;
create table auth.users(id uuid primary key, invited_at timestamptz);
create function auth.uid() returns uuid language sql stable as $$
    select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid;
$$;
create schema storage;
create table storage.buckets(id text primary key, name text, public boolean, file_size_limit bigint, allowed_mime_types text[]);
create table storage.objects(bucket_id text, name text);
alter table storage.objects enable row level security;
grant usage on schema auth, storage to anon, authenticated;
grant select, insert on storage.objects to authenticated;
