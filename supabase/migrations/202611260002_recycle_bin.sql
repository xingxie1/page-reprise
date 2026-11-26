begin;

-- Keep v1 rows and expand the existing event kind constraint in place.
do $$
declare previous name;
begin
    select conname into strict previous from pg_constraint
      where conrelid = 'public.study_events'::regclass and contype = 'c'
        and pg_get_constraintdef(oid) like '%REVIEW%';
    execute format('alter table public.study_events drop constraint %I', previous);
end $$;

alter table public.study_events add constraint reprise_event_kind check (
    payload ?& array['id','card','kind','at'] and payload->>'id' = event_id::text
    and payload->>'card' = card_id::text
    and payload->>'kind' in ('CREATE','REVIEW','UNDO','MOVE','EDIT','TRASH','RESTORE','PURGE')
    and (payload->>'kind' not in ('TRASH','RESTORE','PURGE') or (
        jsonb_typeof(payload->'at') = 'number' and (payload->>'at') ~ '^[0-9]{12,13}$'
        and not (payload ? 'body')))
    and (payload->>'kind' <> 'TRASH' or (payload ? 'base' and (payload->>'base') ~ '^[0-9a-f]{64}$'))
    and (payload->>'kind' <> 'RESTORE' or (payload ? 'refs' and jsonb_typeof(payload->'refs') = 'array'))
    and (payload->>'kind' <> 'PURGE' or event_id = card_id)
);
create index study_card_events on public.study_events(owner, card_id);
create index study_deleted_events on public.study_events(owner, card_id) where payload->>'kind' = 'TRASH';

create table reprise_private.discarded_images (
    owner uuid not null references auth.users(id) on delete cascade,
    hash text not null check (hash ~ '^[0-9a-f]{64}$'),
    primary key(owner, hash)
);

-- Serialize journal writes with compaction. Old backups and late uploads cannot revive a purged ID.
create function reprise_private.guard_journal() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    if auth.uid() is not null and new.owner <> auth.uid() then
        raise exception 'wrong owner' using errcode = '42501';
    end if;
    perform pg_advisory_xact_lock(hashtextextended(new.owner::text, 42));
    if exists (select from public.study_events where owner = new.owner and event_id = new.card_id
                and payload->>'kind' = 'PURGE') then return null; end if;
    return new;
end $$;
revoke all on function reprise_private.guard_journal() from public, anon, authenticated;
create trigger reprise_journal_guard before insert on public.study_events
for each row execute function reprise_private.guard_journal();
alter policy own_event_append on public.study_events with check
    (owner = (select auth.uid()) and (select public.reprise_member()) and payload->>'kind' <> 'PURGE');

create function reprise_private.has_image(account uuid, hash text) returns boolean
language sql stable set search_path = '' as $$
    select exists(select from public.study_events e where e.owner = account and
        ((e.payload #> '{body,pictures}') ? hash or (e.payload #> '{body,answers}') ? hash));
$$;
revoke all on function reprise_private.has_image(uuid,text) from public, anon, authenticated;
create function public.reprise_owns_image(hash text) returns boolean
language sql stable security definer set search_path = '' as $$
    select public.reprise_member() and reprise_private.has_image(auth.uid(), hash);
$$;
revoke all on function public.reprise_owns_image(text) from public, anon;
grant execute on function public.reprise_owns_image(text) to authenticated;
alter policy reprise_images_append on storage.objects with check (
    bucket_id = 'reprise-images' and split_part(name,'/',1) = (select auth.uid())::text and
    name ~ '^[0-9a-f-]{36}/[0-9a-f]{64}$' and public.reprise_owns_image(split_part(name,'/',2))
);

create function reprise_private.sweep(account uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare
    item record;
    now_ms bigint := floor(extract(epoch from clock_timestamp()) * 1000)::bigint;
begin
    perform pg_advisory_xact_lock(hashtextextended(account::text, 42));
    for item in
        select d.card_id, min((d.payload->>'at')::bigint + 604800000) deadline
        from public.study_events d
        where d.owner = account and d.payload->>'kind' = 'TRASH'
          and (d.payload->>'at')::bigint + 604800000 <= now_ms
          and exists (select from public.study_events c where c.owner = account and c.event_id = d.card_id and c.payload->>'kind' = 'CREATE')
          and d.payload->>'base' = (select encode(sha256(convert_to(coalesce(string_agg(v.event_id::text, ',' order by v.event_id), ''), 'UTF8')), 'hex')
              from public.study_events v where v.owner = account and v.card_id = d.card_id and v.payload->>'kind' in ('REVIEW','UNDO'))
          and not exists (select from public.study_events r where r.owner = account and r.card_id = d.card_id
              and r.payload->>'kind' = 'RESTORE' and (r.payload->'refs') ? d.event_id::text
              and (r.payload->>'at')::bigint >= (d.payload->>'at')::bigint
              and (r.payload->>'at')::bigint < (d.payload->>'at')::bigint + 604800000)
        group by d.card_id
    loop
        insert into reprise_private.discarded_images(owner,hash)
        select distinct account, h from public.study_events e cross join lateral
            jsonb_array_elements_text(jsonb_path_query_array(e.payload,'$.body.pictures[*]') ||
                jsonb_path_query_array(e.payload,'$.body.answers[*]')) as images(h)
        where e.owner = account and e.card_id = item.card_id and h ~ '^[0-9a-f]{64}$'
        on conflict do nothing;
        delete from public.study_events where owner = account and card_id = item.card_id;
        insert into public.study_events(owner,event_id,card_id,payload)
            values (account,item.card_id,item.card_id,jsonb_build_object('kind','PURGE','card',item.card_id,
                'id',item.card_id,'at',item.deadline,'value','','base','','refs','[]'::jsonb));
    end loop;
end $$;
revoke all on function reprise_private.sweep(uuid) from public, anon, authenticated;

create function public.reprise_collect_trash() returns void
language plpgsql security definer set search_path = '' as $$
begin
    if not public.reprise_member() then raise exception 'membership required' using errcode = '42501'; end if;
    perform reprise_private.sweep(auth.uid());
end $$;
revoke all on function public.reprise_collect_trash() from public, anon;
grant execute on function public.reprise_collect_trash() to authenticated;
commit;
