#!/usr/bin/env python3
"""Run opt-in tests against an isolated local Mumble binary, using synthetic identities only."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import socket
import sqlite3
import ssl
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--server', type=Path, required=True, help='Path to the official mumble-server binary')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
server = args.server.resolve()
if not server.is_file() or shutil.which('openssl') is None:
    parser.error('A Mumble server binary and openssl are required')
folder = root / 'core/build/interop'
folder.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='run-', dir=folder) as temp:
    work = Path(temp)
    with (work / 'setup.log').open('w') as log:
        def run(*command):
            subprocess.run(list(command), cwd=work, check=True, stdout=log, stderr=subprocess.STDOUT)
        run('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '2', '-subj', '/CN=Voice Test CA', '-keyout', 'ca.key', '-out', 'ca.pem')
        for label, common, extension in [('server', 'localhost', 'subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n'),
                                         ('client', 'Watch Test', 'extendedKeyUsage=clientAuth\n')]:
            (work / f'{label}.ext').write_text(extension + 'basicConstraints=CA:FALSE\n')
            run('openssl', 'req', '-new', '-newkey', 'rsa:2048', '-nodes', '-subj', f'/CN={common}', '-keyout', f'{label}.key', '-out', f'{label}.csr')
            run('openssl', 'x509', '-req', '-in', f'{label}.csr', '-CA', 'ca.pem', '-CAkey', 'ca.key', '-CAcreateserial', '-days', '2', '-extfile', f'{label}.ext', '-out', f'{label}.pem')
        run('openssl', 'pkcs12', '-export', '-in', 'client.pem', '-inkey', 'client.key', '-certfile', 'ca.pem', '-out', 'client.p12', '-passout', 'pass:test-only')
        with socket.socket() as free:
            free.bind(('127.0.0.1', 0))
            port = free.getsockname()[1]
        config = work / 'mumble.ini'
        database = work / 'server.sqlite'
        config.write_text(f'database={database}\nhost=127.0.0.1\nport={port}\nserverpassword=voice-test-password\n'
                          f'sslCert={work / "server.pem"}\nsslKey={work / "server.key"}\nsslCA={work / "ca.pem"}\n'
                          'certrequired=false\nbonjour=false\nice=\nallowping=false\n')
        run(str(server), '-ini', str(config), '-supw', 'voice-admin-password')
        cert = ssl.PEM_cert_to_DER_cert((work / 'client.pem').read_text())
        with sqlite3.connect(database) as db:
            db.execute("insert into users(server_id,user_id,name,pw,lastchannel) values(1,1,'CertWatch','',0)")
            db.execute('insert into user_info(server_id,user_id,key,value) values(1,1,3,?)', (hashlib.sha1(cert).hexdigest(),))
            db.execute("insert into channels(server_id,channel_id,parent_id,name,inheritacl) values(1,17,0,'Watch Test',1)")
        process = subprocess.Popen([str(server), '-fg', '-ini', str(config)], stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(100):
                if process.poll() is not None:
                    raise RuntimeError('Test server exited; inspect its local setup log')
                try:
                    with socket.create_connection(('127.0.0.1', port), .2):
                        break
                except OSError:
                    time.sleep(.05)
            else:
                raise RuntimeError('Test server did not become ready')
            gradle = os.environ.get('GRADLE_EXECUTABLE', str(root / 'gradlew'))
            result = subprocess.run([gradle, ':core:test', f'-PmumbleTestDir={work}', f'-PmumbleTestPort={port}', '--console=plain', '--no-daemon'], cwd=root)
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
    raise SystemExit(result.returncode)
