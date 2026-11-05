const fs=require("fs"),vm=require("vm");
const root="F:/Java/AshVehicles/";
const html=fs.readFileSync(root+"tool/vehicle-editor.html","utf8");
const scripts=[...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m=>m[1]);
function proxy(){const f=function(){};return new Proxy(f,{get(t,k){if(k===Symbol.iterator)return function*(){};if(k===Symbol.toPrimitive)return h=>h==="number"?0:"";if(typeof k==="symbol")return undefined;return proxy();},set(){return true;},apply(){return proxy();},construct(){return proxy();}});}
const byId={out:{value:"",dataset:{}}};
const doc=new Proxy({},{get(t,k){if(k==="getElementById")return id=>byId[id]||(byId[id]=proxy());if(typeof k==="symbol")return undefined;return proxy();}});
const ctx={document:doc,console,setTimeout:()=>0,clearTimeout(){},requestAnimationFrame:()=>0,localStorage:{getItem:()=>null,setItem(){}},navigator:{language:"en"},location:{search:""},addEventListener(){},devicePixelRatio:1,matchMedia:()=>({matches:false,addEventListener(){}}),Image:function(){},FileReader:function(){},URL:{createObjectURL:()=>""},Blob:function(){},TextEncoder,TextDecoder,structuredClone,performance,getComputedStyle:()=>({getPropertyValue:()=>""})};
ctx.window=ctx;ctx.self=ctx;vm.createContext(ctx);
for(const s of scripts){try{vm.runInContext(s,ctx);}catch(e){}}
vm.runInContext("refresh=()=>{};draw=()=>{};say=t=>{};frameAll=()=>{};renderBones=()=>{};",ctx);
module.exports={ctx,vm,root,load(id){
  const def=JSON.parse(fs.readFileSync(root+`src/main/resources/data/ashvehicles/ashvehicles/aircraft/${id}.json`,"utf8"));
  const bb=JSON.parse(fs.readFileSync(root+`src/main/resources/assets/ashvehicles/bbmodel/entity/${id}.bbmodel`,"utf8"));
  ctx.__def=def;ctx.__bb=bb;
  vm.runInContext(`forgetMachine();state.scale=__def.model&&__def.model.scale||1;loadGeo(bbmodelToGeo(__bb).geo);takeJson(__def,"x.json");`,ctx);
  return def;
}};
if(require.main===module){
  const ids=fs.readdirSync(root+"src/main/resources/data/ashvehicles/ashvehicles/aircraft").map(f=>f.replace(".json",""));
  for(const id of ids){
    const def=module.exports.load(id);
    const r=vm.runInContext(`(()=>{const ext=extentOf(state.cubes);const bones=state.bones.map(b=>b.name);
      const interesting=bones.filter(n=>/pylon|hard|station|rail|canopy|cockpit|seat|pilot|glass|bay|gear|wing/i.test(n));
      const stations=ofKind("pylon").map(p=>[p.name,p.pos.map(n=>+n.toFixed(2)).join(","),p.fixed?"gun":p.station].join(" "));
      const seats=ofKind("seat").map(s=>{const e=eyeOf(s);return s.pos.map(n=>+n.toFixed(2)).join(",")+(e?" eye "+e.pos.map(n=>+n.toFixed(2)).join(","):"");});
      return JSON.stringify({ext:[ext.lo.map(n=>+n.toFixed(2)),ext.hi.map(n=>+n.toFixed(2))],bones:interesting,stations,seats});})()`,ctx);
    console.log(id,r);
  }
}
