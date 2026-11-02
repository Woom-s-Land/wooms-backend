#!/usr/bin/env node
// STOMP chat benchmark using Node 22's built-in WebSocket implementation.
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { monitorEventLoopDelay } from 'node:perf_hooks';

const defaults = { url: null, connections: 10, roomSize: 5,
  rate: 100, warmup: 5, duration: 15, drain: 10, connectTimeout: 15, output: null };
function options(argv) {
  const o = { ...defaults };
  for (let i=0; i<argv.length; i++) {
    const k = argv[i];
    if (k === '--help' || k === '-h') {
      console.log('Usage: node tools/stomp-benchmark/bench.mjs --url ws://host:port/ws [--connections N] [--room-size N] [--rate N] [--warmup SEC] [--duration SEC] [--drain SEC] [--connect-timeout SEC] [--output FILE]');
      process.exit(0);
    }
    if (!k.startsWith('--') || i+1 >= argv.length) throw new Error(`invalid option: ${k}`);
    const name = k.slice(2).replace(/-([a-z])/g, (_,c)=>c.toUpperCase());
    if (!(name in o)) throw new Error(`unknown option: ${k}`);
    const v = argv[++i];
    o[name] = ['url','output'].includes(name) ? v : Number(v);
    if (!['url','output'].includes(name) && !Number.isFinite(o[name])) throw new Error(`invalid numeric value for ${k}`);
  }
  if (!o.url || o.connections < 2 || o.roomSize < 1 || o.rate <= 0 || o.warmup < 0 || o.duration <= 0 || o.drain < 0) throw new Error('url is required; numeric options must be positive (warmup/drain may be zero)');
  return o;
}
const nul = '\0';
function frame(command, headers={}, body='') { return `${command}\n${Object.entries(headers).map(([k,v])=>`${k}:${v}\n`).join('')}\n${body}${nul}`; }
function parseFrames(state, chunk) {
  state.buffer += chunk;
  const frames=[];
  for (;;) {
    const p=state.buffer.indexOf(nul); if(p<0) break;
    const raw=state.buffer.slice(0,p); state.buffer=state.buffer.slice(p+1);
    const text=raw.replace(/^\r?\n+/, ''); if(!text) continue;
    const cut=text.indexOf('\n\n'); const head=cut<0?text:text.slice(0,cut), body=cut<0?'':text.slice(cut+2);
    const lines=head.split(/\r?\n/), headers={};
    for(const line of lines.slice(1)){const ix=line.indexOf(':');if(ix>=0)headers[line.slice(0,ix)]=line.slice(ix+1);}
    frames.push({command:lines[0],headers,body});
  }
  return frames;
}
function quantile(values, q) { if(!values.length)return null; values.sort((a,b)=>a-b); return Number(values[Math.min(values.length-1,Math.ceil(q*values.length)-1)].toFixed(3)); }
function stats(values){return {p50:quantile([...values],.5),p95:quantile([...values],.95),p99:quantile([...values],.99)};}
function sendText(ws, text, errors, who) { if(ws.readyState!==WebSocket.OPEN){errors.push(`${who}: send while socket not open`);return false;} try{ws.send(text);return true;}catch(e){errors.push(`${who}: send failed: ${e.message}`);return false;} }

async function main(){
  const o=options(process.argv.slice(2)), runId=randomUUID(), errors=[], clients=[], rooms=new Map();
  const roomCount=Math.ceil(o.connections/o.roomSize);
  for(let i=0;i<o.connections;i++){
    const room=Math.floor(i/o.roomSize); if(!rooms.has(room))rooms.set(room,randomUUID());
    clients.push({id:i,room:rooms.get(room),ws:null,buffer:'',subscribed:false,closed:null,queue:[],probeWait:null});
  }
  const waitFrame=(c,predicate,timeout)=>new Promise((resolve,reject)=>{
    const end=performance.now()+timeout*1000;
    const tick=()=>{for(let i=0;i<c.queue.length;i++)if(predicate(c.queue[i])){const [f]=c.queue.splice(i,1);resolve(f);return;} if(performance.now()>=end){reject(new Error(`timeout waiting for frame on client ${c.id}`));return;}setTimeout(tick,5);};tick();
  });
  try {
    for(const c of clients){
      const ws=new WebSocket(o.url); c.ws=ws;
      ws.addEventListener('message',ev=>{for(const f of parseFrames(c,typeof ev.data==='string'?ev.data:String(ev.data))) c.onFrame?.(f);});
      ws.addEventListener('close',ev=>{c.closed={code:ev.code,reason:ev.reason||''};errors.push(`client ${c.id}: connection closed ${ev.code} ${ev.reason||''}`);});
      ws.addEventListener('error',()=>errors.push(`client ${c.id}: websocket error`));
      await new Promise((resolve,reject)=>{const t=setTimeout(()=>reject(new Error(`client ${c.id} open timeout`)),o.connectTimeout*1000);ws.addEventListener('open',()=>{clearTimeout(t);resolve();},{once:true});ws.addEventListener('error',()=>{clearTimeout(t);reject(new Error(`client ${c.id} open failed`));},{once:true});});
      c.onFrame=f=>c.queue.push(f);
      ws.send(frame('CONNECT',{'accept-version':'1.2','heart-beat':'0,0'}));
      await waitFrame(c,f=>f.command==='CONNECTED',o.connectTimeout);
      ws.send(frame('SUBSCRIBE',{id:`sub-${c.id}`,destination:`/ws/wooms/chat/${c.room}`,ack:'auto'}));c.subscribed=true;
      c.onFrame=f=>c.handleFrame(f);
    }
    const messages=new Map(), cohort=new Set(), measuredLat=[];
    const counters={windowDelivery:0,allDelivery:0,duplicates:0};
    for(const c of clients){c.handleFrame=f=>{
      if(f.command==='ERROR'){errors.push(`client ${c.id}: STOMP ERROR ${f.body.slice(0,300)}`);return;}
      if(f.command!=='MESSAGE')return;
      try{
        const outer=JSON.parse(f.body), payload=JSON.parse(outer.content); if(payload.runId!==runId)return;
        if(typeof payload.sequence==='string'&&payload.sequence.startsWith('probe-')){if(payload.publisherId===c.id)c.probeWait?.();return;}
        const m=messages.get(payload.sequence); if(!m)return;
        const now=performance.now(), key=c.id;
        if(m.recipients.has(key)){if(m.cohort)counters.duplicates++;return;}
        m.recipients.add(key);
        if(m.cohort){measuredLat.push(now-payload.sentMs);if(now>=measurementStart&&now<measurementEnd)counters.windowDelivery++;}
      }catch(e){errors.push(`client ${c.id}: invalid MESSAGE: ${e.message}`);}
    };}
    // Each connection sends a unique probe and waits for its own subscription to receive it.
    for(const c of clients){
      await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error(`probe timeout client ${c.id}`)),o.connectTimeout*1000);c.probeWait=()=>{clearTimeout(timer);c.probeWait=null;resolve();};
        const content=JSON.stringify({runId,sequence:`probe-${c.id}`,publisherId:c.id,sentMs:performance.now()});
        c.ws.send(frame('SEND',{destination:`/ws/send/chat/${c.room}`,'content-type':'text/plain;charset=UTF-8'},JSON.stringify({content,nickname:`bench-${c.id}`})));
      });
    }
    const begin=performance.now(), measurementStart=begin+o.warmup*1000, measurementEnd=measurementStart+o.duration*1000;
    const loopMonitor=monitorEventLoopDelay({resolution:10});loopMonitor.enable();
    // Rate paced with timer budget and monotonic deadlines; only actual sends are recorded.
    const interval=1000/o.rate, end=begin+(o.warmup+o.duration)*1000;
    let next=begin, seq=0, ingress=0, lag=[], sendErrors=0, bufferedAmountMax=0;
    while(performance.now()<end){
      let sentThisTick=0; const tickStart=performance.now();
      while(performance.now()>=next&&sentThisTick<100&&performance.now()-tickStart<2&&performance.now()<end){
        const sentAt=performance.now(), idx=seq%clients.length, c=clients[idx], msgSeq=seq++;
        lag.push(Math.max(0,sentAt-next));
        const isCohort=sentAt>=measurementStart&&sentAt<measurementEnd;
        const record={recipients:new Set(),cohort:isCohort};
        const content=JSON.stringify({runId,sequence:msgSeq,publisherId:c.id,sentMs:sentAt});
        const ok=sendText(c.ws,frame('SEND',{destination:`/ws/send/chat/${c.room}`,'content-type':'text/plain;charset=UTF-8'},JSON.stringify({content,nickname:`bench-${c.id}`})),errors,`client ${c.id}`);
        bufferedAmountMax=Math.max(bufferedAmountMax,c.ws?.bufferedAmount||0);
        if(ok&&isCohort){ingress++;messages.set(msgSeq,record);cohort.add(msgSeq);} else if(!ok)sendErrors++;
        next+=interval; sentThisTick++;
      }
      await new Promise(r=>setTimeout(r,1));
    }
    const sentEnd=performance.now(); await new Promise(r=>setTimeout(r,o.drain*1000));loopMonitor.disable();
    const expected=[...cohort].reduce((n,s)=>n+clients.filter(c=>c.room===clients[s%clients.length].room).length,0);
    const uniqueAfterDrain=[...cohort].reduce((n,s)=>n+(messages.get(s)?.recipients.size||0),0);
    const missingAfterDrain=Math.max(0,expected-uniqueAfterDrain);
    const windowExpected=[...cohort].reduce((n,s)=>n+clients.filter(c=>c.room===clients[s%clients.length].room).length,0);
    const sched=lag;
    const eventLoop={minMs:Number((loopMonitor.min/1e6).toFixed(3)),maxMs:Number((loopMonitor.max/1e6).toFixed(3)),meanMs:Number((loopMonitor.mean/1e6).toFixed(3)),p50Ms:Number((loopMonitor.percentile(50)/1e6).toFixed(3)),p95Ms:Number((loopMonitor.percentile(95)/1e6).toFixed(3)),p99Ms:Number((loopMonitor.percentile(99)/1e6).toFixed(3)),samples:loopMonitor.count};
    const result={runId,url:o.url,anonymous:true,clock:{name:'performance.now()',monotonic:true},connections:o.connections,probeCompletedConnections:clients.filter(c=>c.subscribed).length,liveConnectionsAtReport:clients.filter(c=>c.ws?.readyState===WebSocket.OPEN).length,roomSize:o.roomSize,rooms:roomCount,targetIngressMsgPerSec:o.rate,warmupSeconds:o.warmup,measureSeconds:o.duration,drainSeconds:o.drain,actualSendWindowSeconds:Number(((sentEnd-measurementStart)/1000).toFixed(3)),ingressMessages:ingress,ingressMsgPerSec:Number((ingress/o.duration).toFixed(3)),expectedFanoutDeliveries:expected,measurementWindowUniqueDeliveries:counters.windowDelivery,measurementWindowFanoutDeliveriesPerSec:Number((counters.windowDelivery/o.duration).toFixed(3)),windowExpectedDeliveries:windowExpected,drainCompleteUniqueReceived:uniqueAfterDrain,missingAfterDrain,duplicatesAfterDrain:counters.duplicates,errors,sendErrors,connectionClose:clients.filter(c=>c.closed).map(c=>({clientId:c.id,...c.closed})),measurementCohortLatencyMs:stats(measuredLat),senderScheduleLagMs:{max:lag.reduce((m,v)=>Math.max(m,v),0),...stats(sched)},generator:{eventLoopLagMs:eventLoop,bufferedAmountMax}};
    const text=JSON.stringify(result,null,2);console.log(text);if(o.output)await writeFile(o.output,text+'\n','utf8');
  } finally { for(const c of clients){try{if(c.ws&&c.ws.readyState===WebSocket.OPEN)c.ws.close(1000,'benchmark complete');}catch{}} }
}
main().catch(e=>{console.error(e.stack||e);process.exitCode=1;});
