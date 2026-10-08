"""Execute the actual API client against proxy and application responses."""
import shutil
import subprocess
from pathlib import Path

import unittest


def test_frontend_api_handles_proxy_responses():
    node = shutil.which('node')
    if not node:
        raise unittest.SkipTest('Node is required for the frontend regression test')
    source = Path('liveclip/static/app.js').read_text()
    api = source[source.index('async function api('):source.index('\nfunction sessionCard')]
    script = r'''
const assert = require('node:assert/strict');
let shown=0;
function showLogin(){shown++;}
let response;
async function fetch(url, options){return response;}
''' + api + r'''
(async()=>{
 response={ok:false,status:401,json:async()=>{throw Error('empty')}};
 await assert.rejects(api('/login'), /401.*GitHub/);
 response={ok:false,status:401,json:async()=>({detail:'Senha incorreta.'})};
 await assert.rejects(api('/login'), /Senha incorreta/);
 response={ok:true,status:200,json:async()=>{throw Error('HTML')}};
 await assert.rejects(api('/login'), /resposta.*inválida/i);
 response={ok:false,status:422,json:async()=>({detail:[{msg:'bad'}]})};
 await assert.rejects(api('/login'), /422/);
 response={ok:true,status:200,json:async()=>({ok:true})};
 assert.deepEqual(await api('/login'),{ok:true});
 assert.equal(shown,0);
 response={ok:false,status:401,json:async()=>({detail:'Entre para continuar.'})};
 await assert.rejects(api('/sessions'), /Entre para continuar/);
 assert.equal(shown,1);
})().catch(e=>{console.error(e);process.exitCode=1;});
'''
    result = subprocess.run([node, '-e', script], capture_output=True, text=True)
    assert result.returncode == 0, result.stderr

class FrontendApiTests(unittest.TestCase):
    def test_proxy_responses(self):
        test_frontend_api_handles_proxy_responses()
