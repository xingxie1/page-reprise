-- Run once in the project's SQL Editor after migrations, with the two Vault entries described in docs/cloud.md.
begin;
create extension if not exists pg_cron;
create extension if not exists http with schema extensions;

create or replace function reprise_private.clean_images() returns void
language plpgsql security definer set search_path = '' as $$
declare
    account uuid;
    paths text[];
    endpoint text;
    credential text;
    result integer;
begin
    if not exists(select from reprise_private.discarded_images) then return; end if;
    select decrypted_secret into strict endpoint from vault.decrypted_secrets where name = 'reprise_project_url';
    select decrypted_secret into strict credential from vault.decrypted_secrets where name = 'reprise_storage_admin';
    for account in select distinct owner from reprise_private.discarded_images order by owner limit 20 loop
        perform pg_advisory_xact_lock(hashtextextended(account::text, 42));
        delete from reprise_private.discarded_images where owner = account and reprise_private.has_image(account, hash);
        select array_agg(account::text || '/' || q.hash) into paths from (
            select hash from reprise_private.discarded_images where owner = account order by hash limit 100
        ) q;
        if paths is null then continue; end if;
        select status into result from extensions.http((
            'DELETE', rtrim(endpoint,'/') || '/storage/v1/object/reprise-images',
            array[extensions.http_header('apikey',credential), extensions.http_header('Authorization','Bearer ' || credential)],
            'application/json', jsonb_build_object('prefixes',paths)::text
        )::extensions.http_request);
        if result between 200 and 299 then
            delete from reprise_private.discarded_images where owner = account and (account::text || '/' || hash) = any(paths);
        else
            raise warning 'Reprise storage cleanup returned HTTP %, will retry', result;
        end if;
    end loop;
end $$;
revoke all on function reprise_private.clean_images() from public, anon, authenticated;

select cron.schedule('reprise-trash-expiry','* * * * *',
    $job$select reprise_private.sweep(owner) from (select distinct owner from public.study_events
         where payload->>'kind' = 'TRASH' and (payload->>'at')::bigint <= extract(epoch from now()) * 1000 - 604800000 order by owner) owners$job$);
select cron.schedule('reprise-trash-images','* * * * *', 'select reprise_private.clean_images()');
commit;
