package com.ruoyi.session.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.voice.*;
import com.ruoyi.session.runtime.mapper.VoiceTaskMapper;
import com.ruoyi.session.runtime.mapper.VoiceTaskMapper.*;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Short database transactions only. The operation remains the single billing authority. */
@Component
public class VoiceAttemptStore
{
    private final VoiceTaskMapper mapper;
    private final ObjectMapper json;
    private final String owner=UUID.randomUUID().toString();
    public String owner() { return owner; }
    public VoiceAttemptStore(VoiceTaskMapper mapper,ObjectMapper json) { this.mapper=mapper; this.json=json; }
    public VoiceTaskMapper mapper() { return mapper; }
    public String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(e); } }
    public VoiceBinding binding(Task task,int number)
    { try { var value=json.readValue(task.bindingSnapshot(),VoiceBinding.class); return number==1?value.primaryOnly():value.fallback(); }
      catch(Exception e) { throw problem("VOICE_BINDING_UNAVAILABLE"); } }
    public VoiceBinding fullBinding(Task task)
    { try { return json.readValue(task.bindingSnapshot(),VoiceBinding.class); } catch(Exception e) { throw problem("VOICE_BINDING_UNAVAILABLE"); } }

    private Attempt lockedAttempt(long id) {
        Attempt candidate=mapper.attempt(id);
        if(candidate==null) throw problem("VOICE_NOT_AVAILABLE");
        mapper.task(candidate.taskId()); // All mutations lock task before attempt, including recovery.
        return mapper.attemptForUpdate(id);
    }

    @Transactional public Task create(long account,String purpose,String source,String text,VoiceBinding binding,
        TtsSynthesisWork work,Long epoch,Long grant,Instant deadline)
    {
        if(account<=0 || text==null || text.isBlank() || text.codePointCount(0,text.length())>200 || binding==null)
            throw problem("VOICE_PARAMETER_INVALID");
        String hash=VoiceProtocol.hash(encode(binding)+"\n"+VoiceProtocol.hash(text));
        Task old=mapper.source(account,purpose,source);
        if(old!=null) { if(!old.requestHash().equals(hash)) throw problem("VOICE_REQUEST_CONFLICT"); return old; }
        Long operation=work==null?null:mapper.operation(account,work.principal().sessionId(),Long.parseLong(work.turnId()),work.ordinal());
        if(work!=null && operation==null) throw problem("VOICE_CANCELLED");
        var task=new Task(mapper.nextId(),account,purpose,source,hash,Long.parseLong(binding.voiceVersionId()),encode(binding),binding.policyVersion(),
            "QUEUED",1,null,work==null?null:work.principal().sessionId(),work==null?null:Long.parseLong(work.turnId()),operation,epoch,
            work==null?null:work.generation(),grant,owner,null,deadline,VoiceProtocol.hash(text),text.codePointCount(0,text.length()),null,null);
        mapper.insertTask(task);
        return task;
    }

    @Transactional public Dispatch createAttempt(long taskId,String text,boolean fallback)
    {
        Task task=mapper.task(taskId);
        if(task==null || !owner.equals(task.owner()) || !task.deadlineAt().isAfter(Instant.now()) || task.cancelRequestedAt()!=null)
            throw problem("VOICE_CANCELLED");
        if(fallback && mapper.fallback(taskId)!=1) throw problem("VOICE_CANCELLED");
        int number=fallback?2:1;
        Attempt old=mapper.latest(taskId);
        if(old!=null && old.attemptNo()>=number) throw problem("VOICE_REQUEST_CONFLICT");
        VoiceBinding binding=binding(task,number);
        if(binding==null || !task.inputHash().equals(VoiceProtocol.hash(text))) throw problem("VOICE_REQUEST_CONFLICT");
        long id=mapper.nextId();
        String token=UUID.randomUUID()+"-"+UUID.randomUUID();
        String hash=VoiceProtocol.hash(task.id()+"\n"+id+"\n"+binding.voiceVersionId()+"\n"+task.inputHash());
        var attempt=new Attempt(id,taskId,number,binding.providerType(),Long.parseLong(binding.serviceId()),Long.parseLong(binding.voiceVersionId()),
            binding.modelRevision(),binding.capabilityVersion(),hash,VoiceProtocol.hash(token),"CREATED",null,null,1,task.deadlineAt(),null,null,null,null,"UNKNOWN");
        mapper.insertAttempt(attempt);
        return new Dispatch(mapper.task(taskId),attempt,token,binding);
    }

    @Transactional public boolean authorize(long id,VoiceProtocol.Permit permit)
    {
        Attempt a=lockedAttempt(id);
        if(a==null) throw problem("VOICE_NOT_AVAILABLE");
        Task task=mapper.task(a.taskId());
        if(task.revision()!=permit.taskRevision()) throw problem("VOICE_REQUEST_CONFLICT");
        int changed=mapper.authorize(id,VoiceProtocol.hash(permit.dispatchToken()),permit.taskRevision(),permit.workerInstanceId(),permit.workerBootId());
        if(changed==1) { mapper.running(task.id());mapper.dispatchOperation(task.id()); }
        return changed==1;
    }

    @Transactional public boolean success(long id,VoiceProtocol.Event event,TemporaryAudioReference reference)
    {
        Attempt a=lockedAttempt(id); Task t=mapper.task(a.taskId());
        if(!record(a,t,event)) return false;
        if(!Set.of("DISPATCHING","ACCEPTED","UNKNOWN").contains(a.state())) throw problem("VOICE_REQUEST_CONFLICT");
        mapper.finishAttempt(id,"SUCCEEDED",null,event.failureStage(),encode(event.audio()),event.providerRequestId(),event.costSource());
        mapper.fact(id,event.eventId());
        boolean won=mapper.winner(t.id(),id,encode(reference))==1;
        if(won) mapper.settle(t.id(),"SETTLE");
        return won;
    }

    @Transactional public boolean failure(long id,VoiceProtocol.Event event,boolean keepForFallback)
    { return failure(id,event,keepForFallback,event); }

    @Transactional public boolean failure(long id,VoiceProtocol.Event event,boolean keepForFallback,VoiceProtocol.Event received)
    {
        Attempt a=lockedAttempt(id); Task t=mapper.task(a.taskId());
        if(!record(a,t,received)) return false;
        if(!Set.of("FAILED","CANCELLED","UNKNOWN").contains(event.state())) throw problem("VOICE_PARAMETER_INVALID");
        if(mapper.finishAttempt(id,event.state(),event.errorCode(),event.failureStage(),"null",event.providerRequestId(),event.costSource())!=1) return false;
        mapper.fact(id,received.eventId());
        if(!keepForFallback) {
            mapper.finishTask(t.id(),event.state(),event.errorCode());
            mapper.settle(t.id(),"UNKNOWN".equals(event.state())?"REVIEW":"RELEASE");
        }
        return true;
    }

    private boolean record(Attempt a,Task t,VoiceProtocol.Event e)
    {
        if(a==null || !Long.toString(a.id()).equals(e.attemptId()) || !a.requestHash().equals(e.requestHash())
            || a.attemptNo()!=e.taskRevision() || e.eventId()==null || !e.eventId().matches("[A-Za-z0-9._:-]{1,64}")
            || e.providerRequestId()!=null && e.providerRequestId().length()>128
            || e.failureStage()==null || !Set.of("BEFORE_DISPATCH","DISPATCHING","DEFINITIVE","RECOVERY").contains(e.failureStage())
            || e.costSource()==null || !Set.of("TEST","UNKNOWN","PROVIDER","SELF_HOSTED").contains(e.costSource())
            || e.errorCode()!=null && !e.errorCode().matches("[A-Z0-9_]{1,64}")) throw problem("VOICE_REQUEST_CONFLICT");
        if(a.workerBootId()!=null && (!a.workerBootId().equals(e.workerBootId()) || !a.workerInstanceId().equals(e.workerInstanceId())))
            throw problem("VOICE_REQUEST_CONFLICT");
        String hash=VoiceProtocol.hash(encode(e)), old=mapper.eventHash(a.id(),e.eventId());
        if(old!=null) { if(!old.equals(hash)) throw problem("VOICE_REQUEST_CONFLICT"); return false; }
        mapper.event(a.id(),e.eventId(),hash); return true;
    }

    @Transactional public void recover(Task task)
    {
        Task current=mapper.task(task.id());
        if(!Set.of("QUEUED","RUNNING").contains(current.status())) return;
        mapper.cancel(task.id());
        Attempt a=mapper.latest(task.id());
        boolean unknown=a!=null && Set.of("DISPATCHING","ACCEPTED","UNKNOWN").contains(a.state());
        if(a!=null) mapper.finishAttempt(a.id(),unknown?"UNKNOWN":"CANCELLED",unknown?"VOICE_OUTCOME_UNKNOWN":"VOICE_CANCELLED","RECOVERY","null",null,"UNKNOWN");
        if(a!=null) mapper.fact(a.id(),UUID.randomUUID().toString());
        mapper.finishTask(task.id(),unknown?"UNKNOWN":"CANCELLED",unknown?"VOICE_OUTCOME_UNKNOWN":"VOICE_CANCELLED");
        mapper.settle(task.id(),unknown?"REVIEW":"RELEASE");
    }
    public record Dispatch(Task task,Attempt attempt,String token,VoiceBinding binding) { }
    public static RuntimeProblem problem(String code) { return new RuntimeProblem(HttpStatus.CONFLICT,code,code); }
}
