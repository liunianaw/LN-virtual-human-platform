package com.ruoyi.session.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.voice.*;
import com.ruoyi.session.runtime.mapper.VoiceTaskMapper.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The sole attempt/fallback arbiter; bridge and HTTP execution share the same durable permits. */
@Service
public class VoiceOrchestrationServiceImpl implements IVoiceOrchestrationService
{
    private static final Logger LOG = LoggerFactory.getLogger(VoiceOrchestrationServiceImpl.class);
    private final VoiceAttemptStore store;
    private final VoiceExecutionClient client;
    private final TtsRuntimeAdapterRegistry bridges;
    private final RuntimeAuthorization access;
    private final TemporaryWavStorage audio;
    private final VoiceRuntimeProperties properties;
    private final VoiceExecutionPool pool;
    private final ObjectMapper json;
    private final Map<Long,Context> active=new ConcurrentHashMap<>();
    private static final Set<String> FALLBACK_CODES=Set.of("VOICE_PROVIDER_NOT_READY","VOICE_QUEUE_FULL","VOICE_AUDIO_INVALID","VOICE_PROVIDER_FAILED");
    public VoiceOrchestrationServiceImpl(VoiceAttemptStore store,VoiceExecutionClient client,TtsRuntimeAdapterRegistry bridges,
        RuntimeAuthorization access,TemporaryWavStorage audio,VoiceRuntimeProperties properties,VoiceExecutionPool pool,ObjectMapper json)
    { this.store=store; this.client=client; this.bridges=bridges; this.access=access; this.audio=audio; this.properties=properties; this.pool=pool; this.json=json; }

    @Override public void submit(RuntimeAuthorization.Grant grant,long epoch,TtsSynthesisWork work,TtsCompletionSink sink)
    {
        VoiceBinding binding=work.voice().execution()==null?client.binding(work.voice().voiceVersionId()):work.voice().execution();
        Task task=store.create(work.principal().accountId(),"BUSINESS",work.turnId()+":"+work.ordinal(),work.text(),binding,work,epoch,grant.id(),deadline());
        if(store.mapper().latest(task.id())!=null) return;
        Context context=new Context(work,sink,work.text());
        if(!register(task.id(),context)) return;
        try { start(store.createAttempt(task.id(),work.text(),false),context); }
        catch(RuntimeException error) { release(task.id(),context); throw error; }
    }
    private void release(long id,Context context) {
        active.computeIfPresent(id,(key,current)->current==context?null:current);
    }
    private boolean register(long id,Context context) {
        synchronized(active) {
            if(active.containsKey(id)) return false;
            if(active.size()>=128) throw VoiceAttemptStore.problem("VOICE_QUEUE_FULL");
            active.put(id,context); return true;
        }
    }
    private Instant deadline() { return Instant.now().plus(properties.getOfficial().getQueueTimeout()).plus(properties.getOfficial().getTimeout()); }

    @Override public byte[] audition(long administrator,long version,String key,String text)
    {
        if(key==null || !key.matches("[A-Za-z0-9._:-]{1,64}")) throw VoiceAttemptStore.problem("VOICE_PARAMETER_INVALID");
        VoiceBinding binding=client.binding(version).primaryOnly();
        Task task=store.create(administrator,"AUDITION",version+":"+key,text,binding,null,null,null,deadline());
        long taskId=task.id();
        Context registered=null;
        try {
            if(store.mapper().latest(taskId)==null) {
                Context context=new Context(null,null,text);
                if(register(taskId,context)) {
                    registered=context;
                    start(store.createAttempt(taskId,text,false),context);
                }
            }
            while(true) {
                task=store.mapper().task(taskId);
                if("SUCCEEDED".equals(task.status())) {
                    try { return audio.read(json.readValue(task.resultReference(),TemporaryAudioReference.class)); }
                    catch(Exception e) { throw VoiceAttemptStore.problem("VOICE_RESULT_EXPIRED"); }
                }
                if(Set.of("FAILED","CANCELLED","UNKNOWN").contains(task.status())) throw VoiceAttemptStore.problem(task.errorCode()==null?"VOICE_OUTCOME_UNKNOWN":task.errorCode());
                if(!Instant.now().isBefore(task.deadlineAt())) break;
                try { Thread.sleep(40); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw VoiceAttemptStore.problem("VOICE_OUTCOME_UNKNOWN"); }
            }
            store.recover(task); throw VoiceAttemptStore.problem("VOICE_OUTCOME_UNKNOWN");
        } finally {
            // Only the request that registered this context owns its release. Durable queries remain intact.
            if(registered!=null) release(taskId,registered);
        }
    }

    private void start(VoiceAttemptStore.Dispatch dispatch,Context context)
    {
        if(!dispatch.task().deadlineAt().isAfter(Instant.now())) { failLocal(dispatch,context,"CANCELLED","VOICE_CANCELLED","BEFORE_DISPATCH");return; }
        if(bridges.usesLegacyBridge(dispatch.binding().providerType())) { bridge(dispatch,context); return; }
        try { pool.execute(()-> {
            try {
                Task t=dispatch.task(); Attempt a=dispatch.attempt();
                client.submit(new VoiceProtocol.Request(1,Long.toString(t.id()),Long.toString(t.id()),Long.toString(a.id()),a.attemptNo(),a.requestHash(),
                    dispatch.binding().voiceVersionId(),dispatch.token(),context.text,t.inputHash(),t.inputCharCount(),t.deadlineAt(),maximum(t)));
            } catch(VoiceHttp.Failure e) {
                // Only explicit admission rejection proves that no dispatch permit was issued.
                boolean certain=Set.of("VOICE_QUEUE_FULL","VOICE_PROVIDER_NOT_READY","VOICE_PARAMETER_INVALID").contains(e.code);
                failLocal(dispatch,context,certain?"FAILED":"UNKNOWN",e.code,certain?"BEFORE_DISPATCH":"DISPATCHING");
            } catch(RuntimeException e) { failLocal(dispatch,context,"UNKNOWN","VOICE_OUTCOME_UNKNOWN","DISPATCHING"); }
        },code->failLocal(dispatch,context,"FAILED",code,"BEFORE_DISPATCH")); }
        catch(RuntimeException e) { failLocal(dispatch,context,"FAILED","VOICE_QUEUE_FULL","BEFORE_DISPATCH"); }
    }

    private void bridge(VoiceAttemptStore.Dispatch d,Context context)
    {
        VoiceRuntimeBinding binding=new VoiceRuntimeBinding(Long.parseLong(d.binding().voiceVersionId()),TtsProviderKind.OFFICIAL,
            d.binding().providerVoiceRef(),Long.parseLong(d.binding().serviceId()),d.binding().serviceRevision(),d.binding(),d.task().deadlineAt());
        Runnable permit=()-> { if(!authorize(d.attempt().id(),new VoiceProtocol.Permit(d.attempt().attemptNo(),"bridge",store.owner(),d.token())).authorized()) throw VoiceAttemptStore.problem("VOICE_CANCELLED"); };
        if(context.work==null) {
            // The legacy adapter owns its bounded pool; do not nest blocking work on that same pool.
            try { byte[] bytes=bridges.requireAdapter(binding).audition(binding,context.text,permit); acceptBytes(d.attempt().id(),localEvent(d,"SUCCEEDED",null,"DEFINITIVE",bytes),bytes); }
            catch(Exception e) { failLocal(d,context,"UNKNOWN","VOICE_OUTCOME_UNKNOWN","DISPATCHING"); }
            return;
        }
        var p=context.work.principal();
        var work=new TtsSynthesisWork(context.work.turnId(),context.work.generation(),context.work.segmentId(),context.work.ordinal(),context.text,
            new RuntimePrincipal(p.accountId(),p.applicationId(),p.sessionId(),p.snapshotId(),p.scopes(),binding));
        try { bridges.requireAdapter(binding).submit(work,new TtsCompletionSink() {
            public void beforeExternal(TtsSynthesisWork ignored) { permit.run(); }
            public AudioReadyResult onAudioReady(RuntimePrincipal ignored,AudioReadyInput input) {
                try {
                    byte[] bytes=audio.read(input.temporaryAudio());
                    acceptBytes(d.attempt().id(),localEvent(d,"SUCCEEDED",null,"DEFINITIVE",bytes),bytes);
                    return AudioReadyResult.ignored(); // The common arbiter stores/owns its own verified result.
                } catch(RuntimeException e) { throw e; }
            }
            public void onAudioFailed(RuntimePrincipal ignored,TtsSynthesisWork item,String code) {
                Attempt a=store.mapper().attempt(d.attempt().id());
                failLocal(d,context,"CREATED".equals(a.state())?"FAILED":"UNKNOWN",code,"CREATED".equals(a.state())?"BEFORE_DISPATCH":"DISPATCHING");
            }
        }); } catch(RuntimeException e) { failLocal(d,context,"FAILED","VOICE_QUEUE_FULL","BEFORE_DISPATCH"); }
    }

    @Override public VoiceProtocol.Authorized authorize(long id,VoiceProtocol.Permit permit)
    {
        if(permit==null || permit.dispatchToken()==null || permit.dispatchToken().length()>128 || permit.workerInstanceId()==null
            || !permit.workerInstanceId().matches("[A-Za-z0-9._:-]{1,64}") || permit.workerBootId()==null || !permit.workerBootId().matches("[A-Za-z0-9._:-]{1,64}"))
            throw VoiceAttemptStore.problem("VOICE_PARAMETER_INVALID");
        Attempt a=store.mapper().attempt(id);
        if(a==null) throw VoiceAttemptStore.problem("VOICE_NOT_AVAILABLE");
        Task t=store.mapper().task(a.taskId()); VoiceBinding binding=store.binding(t,a.attemptNo());
        // Network authorization completes before acquiring the short database permit transaction.
        if(t.grantId()!=null) access.verify(t.grantId());
        client.verify(binding);
        // Resolve before the permit transaction, so resolution failures cannot leave a submitted attempt.
        VoiceProtocol.Execution material=bridges.usesLegacyBridge(binding.providerType())?null:client.material(binding);
        boolean granted=store.authorize(id,permit);
        return new VoiceProtocol.Authorized(granted,binding,granted?material:null);
    }

    @Override public void event(long id,VoiceProtocol.Event event)
    {
        Attempt a=store.mapper().attempt(id);
        if(event==null || a==null || !Long.toString(id).equals(event.attemptId())
            || a.workerBootId()!=null && (!a.workerBootId().equals(event.workerBootId()) || !a.workerInstanceId().equals(event.workerInstanceId()))
            || a.workerBootId()==null && !("UNKNOWN".equals(event.state()) || Set.of("FAILED","CANCELLED").contains(event.state()) && "BEFORE_DISPATCH".equals(event.failureStage()))
            || !a.requestHash().equals(event.requestHash()) || a.attemptNo()!=event.taskRevision()) throw VoiceAttemptStore.problem("VOICE_REQUEST_CONFLICT");
        String old=store.mapper().eventHash(id,event.eventId());
        if(old!=null) { if(!old.equals(VoiceProtocol.hash(store.encode(event)))) throw VoiceAttemptStore.problem("VOICE_REQUEST_CONFLICT");return; }
        if("SUCCEEDED".equals(event.state())) {
            try { acceptBytes(id,event,client.audio(id,(int)maximum(store.mapper().task(a.taskId())))); }
            catch(RuntimeProblem invalid) {
                if(!"VOICE_AUDIO_INVALID".equals(invalid.code())) throw invalid;
                failure(id,new VoiceProtocol.Event(event.eventId(),event.attemptId(),event.taskRevision(),event.requestHash(),
                    event.workerInstanceId(),event.workerBootId(),"FAILED","VOICE_AUDIO_INVALID","DEFINITIVE",event.audio(),
                    event.providerRequestId(),event.costSource(),event.modelRevision()),event);
            }
        }
        else failure(id,event);
    }

    private void acceptBytes(long id,VoiceProtocol.Event event,byte[] bytes)
    {
        Attempt a=store.mapper().attempt(id); Task t=store.mapper().task(a.taskId());
        long duration;
        try { duration=WavAudio.requireMonoPcm16Khz(bytes,maximum(t)); }
        catch(IllegalArgumentException invalid) { throw VoiceAttemptStore.problem("VOICE_AUDIO_INVALID"); }
        VoiceProtocol.Audio meta=event.audio();
        if(meta==null || !VoiceProtocol.hash(bytes).equals(meta.sha256()) || meta.byteLength()!=bytes.length || meta.durationMs()!=duration
            || meta.sampleRateHz()!=24000 || meta.channels()!=1 || meta.sampleWidthBits()!=16 || !"PCM_S16LE".equals(meta.codec())
            || !"audio/wav".equals(meta.mimeType()) || !a.modelRevision().equals(event.modelRevision())) throw VoiceAttemptStore.problem("VOICE_AUDIO_INVALID");
        TemporaryWavStorage.StoredWav stored=audio.store(bytes);
        boolean winner=false,retained=false;
        try {
            winner=store.success(id,event,stored.reference());
            if(!winner) return;
            Context context=active.remove(t.id());
            if("AUDITION".equals(t.purpose())) { retained=true; return; }
            if(context!=null && context.work!=null) {
                var w=context.work;
                try {
                    retained=context.sink.onAudioReady(w.principal(),new AudioReadyInput(w.turnId(),w.generation(),w.segmentId(),w.ordinal(),
                        "audio/wav",stored.durationMs(),stored.bytes(),stored.reference(),a.attemptNo()==2,displayName(store.binding(t,a.attemptNo())),
                        a.attemptNo()==2?store.mapper().attempts(t.id()).get(0).errorCode():"")).accepted();
                } catch(RuntimeException deliveryFailure) {
                    // Synthesis and settlement succeeded. End the playback turn without releasing its fee.
                    LOG.warn("Voice audio delivery failed taskId={} attemptId={}", t.id(), id, deliveryFailure);
                    context.sink.onAudioFailed(w.principal(),w,"VOICE_AUDIO_DELIVERY_FAILED");
                }
            }
        } finally {
            if(!retained) try { audio.delete(stored.reference()); } catch(RuntimeException ignored) { }
        }
    }

    private void failure(long id,VoiceProtocol.Event event)
    { failure(id,event,event); }
    private void failure(long id,VoiceProtocol.Event event,VoiceProtocol.Event received)
    {
        Attempt a=store.mapper().attempt(id); Task t=store.mapper().task(a.taskId());
        VoiceBinding full=store.fullBinding(t); Context context=active.get(t.id());
        boolean fallback="BUSINESS".equals(t.purpose()) && a.attemptNo()==1 && full.fallback()!=null && full.allowVoiceChange()
            && "FAILED".equals(event.state()) && FALLBACK_CODES.contains(event.errorCode())
            && Set.of("BEFORE_DISPATCH","DEFINITIVE").contains(event.failureStage()) && context!=null;
        if(!store.failure(id,event,fallback,received)) return;
        if(fallback) {
            try { start(store.createAttempt(t.id(),context.text,true),context); return; }
            catch(RuntimeException error) { store.recover(store.mapper().task(t.id())); }
        }
        context=active.remove(t.id());
        if(context!=null && context.work!=null) context.sink.onAudioFailed(context.work.principal(),context.work,event.errorCode()==null?"VOICE_OUTCOME_UNKNOWN":event.errorCode());
    }
    private void failLocal(VoiceAttemptStore.Dispatch d,Context context,String state,String code,String stage)
    { failure(d.attempt().id(),localEvent(d,state,code,stage,null)); }
    private VoiceProtocol.Event localEvent(VoiceAttemptStore.Dispatch d,String state,String code,String stage,byte[] bytes)
    {
        Attempt a=store.mapper().attempt(d.attempt().id());
        VoiceProtocol.Audio meta=bytes==null?null:new VoiceProtocol.Audio("audio/wav","PCM_S16LE",24000,1,16,bytes.length,WavAudio.requireMonoPcm16Khz(bytes,maximum(d.task())),VoiceProtocol.hash(bytes));
        return new VoiceProtocol.Event(UUID.randomUUID().toString(),Long.toString(a.id()),a.attemptNo(),a.requestHash(),
            a.workerInstanceId(),a.workerBootId(),state,code,stage,meta,null,"UNKNOWN",a.modelRevision());
    }
    private String displayName(VoiceBinding binding) {
        return new VoiceCatalog().require(binding.providerType(),binding.modelId()).voices().stream()
            .filter(v->v.id().equals(binding.providerVoiceRef())).map(v->v.displayName()).findFirst().orElse("参考音色");
    }
    private long maximum(Task task) { return "AUDITION".equals(task.purpose())?Math.min(1048576,properties.getMaxAudioBytes()):properties.getMaxAudioBytes(); }
    @Scheduled(fixedDelay=5000) public void recover()
    {
        for(Attempt pending:store.mapper().queries()) {
            if(bridges.usesLegacyBridge(pending.providerType()) || store.mapper().claimQuery(pending.id())!=1) continue;
            try { pool.execute(()-> {
                try { VoiceProtocol.Event result=client.status(pending.id()); if(result!=null) event(pending.id(),result); }
                catch(RuntimeException ignored) { /* Missing process state is not proof of no synthesis. */ }
            },code->{}); } catch(RuntimeException ignored) { }
        }
        for(Task task:store.mapper().recoverable(store.owner())) {
            store.recover(task);
            Attempt attempt=store.mapper().latest(task.id());
            if(attempt!=null && !bridges.usesLegacyBridge(attempt.providerType())) try { pool.execute(()->{ try { client.cancel(attempt.id()); } catch(RuntimeException ignored) { } },code->{}); } catch(RuntimeException ignored) { }
            Context context=active.remove(task.id());
            if(context!=null && context.work!=null) context.sink.onAudioFailed(context.work.principal(),context.work,"VOICE_OUTCOME_UNKNOWN");
        }
    }
    @Override public List<Map<String,Object>> diagnostics(Long account)
    {
        return store.mapper().diagnostics(account).stream().map(this::diagnosticView).toList();
    }
    @Override public Map<String,Object> pageDiagnostics(Long account,int pageNum,int pageSize,Long taskId,String status)
    {
        if(pageNum<1 || pageSize<1 || pageSize>100 || (long)(pageNum-1)*pageSize>Integer.MAX_VALUE
            || (account!=null && account<=0) || (taskId!=null && taskId<=0))
            throw new RuntimeProblem(org.springframework.http.HttpStatus.BAD_REQUEST,"INVALID_PAGE","分页参数无效");
        String filter=status==null || status.isBlank()?null:status.trim();
        if(filter!=null && !filter.matches("[A-Z_]{1,32}"))
            throw new RuntimeProblem(org.springframework.http.HttpStatus.BAD_REQUEST,"INVALID_FILTER","状态筛选无效");
        return Map.of("items",store.mapper().pageDiagnostics(account,taskId,filter,pageSize,(pageNum-1)*pageSize)
            .stream().map(this::diagnosticView).toList(),"total",store.mapper().countDiagnostics(account,taskId,filter),
            "pageNum",pageNum,"pageSize",pageSize);
    }
    private Map<String,Object> diagnosticView(com.ruoyi.session.runtime.mapper.VoiceTaskMapper.Task t)
    {
            Map<String,Object> out=new LinkedHashMap<>(); out.put("taskId",Long.toString(t.id()));out.put("purpose",t.purpose());out.put("status",t.status());
            out.put("voiceVersionId",Long.toString(t.voiceVersionId()));out.put("errorCode",t.errorCode());out.put("winnerAttemptId",t.winnerAttemptId()==null?null:Long.toString(t.winnerAttemptId()));
            out.put("factDeliveryReview",store.mapper().deliveryReview(t.id())>0);
            out.put("settlement",t.operationId()==null?"NOT_BILLED":store.mapper().settlement(t.id()));
            out.put("degraded",t.winnerAttemptId()!=null && store.mapper().attempt(t.winnerAttemptId()).attemptNo()==2);
            out.put("attempts",store.mapper().attempts(t.id()).stream().map(a->Map.of("attemptId",Long.toString(a.id()),"providerType",a.providerType(),"state",a.state(),"reasonCode",a.errorCode()==null?"":a.errorCode(),"costSource",a.costSource())).toList());return out;
    }
    private record Context(TtsSynthesisWork work,TtsCompletionSink sink,String text) { }
}
