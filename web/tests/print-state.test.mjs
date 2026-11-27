import test from "node:test";
import assert from "node:assert/strict";
import {automaticPrintOnce,confirmPrintResult,printState} from "../src/print-state.js";
function storage(){const entries=new Map();return {getItem:key=>entries.get(key)||null,setItem:(k,v)=>entries.set(k,v)}}
function printer(settings={configured:true,mode:"accept"}){let calls=0;return {printerSettings:()=>settings,printDirect:()=>{calls++;return true},calls:()=>calls}}
test("Confirmed native print does not repeat on refresh",()=>{
 const st=storage(),bridge=printer(),order={id:"receipt-1"};
 assert.equal(automaticPrintOnce(order,"accept",bridge,()=>"<h1>test</h1>",st),true);
 assert.equal(printState(order.id,"accept",st),"pending");
 assert.equal(automaticPrintOnce(order,"accept",bridge,()=>"",st),false);
 assert.equal(confirmPrintResult({id:order.id,ok:true,message:"Fila confirmada"},st),true);
 assert.equal(printState(order.id,"accept",st),"confirmed");
 assert.equal(automaticPrintOnce(order,"accept",bridge,()=>"",st),false);
 assert.equal(bridge.calls(),1);
});
test("Failure cannot automatically generate a duplicate receipt",()=>{
 const st=storage(),bridge=printer(),order={id:"receipt-failed"};
 automaticPrintOnce(order,"accept",bridge,()=>"<p>test</p>",st);
 confirmPrintResult({id:order.id,ok:false,message:"Impressora offline"},st);
 assert.equal(printState(order.id,"accept",st),"failed");
 assert.equal(automaticPrintOnce(order,"accept",bridge,()=>"",st),false);
 assert.equal(bridge.calls(),1);
});
test("Printer unconfigured: no marker",()=>{
 const st=storage(),bridge=printer({configured:false,mode:"accept"});
 assert.equal(automaticPrintOnce({id:"no-printer"},"accept",bridge,()=>"",st),false);
 assert.equal(printState("no-printer","accept",st),"");
});
test("Pending result after process restart never auto-repeats",()=>{
 const st=storage(),bridge=printer(),order={id:"uncertain"};
 automaticPrintOnce(order,"accept",bridge,()=>"<p>test</p>",st);
 assert.equal(automaticPrintOnce(order,"accept",bridge,()=>"",st),false);
 assert.equal(bridge.calls(),1);
});
test("Legacy markers remain protected",()=>{
 const st=storage(),bridge=printer();
 st.setItem("gestor-print-accept-legacy","111");
 assert.equal(automaticPrintOnce({id:"legacy"},"accept",bridge,()=>"",st),false);
});
