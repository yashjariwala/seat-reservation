#!/usr/bin/env python3
"""Crash only our own child JVM and disposable local PostgreSQL database. No live service access."""
import concurrent.futures
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[1]
DATABASE = "seatlab_restart_" + uuid.uuid4().hex[:8] + "_tests"
ARTIFACTS = Path(tempfile.mkdtemp(prefix="seatlab-restart-"))
ADMIN = "restart-test-admin"
process = None
log_files = []
created = False
checks = []


def sql(statement):
    return subprocess.check_output(
        ["psql", "-h", "127.0.0.1", "-d", DATABASE, "-At", "-v", "ON_ERROR_STOP=1", "-c", statement],
        text=True, stderr=subprocess.PIPE).strip()


def until(check, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(0.1)
    raise AssertionError("Timed out waiting for test condition")


def api(path, payload=None, headers=None):
    request = urllib.request.Request(base + path,
        data=None if payload is None else json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.status, json.load(response)


def healthy():
    try:
        return api("/actuator/health/readiness")[1]["status"] == "UP"
    except (OSError, urllib.error.URLError):
        return False


def start():
    global process
    output = (ARTIFACTS / ("app-" + str(len(log_files)) + ".log")).open("w")
    log_files.append(output)
    env = dict(os.environ, PORT=str(port), DATABASE_URL="jdbc:postgresql://127.0.0.1:5432/" + DATABASE,
               DATABASE_USER=os.environ.get("USER", "postgres"), DATABASE_PASSWORD="",
               ADMIN_KEY=ADMIN, TOKEN_SECRET="restart-test-secret", DB_POOL_SIZE="10", HTTP_THREADS="64")
    process = subprocess.Popen(["java", "-Xmx256m", "-Xss512k", "-XX:+UseSerialGC",
        "-XX:TieredStopAtLevel=1", "-jar", str(ROOT / "target/app.jar")], env=env,
        stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT)
    until(healthy)


def crash():
    global process
    process.kill()  # SIGKILL: no graceful shutdown may finish the transaction for us.
    process.wait(timeout=10)
    process = None


def passed(name):
    checks.append(name)
    print("PASS:", name, flush=True)


try:
    assert (ROOT / "target/app.jar").exists(), "Build first: ./mvnw -DskipTests package"
    subprocess.run(["createdb", "-h", "127.0.0.1", DATABASE], check=True)
    created = True
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    base = "http://127.0.0.1:" + str(port)
    start()
    passed("fresh process becomes ready")
    status, show = api("/shows", {"name": "Process crash regression", "seats": ["A"],
        "price_paise": 25000, "per_user_limit": 4}, {"X-Admin-Key": ADMIN})
    assert status == 201
    show_id = str(uuid.UUID(show["id"]))
    _, token = api("/auth/token", {"user_id": "restart-user"}, {"X-Admin-Key": ADMIN})
    headers = {"Authorization": "Bearer " + token["token"]}
    payload = {"seats": ["A"], "idempotency_key": "restart-key"}
    sql("""CREATE FUNCTION pause_write() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN PERFORM pg_sleep(15); RETURN NEW; END $$;
        CREATE TRIGGER pause_write AFTER UPDATE OF status ON seats FOR EACH ROW
        WHEN (NEW.status='confirmed') EXECUTE FUNCTION pause_write();""")
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as worker:
        request = worker.submit(api, "/shows/" + show_id + "/reserve", payload, headers)
        until(lambda: sql("SELECT count(*) FROM pg_stat_activity WHERE datname='" + DATABASE
              + "' AND wait_event='PgSleep' AND query LIKE '%UPDATE seats SET status%'") == "1", 10)
        # The seat UPDATE has run inside the transaction, but none of its rows are visible yet.
        assert sql("SELECT count(*) FROM reservations") == "0"
        assert sql("SELECT count(*) FROM user_counts") == "0"
        crash()
        try:
            request.result(timeout=10)
            raise AssertionError("Killed process unexpectedly returned a successful response")
        except (OSError, urllib.error.URLError):
            pass
    # PostgreSQL may finish its sleeping statement before noticing the dead client.
    # Waiting for the trigger's table lock verifies that the abandoned transaction has ended.
    sql("DROP TRIGGER pause_write ON seats; DROP FUNCTION pause_write();")
    assert sql("SELECT count(*) FROM reservations") == "0"
    assert sql("SELECT count(*) FROM user_counts") == "0"
    assert sql("SELECT count(*) FROM seats WHERE status='available' AND reservation_id IS NULL") == "1"
    passed("crash before commit leaks no seat ownership, reservation, or user counter")
    start()
    status, reservation = api("/shows/" + show_id + "/reserve", payload, headers)
    assert status == 201 and reservation["status"] == "confirmed"
    reservation_id = str(uuid.UUID(reservation["reservation_id"]))
    passed("restart accepts the same key after rollback")
    crash()
    start()
    status, replay = api("/shows/" + show_id + "/reserve", payload, headers)
    assert status == 200 and replay["reservation_id"] == reservation_id
    assert sql("SELECT count(*) FROM reservations WHERE status='confirmed'") == "1"
    assert sql("SELECT seat_count FROM user_counts WHERE user_id='restart-user'") == "1"
    assert sql("SELECT count(*) FROM seats WHERE status='confirmed' AND reservation_id='" + reservation_id + "'::uuid") == "1"
    passed("commit survives a second crash; retry replays exactly one durable booking")
    report = {"passed": True, "checks": checks, "logs": str(ARTIFACTS)}
    (ARTIFACTS / "report.json").write_text(json.dumps(report, indent=2))
    print("Report:", ARTIFACTS / "report.json", flush=True)
finally:
    if process is not None:
        process.kill()
        process.wait(timeout=10)
    for output in log_files:
        output.close()
    if created:
        subprocess.run(["dropdb", "-h", "127.0.0.1", "--force", DATABASE], check=True)
