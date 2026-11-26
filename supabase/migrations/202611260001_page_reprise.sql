begin;

create schema if not exists reprise_private;
revoke all on schema reprise_private from public, anon, authenticated;

create table reprise_private.members (
    user_id uuid primary key references auth.users(id) on delete cascade
);

create function reprise_private.enroll_invited() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    if new.invited_at is not null then
        insert into reprise_private.members(user_id) values (new.id) on conflict do nothing;
    end if;
    return new;
end;
$$;
revoke all on function reprise_private.enroll_invited() from public, anon, authenticated;
create trigger page_reprise_invited after insert or update of invited_at on auth.users
for each row execute function reprise_private.enroll_invited();
insert into reprise_private.members(user_id) select id from auth.users where invited_at is not null
on conflict do nothing;

create function public.reprise_member() returns boolean
language sql stable security definer set search_path = '' as $$
    select exists (select 1 from reprise_private.members where user_id = (select auth.uid()));
$$;
revoke all on function public.reprise_member() from public, anon;
grant execute on function public.reprise_member() to authenticated;

create table public.study_events (
    owner uuid not null default auth.uid() references auth.users(id) on delete cascade,
    event_id uuid not null,
    card_id uuid not null,
    payload jsonb not null,
    primary key(owner, event_id),
    check (jsonb_typeof(payload) = 'object' and octet_length(payload::text) <= 180000),
    check (payload ?& array['id','card','kind','at'] and payload->>'id' = event_id::text
        and payload->>'card' = card_id::text and payload->>'kind' in ('CREATE','REVIEW','UNDO','MOVE','EDIT'))
);
alter table public.study_events enable row level security;
revoke all on public.study_events from public, anon, authenticated;
grant select, insert on public.study_events to authenticated;
create policy own_event_read on public.study_events for select to authenticated
using (owner = (select auth.uid()) and (select public.reprise_member()));
create policy own_event_append on public.study_events for insert to authenticated
with check (owner = (select auth.uid()) and (select public.reprise_member()));

insert into storage.buckets(id, name, public, file_size_limit, allowed_mime_types)
values ('reprise-images','reprise-images',false,16777216,array['image/jpeg'])
on conflict(id) do update set public = false, file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

create policy reprise_images_read on storage.objects for select to authenticated
using (bucket_id = 'reprise-images' and split_part(name,'/',1) = (select auth.uid())::text
    and (select public.reprise_member()));
create policy reprise_images_append on storage.objects for insert to authenticated
with check (bucket_id = 'reprise-images' and split_part(name,'/',1) = (select auth.uid())::text
    and name ~ '^[0-9a-f-]{36}/[0-9a-f]{64}$' and (select public.reprise_member()));
commit;
