// Finish the Harrier in the vehicle editor: chase camera, MFDs from the model, raw hit boxes.
// Station heights come from build_av8b.py (measured on faces; the swept wing cubes fool a bounding-box probe).
// usage: node build_av8b.js <draft.json> <bundled.bbmodel> <out.json>
const A = require("./harness.js"); const { ctx, vm } = A; const fs = require("fs");
const [draft, model, outPath] = process.argv.slice(2);
ctx.__def = JSON.parse(fs.readFileSync(draft, "utf8"));
ctx.__bb = JSON.parse(fs.readFileSync(model, "utf8"));
// doors and the bicycle main leg have no role of their own: lend them unused ones while the boxes are made
ctx.__extra = { nose_gear_door: "FLGearHatch", left_gear_door: "FRGearHatch", right_gear_door: "BGearHatch", rotor: "BGear" };
const r = vm.runInContext(`(()=>{
  forgetMachine(); state.scale = __def.model.scale; loadGeo(bbmodelToGeo(__bb).geo); takeJson(__def, "av_8b.json");
  Object.assign(state.roles, __extra);
  const eye = point("cockpit").pos.slice();
  const pylons = ofKind("pylon").map(p => [p.name, p.pos.slice()]);
  autoCameras();
  point("cockpit").pos = eye;
  for (const [n, pos] of pylons) { const p = ofKind("pylon").find(q => q.name === n); if (p) p.pos = pos; }
  state.auto.raw = true; state.auto.skip = true; state.auto.minCube = 0.25;
  const plan = planBoxes();
  const items = state.items.filter(i => i.kind !== "box");
  const made = plan.boxes.map(b => { const nb = newBox(b.name, b.offset.slice(), b.size.slice());
    Object.assign(nb, JSON.parse(JSON.stringify(b)), { id: nb.id, kind: "box" }); return nb; });
  state.items = items.concat(made);
  for (const k of Object.keys(__extra)) delete state.roles[k];
  const ratio = boxToModelRatio(ofKind("box"));
  exportMachine();
  return { chase: point("chase").pos, n: plan.boxes.length, covered: plan.covered, ratio, text: out().value };
})()`, ctx);
console.log("chase", r.chase, "boxes", r.n, "covered", r.covered, "ratio", r.ratio);
fs.writeFileSync(outPath, r.text);
