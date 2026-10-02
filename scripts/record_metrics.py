#!/usr/bin/env python3
"""Read-only public metrics recorder. Does not create shows or send reservations."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import time
import urllib.error
import urllib.request

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('base_url')
p.add_argument('--seconds', type=float, default=300)
p.add_argument('--interval', type=float, default=2)
p.add_argument('--output', default='metrics-recording.jsonl')
a = p.parse_args()
if a.seconds <= 0 or a.interval < 1:
    p.error('seconds must be positive and interval must be at least one second')
url = a.base_url.rstrip('/') + '/actuator/prometheus'
end = time.monotonic() + a.seconds
previous = None
prefixes = ('process_', 'system_cpu_', 'jvm_memory_', 'jvm_buffer_', 'jvm_gc_',
            'hikaricp_', 'reservations_', 'http_server_requests_', 'reservation_preflight_')
print('Recording read-only metrics to', Path(a.output).resolve(), flush=True)
with open(a.output, 'w') as output:
    try:
        while time.monotonic() < end:
            started = time.monotonic()
            row = {'timestamp_utc': datetime.now(timezone.utc).isoformat()}
            try:
                with urllib.request.urlopen(url, timeout=5) as response:
                    lines = response.read().decode().splitlines()
                values = {}
                for line in lines:
                    if line.startswith(prefixes):
                        key, value = line.rsplit(' ', 1)
                        values[key] = float(value)
                if 'process_uptime_seconds' not in values:
                    raise ValueError('Missing process uptime; not a valid app metrics scrape')
                row.update(ok=True, metrics=values, scrape_seconds=time.monotonic() - started)
                uptime = values['process_uptime_seconds']
                row['restart_observed'] = previous is not None and uptime < previous
                previous = uptime
                heap = sum(v for k, v in values.items() if k.startswith('jvm_memory_used_bytes{') and 'area="heap"' in k)
                print('uptime=%.0fs heap=%.1fMiB restart=%s' % (uptime, heap / 1048576, row['restart_observed']), flush=True)
            except Exception as error:
                row.update(ok=False, error_type=type(error).__name__, error=str(error), scrape_seconds=time.monotonic() - started)
                if isinstance(error, urllib.error.HTTPError):
                    row['http_status'] = error.code
                print('scrape failed:', row['error'], flush=True)
            output.write(json.dumps(row, allow_nan=False) + '\n')
            output.flush()
            time.sleep(max(0, min(a.interval - (time.monotonic() - started), end - time.monotonic())))
    except KeyboardInterrupt:
        print('Stopped; recording saved.', flush=True)
