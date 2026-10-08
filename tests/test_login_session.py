import shutil
import subprocess
import unittest
from pathlib import Path


class LoginSessionTests(unittest.TestCase):
    def test_login_cannot_silently_swallow_session_failure(self):
        node = shutil.which('node')
        if not node:
            self.skipTest('Node required')
        source = Path('liveclip/static/app.js').read_text()
        refresh = source[source.index('async function refresh('):source.index('\nasync function status(')]
        script = r'''
const assert=require('node:assert/strict');
let busy=false, cloudStopping=false, timer=null, sessionData=[],clipData=[];
const elements={login:{hidden:false},studio:{hidden:true},connection:{}};
const $=id=>elements[id];
function paint(){} function tell(){} let reject=true;
async function api(){if(reject)throw Error('Entre para continuar.');return [];}
''' + refresh + r'''
(async()=>{
 await assert.rejects(refresh({required:true}), /sessão/i);
 assert.equal(busy,false);
 assert.equal(await refresh(),false);
 busy=true;
 await assert.rejects(refresh({required:true}), /Aguarde/);
 busy=false; reject=false;
 assert.equal(await refresh({required:true}),true);
 assert.equal(elements.studio.hidden,false);
 clearTimeout(timer);
})().catch(e=>{console.error(e);process.exitCode=1;});
'''
        result=subprocess.run([node,'-e',script],capture_output=True,text=True)
        self.assertEqual(result.returncode,0,result.stderr)
