import hashlib
import json
import pathlib
import sqlite3
import subprocess
import sys
import tempfile
import time
import uuid

app = pathlib.Path(sys.argv[1]).resolve(strict=True)
with tempfile.TemporaryDirectory(prefix="reprise-test-") as scratch:
    profile = pathlib.Path(scratch).resolve()
    task = "PageReprise-Trash-" + hashlib.sha256(str(profile).encode()).hexdigest()[:16]
    owner = "local:" + str(uuid.uuid4())
    (profile / "preferences.properties").write_text("guest=" + owner + "\n", encoding="ascii")
    subprocess.run([str(app), "--collect-trash", str(profile)], check=True, timeout=20)
    db = profile / "circulation.db"
    card, mark = str(uuid.uuid4()), str(uuid.uuid4())
    deadline = int(time.time()*1000) + 33000
    deleted = deadline - 7*86400000
    events = [(card, dict(id=card,card=card,kind="CREATE",at=deleted-86400000,value="math",body=dict(title="临时验收"))),
              (mark, dict(id=mark,card=card,kind="TRASH",at=deleted,base=hashlib.sha256(b"").hexdigest()))]
    with sqlite3.connect(db) as conn:
        for eid, payload in events:
            conn.execute("insert into operations values(?,?,?,?,1)", (owner,eid,card,json.dumps(payload)))
    conn.close()
    try:
        subprocess.run([str(app), "--collect-trash", str(profile)], check=True, timeout=20)
        result = subprocess.run(["schtasks.exe","/query","/tn",task], capture_output=True)
        assert result.returncode == 0, "Windows did not register cleanup for the test subject"
        print("Registered the test deadline",flush=True)
        limit = time.monotonic()+75
        while time.monotonic()<limit:
            with sqlite3.connect(db) as conn:
                events = [json.loads(row[0])["kind"] for row in conn.execute("select payload from operations")]
            conn.close()
            if events == ["PURGE"]:
                print("Windows task actually ran and purged only the isolated test card",flush=True)
                break
            time.sleep(1)
        else: raise RuntimeError("No purge at the scheduled deadline")
    finally:
        subprocess.run(["schtasks.exe","/delete","/tn",task,"/f"],capture_output=True)
