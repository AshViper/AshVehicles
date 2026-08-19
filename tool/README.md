# AshVehicles tools

Editing aids for the mod's data files. Nothing here is part of the mod: nothing is compiled,
nothing ships, and the mod neither knows nor cares that this folder exists.

## hitbox-editor.html

Draws an aircraft's model and lets you build its collision boxes and pylons over the top of it,
in the three orthographic views you would use in any CAD package, plus a turntable to check the
result. Everything is shown and typed in the aircraft's own frame in blocks, so what you read on
screen is exactly what goes in the file.

Open the file in a browser. It needs no server, no build step and no network — it is one page with
no outside dependencies, so it works offline and from a memory stick.

### Using it

1. **Drop a `.geo.json`** on the panel at the top left, or click to choose one. The aircraft's own
   model lives in `src/main/resources/assets/ashvehicles/geo/entity/`.
2. **Set the model scale** to whatever the aircraft's file says under `model.scale` — 0.65 for the
   Su-25. **Nothing lines up until this is right**, and a model built at the wrong scale sitting
   beside boxes that are already in blocks looks for all the world like a broken model. Dropping the
   aircraft's own `.json` on the page reads the scale, and its existing hardpoints, for you; and if
   boxes are loaded the editor compares their span against the model's, says so when they disagree,
   and offers the scale that would make them match.
3. **Add boxes and pylons** and drag them into place. Left-drag moves the selected item in the two
   axes of whichever view you are dragging in, so place it in the top view and then set its height
   in the side view. Right-drag pans, the wheel zooms, and every number can be typed exactly.
4. **Fit** shrinks the selected box onto whatever model geometry it already contains — make a box
   roughly big enough over a wing, press Fit, and it closes onto it.
5. **Export** writes the two files' worth of JSON into the box at the bottom, to copy or save:
   - *collision json* → `src/main/resources/data/ashvehicles/collision/<aircraft>.json`
   - *hardpoints* → the `hardpoints` array inside
     `src/main/resources/data/ashvehicles/aircraft/<aircraft>.json`
6. To carry on from where you left off, paste an existing file into the same box and press
   **Load JSON from the box above**.

`/reload` in game picks up a changed collision file for aircraft already placed. Changing the
*number* of boxes or pylons only fully applies to aircraft placed afterwards, because the game is
told an entity's boxes once and cannot be told about a different set later.

### What the colours mean

| | |
|---|---|
| tinted solids | the model, a colour per bone, for fitting against |
| green | collision boxes |
| **red** | pylons |
| yellow | whatever is selected |
| dashed orange | the upright box the game will *really* collide with, when a box is rotated |

That last one is worth watching. Minecraft can only collide with boxes square to the world, so a
rotated box is enlarged into the upright box that encloses it — always bigger, sometimes twice the
volume. The editor prints the figure next to the rotation fields. If the dashed box is much larger
than the solid one, two or three small unrotated boxes will fit the part more tightly than one
rotated box.

### Coordinates

A `.geo.json` is written in model units, sixteen to a block, Y up, facing north. The mod describes
an aircraft in blocks in the aircraft's own frame — x to the right, y up, z towards the nose — and
the renderer turns the model half a circle about Y and scales it. So

    aircraft = (mx · k, my · k, −mz · k),   k = model scale ÷ 16

The editor does this for you; it is written down here because it is the one thing that silently
ruins everything if it is wrong. It was checked against the Su-25: the model spans 14.50 blocks
across and 15.49 along, and the hand-fitted collision boxes span 14.48 and 15.44.

Rotation follows the mod's own convention, the same one `AircraftShape.Box` documents: **x** pitches
the box nose up, **y** yaws it to the right, **z** rolls its right-hand side down.

Note that the mod applies that rotation in a frame where +X points *left* — which is why its own
renderer negates `offset.x`. This editor works in the frame the file is written in, where +X is
right, so it turns the yaw and the roll around to match. Seen through a mirror, a rotation is a
rotation the other way; getting this wrong shows every swept part leaning the wrong way.

**The same mirror applies to the model's own cubes and bones.** GeckoLib composes a cube's rotation
as `Rz(z) · Ry(−y) · Rx(−x)` in the space it draws in, which is X-mirrored against the file, so
reading the file directly needs `Rz(−z) · Ry(y) · Rx(−x)`. Getting this wrong leaves single-axis
parts looking right and scrambles anything turned about two axes at once — which on a real model is
most of it. It was settled by measurement rather than by reading: an Su-25 is left-right symmetric,
and only this convention reconstructs it that way (5.7% of corners without a mirror partner, against
8.8–9.5% for the alternatives, the remainder being the cockpit and pitot boom, which really are
asymmetric).

### Seeing the model

The model is drawn filled, tinted by bone, and painted back to front. Five hundred cubes of
wireframe on top of one another is a haze you cannot read a shape out of, and the point of having
the model here at all is to see what you are fitting to. **Wireframe** switches back when you need
to see through it — into a fuselage, say, to place something inside.
