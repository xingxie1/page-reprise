import hashlib
import json
import subprocess
import uuid
import time
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


def request(path, key, token=None, data=None, mime="application/json", method=None, headers=None):
    extra = {"apikey": key, "Content-Type": mime, **(headers or {})}
    if token:
        extra["Authorization"] = "Bearer " + token
    body = json.dumps(data).encode() if data is not None and not isinstance(data, bytes) else data
    call = Request(endpoint + path, data=body, method=method, headers=extra)
    try:
        with urlopen(call, timeout=20) as response:
            return response.status, response.read()
    except HTTPError as failure:
        return failure.code, failure.read()


def ok(result):
    status, body = result
    assert 200 <= status < 300, (status, body[:160] if status >= 400 else "")
    return body


def sql(statement):
    subprocess.run(["docker", "exec", "-i", "supabase_db_page-reprise", "psql", "-Xq", "-U", "postgres",
                    "-v", "ON_ERROR_STOP=1"], input=statement, text=True, stdout=subprocess.DEVNULL, check=True)


def check_recycle_bin(who, access, friend_access, kept_hash):
    endpoint_events = "/rest/v1/study_events?on_conflict=owner,event_id"

    def append(card, kind, at, **props):
        eid = card if kind == "CREATE" else str(uuid.uuid4())
        payload = dict(id=eid, card=card, kind=kind, at=at, **props)
        row = dict(owner=who, event_id=eid, card_id=card, payload=payload)
        ok(request(endpoint_events, public, access, [row],
                   headers={"Prefer": "resolution=ignore-duplicates,return=minimal"}))
        return eid

    now = int(time.time() * 1000)
    day = 86400000
    old = now - 8 * day
    empty = hashlib.sha256(b"").hexdigest()
    content = b"private recycle-bin fixture"
    removed_hash = hashlib.sha256(content).hexdigest()
    card, restored = str(uuid.uuid4()), str(uuid.uuid4())
    append(card, "CREATE", old - day, body={"pictures": [kept_hash], "answers": [removed_hash]})
    prefix = "/storage/v1/object/reprise-images/" + who + "/"
    ok(request(prefix + removed_hash, public, access, content, "image/jpeg"))
    append(card, "TRASH", old, base=empty)
    append(restored, "CREATE", old - day, body={"title": "restored"})
    mark = append(restored, "TRASH", old, base=empty)
    append(restored, "RESTORE", old + day, refs=[mark])
    ok(request("/rest/v1/rpc/reprise_collect_trash", public, friend_access, {}))
    assert b"CREATE" in ok(request("/rest/v1/study_events?card_id=eq." + card, public, access))
    ok(request("/rest/v1/rpc/reprise_collect_trash", public, access, {}))
    append(card, "CREATE", old - day, body={"title": "stale offline copy"})
    rows = json.loads(ok(request("/rest/v1/study_events?card_id=eq." + card, public, access)))
    assert len(rows) == 1 and rows[0]["payload"]["kind"] == "PURGE" and "body" not in rows[0]["payload"]
    assert len(json.loads(ok(request("/rest/v1/study_events?card_id=eq." + restored, public, access)))) == 3
    sql("select reprise_private.clean_images();")
    download = "/storage/v1/object/authenticated/reprise-images/" + who + "/"
    assert request(download + removed_hash, public, access)[0] in (400, 403, 404)
    assert request(download + kept_hash, public, access)[0] == 200


def invite(name):
    email = name + "@example.test"
    signup = json.loads(ok(request("/auth/v1/admin/generate_link", admin, admin, {"type": "invite", "email": email})))
    code = signup.get("email_otp") or signup["properties"]["email_otp"]
    session = json.loads(ok(request("/auth/v1/verify", public, data={"type": "invite", "email": email, "token": code})))
    password = uuid.uuid4().hex
    ok(request("/auth/v1/user", public, session["access_token"], {"password": password}, method="PUT"))
    return email, password, session


if __name__ == "__main__":
    config = json.loads(subprocess.check_output(["supabase", "status", "-o", "json"], text=True))
    endpoint = config["API_URL"].rstrip("/")
    assert urlparse(endpoint).hostname in {"localhost", "127.0.0.1"}, "Run only against a disposable local instance"
    admin, public = config["SERVICE_ROLE_KEY"], config["ANON_KEY"]
    sql("select vault.create_secret('http://supabase_kong_page-reprise:8000','reprise_project_url');"
        + "select vault.create_secret('" + admin + "','reprise_storage_admin');")
    sql(Path("supabase/setup-trash-cleanup.sql").read_text())
    # Drive the installed jobs explicitly while seeding backdated fixtures.
    sql("select cron.unschedule('reprise-trash-expiry'); select cron.unschedule('reprise-trash-images');")
    email, password, tablet = invite("tablet")
    _, _, friend = invite("friend")
    phone = json.loads(ok(request("/auth/v1/token?grant_type=password", public,
                                  data={"email": email, "password": password})))
    who, access = tablet["user"]["id"], tablet["access_token"]
    assert phone["user"]["id"] == who
    assert json.loads(ok(request("/rest/v1/rpc/reprise_member", public, access, {}))) is True
    image = bytes.fromhex("ffd8ffe0") + b"PageReprise disposable storage fixture" + bytes.fromhex("ffd9")
    photo = hashlib.sha256(image).hexdigest()
    remote = "reprise-images/" + who + "/" + photo
    identity = str(uuid.uuid4())
    event = {"id": identity, "card": identity, "kind": "CREATE", "at": 1800000000000, "base": "",
             "value": "math", "number": 7, "refs": [], "body": {"title": "math", "source": "", "solution": "",
             "pictures": [photo], "answers": [photo], "readings": {"questions": {photo: "求 $x^2$ 的导数"},
             "answers": {photo: "原稿写作 $2x$"}}, "knowledge": {"manual": "导数的计算",
             "suggestions": {"questions": {photo: "幂函数求导"}, "answers": {photo: "导数运算"}}}}}
    row = {"owner": who, "event_id": identity, "card_id": identity, "payload": event}
    for device in (access, phone["access_token"]):
        ok(request("/rest/v1/study_events?on_conflict=owner,event_id", public, device, [row],
                   headers={"Prefer": "resolution=ignore-duplicates,return=minimal"}))
    ok(request("/storage/v1/object/" + remote, public, access, image, "image/jpeg"))
    duplicated, error = request("/storage/v1/object/" + remote, public, access, image, "image/jpeg")
    assert duplicated in (400, 409) and any(marker in error.lower() for marker in (b"duplicate", b"already exists", b"409"))
    route = "/rest/v1/study_events?select=event_id,card_id,payload&order=event_id.asc"
    records = json.loads(ok(request(route, public, phone["access_token"])))
    assert len(records) == 1 and records[0]["payload"] == event
    assert ok(request("/storage/v1/object/authenticated/" + remote, public, phone["access_token"])) == image
    assert json.loads(ok(request(route, public, friend["access_token"]))) == []
    assert request("/storage/v1/object/authenticated/" + remote, public, friend["access_token"])[0] in (400, 403, 404)
    check_recycle_bin(who, access, friend["access_token"], photo)
    print("Passed: invitations, sync, account isolation, restore, expiry, stale uploads and physical photo cleanup.")
