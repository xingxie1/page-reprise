insert into auth.users values
    ('10000000-0000-4000-8000-000000000001', now()),
    ('10000000-0000-4000-8000-000000000002', now()),
    ('10000000-0000-4000-8000-000000000003', null);
set role authenticated;
select set_config('request.jwt.claim.sub','10000000-0000-4000-8000-000000000001',false);
insert into public.study_events(event_id,card_id,payload)
values ('20000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000001',
'{"id":"20000000-0000-4000-8000-000000000001","card":"20000000-0000-4000-8000-000000000001","kind":"CREATE","at":1800000000000}');
insert into public.study_events select * from public.study_events on conflict(owner,event_id) do nothing;
insert into storage.objects values ('reprise-images', auth.uid()::text || '/' || repeat('a',64));
do $$
begin
    if (select count(*) from public.study_events) <> 1 or (select count(*) from storage.objects) <> 1 then
        raise exception 'owner access or idempotency failed';
    end if;
    if has_table_privilege(current_user,'public.study_events','UPDATE') or has_table_privilege(current_user,'public.study_events','DELETE') then
        raise exception 'journal should be append-only';
    end if;
end;
$$;

select set_config('request.jwt.claim.sub','10000000-0000-4000-8000-000000000002',false);
do $$
begin
    if not public.reprise_member() or exists(select from public.study_events) or exists(select from storage.objects) then
        raise exception 'cross-account read isolation failed';
    end if;
    begin
        insert into storage.objects values ('reprise-images','10000000-0000-4000-8000-000000000001/' || repeat('b',64));
        raise exception 'wrong owner image accepted';
    exception when insufficient_privilege then null;
    end;
    begin
        insert into public.study_events(owner,event_id,card_id,payload) values
        ('10000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001',
        '{"id":"30000000-0000-4000-8000-000000000001","card":"30000000-0000-4000-8000-000000000001","kind":"CREATE","at":1800000000000}');
        raise exception 'wrong owner event accepted';
    exception when insufficient_privilege then null;
    end;
end;
$$;

select set_config('request.jwt.claim.sub','10000000-0000-4000-8000-000000000003',false);
do $$
begin
    if public.reprise_member() then raise exception 'uninvited user enrolled'; end if;
    begin
        insert into storage.objects values ('reprise-images',auth.uid()::text || '/' || repeat('c',64));
        raise exception 'uninvited upload accepted';
    exception when insufficient_privilege then null;
    end;
end;
$$;
