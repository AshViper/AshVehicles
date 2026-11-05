// Finish a definition in the vehicle editor: station heights from the skin, the chase camera, raw hit boxes.
// usage: node build3.js <id>   (reads <id>.draft.json, writes <id>.out.json)
const A = require("./harness.js"); const { ctx, vm } = A; const fs = require("fs");
const dir = __dirname.replace(/\\/g, "/") + "/";
const id = process.argv[2];
ctx.__def = JSON.parse(fs.readFileSync(dir + id + ".draft.json", "utf8"));
ctx.__extra = JSON.parse(fs.readFileSync(dir + id + ".buildroles.json", "utf8"));
ctx.__bb = JSON.parse(fs.readFileSync(A.root + "src/main/resources/assets/ashvehicles/bbmodel/entity/" + id + ".bbmodel", "utf8"));
const r = vm.runInContext(`(()=>{
  forgetMachine(); state.scale = __def.model.scale; loadGeo(bbmodelToGeo(__bb).geo); takeJson(__def, "${id}.json");
  Object.assign(state.roles, __extra);             // doors with no role of their own stay out of the boxes
  const moving = Object.values(state.roles || {}).flat();
  function skinAt(x, z) { let lo = null, hi = null;
    for (const c of state.cubes) { if (moving.includes(c.bone)) continue;
      let x0=1e9,x1=-1e9,z0=1e9,z1=-1e9,y0=1e9,y1=-1e9;
      for (const p of c.pts) { x0=Math.min(x0,p[0]);x1=Math.max(x1,p[0]);z0=Math.min(z0,p[2]);z1=Math.max(z1,p[2]);y0=Math.min(y0,p[1]);y1=Math.max(y1,p[1]); }
      if (x<x0-0.05||x>x1+0.05||z<z0-0.05||z>z1+0.05) continue; if (lo===null||y0<lo) lo=y0; if (hi===null||y1>hi) hi=y1; }
    return [lo, hi]; }
  const log = [];
  for (const p of ofKind("pylon")) log.push(p.name + " y " + p.pos[1]);   // heights measured from sampled faces
  const eye = point("cockpit").pos.slice();
  autoCameras();
  point("cockpit").pos = eye;                       // keep the eye the draft put under the canopy
  state.auto.raw = true; state.auto.skip = true; state.auto.minCube = 0.25;
  const plan = planBoxes();
  const items = state.items.filter(i => i.kind !== "box");
  const made = plan.boxes.map(b => { const nb = newBox(b.name, b.offset.slice(), b.size.slice());
    Object.assign(nb, JSON.parse(JSON.stringify(b)), { id: nb.id, kind: "box" }); return nb; });
  state.items = items.concat(made);
  for (const k of Object.keys(__extra)) delete state.roles[k];
  const ratio = boxToModelRatio(ofKind("box"));
  exportMachine();
  return { log, chase: point("chase").pos, n: plan.boxes.length, covered: plan.covered, ratio, text: out().value };
})()`, ctx);
console.log(r.log.join("\n"));
console.log("chase", r.chase, "boxes", r.n, "covered", r.covered, "ratio", r.ratio);
fs.writeFileSync(dir + id + ".out.json", r.text);
