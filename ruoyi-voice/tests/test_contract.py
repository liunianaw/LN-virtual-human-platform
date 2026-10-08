import hashlib
import json
import threading
import time
import unittest
import urllib.request
import urllib.error
from datetime import datetime, timezone, timedelta
from pathlib import Path
from ruoyi_voice.executor import Executor, Problem, audio_metadata
from ruoyi_voice.providers import FakeProvider, ProviderFailure
from ruoyi_voice.server import Server


def request(key="2"):
    text="你好🙂"
    digest=hashlib.sha256(text.encode()).hexdigest()
    return {"schemaVersion":1,"traceId":"1","taskId":"1","attemptId":key,"taskRevision":1,
        "requestHash":hashlib.sha256(f"1\n{key}\n3\n{digest}".encode()).hexdigest(),"voiceVersionId":"3",
        "dispatchToken":"test-permit","text":text,"textHash":digest,"inputCharCount":len(text),
        "deadlineAt":(datetime.now(timezone.utc)+timedelta(seconds=30)).isoformat(),"maxAudioBytes":1048576}


class ContractTest(unittest.TestCase):
    def test_idempotency_permit_loss_limits_cancel_and_audio(self):
        calls=[]; events=[]; release=threading.Event()
        binding={"providerType":"TEST_TONE","capabilityVersion":"test-tone-v1","modelRevision":"1","language":"zh-CN","providerVoiceRef":"tone","parameters":{}}
        def authorize(key,body):
            calls.append(key)
            if key=="5": raise TimeoutError()
            if key=="2": release.wait(2)
            return {"authorized":True,"binding":binding}
        engine=Executor({"TEST_TONE":FakeProvider()},authorize,lambda key,event:events.append(event),capacity=1)
        try:
            first=request();engine.submit(first,"2");engine.submit(first,"2")
            conflict=request();conflict["text"]="changed"
            with self.assertRaises(Problem):engine.submit(conflict,"2")
            engine.submit(request("4"),"4");engine.cancel("4")
            with self.assertRaises(Problem) as full:engine.submit(request("6"),"6")
            self.assertEqual("VOICE_QUEUE_FULL",full.exception.code)
            release.set()
            self.wait(lambda:len(events)==2)
            self.assertEqual(["2"],calls)
            self.assertEqual("CANCELLED",engine.status("4")["state"])
            data=engine.read_audio("2");self.assertEqual(100,audio_metadata(data,1048576)["durationMs"])
            for bad in (data[:-1],data+b"x",b"not wave"):
                with self.assertRaises(Problem):audio_metadata(bad,1048576)
            uncertain=request("5");engine.submit(uncertain,"5");self.wait(lambda:len(events)==3)
            self.assertEqual("UNKNOWN",engine.status("5")["state"])
            engine.submit(uncertain,"5");self.assertEqual(1,calls.count("5"))
            engine.attempts["2"].created-=1000;engine.maintain()
            with self.assertRaises(Problem) as expired:engine.read_audio("2")
            self.assertEqual("VOICE_RESULT_EXPIRED",expired.exception.code)
        finally:release.set();engine.close()

        retries=[];received=[]
        def unavailable(key,event):
            retries.append(key);raise TimeoutError()
        replay=Executor({"TEST_TONE":FakeProvider()},lambda key,body:(received.append(key) or {"authorized":True,"binding":binding}),unavailable)
        try:
            replay.submit(request(),"2");self.wait(lambda:len(retries)>=1)
            for _ in range(30):replay.maintain()
            self.assertEqual(12,len(retries));self.assertEqual(["2"],received)
            self.assertTrue(replay.status("2")["reviewRequired"])
            self.assertEqual("SUCCEEDED",replay.status("2")["state"])
        finally:replay.close()

    def test_installed_catalog_matches_shared_descriptor(self):
        manifest=Path(__file__).resolve().parents[2]/"RuoYi-Cloud/ruoyi-common/ruoyi-common-voice/src/main/resources/voice/capabilities.json"
        shared=json.loads(manifest.read_text(encoding="utf-8"))
        self.assertEqual(FakeProvider.capability,next(x for x in shared if x["providerType"]=="TEST_TONE"))
        source=manifest.parent/"protocol-v1.schema.json"
        packaged=Path(__file__).resolve().parents[1]/"ruoyi_voice/protocol-v1.schema.json"
        self.assertEqual(json.loads(source.read_text(encoding="utf-8")),json.loads(packaged.read_text(encoding="utf-8")))
        self.assertEqual(set(request()),set(json.loads(source.read_text(encoding="utf-8"))["$defs"]["SynthesisAttemptRequest"]["required"]))

    def test_authenticated_http_full_attempt_and_replay(self):
        events=[];permits=[]
        binding={"providerType":"TEST_TONE","capabilityVersion":"test-tone-v1","modelRevision":"1","language":"zh-CN","providerVoiceRef":"tone","parameters":{}}
        def authorize(key,body):
            permits.append(key);return {"authorized":True,"binding":binding}
        engine=Executor({"TEST_TONE":FakeProvider()},authorize,lambda key,event:events.append(event))
        bearer="fixture-"+"x"*40
        server=Server(("127.0.0.1",0),engine,bearer)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        base=f"http://127.0.0.1:{server.server_port}/internal/voice/v1/"
        def call(path,body=None,auth=True):
            headers={"Content-Type":"application/json","Idempotency-Key":"2"}
            if auth:headers["Authorization"]="Bearer "+bearer
            req=urllib.request.Request(base+path,None if body is None else json.dumps(body).encode(),headers)
            with urllib.request.urlopen(req,timeout=3) as response:return response.read()
        try:
            with self.assertRaises(urllib.error.HTTPError) as denied:call("attempts",request(),auth=False)
            self.assertEqual(401,denied.exception.code);self.assertEqual([],permits)
            self.assertTrue(json.loads(call("readiness"))["modelReady"])
            item=request();call("attempts",item);self.wait(lambda:len(events)==1)
            call("attempts",item);self.assertEqual(["2"],permits)
            changed=dict(item,taskRevision=2)
            with self.assertRaises(urllib.error.HTTPError) as conflict:call("attempts",changed)
            self.assertEqual(409,conflict.exception.code)
            result=call("attempts/2/audio")
            self.assertEqual(events[0]["audio"],audio_metadata(result,1048576))
            self.assertEqual("PROCESS",json.loads(call("attempts/2"))["queryMode"])
            self.assertEqual("QUEUED_ONLY",json.loads(call("attempts/2/cancel",{}))["cancelMode"])
        finally:server.shutdown();server.server_close();thread.join(3);engine.close()

    @staticmethod
    def wait(predicate):
        deadline=time.monotonic()+3
        while not predicate():
            if time.monotonic()>deadline:raise AssertionError("bounded executor did not finish")
            time.sleep(.01)


if __name__=="__main__":unittest.main()
