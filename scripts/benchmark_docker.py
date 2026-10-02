#!/usr/bin/env python3
"""Compare local CPU-limited containers. Never contacts Render or Neon."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request
import uuid

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--workers', type=int, nargs='+', default=[64, 16])
p.add_argument('--cpus', type=float, default=.1)
p.add_argument('--compiler', choices=['c1', 'tiered'], default='c1')
p.add_argument('--profile', action='store_true', help='Record JFR; adds profiling overhead to every variant')
a = p.parse_args()
if a.cpus <= 0 or any(n < 1 for n in a.workers):
    p.error('CPU quota and worker counts must be positive')
ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = Path(tempfile.mkdtemp(prefix='seatlab-docker-bench-'))
TAG = 'seatlab-bench-' + uuid.uuid4().hex[:8]
DB = TAG + '-db'
owned = []
network_created = False
results = []


def docker(args, **kwargs):
    return subprocess.run(['docker', *args], check=True, text=True, **kwargs)


try:
    print('Artifacts:', ARTIFACTS, flush=True)
    with (ARTIFACTS / 'build.log').open('w') as output:
        subprocess.run([str(ROOT / 'mvnw'), '-q', '-DskipTests', 'package'], cwd=ROOT,
                       stdout=output, stderr=subprocess.STDOUT, check=True)
    # Never mount mutable build output: another build can invalidate a running JVM's lazy class loading.
    jar = ARTIFACTS / 'app.jar'
    shutil.copy2(ROOT / 'target/app.jar', jar)
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    docker(['network', 'create', TAG], stdout=subprocess.DEVNULL)
    network_created = True
    docker(['run', '-d', '--name', DB, '--network', TAG, '--memory', '512m',
            '--tmpfs', '/var/lib/postgresql', '-e', 'POSTGRES_PASSWORD=local-benchmark-only',
            'postgres:18'], stdout=subprocess.DEVNULL)
    owned.append(DB)
    for _ in range(60):
        ready = subprocess.run(['docker', 'exec', DB, 'pg_isready', '-U', 'postgres'],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if ready.returncode == 0:
            break
        time.sleep(1)
    else:
        raise RuntimeError('Local PostgreSQL container did not become ready')
    for index, workers in enumerate(a.workers):
        name = TAG + '-app-' + str(index)
        database = 'bench' + str(index)
        docker(['exec', DB, 'createdb', '-U', 'postgres', database])
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            port = sock.getsockname()[1]
        base = 'http://127.0.0.1:' + str(port)
        flags = ['-Xmx256m', '-Xss512k', '-XX:MaxMetaspaceSize=128m', '-XX:ReservedCodeCacheSize=48m',
                 '-XX:MaxDirectMemorySize=64m', '-XX:+UseSerialGC', '-XX:+ExitOnOutOfMemoryError']
        if a.compiler == 'c1':
            flags.append('-XX:TieredStopAtLevel=1')
        if a.profile:
            flags.append(f'-XX:StartFlightRecording=filename=/artifacts/profile-{index}.jfr,settings=profile,dumponexit=true')
        options = ['run', '-d', '--name', name, '--network', TAG, '--cpus', str(a.cpus), '--memory', '512m',
                   '-p', f'127.0.0.1:{port}:8080', '-v', str(jar) + ':/app.jar:ro',
                   '-v', str(ARTIFACTS) + ':/artifacts',
                   '-e', f'DATABASE_URL=jdbc:postgresql://{DB}:5432/{database}',
                   '-e', 'DATABASE_USER=postgres', '-e', 'DATABASE_PASSWORD=local-benchmark-only',
                   '-e', 'ADMIN_KEY=local-benchmark-admin', '-e', 'TOKEN_SECRET=local-benchmark-secret',
                   '-e', f'HTTP_THREADS={workers}', '-e', 'MALLOC_ARENA_MAX=2',
                   'eclipse-temurin:17-jre', 'java', *flags, '-jar', '/app.jar']
        print(f'Starting workers={workers}, cpus={a.cpus}, compiler={a.compiler}', flush=True)
        docker(options, stdout=subprocess.DEVNULL)
        owned.append(name)
        cold_start = time.monotonic()
        deadline = cold_start + 600
        while True:
            state = json.loads(subprocess.check_output(['docker', 'inspect', name], text=True))[0]['State']
            if not state['Running']:
                raise RuntimeError('App container exited before readiness: ' + str(state))
            try:
                with urllib.request.urlopen(base + '/actuator/health/readiness', timeout=2) as response:
                    if json.load(response)['status'] == 'UP':
                        break
            except Exception:
                pass
            if time.monotonic() > deadline:
                raise TimeoutError('Container did not become ready within 600 seconds')
            time.sleep(2)
        startup_seconds = time.monotonic() - cold_start
        print('cold_start_seconds=%.1f' % startup_seconds, flush=True)
        start = time.monotonic()
        with (ARTIFACTS / f'burst-{index}.log').open('w') as output:
            test = subprocess.run([str(ROOT / 'burst.sh'), base, '20000', '20000', '5000'], cwd=ROOT,
                                  env=dict(os.environ, ADMIN_KEY='local-benchmark-admin'),
                                  stdout=output, stderr=subprocess.STDOUT, timeout=900)
        full_seconds = time.monotonic() - start
        docker(['stop', '--time', '30', name], stdout=subprocess.DEVNULL)
        logs = subprocess.check_output(['docker', 'logs', name], text=True, stderr=subprocess.STDOUT)
        (ARTIFACTS / f'app-{index}.log').write_text(logs)
        samples = []
        for line in logs.splitlines():
            try:
                entry = json.loads(line)
            except ValueError:
                continue
            if entry.get('message') == 'resource_sample':
                samples.append(entry)
        peaks = {key: max((entry[key] for entry in samples if key in entry), default=None)
                 for key in ('heap_used_bytes', 'container_memory_bytes', 'http_worker_queue')}
        burst = (ARTIFACTS / f'burst-{index}.log').read_text()
        match = re.search(r'done in ([\d.]+)s', burst)
        result = dict(workers=workers, cpus=a.cpus, compiler=a.compiler, profiling=a.profile,
                      burst_exit_code=test.returncode, cold_start_seconds=startup_seconds,
                      full_test_seconds=full_seconds, reservation_seconds=float(match[1]) if match else None,
                      jar_sha256=digest, sampled_peaks=peaks)
        results.append(result)
        (ARTIFACTS / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
        print(json.dumps(result), flush=True)
        docker(['rm', '-v', name], stdout=subprocess.DEVNULL)
        owned.remove(name)
finally:
    for name in reversed(owned):
        if name != DB:
            log = subprocess.run(['docker', 'logs', name], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            (ARTIFACTS / (name + '-cleanup.log')).write_text(log.stdout)
        subprocess.run(['docker', 'rm', '-f', '-v', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if network_created:
        subprocess.run(['docker', 'network', 'rm', TAG], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
if any(r['burst_exit_code'] for r in results):
    raise SystemExit(1)
