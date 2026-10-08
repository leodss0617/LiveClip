import os
import subprocess
import tempfile
import unittest
from pathlib import Path


class NetworkRecoveryTests(unittest.TestCase):
    def test_recovery_is_scoped_and_idempotent(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            log = root / 'rules'
            for name, source in {
                'docker': '#!/bin/sh\nprintf "6b5878842a8801dec770cb12ff45c243231e43f947dade667bb20074c4306828\\n"\n',
                'ip': '#!/bin/sh\nprintf "default via 1.2.3.4 dev eth0\\n"\n',
                'sudo': '#!/bin/sh\n[ "$1" = -n ] && shift\nexec "$@"\n',
                'iptables-legacy': '''#!/bin/sh
case "$1" in
-S) echo '-P FORWARD DROP';;
-C) test -f "$RULE_LOG" && grep -Fxq -- "$*" "$RULE_LOG";;
-I) echo "-C ${3#1} ${*#-I FORWARD 1 }" >/dev/null
    printf '%s\\n' "-C FORWARD ${*#-I FORWARD 1 }" >> "$RULE_LOG";;
esac
''',
            }.items():
                p = root / name
                p.write_text(source)
                p.chmod(0o755)
            env = dict(os.environ, PATH=str(root)+':'+os.environ['PATH'], RULE_LOG=str(log))
            env.pop('CODESPACES', None)
            subprocess.run(['bash', '.devcontainer/recover-network.sh'], env=env, check=True)
            self.assertFalse(log.exists())
            env['CODESPACES'] = 'true'
            for _ in range(2):
                subprocess.run(['bash', '.devcontainer/recover-network.sh'], env=env, check=True)
            rules = log.read_text().splitlines()
            self.assertEqual(len(rules), 3)
            self.assertTrue(all('br-6b5878842a88' in x for x in rules))
            self.assertFalse(any('-P' in x or '-F' in x for x in rules))
