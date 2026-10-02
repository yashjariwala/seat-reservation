#!/usr/bin/env python3
"""Run a simultaneous burst on our own JVM and disposable loopback PostgreSQL database."""
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = Path(tempfile.mkdtemp(prefix="seatlab-local-burst-"))
DATABASE = "seatlab_burst_" + uuid.uuid4().hex[:8] + "_tests"
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
base = "http://127.0.0.1:" + str(port)
env = dict(os.environ, PORT=str(port), DATABASE_URL="jdbc:postgresql://127.0.0.1:5432/" + DATABASE,
           DATABASE_USER=os.environ.get("USER", "postgres"), DATABASE_PASSWORD="",
           ADMIN_KEY="local-burst-admin", TOKEN_SECRET="local-burst-secret", DB_POOL_SIZE="10", HTTP_THREADS="64")
process = None
created = False
stop = threading.Event()
peak = {"sampled_peak_rss_mib": 0, "sampled_peak_heap_mib": 0}


def monitor():
    while not stop.is_set():
        try:
            rss = int(subprocess.check_output(["ps", "-o", "rss=", "-p", str(process.pid)], text=True).strip())
            peak["sampled_peak_rss_mib"] = max(peak["sampled_peak_rss_mib"], rss / 1024)
            metrics = urllib.request.urlopen(base + "/actuator/prometheus", timeout=2).read().decode()
            heap = sum(float(line.split()[-1]) for line in metrics.splitlines()
                       if line.startswith("jvm_memory_used_bytes{") and 'area="heap"' in line)
            peak["sampled_peak_heap_mib"] = max(peak["sampled_peak_heap_mib"], heap / 1048576)
        except Exception:
            pass  # Sampling is diagnostic, not an acceptance check.
        stop.wait(.25)


try:
    print("Artifacts:", ARTIFACTS, flush=True)
    subprocess.run(["createdb", "-h", "127.0.0.1", DATABASE], check=True)
    created = True
    with (ARTIFACTS / "build.log").open("w") as output:
        subprocess.run([str(ROOT / "mvnw"), "-q", "-DskipTests", "package"], cwd=ROOT,
                       stdout=output, stderr=subprocess.STDOUT, check=True)
    with (ARTIFACTS / "app.log").open("w") as output:
        process = subprocess.Popen(["java", "-Xmx256m", "-Xss512k", "-XX:MaxMetaspaceSize=128m",
            "-XX:ReservedCodeCacheSize=48m", "-XX:MaxDirectMemorySize=64m", "-XX:+UseSerialGC",
            "-XX:TieredStopAtLevel=1", "-XX:+ExitOnOutOfMemoryError", "-jar", str(ROOT / "target/app.jar")],
            cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 60
        while True:
            if process.poll() is not None:
                raise RuntimeError("Server exited before readiness; see app.log")
            try:
                if json.load(urllib.request.urlopen(base + "/actuator/health/readiness", timeout=2))["status"] == "UP":
                    break
            except Exception:
                pass
            if time.monotonic() > deadline:
                raise TimeoutError("Local server did not become ready")
            time.sleep(.2)
        sampler = threading.Thread(target=monitor)
        sampler.start()
        try:
            with (ARTIFACTS / "burst.log").open("w") as burst:
                result = subprocess.run([str(ROOT / "burst.sh"), base, "20000", "20000", "5000"],
                    cwd=ROOT, env=env, stdout=burst, stderr=subprocess.STDOUT, timeout=600)
        finally:
            stop.set()
            sampler.join(timeout=5)
        report = dict(peak, burst_exit_code=result.returncode, heap_limit_mib=256, direct_memory_limit_mib=64,
                      cpu_limited=False, http_version_override=env.get("BURST_HTTP_VERSION"))
        (ARTIFACTS / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        print((ARTIFACTS / "burst.log").read_text())
        print(json.dumps(report, indent=2))
        raise SystemExit(result.returncode)
finally:
    stop.set()
    if process and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
    if created:
        subprocess.run(["dropdb", "-h", "127.0.0.1", "--force", DATABASE], check=True)
