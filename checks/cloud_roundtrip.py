import hashlib
import json
import subprocess
import uuid
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
    ok(request("/storage/v1/object/" + remote, public, access, image, "image/jpeg"))
    duplicated, error = request("/storage/v1/object/" + remote, public, access, image, "image/jpeg")
    assert duplicated in (400, 409) and any(marker in error.lower() for marker in (b"duplicate", b"already exists", b"409"))
    identity = str(uuid.uuid4())
    event = {"id": identity, "card": identity, "kind": "CREATE", "at": 1800000000000, "base": "",
             "value": "math", "refs": [], "body": {"title": "math", "source": "", "solution": "", "pictures": [photo], "answers": []}}
    row = {"owner": who, "event_id": identity, "card_id": identity, "payload": event}
    for device in (access, phone["access_token"]):
        ok(request("/rest/v1/study_events?on_conflict=owner,event_id", public, device, [row],
                   headers={"Prefer": "resolution=ignore-duplicates,return=minimal"}))
    route = "/rest/v1/study_events?select=event_id,card_id,payload&order=event_id.asc"
    records = json.loads(ok(request(route, public, phone["access_token"])))
    assert len(records) == 1 and records[0]["payload"] == event
    assert ok(request("/storage/v1/object/authenticated/" + remote, public, phone["access_token"])) == image
    assert json.loads(ok(request(route, public, friend["access_token"]))) == []
    assert request("/storage/v1/object/authenticated/" + remote, public, friend["access_token"])[0] in (400, 403, 404)
    print("Passed: invitation, password activation, second login, idempotent journal, private photos, account isolation.")
