import base64
import hashlib
import json
import os
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from unittest.mock import patch, Mock
from ruoyi_voice.executor import Executor, audio_metadata
from ruoyi_voice.providers import FakeProvider, ProviderFailure
from ruoyi_voice.native_providers import QwenProvider, ModelProvider, capability
from ruoyi_voice.model_server import ModelServer
from ruoyi_voice.model_backends import CosyVoiceBackend, KokoroBackend, reference_audio
from test_contract import request
import test_contract


def binding(name):
    cap=capability(name)
    return {"providerType":name,"capabilityVersion":cap["capabilityVersion"],"modelId":cap["modelId"],
        "modelRevision":cap["modelRevision"],"language":"zh-CN","providerVoiceRef":cap["voices"][0]["id"] if cap["voices"] else "reference:3",
        "parameters":{},"endpoint":"wss://dashscope.aliyuncs.com/api-ws/v1/realtime"}


class NativeProvidersTest(unittest.TestCase):
    def test_qwen_full_finish_protocol_language_and_uncertain_disconnect(self):
        socket=Mock();sent=[]
        socket.send.side_effect=lambda payload:sent.append(json.loads(payload))
        socket.recv.side_effect=[json.dumps(e) for e in [
            {"type":"session.created"},{"type":"session.updated"},
            {"type":"response.audio.delta","delta":base64.b64encode(b'\x01\x00'*2400).decode()},
            {"type":"response.audio.done"},{"type":"response.done","response":{"status":"completed","id":"actual-upstream-id"}},
            {"type":"session.finished"}]]
        connection=Mock();connection.__enter__=Mock(return_value=socket);connection.__exit__=Mock(return_value=False)
        voice=binding('DASHSCOPE_QWEN_TTS');voice['language']='en-US'
        with patch('websockets.sync.client.connect',return_value=connection) as connect:
            result=QwenProvider().synthesize('Hello',voice,time.time()+10,{'credential':'fixture-provider-secret'})
            self.assertEqual('actual-upstream-id',result.provider_request_id)
            self.assertEqual('PROVIDER',result.cost_source)
            self.assertEqual(100,audio_metadata(result.audio,1048576)['durationMs'])
            self.assertEqual('English',sent[0]['session']['language_type'])
            self.assertEqual(['session.update','input_text_buffer.append','input_text_buffer.commit','session.finish'],[e['type'] for e in sent])
            self.assertIsNone(connect.call_args.kwargs['proxy'])
            socket.recv.side_effect=[json.dumps(e) for e in [
                {'type':'session.created'},{'type':'session.updated'},
                {'type':'response.audio.delta','delta':base64.b64encode(b'\x01\x00'*2400).decode()},
                {'type':'response.audio.done'},{'type':'response.done','response':{'status':'completed'}},{'type':'session.finished'}]]
            voice['capabilityVersion']='qwen-bridge-v1';facts=[]
            engine=Executor({'DASHSCOPE_QWEN_TTS':QwenProvider()},
                lambda key,body:{'authorized':True,'binding':voice,'execution':{'credential':'fixture'}},
                lambda key,event:facts.append(event))
            try:
                engine.submit(request(),'2');test_contract.ContractTest.wait(lambda:len(facts)==1)
                self.assertEqual('SUCCEEDED',facts[0]['state'])
            finally:engine.close()
            socket.recv.side_effect=[json.dumps({'type':'session.finished'})]
            with self.assertRaises(ProviderFailure):QwenProvider().synthesize('Hi',voice,time.time()+10,{'credential':'fixture'})
            socket.recv.side_effect=TimeoutError()
            with self.assertRaises(TimeoutError):QwenProvider().synthesize('Hi',voice,time.time()+10,{'credential':'fixture'})

    def test_model_http_same_executor_path_auth_capacity_and_no_secret_in_facts(self):
        cap=capability('KOKORO');backend=Mock()
        backend.synthesize.return_value=FakeProvider().synthesize('fixture',{},time.time()+10)
        token='fixture-model-'+('x'*32)
        lock={'source':{'commit':'locked-source'},'weights':{'revision':cap['modelRevision']}}
        server=ModelServer(('127.0.0.1',0),token,cap,lock,backend)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        endpoint=f'http://127.0.0.1:{server.server_port}'
        voice=binding('KOKORO');voice['endpoint']=endpoint
        provider=ModelProvider('KOKORO');facts=[];permits=[]
        def grant(key,body):
            permits.append(key);return {'authorized':True,'binding':voice,'execution':{'credential':token}}
        engine=Executor({'KOKORO':provider},grant,lambda key,event:facts.append(event))
        try:
            with patch.dict(os.environ,{'LN_VOICE_ALLOWED_ENDPOINTS':endpoint}):
                original=request();engine.submit(original,'2');test_contract.ContractTest.wait(lambda:len(facts)==1)
                engine.submit(original,'2');self.assertEqual(['2'],permits)
                self.assertEqual('SUCCEEDED',facts[0]['state']);self.assertEqual('SELF_HOSTED',facts[0]['costSource'])
                self.assertNotIn(token,json.dumps(engine.status('2')))
                self.assertEqual(1,backend.synthesize.call_count)
                with self.assertRaises(ProviderFailure) as bad:provider.ready(voice,{'credential':'wrong'})
                self.assertEqual('VOICE_CREDENTIAL_INVALID',bad.exception.code)
                server.inference_slot.acquire()
                try:
                    with self.assertRaises(ProviderFailure) as busy:provider.synthesize('Hi',voice,time.time()+10,{'credential':token})
                    self.assertEqual('VOICE_QUEUE_FULL',busy.exception.code)
                finally:server.inference_slot.release()
                server.backend=None
                self.assertFalse(provider.ready(voice,{'credential':token}))
        finally:
            engine.close();server.shutdown();server.server_close();thread.join(3)

    def test_official_calls_reference_cleanup_and_kokoro_no_truncation(self):
        model=Mock();seen=[]
        def output(text,prompt,path,**kwargs):
            self.assertTrue(Path(path).is_file());self.assertIn('<|endofprompt|>',prompt)
            seen.append(Path(path));return iter([{'tts_speech':'tensor'}])
        model.inference_zero_shot.side_effect=output
        cosy=CosyVoiceBackend.__new__(CosyVoiceBackend);cosy.model=model;cosy.rate=24000
        voice=binding('COSYVOICE3');voice.update(referenceAssetId='3',referenceText='参考文本',parameters={'speed':1.2})
        native={'text':'你好','binding':voice,'deadlineAt':time.time()+10}
        with patch('ruoyi_voice.model_backends.reference_audio',return_value=b'checked-reference'), \
             patch('ruoyi_voice.model_backends.encode_chunks',side_effect=lambda chunks,rate,deadline:(list(chunks) or b'')[0]):
            self.assertEqual('tensor',cosy.synthesize(native))
        self.assertFalse(seen[0].exists())
        self.assertEqual(False,model.inference_zero_shot.call_args.kwargs['stream'])
        pipeline=Mock();pipeline.g2p.return_value=('x'*511,None)
        kokoro=KokoroBackend.__new__(KokoroBackend);kokoro.weights=Path('/controlled-model');kokoro.pipelines={'zh-CN':pipeline}
        with self.assertRaises(ProviderFailure):kokoro.synthesize(dict(native,binding=binding('KOKORO')))
        pipeline.assert_not_called()

    def test_reference_denies_arbitrary_host_before_download_and_checks_hash(self):
        voice=binding('COSYVOICE3');voice.update(referenceAssetId='3',referenceText='参考')
        data=FakeProvider().synthesize('fixture',{},time.time()+10)
        native={'binding':voice,'referenceAudioUrl':'https://untrusted.invalid/ref.wav',
                'referenceAudioSha256':hashlib.sha256(data).hexdigest(),'referenceAudioBytes':len(data)}
        with patch.dict(os.environ,{'LN_VOICE_REFERENCE_HOSTS':'trusted.invalid'}), \
             patch('urllib.request.build_opener') as opener:
            with self.assertRaises(ProviderFailure):reference_audio(native,time.time()+10)
            opener.assert_not_called()
            native['referenceAudioUrl']='https://trusted.invalid/ref.wav?short-lived=fixture'
            response=Mock();response.read1.side_effect=[data,b'']
            opener.return_value.open.return_value.__enter__.return_value=response
            self.assertEqual(data,reference_audio(native,time.time()+10))
            native['referenceAudioSha256']='0'*64;response.read1.side_effect=[data,b'']
            with self.assertRaises(ProviderFailure):reference_audio(native,time.time()+10)


if __name__=='__main__':unittest.main()
