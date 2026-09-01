# AshVehicles tools

Editing aids for the mod's data files. Nothing here is part of the mod: nothing is compiled,
nothing ships, and the mod neither knows nor cares that this folder exists.

## vehicle-editor.html

Draws a machine's model and lets you build everything that has a *place* on it over the top: the
collision boxes, the turret ring, the trunnion the gun swings on, the crew seats, both cameras, an
aircraft's stations — with the racks, the stores and the pods hung on them, out of the game's own
files — and the plain hitbox the game files the entity under. One view you turn with
the mouse — square on to any of the six sides at a keystroke, anywhere in between by dragging — and
a pose you can traverse and elevate to check that what you have built follows the model when the
turret comes round.

Everything is shown and typed in the machine's own frame in blocks, so what you read on screen is
exactly what goes in the file.

It edits the rest of a machine's file as well — the thrust, the suspension, the sounds, every block
in it — and the three sorts of file a machine's *armament* is made of, which are not in its file at
all: the weapons, the racks they hang on, and the pods.

And it builds the whole **pack**: the definitions, the model, the skin, the names people read and
the recipes at the workbench, out as one zip laid out the way `ashvehiclespack/` reads it. See
*Building a pack* below.

Open the file in a browser. It needs no server, no build step and no network — it is one page with
no outside dependencies, so it works offline and from a memory stick.

It reads in English and in Japanese. Which one it opens in is whatever the browser is set to, the
button at the top of the sidebar changes it, and the choice is remembered. Nothing but the wording
moves: the boxes, the draft on screen and everything placed stay exactly as they were, and the two
things that are *not* translated are deliberate — a bone's name and a box's name are what go in the
file, so they are shown exactly as they are written there.

日本語で開きます。右上のボタンで英語と切り替えられ、選んだほうが次回も使われます。ボーン名と
ボックス名だけは、ファイルに書かれるとおりの綴りで表示します。

It is drawn in two colours: near-black for everything the tool is *made* of, and one yellow-green
for everything it wants you to look at. A heading, a hovered button, a focused field, the pack
panel's chrome — all the same green at different weights, so nothing on screen has to be learnt.
Brighter is nearer to hand.

The one thing that is deliberately *not* two colours is the half-dozen colours that stand for
something: a hull box is green, a turret box is blue, a pylon is red, a seat is violet. Those are
the legend rather than the furniture, and painting them all one colour would say that a seat and a
station are the same thing — which is exactly what the drawing is there to tell apart.

It comes in three weights of that one scheme — **Black**, the one it opens in; **Pitch**, taken all
the way down for a dark room; and **Paper**, for a bright one or a projector, the same green
darkened until it holds up on white. The button beside the language one walks through them and
<kbd>T</kbd> does the same, and the choice is remembered. A theme is not just the panels: the grid,
the model's own shading and every label on the canvas come out of the same list of colours, so
*Paper* is a genuinely light tool rather than a light frame round a dark picture.

The bar between the panels and the view is draggable: a machine with forty boxes wants a wider
list than one with four, and a name like `hull_glacis_left` wants more room than `hull`.
Double-clicking it puts the width back, <kbd>\\</kbd> hides the panels altogether, and the width
you leave it at is the width it opens at next time.

配色は黒と黄緑の 2 色で統一されています（3 段階：ブラック／ピッチ／ペーパー）。言語ボタンの隣、
または <kbd>T</kbd> で切り替わり、次回も同じ配色で開きます。ボックスや座席の色だけは、
種類を見分けるための色なのでそのままです。パネルとビューの境界はドラッグで幅を変えられます（ダブルクリックで既定に戻ります）。

*(It supersedes `hitbox-editor.html`, which did the boxes and the pylons for aircraft only. That
file is still in the history if you want it: `git show cf0ee44:tool/hitbox-editor.html`.)*

### Using it

1. **Drop the folder in.** The two files a machine is made of live nowhere near each other — the
   model in `src/main/resources/assets/ashvehicles/geo/entity/`, and the machine's own file, the
   boxes included in its `hitbox` block, in `data/ashvehicles/vehicle/` or
   `data/ashvehicles/aircraft/` — so hand the editor `src/main/resources` whole, by dropping it on
   the page or through **A folder…**. It is walked once, the pairs are matched up by name, and
   every machine in there comes up as a list you can click: pick one and both its files are read.
   Nothing is opened until it is picked, so handing over the whole repository costs a walk and not
   a read. Which folder is up to you — the repository root works too, and `.git`, `build` and the
   rest are walked past.

   Individual files still work exactly as they did: drop a `.geo.json` and a machine's file
   together, anywhere on the page, or use **Files…**. Either way the geometry is read first,
   because the scale in the machine's file decides how big the model is built and the bones it
   names decide which of its cubes come round with the turret.

   A machine that has a model and no file yet shows as *the model only*, one whose model has not
   been made yet as *the file only*, and both open — the missing half is simply what you are about
   to write. Animations, item models and the language files are not machines and are left out of
   the list; the weapons, the racks and the pods are not machines either and get a list of their
   own, under *[Armament](#what-it-carries)*.

   A folder handed over this way is a folder the editor can **write back into**, which is what step
   8 is about: the file goes over the original where it lies, rather than into your downloads to be
   moved by hand. The right to do that is asked for once, by the browser, when the folder is
   chosen — the editor never sees anything outside the folder you point it at, and it writes one
   file, the machine's own. The line under the list says which folder it is writing into.

   That much wants Chrome or Edge, which is where the folder picker is. Everywhere else the folder
   is still read exactly as it always was, and the file comes back out through **Save…**.

   フォルダーごと渡せます。`src/main/resources` をドロップするか **フォルダー…** で選ぶと、中の機体を
   すべて一覧にします。クリックしたものだけを読み込むので、リポジトリ全体を渡しても走査だけで済みます。
   個別のファイルをドロップする従来のやり方もそのままです。渡したフォルダーには**そのまま書き戻せます**
   （手順 8）。許可を訊くのは最初の一度だけで、書き込むのは選んだ機体のファイルだけです。書き込みには
   Chrome か Edge が必要で、それ以外のブラウザーでは読み込みだけ従来どおり動きます。
2. **Check the scale.** It comes from the machine's file, and nothing lines up until it is right.
   A model built at the wrong scale sitting beside boxes that are already in blocks looks for all
   the world like a broken model, so the editor compares their spans, says so when they disagree,
   and offers the scale that would make them match.
3. **Place things.** Left-drag moves the selected item in the two axes the view is nearest to
   holding across the screen and up it — the corner label says which two — so put it where it goes
   from above (<kbd>5</kbd>) and then set its height from the side (<kbd>3</kbd>). The selected box
   wears a small grip on the middle of every face you can see: drag one and that face moves while
   the one opposite stays where it is. Square on to an axis, anywhere along an edge does the same.
   Dragging bare ground turns the view, right-drag pans, the wheel zooms towards the cursor,
   double-click puts what you clicked in the middle and turns about it from then on, the arrow keys
   nudge, and every number can be typed exactly.
4. **Build the boxes off the bones.** Press <kbd>Shift</kbd>+<kbd>B</kbd> for a whole set at once,
   worked out from the model — see *[Automatic boxes](#automatic-boxes)* below — or build them one
   at a time: pick a bone under **Bones** and press <kbd>Shift</kbd>+<kbd>F</kbd> and you get a box
   round exactly that part, marked as being on the turret — or on the gun, if the bone is the barrel
   or the mantlet — if that is where the bone is. **Fit** does
   the other half — make a box roughly big enough over something, press <kbd>F</kbd>, and it closes
   onto whatever geometry is inside it.
5. **Mirror the pairs.** A machine is symmetric and its boxes come in pairs, so **Mirror** makes
   the twin across the centreline and **Sym** keeps the two in step from then on. Fit a skirt once.
6. **Traverse the turret** (<kbd>Q</kbd> <kbd>E</kbd>) and watch the boxes marked *turret* follow
   the model. If they drift, the ring is in the wrong place, and the editor will say so and offer
   to put it where the model's own turret bone turns. **Elevate the gun** (<kbd>[</kbd> <kbd>]</kbd>)
   and the boxes marked *gun* should follow the barrel the same way; if those drift, it is the
   trunnion that is wrong.
7. **Fill in the figures.** Everything the file says that is *not* a place on the machine — thrust,
   stall speed, gear cycle time, the powertrain, the suspension, what it sounds like — is under
   **Performance**, a field to a line, laid out block by block as the file is. It starts at what the
   mod itself would have assumed, so a machine begun here from nothing comes out complete and
   loadable without a single figure being typed.
8. **Export.** *Write the file* gives you the whole vehicle or aircraft file, boxes and all,
   merged into the one you loaded — so the powertrain, the suspension and the sounds survive the
   round trip untouched. It comes out in the style the data files are kept in — one key to a line,
   and a point on the line it belongs to, `"offset": [0.0, 1.81, -1.25]`, rather than a triplet
   spread down the page.

   Then **Back into the folder**, or <kbd>Ctrl</kbd>+<kbd>S</kbd>, puts it *straight over the file
   it was read from* — no dialog, no download, nothing to drag anywhere — and `/reload` in the game
   picks it up. That is the whole loop: drag a box, <kbd>Ctrl</kbd>+<kbd>S</kbd>, `/reload`, look.
   The button writes what is in the box, and builds it first when the box is empty, so a figure
   typed in there by hand goes in as it stands.

   A machine the folder has no file for yet — *the model only* in the list — gets one made, in the
   folder the rest of the machines of its kind are kept in. Which folder that is, is read off the
   ones already there rather than assumed, so it lands correctly whether you handed over the
   repository, `src/main/resources`, or a working folder with the two files side by side.

   <kbd>Ctrl</kbd>+<kbd>S</kbd> falls back to **Save…** — the old download — when no folder is open
   to write into, and so does everything else: **Save…** and **Copy** are still there, unchanged,
   and are the whole of it in a browser with no folder picker (Firefox, Safari). The button says
   which of the two you are in when you hover it.

   **フォルダーに書き戻す**（<kbd>Ctrl</kbd>+<kbd>S</kbd>）で、読み込んだファイルにそのまま上書き
   します。ダウンロードも手作業のコピーも要りません。あとはゲーム内で `/reload`。ファイルがまだ
   ない機体には、同じ種類の機体が置かれているフォルダーに新しく作ります。フォルダーを開いて
   いないとき、またはフォルダー選択に対応していないブラウザーでは、従来どおり **保存…** で
   ダウンロードします。

`/reload` in game picks up a changed file for machines already placed. Changing the *number* of
boxes only fully applies to ones placed afterwards, because the game is told an entity's boxes once
and cannot be told about a different set later. The plain hitbox is the other thing a reload will
not move: its width and height are fixed when the entity type is registered, which is long before
any data pack is read.

### The figures

The panels above **Performance** are for the things that have a *place*: a box, a seat, the ring,
the camera. They are edited by dragging them about and watching what happens, and half of what the
tool is for is that watching. Nothing under **Performance** can be watched — a stall speed is a
stall speed — so it is a plain list of fields, one per figure, in the blocks the file is written in.

Which is why it is a table in the source and not a form. `GROUPS` is one line per field: the key it
goes under, what it is worth when nobody says, and how big a step the arrows should take. The panel,
the reading and the writing all come off that one line, so a figure the mod grows later is a line
here and nothing else.

Two rules about what gets written, both of which matter more than they look:

- **A figure is left out when it is what the mod would have assumed anyway** — unless the file being
  edited already said it, in which case it is kept where it was. That is how the files here are
  written, and it is not only taste: a tank with no `radar` block has no radar at all, while one
  with a block full of the defaults has three kilometres of it. Writing out a default is not always
  the same as leaving it out.
- **A block that is switched on is written even when it is empty.** `"rotor": {}` is a helicopter
  with an entirely standard rotor; an aeroplane with no `rotor` block is not a helicopter at all.

Every machine file in this repository goes in and comes back out of the editor byte for byte, which
is the check that both of those rules are right.

There is a third rule, and it is the one that took the longest to notice: **a file is read onto a
clean sheet.** Everything goes back to the codec's own before the new file is read, because "the
file does not say" has to mean the same thing on the second machine opened as on the first. Without
that, opening a bomber and then a helicopter left the helicopter carrying the bomber's fuel tank,
its heat signature and its slaved turret — figures no file on screen had ever mentioned, written
into the helicopter's file the moment it was saved.

The blocks a machine either has or has not — an afterburner, a rotor or a thrust-vectoring system,
a swing wing, an ejection seat, a coaxial machine gun, a missile rail, a radar — have a switch
beside their heading. The rest are always there.

`GROUPS` covers every block of both codecs: the fuel tank an engine drinks out of, the swing wing,
the ejection seat, what the suspension does with the weight on it, the gun beside the main one, the
hull in the water and what it drives through rather than round. If the mod can read a figure, the
panel can write it.

### What it carries

A machine's file says where its stations are and nothing whatever about what hangs on them. That is
three other sorts of file, each in a folder of its own beside the machines, and **Armament** edits
all three:

| | what it is |
| --- | --- |
| `data/ashvehicles/weapon/` | what is fired: the rate, the round, the motor, the seeker, the smoke |
| `data/ashvehicles/rack/` | the carriage between a station and its stores — how many, and where each hangs |
| `data/ashvehicles/equipment/` | a pod, which is carried rather than fired |

None of the three has anything with a *place* on a machine, which is why it is a panel of figures
and not a thing to drag: a rack's stations are offsets from a pylon and a pod's lens is an offset
from its station, and neither is anywhere at all until it is hung on something. So it is the same
machinery **Performance** is — a table of every field with the codec's own default beside it,
written out only where it says something — pointed at a different file. The two rules above hold
here too, and every armament file in this repository goes in and comes back out of it unchanged.

Hand over a folder and everything of the three sorts in it comes up as a list of its own, under the
machines. Pick one and it is read; **Start one** begins a fresh file at every default the mod has,
under a name nothing in the folder is using yet. It goes out through the same box a machine does and
back into the same folder, and <kbd>Ctrl</kbd>+<kbd>S</kbd> writes whichever of the two — the
machine or the armament — you were last in.

Two small things fall out of having the list. A station's **fixed weapon** offers the weapons the
folder holds rather than asking you to spell one. And the armament files keep their own house style:
they write a float as `1.0` where a machine's file writes `16`, and the editor writes each of them
the way its own files are written rather than restyling one into the other.

**兵装**パネルで、`weapon/`（武装）・`rack/`（ラック）・`equipment/`（ポッド）の 3 種類のファイルを
編集できます。いずれも機体のように配置するものがないので、**性能**と同じ「既定値つきの項目一覧」です。
フォルダーを渡せば中の兵装ファイルが一覧になり、選べば読み込み、**新規作成**なら既定値のまま新しい
ファイルを起こします。書き戻しも機体と同じで、<kbd>Ctrl</kbd>+<kbd>S</kbd> は機体と兵装のうち最後に
触っていたほうを書き込みます。

### Crew guns, extra barrels, and what the file calls itself

Three things in a machine's file are neither a figure nor a place, and so belong to neither panel.

**What it calls itself** is one word, under **Machine**. The tool works in two modes — a thing that
flies and a thing that drives — but the mod files four kinds: `aircraft`, `helicopter`,
`ground_vehicle` and `ship`. The extra two are not a different mode, they are the same mode saying
something more about itself: a helicopter is an aeroplane held up by its rotor rather than its wing,
and a ship is a ground vehicle that settles on the water rather than the ground. So it is a select
beside the kind, written into the file only where it is not the one the mod would have assumed. Pick
*helicopter* and switch the lift over to **rotor** under **Performance**; pick *ship* and the
**buoyancy** block is the one that says how deep it floats.

**Crew guns** (`stations`, aircraft) are the guns somebody who is not flying swings and fires: the
gunner looks, the gun follows, inside an arc the file gives it. A station does not *place* anything —
it names pylons that are already placed and named on the machine — so it picks them off a row of
buttons, one per pylon, rather than asking you to spell them. That is the whole reason it is not in
the table of figures: `pylons` is a list of names that only mean anything against this machine.

An aeroplane with no stations fires everything straight down the nose, which is every fighter. The
ones that have them are the gunships and the helicopters, and the seat number is what decides whose
gun it is — `0` is the pilot's, so a single-seat aeroplane's turret is the pilot's turret.

**Extra barrels** (`armament.barrels`, ground) are the second and third guns of a ship, each with a
trunnion of its own and, if it needs one, a ring of its own. The *main* gun is not one of these: it
is the trunnion you drag under **Parts**, and these hang off it. A vehicle with none has the one
gun, which is every tank in this repository but two.

**機体**パネルの種別（固定翼機／ヘリコプター／地上車両／艦艇）は、ファイルの `type` にそのまま
書かれます。**旋回機銃**は乗員が自分で振って撃つ砲（`stations`）で、機体に置いたパイロンを名前で
選びます。**追加の砲**は軍艦の第 2・第 3 砲塔（`armament.barrels`）で、主砲は含みません。

### Building a pack

Everything above makes one file. A pack is not one file. An aeroplane somebody can actually build
and fly is five things, and three of them live nowhere near the definition:

| | where | who writes it |
| --- | --- | --- |
| the definition | `data/<ns>/aircraft/foo.json` | this tool |
| the model | `assets/<ns>/geo/entity/foo.geo.json` | Blockbench |
| the skin | `assets/<ns>/textures/entity/foo.png` | you |
| the name people read | `assets/<ns>/lang/en_us.json` | this tool |
| the recipe at the workbench | `data/<ns>/recipe/foo.json` | this tool |

**Pack** keeps a list of them. Whatever is open goes on it with one button — **Put the machine on**
takes the definition the Export box would have written, along with the model and the skin that were
loaded with it, and **Put the armament on** does the same for the weapon, rack or pod. Pick a row
and you get the two things the definition has no room for: what it is called, in English and in
Japanese, and what it is built out of.

**Build the pack** hands over one `.zip`, laid out exactly the way `ashvehiclespack/` reads it —
which is the same layout the mod itself is written in, so a pack made here and the mod's own files
are the same shape. Drop it in the game folder's `ashvehiclespack/`. There is nothing to enable.

What comes out, for a pack called `mypack` holding one aeroplane and one missile:

```
mypack.zip
  data/mypack/aircraft/foo.json          the definition
  data/mypack/recipe/foo.json            what it is built out of
  data/mypack/weapon/mymissile.json
  data/mypack/recipe/mymissile.json
  assets/mypack/geo/entity/foo.geo.json  the model, as it was loaded
  assets/mypack/textures/entity/foo.png  the skin, as it was loaded
  assets/mypack/models/item/mymissile.json
  assets/mypack/lang/en_us.json
  assets/mypack/lang/ja_jp.json
  assets/mypack/sounds.json              only where the files name a sound of their own
  README.txt                             what is in it, and what is still missing from it
```

Three things about that list are worth saying out loud.

- **The namespace is the folder and the first half of every name.** `data/mypack/aircraft/foo.json`
  is the aeroplane `mypack:foo`; its entity, its item, its model and its skin are all looked for
  under that one word. Two packs can both ship a `foo` without either losing. Calling your namespace
  `ashvehicles` is how you would *replace* something the mod ships — do that on purpose or not at
  all.
- **A machine gets its inventory icon for free** — the mod draws it off the model you already
  shipped — and nothing else does. A weapon, a rack or a pod is an ordinary item and wants an
  ordinary 16×16 `assets/<ns>/textures/item/<id>.png`, which is the one file in the whole pack that
  has to be drawn rather than written. The item *model* that points at it is written for you.
- **A row with something still missing is marked**, and the reason is in the zip's own `README.txt`
  as well as beside the row. A pack that is short a name or a model still builds; it is better to
  have the zip and the list of what is left than neither.

The list survives closing the tab — the typing does, at least. The models and the skins do not,
because four aeroplanes' worth of them is several megabytes and browser storage is not the place for
that; open the machine again and press **Re-take**.

The zip is written by the tool itself, stored rather than compressed. That is the whole reason it
can be done at all without an outside library, which this page has managed to do without everywhere
else and was not going to start here.

**パック**パネルで、パック 1 つ分をまとめて zip として書き出せます。開いている機体や兵装を
**機体を載せる**／**兵装を載せる**でリストに追加し、行を選んで表示名（英語・日本語）と作業台の
レシピを入力します。**パックを書き出す**で、`ashvehiclespack/` がそのまま読める形の zip が
できます——定義ファイル、モデル、スキン、`lang`、レシピ、必要なら `sounds.json` まで。
名前空間はフォルダ名であり ID の前半でもあるので、他のパックと衝突しません。機体のアイテム画像は
MOD がモデルから描きますが、兵装・ラック・ポッドのアイテム画像だけは自分で用意する必要があり、
足りない物は行の脇と zip 内の `README.txt` に書き出されます。

### Reading it all again

**Re-read** (<kbd>R</kbd>) walks the open folder again and reads the machine and the armament that
are open out of it afresh. It is the other half of *Back into the folder*: these files are edited
from both ends — by this tool, by hand, by a merge — and a tool holding a copy from ten minutes ago
will put it back over somebody else's work without a word. The walk is done again rather than the
two files merely re-opened, so anything added to the folder since it was handed over turns up in the
lists as well.

Anything placed and not written back is lost, so it asks first. A folder read through **Files…**
rather than opened as a folder cannot be re-read, and says so.

**再読み込み**（<kbd>R</kbd>）で、開いているフォルダーを走査し直し、開いている機体と兵装を読み直します。
ゲーム側やエディターで書き換えたファイルを取り込み直すためのものです。書き戻していない変更は失われるので、
先に確認します。

### Automatic boxes

The model already knows the shape. It is five hundred cubes of it, in the right places, sorted into
bones by whoever built it. What it does not know is which of those cubes are the machine and which
are the door handles — and it has five hundred of them where the file must have a dozen, because a
box is a `PartEntity`, the game is told how many a machine has once and can never be told again, and
everything that moves is swept against every one of them every tick.

So **Generate** does the reduction. It throws away the detail, groups what is left by bone, puts
groups back together for as long as doing so is nearly free, and then cuts up whatever is still
mostly air until the boxes are as tight as you asked for or the budget runs out.

**Follow the model** does it the other way about, for when the bones are no help. Every cube starts
as a box of its own and the two whose join adds the least empty space are joined, over and over,
until only the budget is left standing — bones never come into it. That is the whole difference and
it matters most where a model is drawn in a few big bones: the Zumwalt is two hundred and fifty
cubes in *nine* bones, so the by-bone pass can only ever give three boxes however large a budget it
is handed, and those three enclose 73,000 blocks³. Following the model, forty boxes bring that to
29,000 and eighty bring it to 24,000 — at which point 99% of what is enclosed is actually ship.

**Trace the outline** goes at it a third way, and it is the one to reach for when what you want is
a hitbox rather than a tidy set of boxes. The model is burnt into cells — each cell filled if a
cube comes within half a cell of its middle, tested against the cube as it really lies — the cells
are swept up into a few hundred fine boxes, and the boxes are then joined back together, cheapest
pair first, at whichever size and angle holds each pair tightest. The candidate angles are read
off the machine itself, two ways: every cube edge votes for the angle it lies at in each of the
three pictures the six square-on views come to, the silhouette's boundary votes for the way it
runs there, and the loudest few angles per axis are tried on every join, with a hill climb
settling the winner to the half degree afterwards. So the budget is met by joining, not by
coarsening the cells, and a sloped plate comes back as one turned box rather than a staircase of
square ones. How fine to sweep before joining is a genuine trade — fine cells and much joining
follow a slope best, coarse cells and little joining box the least air on a small budget — so a
few sweeps are tried and the draft that encloses least is kept. The budget is also spent whole:
joining greedily overshoots, so while boxes are left over, joins are undone again, dearest first
— which is how a wing boxed in one piece gets its chord steps handed back.

The cells decide the *grouping*; the model decides the *box*. Each finished box is fitted to the
cubes it owns — a cube belongs to whichever box's swept cells hold its middle, so no two boxes
are fitted to the same cube and what they cover between them is still the whole machine. That is
what lets a draft match the model's own thicknesses: a wing comes out a plate a quarter of a
block thick at the wing's own sweep and dihedral, where a box kept to the cell grid could never
be thinner than the cells it was traced in. What a cube overhangs into a neighbour's box, the
neighbour's fit mostly picks up again, and the coverage figure prints what was not.

With **Symmetric** on, what is traced is the machine and its own reflection, and the two sides
are merged in lockstep: every side box comes out with its exact mirror as its twin, anything
touching the centreline comes out as one box centred on it, and no squaring-up of two drafts that
grew apart is ever needed — that pairing, on a machine whose halves happened to cluster
differently, used to cost a fifth of the volume on its own.

**Two figures, not one.** The panel reports how much of the *machine is covered* as well as how
much of the *draft is machine*, and the first is the one that decides whether a hitbox is any good.
A draft scores well on the second by shrinking away from the model — boxes hugging a few solid
lumps, the rest of the hull outside them — and that is a machine you can shoot straight through.
On the Zumwalt, *Follow the model* at eighty boxes gets the enclosed volume down to 24,000 blocks³
and 99% of it is ship, which reads beautifully and covers **half the vessel**. Tracing the same
ship at eighty boxes encloses 25,000 and covers 95%. The panel says so in as many words, and
warns outright when a draft leaves more than a tenth of the machine outside every box.

**Turning.** The part's own angle has priority. A box is fitted at whatever angle its geometry
really lies at, and comes out upright only where the part has no angle to speak of — the fitter
never reports a turn the geometry does not have — or where the upright box undercuts the turned
one by more than *turn may cost %*. That figure starts at 5: a turn may give away up to five per
cent of volume and still be preferred, because it is the part's own. Set it to 0 to take the
angle only when it is at least as tight as upright, or raise it to hold angles more stubbornly
still. This is the burden of proof the other way round from where the dial began — it used to be
"a turn must *save* so much before it is taken", on the sound reasoning that a turn is not free
(the game searches the upright box round it first, twice the volume at forty-five degrees) — but
the hand-fitted sets settle the argument the other way: thirty rotations in the Su-57's fifty
boxes, thin plates at the parts' own angles throughout. The angle search goes round three times,
sweeping each axis coarsely and then closely about whatever the coarse sweep liked, so a part at
27° is fitted at 27° and not at 25°.

On the Leopard 2A4 at eighty boxes, *Follow the model* turns 31 of its 69 boxes where it used to
turn 3, and the enclosed volume falls from 125 to 111 blocks³.

Turning used to cost the tracing pass coverage outright — the panel printed figures like 92%
square against 84% turned for the same Leopard, because each cell-grid box re-fitted itself to a
handful of cubes and let everything else go. Owning the cubes through the swept cells brings
that down to a point or three on the machines here, and the panel prints whatever it really is.

And a turn has to stay home. The smallest box round a delta wing is a diamond over the leading
edge — genuinely smaller than any upright box, with corners standing half a wing outside the
wing, across the tail and the nose. The volume test loves it, and it is exactly what a hitbox
that does not follow the outline looks like; the hand-fitted sets spend three boxes and *more*
volume on that wing sooner than let one box wander off its part. So the trace holds a turned box
to the upright envelope of what it was fitted to: its reach — the upright box round it, the very
thing the game searches — may not stand more than a margin outside the flat box over the same
cubes. A glacis slab, a dihedral wing plate and a canted fin all pass; the diamond is refused,
whatever it saves.

So: *trace* when what you want is a hitbox, at any budget — it is the one of the three that never
leaves part of the machine outside every box; *by bone* when the bones are good and a handful of
nameable boxes is the point; *follow* when you want the tightest enclosure of the solid parts and
know what you are giving up; *keep the shape* whenever the budget runs to forty boxes or more,
because there it encloses less than any of them and encloses the machine exactly. What any of them cost is on the other side of the file — every box is a `PartEntity` the
game tests against every shot and every mover, and it takes an entity id of its own from a run
reserved after the machine's. Vanilla's ender dragon claims nine of those. Forty is already a lot.

**Keep the shape** is the fourth, and it works from the opposite end of the problem. The other
three build boxes *up* — out of bones, out of cubes, out of cells — and every one of them has
lost the model's own shape by the end of its first join. This one starts with the shape already
perfect and takes boxes *away*.

It begins where the model does: every cube becomes the box it already is, at the angle it already
lies at. Nothing is searched for — a cube's three edges *are* its axes, so the angles come out of
the geometry by arithmetic, and the boxes reproduce the model's own corners to the last decimal.
That is nine hundred boxes on a T-64, which is not a hitbox but the model again, so boxes are
then merged away until the budget is met.

**The merging is done in the boxes' own frames, and that is the whole idea.** Two boxes at the
same angle, flush, are one box of their joint extent and not a scrap of air more — so that merge
is free, and the shape after it is the shape before it. *Follow the model* cannot do this: it
merges by upright extent, so a pair of sloped plates becomes an upright slab holding a wedge of
air on the very first join, and every merge after that is priced against that wedge. Here the
angles are what the merging is done *in*. Each pair is priced in three frames — each cluster's
own, and upright — and the cheapest wins; then, at the end, every box that holds more than one
cube is given the chance of an angle of its own, searched over the cubes it really holds, because
the angle that suits nine plates together is often neither the first plate's nor the second's.

**Every cube ends up wholly inside a box.** Each box is fitted to the cubes it owns, and each cube
belongs to exactly one box, so the machine is enclosed exactly — not to 99%, exactly, at any
budget. No other pass can promise that. (The panel's covered figure still reads 94–100% here, and
it is the figure that is wrong: it samples a model spread half a cell wider than it really is,
which boxes this tight fall inside.)

Against the tracing pass on the same machines and the same budgets, in blocks³ enclosed:

| | Keep the shape | Trace the outline |
|---|---|---|
| Zumwalt @80 | **10,900** | 24,600 |
| T-64 @80 | **52** | 69 |
| Su-25 @80 | **34** | 44 |
| T-64 @40 | **75** | 83 |
| T-64 @12 | 127 | **102** |

The pattern is worth knowing. Above about forty boxes this pass wins, and on something the size
of a destroyer it wins by more than half. Below about twenty it loses, and loses for a reason:
merging greedily from nine hundred pieces accumulates decisions that cannot be taken back, while
tracing re-derives the whole shape at a coarser scale and is not carrying any of that history.
So: *keep the shape* when you can spend forty boxes or more, *trace* when you cannot.

Two things it does that are worth knowing. A pane drawn with no thickness — these models are full
of them — has an axis with no direction to read, so the box is laid in the pane at the pane's own
angle with the usual five-hundredth of a block under its thickness, rather than at whatever angle
a search would have settled on. And which edge is called *x*, and which way along it, is not the
box's to decide: read as it comes, a cube square to the machine lands on `"rotation": [0, 180, 0]`
as readily as on no rotation at all — the same eight corners written the long way round, and
written into the file that way for good. So all six orderings are tried and the one closest to no
turn at all is kept. On the T-64 that leaves 306 boxes with no rotation and the rest at the
angles the modeller actually typed: 35°, 40°, 42.5° down the glacis, ±27.5° across the turret.

**Symmetric** is greyed out for it: it merges what the modeller drew, and a machine drawn
symmetric comes out symmetric without being squared up afterwards.

「モデルに合わせる」は逆の手順で、全キューブから最も安い組を統合し続けて上限個数まで減らします。
「輪郭をなぞる」はさらに別で、モデルを細かいセルに焼いて数百個の箱で拾い上げ、その箱同士を最も
無駄なく収まるサイズと角度で統合して上限個数まで減らします。角度の候補は機体そのものから読み
取ります — キューブ自身の辺の角度と、6方向から正対して見たシルエット輪郭の傾きの両方を集計し、
軸ごとに有力な角度を選んで統合のたびに試し、最後に山登りで0.5度刻みまで詰めます。上限個数は
セルを粗くするのではなく統合で満たすので、傾斜装甲は正立の箱の階段ではなく1つの回転ボックスに
まとまります。どこまで細かくなぞってから統合するかは機体と上限個数しだいなので、細かさを変えて
数通り作り、体積が最小の下書きを採用します。予算は使い切ります — 統合は貪欲なのでやり過ぎるため、
箱が余っているあいだは最も体積を買い戻せる統合から順に巻き戻します。翼が1箱に丸められていたら、
翼弦方向の段々が返ってきます。

セルが決めるのは**分け方**で、箱そのものは**モデル**に合わせます。仕上げの各ボックスは自分の
受け持つキューブ（中心がそのボックスの担当セルにあるキューブ）に丸ごと沿わせるので、キューブは
必ずどれか1箱に属し、全体では機体全部をカバーしたままです。これで下書きがモデルの実寸の厚さに
なります — 主翼はセルの厚さの板ではなく、実際の後退角・上反角のついた厚さ0.2〜0.3の薄板です。
隣の箱へはみ出した分は概ね隣の箱が拾い、拾い切れなかった分はカバー率の数字に出ます。

**左右対称**が入っているときは、機体とその鏡像をなぞって左右を完全に連動させて統合するので、
片側の箱には必ず正確な鏡像の相方ができ、中心線にかかるものは中心にぴったり乗ります。

回転が優先です。各ボックスは部位そのものの角度で嵌まり、正立になるのは「部位に角度がない」
（フィッターは幾何が実際に持つ角度しか報告しません）か「正立のほうが**回転の許容コスト %**を
超えて小さい」ときだけです。既定は 5% — 部位自身の角度であることを買って、体積を5%までなら
譲ります。0 にすれば正立と同等以下のときだけ回し、上げればさらに角度に固執します。以前は逆向き
（「回転は◯%節約しなければ採用しない」）でしたが、手作業のセットが答えです — Su-57の50箱のうち
30箱が回転で、どれも部位の角度どおりの薄板です。角度探索は3周・粗探索＋細探索なので、27度の
部品は27度で嵌まります。

**形を保つ**は4つめで、問題を逆側から解きます。他の3つはボックスを「積み上げる」ので、最初の統合の
時点でモデル本来の形を失っています。これは形が完璧な状態から始めて、ボックスを「削る」方式です。

出発点はモデルそのもので、全キューブがそのままの角度のボックスになります。探索は一切せず、キューブの
3辺がそのまま軸なので、角度は幾何から計算で出て、モデルの角が小数の最後まで一致します。T-64 なら
903個 — ヒットボックスではなくモデルの複製なので、そこから上限個数まで統合して減らします。

**統合をボックス自身の角度フレームで行うのが要点です。** 同じ角度で隣り合う2つのボックスは、その角度の
まま統合すれば体積が1ミリも増えません。つまりその統合は無料で、統合後の形は統合前と同一です。
「モデルに合わせる」はこれができません — 世界軸の範囲で統合するので、傾いた板2枚は最初の統合で
正立の箱＋くさび状の空気になり、以降の統合はすべてその空気込みで評価されます。ここでは角度こそが
統合を行う座標系です。各ペアは3つのフレーム（両クラスタ自身の角度と正立）で評価して最安を採り、
最後に、複数キューブを持つボックスには専用の角度を探し直させます（9枚の板全体に合う角度は、1枚目の
角度でも2枚目の角度でもないことが多いため）。

**全キューブが必ずどれかのボックスに完全に収まります。** 各ボックスは自分の持つキューブに合わせて
作られ、各キューブはちょうど1つのボックスに属するので、機体は「99%」ではなく厳密に全部囲まれます。
どの上限個数でもこれは保証され、他の方式にはできません（パネルのカバー率は94〜100%と出ますが、
そちらが不正確です。モデルを半セル分広げたものに対する標本計測なので、密着したボックスは低めに出ます）。

同じ機体・同じ上限個数での「輪郭をなぞる」との比較（囲んだ体積、ブロック³）:

| | 形を保つ | 輪郭をなぞる |
|---|---|---|
| Zumwalt @80 | **10,900** | 24,600 |
| T-64 @80 | **52** | 69 |
| Su-25 @80 | **34** | 44 |
| T-64 @40 | **75** | 83 |
| T-64 @12 | 127 | **102** |

傾向は覚えておく価値があります。40個以上ならこちらが勝ち、駆逐艦規模なら半分以下になります。20個以下
では負け、それには理由があります — 903個から貪欲に統合すると取り消せない判断が積み重なるのに対し、
なぞる方式は粗い縮尺で形を導出し直すので、その履歴を背負っていません。40個以上なら**形を保つ**、
それ未満なら**輪郭をなぞる**です。

**左右対称**は無効になります（モデラーが描いたものを統合するだけなので、対称に描かれた機体は
そのまま対称に出てきます）。

2点、仕様として知っておく価値のあることがあります。厚みゼロの板（このMODのモデルには多数あります）は
読み取れる方向を持たない軸があるので、面の法線から板そのものの角度で寝かせ、厚みには通常どおり
0.05の下限を入れます。もう1つ、どの辺を x と呼ぶか・どちら向きに取るかはボックス側の都合ではありません。
そのまま読むと、機体に対して正立したキューブが `"rotation": [0, 180, 0]` になり得ます（同じ8頂点の
遠回りな表記が、そのままファイルに残ります）。そこで6通りの並べ方をすべて試し、最も無回転に近いものを
採用します。T-64 ではこれで306個が無回転になり、残りはモデラーが実際に入力した角度 — 車体前面の
35°・40°・42.5°、砲塔の±27.5° — になります。

以前は「輪郭をなぞる」で回転を使うとカバー率が大きく落ちました（レオパルトで正立92%に対して
回転84%）。いまはキューブの所属をセルで決めてから丸ごと沿わせるので、落ちてもここの機体で
数%です。実際にいくつだったかは、そのままパネルに出ます。

また、回転は自分の場所に留まる必要があります。デルタ翼を最小体積で包むのは前縁に沿った菱形で、
体積ではどの正立箱より本当に小さいのですが、その角は翼の外 — 機首や尾翼の上 — まで突き出します。
手作業のセットは、1つの箱を部位の外へはみ出させるくらいなら、その翼に箱を3つと多めの体積を
使っています。そこで回転ボックスには「reach（それを含む正立箱）が、同じキューブを包む正立箱から
マージン以上はみ出さないこと」を課しました。傾斜装甲・上反角つきの翼板・傾いた垂直尾翼は通り、
菱形はどれだけ体積を節約しても却下されます。

パネルには「機体の何%をカバーしたか」と「下書きの何%が機体か」の**両方**が出ます。前者が
ヒットボックスの良し悪しを決めます。後者だけが良い下書きは、モデルから縮こまって塊だけを掴んで
いる状態で、撃ち抜ける機体です（Zumwalt で「モデルに合わせる」80個は体積24,000で99%が艦体、
しかしカバー率は50%）。1割以上が箱の外に出る場合は警告が出ます。 What comes out is
drawn dashed over the top of the boxes you already have, which are dimmed to a whisper while it is
up: **Replace** takes it, **Discard** throws it away, and <kbd>Ctrl</kbd>+<kbd>Z</kbd> puts the old
set back if you take it and change your mind.

It is a draft. On the machines in this repository it lands somewhere between a quarter and twice as
much volume as the hand-fitted sets — an Su-25 comes out at eleven boxes and 74 blocks³ against the
eleven and 58 in its file — and it is meant to save the half hour of dragging boxes roughly into
place, not the ten minutes of fitting them properly afterwards.

Three figures steer it:

| | |
|---|---|
| **ignore cubes under** | in blocks. A cube shorter than this *every* way about is a bolt, a step or a lamp and is thrown away. A cube that is small two ways and long the third is a pipe and is kept |
| **at most** | how many boxes it may spend. Twelve is a sensible ceiling for anything |
| **fill %** | how much of a box has to be machine before it is left alone. Raise it for a finer set, at a box apiece |

And three switches. **Turned** lets a box be turned when a turned one is much smaller — a glacis
comes out at its own angle to the degree — and leaves everything square when it is off. **Symmetric**
squares the draft up with the centreline: pairs are made exact mirrors of each other and linked as
twins, and anything straddling the middle is centred on it. **Skip rotor & gear** leaves out
whatever the roles name as a rotor or an undercarriage, which is how an Apache's rotor disc stays
out of its hitbox — so set the roles under **Bones** before you generate.

What it cannot do is name things. A model's bones are called `CORPUS` and `bone3` as often as they
are called anything, so a box is named after the bone most of it came from when that is a word and
after what it is bolted to when it is not. Every set in the data files was renamed by hand and this
one wants renaming too.

Two things about the result are worth knowing before you keep it. A group it cannot improve by
cutting is left whole, so a boxy hull comes out as *one* box with the tracks inside it — that is
correct as far as it goes and coarser than a set of three that follows the silhouette. And a turned
box is not free even though the mod collides with it exactly: `Hitbox.reach`, the upright box the
world is searched with before any real test is run, is always bigger round a turned box than round a
straight one, so the draft only turns a box where the turn plainly pays.

### The view

One pane, turned with the mouse. There were four before — top, side, front and a turntable — and on
a machine the size of an aeroplane each of them was a thumbnail; a thumbnail is something to look
at, not something to place a box on.

Drag bare ground to orbit, or <kbd>Alt</kbd>+drag anywhere. Drag with the right or the middle button
to pan, and the wheel zooms towards the cursor. Double-click a box, a seat or a point to put it in
the middle and turn about it from then on; double-click empty ground to go back to the middle of the
machine. <kbd>A</kbd> frames the lot.

<kbd>1</kbd>…<kbd>6</kbd> park the camera square on to one of the six sides, and <kbd>7</kbd> or
<kbd>0</kbd> put it back in the corner it started in. Parked square on, the pane *is* one of the old
flat ones: the same picture, the same grid, and the same edge of a box you can take hold of anywhere
along its length. The bar along the top of the pane does the same by clicking, and shows where the
camera is standing.

The corner label reads, say, `iso — x / y`. The two letters are the axes a drag and the arrow keys
work in from where you are standing — whichever pair the view is nearest to holding across the
screen and up it — and <kbd>,</kbd> <kbd>.</kbd> move along the third. The little cross of axes in
the bottom right corner says which way each of them is pointing once the origin has been panned off
the pane.

**The top view has +x on the left, and that is not a mistake.** Inside the file +x points to the
machine's *left* — see *[Coordinates](#coordinates)* — so looking down at a machine with its nose
up the screen, its left side really is on your left. The old top pane drew the mirror image of that.
If it is the old orientation you want, <kbd>6</kbd> is the same picture from below.

ビューは 1 面になり、マウスで回します。何も無いところをドラッグ（または <kbd>Alt</kbd>+ドラッグ）で
回転、右／中ドラッグで平行移動、ホイールでズーム、ダブルクリックでそこを中心に据えます。
<kbd>1</kbd>〜<kbd>6</kbd> で正面・背面・右舷・左舷・上面・下面に正対し、<kbd>7</kbd> か <kbd>0</kbd>
で斜めに戻ります。正対しているときの見え方・目盛り・辺の掴み方は、従来の平面ビューとまったく同じです。
左上の `斜め — x / y` はドラッグと矢印キーが効く二軸、右下の十字は各軸の向きです。上面で +x が左に来る
のは仕様です（ファイル内の +x は機体の左。*[Coordinates](#coordinates)* を参照）。従来の向きが要るなら
<kbd>6</kbd>（下面）が同じ絵です。

### Shortcuts

<kbd>H</kbd> or <kbd>?</kbd> puts the whole list on screen. The ones worth knowing:

| | |
|---|---|
| <kbd>1</kbd>…<kbd>6</kbd> | square on — front, back, right, left, top, bottom. <kbd>7</kbd> or <kbd>0</kbd> back to the corner |
| drag / <kbd>Alt</kbd>+drag | orbit — the bare drag only where nothing is under the cursor |
| <kbd>B</kbd> <kbd>P</kbd> <kbd>S</kbd> <kbd>C</kbd> | boxes · pylons · seats · points |
| <kbd>Tab</kbd> | the next thing in the list |
| arrows | nudge, <kbd>Shift</kbd> ten times as far, <kbd>Alt</kbd> a tenth. <kbd>,</kbd> <kbd>.</kbd> for the axis the view cannot show you |
| <kbd>N</kbd> <kbd>Shift</kbd>+<kbd>D</kbd> <kbd>Delete</kbd> | add · copy · remove |
| <kbd>F</kbd> / <kbd>Shift</kbd>+<kbd>F</kbd> | fit the box to what is inside it / a box round the picked bone |
| <kbd>Shift</kbd>+<kbd>B</kbd> | a whole set of boxes off the model — <kbd>Enter</kbd> takes it, <kbd>Esc</kbd> throws it away |
| <kbd>Shift</kbd>+<kbd>M</kbd> <kbd>Y</kbd> | mirrored twin · keep twins in step |
| <kbd>G</kbd> | snap: 0.05 → 0.1 → 0.25 → 0.5 → 1 → off |
| <kbd>V</kbd> <kbd>L</kbd> <kbd>A</kbd> | model skin/solid/ghost/wire/off · the names, off to begin with · frame everything |
| <kbd>W</kbd> | the racks and the stores hung on the stations, drawn or not |
| <kbd>X</kbd> <kbd>Shift</kbd>+<kbd>X</kbd> | the collision boxes, drawn or not · the crosses. Nothing is forgotten either way |
| <kbd>Q</kbd> <kbd>E</kbd> <kbd>[</kbd> <kbd>]</kbd> <kbd>K</kbd> | traverse · elevate · centre |
| <kbd>T</kbd> <kbd>\\</kbd> | the tool's colours: slate → noir → paper · hide the panels |
| <kbd>Ctrl</kbd>+<kbd>Z</kbd> | undo |
| <kbd>Ctrl</kbd>+<kbd>S</kbd> | the file, straight back into the folder it was read from — the machine's or the armament's, whichever you were last in. It works from inside a field as well, which is where your hands usually are |
| <kbd>R</kbd> | read the folder again, and whatever is open out of it |

### What the colours mean

| | |
|---|---|
| tinted solids | the model, a colour per bone, for fitting against |
| green | collision boxes on the hull |
| blue | collision boxes on the turret, swung about the ring |
| yellow | collision boxes on the gun: swung about the ring and rocked about the trunnion |
| violet | a crew place, drawn as the crew: a seat is where their *feet* go, and whether their head is inside the roof is the only question anyone asks of one |
| pale blue, tied to a seat by a hairline | that seat's own first-person eye, if it has been given one |
| orange | the turret ring, and the circle its boxes sweep |
| yellow | the trunnion, the barrel and the wedge between full depression and full elevation |
| pale blue | the first-person eye |
| pink | the chase camera, and roughly what it can see |
| teal | the lens of a targeting pod fitted to a special station, and roughly what it would see |
| red | an aircraft's weapon stations |
| amber | an aircraft's special stations, the ones that take a pod |
| painted, in their own sheets | whatever has been hung on the stations: the racks, the stores on them and the pods |
| dashed orange, round a store | two stations' loads into one another |
| dashed blue-grey | the plain hitbox: the shed Minecraft files the entity under |
| dashed orange | the upright box the game will *really* collide with, when a box is turned |
| dashed green and blue | an automatic draft, in the colours of the boxes it would become. While one is up the boxes it would replace are dimmed to a whisper |

The orange one is worth watching. Minecraft can only collide with boxes square to the world, and the
mod does not use its collision at all for these — `Hitbox` is a middle, three half-lengths and three
axes, and what a shot hits and what a player walks into are decided against *that*, exactly, at
whatever angle it is lying at. But the world is still filed by upright boxes, so `Hitbox.reach` — the
patch of world searched before any of the real tests are run — is the upright box round the turned
one, always bigger and at forty-five degrees twice the volume. The editor prints the figure next to
the rotation fields. So a turn costs a wider search and not accuracy: worth having where it really
fits the part, wasted where it saves a tenth of nothing, and a part that two or three small unturned
boxes follow more closely is better off with those.

### An aircraft's stations

A station on an aircraft is one of two things, and the difference is not cosmetic — the game will not
hang a missile on one sort or a pod on the other. Select a pylon and set **what this station takes**:

- **weapon** — armament. Nothing hangs on it directly: a *rack* is bolted on first, and the stores go
  on the rack. How many the station then carries, where each of them hangs and what sort it will take
  are all the rack's to say, and racks are their own files in `data/ashvehicles/rack/`. Drawn red,
  with the hook the pylons have always had.
- **special** — one pod, bolted straight on with no rack in between: a targeting pod, a jammer, a
  decoy set, out of `data/ashvehicles/equipment/`. Nothing here is ever fired. Drawn amber, and as a
  cross rather than a hook, because nothing hangs off it.

A station with a **fixed weapon** is neither. It is part of the airframe, the game ignores the kind
on one, and the editor greys the selector out and writes no `kind` for it.

```json
{ "name": "pylon2_left", "pos": [3.6, 2.65, -2.2], "kind": "weapon" },
{ "name": "sensor_left", "pos": [1.05, 1.65, 2.0], "kind": "special" },
{ "name": "gun",         "pos": [2.0, 3.0, 0.6],   "fixed": "ashvehicles:m61" }
```

The kind is written on every station a player loads, even the plain ones. It is the field that
decides what a station is for, and an aircraft file is read by people as well as by the game.

**Where a pod would be looking from.** Select a special station and a teal cross appears a little
below and ahead of it, with the same dashed line of sight the cockpit eye has. That is where the lens
of a targeting pod fitted there would be, and it is drawn because it is the one thing choosing a
station for a pod actually decides: the picture comes off the pod, so it hangs where the pod hangs.
Put the pod on an inboard fuselage station and the pilot is looking out from under the belly; put it
outboard and they are looking out from under the wing, past everything hanging between. Check the
station has a clear view down and ahead before settling on it.

The offset from the station to the lens is *not* the aeroplane's to set, and there is nothing here to
type: it is read off the pod's own model and lives in the pod's file, under `camera`, in
`data/ashvehicles/equipment/`. Fit a pod to the station and the cross moves to *that* pod's own
figure; with none fitted the editor falls back on a copy of the shipped targeting pod's — `POD_LENS`
in the source, one line — purely so it can draw the preview. A pack that redraws the pod should
change both.

**ポッドのレンズ位置。** 特殊ステーションを選ぶと、その少し下・前方に青緑の十字と視線が出ます。
そこにターゲティングポッドを付けたときのレンズ位置です。映像はポッドから撮るので、どのステーション
に付けるかで見える範囲が変わります — 胴体内側なら機体下面から、翼下外側なら間に吊った兵装越しに。
前下方が抜けているかを確認してから決めてください。ステーションとレンズの相対位置は機体側ではなく
ポッド側（`data/ashvehicles/equipment/` の `camera`）が持ちます。

パイロンには2種類あります。**兵装**はラックを付けてからその上に武装を吊るステーション（赤・フック
形）、**特殊**はポッドを直付けするステーション（橙・十字形）で、ゲーム側はこの2つを取り違えません。
**固定武装**を書いたステーションはそのどちらでもなく、機体に組み込まれた武装なので種別は書きません。

### Getting the boxes out of the way

Forty collision boxes on an aeroplane is forty wireframe boxes drawn over exactly the thing you are
trying to look at, and once there are racks and stores hanging under the wings as well there is no
seeing any of it through the netting. **Boxes** (<kbd>X</kbd>) takes the boxes off the picture and
**Marks** (<kbd>Shift</kbd>+<kbd>X</kbd>) takes the crosses with them — the stations, the seats, the
points and the lines each of them draws — which leaves the model and whatever is hung on it, and
nothing else.

**Names** (<kbd>L</kbd>) is the third of them and starts *off*, because a name is written across
the very shape it is naming: forty boxes on a tank is forty words over the hull, and the name of
the box you are actually dragging is in the panel in front of you the whole time. Switch it on
when the naming is the job — checking a set reads sensibly before it goes in the file. The grid's
own figures are not names and never go: the ruler stays whatever this is set to.

Only the picture. Everything stays in the list, stays in the file, and is back the moment the button
goes on again. What is switched off is also not under the cursor: a box you cannot see cannot be
picked up and dragged half a hull by accident, so hiding is a safe thing to leave on while you turn
the machine about and look at it.

**ボックスと印と名前を消す。** 機体に箱が40個も置かれていると、見たい形の上に緑の枠が40個重なります。
兵装まで吊ればなおさらです。**ボックス**（<kbd>X</kbd>）で箱を、**印**（<kbd>Shift</kbd>+<kbd>X</kbd>）
でステーション・座席・各点の十字とそこから伸びる線を消せます。

**名前**（<kbd>L</kbd>）は3つめで、こちらは**既定で非表示**です。名前は、まさにその名前が指す形の上に
書かれてしまう（戦車なら車体の上に40語）うえ、いま掴んでいる箱の名前は常に左のパネルに出ているので、
形を見ながら合わせる作業には邪魔にしかなりません。ファイルに入れる前に名前を通して確認したいときだけ
オンにしてください。なお目盛りの数字は名前ではないので、この設定に関わらず常に表示されます。

消えるのは表示だけで、一覧にもファイルにもそのまま残り、ボタンを戻せば元通りです。非表示のものは
クリックの対象からも外れるので、見えない箱をうっかり掴んで動かす心配はありません。

### Hanging things on the stations

A cross on a wing is a thing nobody can be wrong about, which is exactly the trouble with it. What is
actually being settled when a station is placed is where a rack and a missile the length of a car will
sit: whether the outboard pair clear each other, whether a bomb hangs through the flap, whether an
inboard rail leaves the undercarriage room to come down past it. None of that can be read off a cross.

So the editor hangs them. Select a station and, under **what hangs here**, pick a rack; the rack's own
places appear beneath it, one line each in the order they load, and each takes whatever that rack will
carry — a launch rail is not offered a bomb, because the game would not take one either. A special
station takes one pod and no rack. Everything is drawn from the same files the game draws it from,
painted in its own sheet, and placed by the same arithmetic: the rack at the station, each store at the
station plus that rack's own offset for it.

**None of it is written to the aeroplane's file, and nothing here has to be tidied up afterwards.** An
aeroplane's file says where its stations are; what is on them is a player's business and lives in the
aeroplane's stack. Opening another machine empties every station, and <kbd>W</kbd> takes the whole load
off the picture without forgetting what was on it.

Two things are said in words rather than left to be spotted. A load that hangs below the lowest part of
the machine is named as such — that is a store the undercarriage has to clear — and two stations whose
loads are *into* one another are boxed in dashed orange and named in the panel. Two things on the same
station never count: a store sits on its rack, and touching it is what a rack is for.

**Every station like this one** copies the load on the selected station onto every other station of the
same sort, which is how a wing is laid out in one press; **Strip them all** takes the lot off. With
**Sym** on, a rack chosen on the left is fitted on the right as well.

It all needs the folder: hand it over with **A folder…** and everything in `rack/`, `weapon/` and
`equipment/` — the data files, the models and the sheets — is there to be hung. Something with no model
of its own is drawn from `default.geo.json` in its own folder, which is exactly what the game does with
one, so a weapon added five minutes ago is visible before it is drawn.

**ステーションに実際に吊って確かめる。** パイロンは十字だけ見ていても正しいかどうか分かりません。
決めているのは、ラックと全長数ブロックの兵装が実際にどこへ収まるか — 隣のパイロンとぶつからないか、
フラップを突き抜けないか、脚が出る隙間が残るか — です。ステーションを選び、**ここに吊るもの**で
ラックを選ぶと、そのラックの搭載位置が順番に並び、各位置にはそのラックが受け付ける種別の武装だけが
出ます。特殊ステーションはポッド1基だけです。描画はゲームと同じファイル・同じ計算（ラックは
ステーション位置、武装はステーション位置＋ラックの搭載オフセット）で、テクスチャもそのまま使います。

**ここで吊ったものは機体ファイルには一切書き込まれません。** 機体ファイルが持つのはステーションの
位置だけで、何を積むかはプレイヤー側の話です。別の機体を開けば空になり、<kbd>W</kbd> で表示だけを
消せます（積んだ内容は保持されます）。積荷が機体の最下端より下に出る場合と、隣のステーションの積荷と
干渉している場合は、橙の破線とパネルの文で知らせます（同一ステーション内はラックと武装が接するのが
当然なので数えません）。**全ステーションを同じ積みかたに**で選択中の積みかたを同種のステーション全部
へ複写、**全部外す**で全解除。**Sym** が入っていれば左右の対にも同じものが付きます。

これには**フォルダー…**でフォルダーを渡す必要があります。`rack/`・`weapon/`・`equipment/` の
データ・モデル・テクスチャをそのまま読みます。モデルが無いものは、ゲームと同じく同フォルダーの
`default.geo.json` で描きます。

### Where each seat looks out from

A seat is where a crew member's feet are. Where their *eye* is, is a second point, and it used to be
one point for the whole machine — so a CV90 showed its seven dismounts the commander's cupola, an
F-14's back-seater looked out of the front canopy, and a destroyer sat a man eight blocks below the
bridge and showed him the bridge.

So the eye belongs to the seat. Select a seat and press **Give this seat its own eye**: a second
point appears above it, tied to its seat by a hairline, and you drag it to wherever that crew member's
head actually is — out of a hatch, under a canopy, behind a vision block. It is listed under its own
seat, so which head goes with which pair of feet is never in question.

A seat with no eye of its own goes on using the machine's single eye, the pale blue **cockpit eye**
under *Points*, exactly as it always has. Nothing has to be given one, and a machine is improved a
seat at a time. In the file that is the difference between a bare point and a block:

```json
"seats": [
  { "pos": [0.97, 1.35, 2.36], "eye": [0.97, 2.05, 2.7], "mount": "hull" },
  { "pos": [0.78, 1.6, -0.44], "eye": [0.78, 3.45, -0.34], "mount": "turret" },
  [-0.69, 1.6, -0.44]
]
```

**mount** is what that eye is bolted to, and a tank's crew genuinely differ: the commander's head is
out of the turret roof and comes round with the gun, the driver's is in the glacis and does not.
Traverse the turret with <kbd>Q</kbd> <kbd>E</kbd> and watch — the ones on the turret swing about the
ring and the ones on the hull stay where they are, which is the only way to tell that you have put
them on the right thing. Left unsaid it is whatever the machine does by default: the turret on
anything with one, the hull on a ship or an aircraft, which is what every machine did before.

Mirroring a seat brings its eye across the centreline with it, and deleting a seat takes its eye
with it. An aircraft has no turret, so it has no *mount* to set.

座席は足元、視点は頭です。従来は機体に1つしか視点が持てず、複数座席の機体では全員が同じハッチから
外を見ていました。座席を選んで **この座席専用の視点を置く** を押すと、その座席専用の視点が生まれ、
ドラッグで実際の頭の位置に置けます。**取り付け先**（車体／砲塔）は、その視点が砲塔と一緒に回るか
どうかです。視点を置かなかった座席は、従来どおり「点」の一人称視点を使います。

### Coordinates

A `.geo.json` is written in model units, sixteen to a block, Y up, facing north. The mod describes
a machine in blocks in the machine's own frame — x to the right, y up, z towards the nose. Getting
from one to the other is three steps and every one of them flips something:

1. GeckoLib bakes the geometry with **x turned round** — `BakedModelFactory` builds a cube's origin
   as `-(origin.x + size.x)/16` and a bone's pivot as `-pivot.x` — so a model point lands at
   `(-mx, my, mz)/16` in the space it draws in.
2. `VehicleRenderer.applyRotations` turns that by a half circle about Y, after the attitude, which
   puts it at `(mx, my, -mz)/16` in the frame the attitude leaves behind.
3. That frame is not the file's. `Attitude.toWorld` negates x on the way out, and
   `VehicleShapeRenderer` translates a box by `-offset.x` for the same reason: **inside the mod,
   +X points left.** So a file offset is that same point written `(-fx, fy, fz)`.

Setting the two against each other:

    machine = (−mx · k, my · k, −mz · k),   k = model scale ÷ 16

**The x is the part that catches people, the old editor included.** It cannot be caught by looking:
mirror a symmetric aeroplane and it is the same aeroplane, which is why it went unnoticed for as
long as there was nothing here but aeroplanes. It shows on a vehicle that is not symmetric, and the
Leopard settles it four ways over: its file puts the turret ring at x +0.03 and the trunnion at
−0.01, and its model's turret and gun bones pivot at exactly those two figures under the mapping
above and at their negatives under any other; its driver's hatch lands on the right of the hull,
where a Leopard's driver sits, and its MG on the left of the turret, where the loader's is.

Nothing in the repository is visibly wrong because of it — every set of boxes here is a symmetric
one, give or take a hand-fitted centimetre — but anything placed off the centreline from now on
would have been, and it is not the sort of mistake you find by staring at a tank.

Rotation follows the mod's own convention, the same one `VehicleShape.Box` documents: **x** pitches
the box nose up, **y** yaws it to the right, **z** rolls its right-hand side down. The editor turns
the yaw and the roll around against the mod's own figures, because a rotation applied in a frame
where +X points left is the same rotation the other way round in this one.

**The same care applies to the model's own cubes and bones.** GeckoLib negates a cube's x and y
angles as it loads them and applies Z, then Y, then X; conjugating that back through the half circle
above turns the z and the x around again, which is what `bbRot` does. It is written in model
coordinates because that is where it is used — the corners are turned first and mapped afterwards.
Getting this wrong leaves single-axis parts looking right and scrambles anything turned about two
axes at once, which on a real model is most of it.

### The turret

Two pivots have to agree and the file only knows about one of them.

The model turns its turret about whatever pivot the geometry gives the bone. The **boxes** are
swung about `turret.ring`, out of the vehicle's file, because nothing on the server has a model to
ask. If the two are not the same point, the boxes go round a different circle from the armour, and
a shell aimed at the mantlet passes through air — at every angle but dead ahead, which is the one
angle anybody checks. Only the ring's x and z matter; y is not used by the traverse at all, and is
there to mark where the ring is for the eye.

The trunnion is the same story one bone down: the gun bone elevates about its own pivot and the
muzzle is worked out from `armament.trunnion` and `barrel_length`, so a trunnion that is not where
the gun bone pivots means the drawn barrel and the fired round part company as the gun is raised.
Its y and z are what matter there.

The editor draws both circles, checks both pivots against the model, and offers to put each point
where the model says it should be.

`VehicleShape.Mount` has three cases and a box may be bolted to any of them. **hull** is the
machine itself. **turret** is swung about `turret.ring` by the traverse. **gun** is swung about the
ring as well *and* rocked about `armament.trunnion` by the elevation, which is what a barrel does and
what anything clamped to a barrel does with it — the mantlet, and the coaxial's own box.

Elevate the gun (<kbd>[</kbd> <kbd>]</kbd>) and a box marked *gun* rises with the model's barrel.
If it does not, the trunnion is in the wrong place, exactly as a box marked *turret* drifting under
<kbd>Q</kbd> <kbd>E</kbd> means the ring is.

Get that mount right or the barrel is shot at through the wrong volume: left on *turret*, a barrel's
box stays at its resting angle however far the gun is laid, so a gun elevated at an aircraft is hit
where it is not and missed where it is.

### Seeing the model

The model is drawn filled, tinted by bone, and painted back to front. Five hundred cubes of
wireframe on top of one another is a haze you cannot read a shape out of, and the point of having
the model here at all is to see what you are fitting to. **Ghost** is the same picture at a fraction
of the ink, for when the boxes are what matter; **Wireframe** switches to lines when you need to see
through it — into a hull, say, to place something inside; and picking a bone out under **Bones**
dims everything except that bone, which is how you find one part in a model made of two hundred.

**Skin** paints the model with its own texture. Drop the `.png` in — or let the folder find it,
which it does, by the name the model goes by — and the machine comes up as the machine rather than
as a heap of coloured blocks. It is the mode to fit a driver's eye in: the cockpit reads as a
cockpit, and a hatch you can see is a hatch you can put a seat under. The tinted mode is still the
one to fit *boxes* in, because there a colour means a bone and the seams are where the parts are.

Everything here is a box and every view is a flat projection, so a face comes out as a
parallelogram whatever the model has been turned by, and a parallelogram is what an affine
transform puts a rectangle onto: each face is one draw, at the sheet's own resolution, unsmoothed.
Both ways of writing UVs work — a cube that names every face and a cube that gives one corner and
lets the six be laid out in the net Minecraft has always used, `mirror` included. Which corner of a
face each patch is pinned to is read off GeckoLib’s own cube builder rather than worked out from
the format, because two things about it are not what anyone would assume: the u axis runs from the
second corner of the quad to the first, and `east` is the file’s −x side because the loader has
already turned the model half round by the time the faces are named. Faces are lit by which way
they point, or a machine painted one green all over would come out as a green blob.

A model that asks for a sheet of one size and is given another is stretched to fit, exactly as the
game does it — the Leopard's model says 1024 over a sheet drawn at 2048 — and the panel says so
when the two disagree, because a sheet swapped for one of an odd size is worth knowing about.

A machine is nearly always smaller on screen than its own sheet — a tank painted on two thousand
pixels a side is three hundred pixels wide in the pane — so the sheet is kept at every size down,
each half the one before, and a face reads from whichever copy is nearest its own size. Those
copies are held to being **cutout**, the way the game itself paints these sheets: every texel
either painted or not, with nothing part-way. That is not a nicety. A sheet is three quarters
empty — the artwork is islands on a transparent field — and shrinking one averages the edge of
every island with the nothing beside it, so a plain copy comes back with a soft translucent
fringe where the source had a hard edge. Nine steps of that and a sheet 23% solid is 0% solid,
and a machine painted out of it is a machine you can see the far side of. Snapping the alpha back
after each step is what stops that, and it is exactly what `RenderType.entityCutout` does in the
game: drawn above a tenth of a texel's coverage, and drawn solid. A sheet that really is painted
in half-tones — a canopy meant to be seen through — is spotted and left alone, blending and all.

**テクスチャ**: `.png` をドロップするか、フォルダー読み込みで自動的に見つけます。モデルを実際の
テクスチャで表示するモードで、<kbd>V</kbd> で切り替わります。ボックスを合わせるときは従来のボーン別
配色のほうが見やすく、座席や視点を置くときはテクスチャ表示のほうが分かりやすい、という使い分けです。

機体は画面上ではたいていシートより小さく写る（2048pxで描かれた戦車がペインでは300px）ので、シートは
半分ずつ縮小したコピーを持ち、各面は自分の大きさに近いコピーから読みます。この縮小コピーは
**cutout**（各テクセルは塗ってあるか無いかのどちらかで、中間を持たない）に保たれます。これは
見た目の好みではありません。シートは4分の3が空白で、絵柄は透明な地の上の島なので、そのまま縮小すると
島の縁が隣の「無」と平均され、元は硬かった縁に半透明の縁取りができます。9段も縮小すれば、23%が
不透明だったシートは不透明0%になり、それで塗られた機体は反対側が透けて見えます。各段でアルファを
戻すのがその対策で、ゲーム側の `RenderType.entityCutout`（テクセルの被覆が1割を超えれば描画し、
描くときは完全不透明）と同じ扱いです。本当に半調で描かれたシート（透けて見せるキャノピーなど）は
検出して、ブレンドも含めそのまま残します。
