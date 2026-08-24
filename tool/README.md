# AshVehicles tools

Editing aids for the mod's data files. Nothing here is part of the mod: nothing is compiled,
nothing ships, and the mod neither knows nor cares that this folder exists.

## vehicle-editor.html

Draws a machine's model and lets you build everything that has a *place* on it over the top: the
collision boxes, the turret ring, the trunnion the gun swings on, the crew seats, both cameras, an
aircraft's pylons, and the plain hitbox the game files the entity under. Three orthographic views,
a turntable, and a pose you can traverse and elevate to check that what you have built follows the
model when the turret comes round.

Everything is shown and typed in the machine's own frame in blocks, so what you read on screen is
exactly what goes in the file.

Open the file in a browser. It needs no server, no build step and no network — it is one page with
no outside dependencies, so it works offline and from a memory stick.

It reads in English and in Japanese. Which one it opens in is whatever the browser is set to, the
button at the top of the sidebar changes it, and the choice is remembered. Nothing but the wording
moves: the boxes, the draft on screen and everything placed stay exactly as they were, and the two
things that are *not* translated are deliberate — a bone's name and a box's name are what go in the
file, so they are shown exactly as they are written there.

日本語で開きます。右上のボタンで英語と切り替えられ、選んだほうが次回も使われます。ボーン名と
ボックス名だけは、ファイルに書かれるとおりの綴りで表示します。

It comes in three themes — **Slate**, the blue-grey it opens in; **Noir**, near-black and sharper,
for a dark room; and **Paper**, for a bright one or a projector. The button beside the language one
walks through them and <kbd>T</kbd> does the same, and the choice is remembered. A theme is not
just the panels: the grid, the model's own shading and every label on the canvases come out of the
same list of colours, so *Paper* is a genuinely light tool rather than a light frame round a dark
picture. The colours a box, a seat or the ring is drawn in keep their meanings in all three.

The bar between the panels and the views is draggable: a machine with forty boxes wants a wider
list than one with four, and a name like `hull_glacis_left` wants more room than `hull`.
Double-clicking it puts the width back, <kbd>\\</kbd> hides the panels altogether, and the width
you leave it at is the width it opens at next time.

配色は 3 種類（スレート／ノワール／ペーパー）。言語ボタンの隣、または <kbd>T</kbd> で切り替わり、
次回も同じ配色で開きます。パネルとビューの境界はドラッグで幅を変えられます（ダブルクリックで既定に戻ります）。

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
   to write. Weapons, animations, item models and the language files are not machines and are left
   out of the list.

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
3. **Place things.** Left-drag moves the selected item in the two axes of whichever pane you are
   dragging in, so put it where it goes in the top view and then set its height in the side view.
   Dragging an *edge* of the selected box moves that face and leaves the opposite one where it is.
   Right-drag pans, the wheel zooms towards the cursor, the arrow keys nudge, and every number can
   be typed exactly.
4. **Build the boxes off the bones.** Press <kbd>Shift</kbd>+<kbd>B</kbd> for a whole set at once,
   worked out from the model — see *[Automatic boxes](#automatic-boxes)* below — or build them one
   at a time: pick a bone under **Bones** and press <kbd>Shift</kbd>+<kbd>F</kbd> and you get a box
   round exactly that part, marked as being on the turret if that is where the bone is. **Fit** does
   the other half — make a box roughly big enough over something, press <kbd>F</kbd>, and it closes
   onto whatever geometry is inside it.
5. **Mirror the pairs.** A machine is symmetric and its boxes come in pairs, so **Mirror** makes
   the twin across the centreline and **Sym** keeps the two in step from then on. Fit a skirt once.
6. **Traverse the turret** (<kbd>Q</kbd> <kbd>E</kbd>) and watch the boxes marked *turret* follow
   the model. If they drift, the ring is in the wrong place, and the editor will say so and offer
   to put it where the model's own turret bone turns.
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

The blocks a machine either has or has not — an afterburner, a rotor or a thrust-vectoring system,
a missile rail, a radar — have a switch beside their heading. The rest are always there.

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
cube comes within half a cell of its middle, tested against the cube as it really lies — and the
cells are then swept up into boxes. The budget is met by making the cells coarser, never by putting
a box round two things that are not touching, and every box comes out square to the machine, which
is worth having on its own account: a turned box is searched for in the upright box that contains
it, and at forty-five degrees that is twice the volume for the same part.

**Two figures, not one.** The panel reports how much of the *machine is covered* as well as how
much of the *draft is machine*, and the first is the one that decides whether a hitbox is any good.
A draft scores well on the second by shrinking away from the model — boxes hugging a few solid
lumps, the rest of the hull outside them — and that is a machine you can shoot straight through.
On the Zumwalt, *Follow the model* at eighty boxes gets the enclosed volume down to 24,000 blocks³
and 99% of it is ship, which reads beautifully and covers **half the vessel**. Tracing the same
ship at eighty boxes encloses 44,000 and covers 92%. The panel says so in as many words, and warns
outright when a draft leaves more than a tenth of the machine outside every box.

**Turning.** A turned box has to be smaller than the upright one by *turn saves %* before it is
taken, and that figure starts at 2 — which is to say, take the turn wherever it is worth anything
at all. It used to be fifteen, on the reasoning that a turn is not free: the game files a box under
the upright box that contains it and searches that first, so a box at forty-five degrees is looked
for over twice its own volume. The reasoning is sound and it was still the wrong default for these
models, which are drawn almost entirely out of turned cubes — a T-64 is 1303 of them and 1186 are
turned. A draft that will not turn puts upright boxes round sloped armour, and that empty space is
paid for on every shot as surely as a wider search is. Set it to 0 to turn wherever it helps by any
margin at all, or wind it back up to 15 for the old behaviour. The angle search goes round three
times now, sweeping each axis coarsely and then closely about whatever the coarse sweep liked, so
a part at 27° is fitted at 27° and not at 25°.

On the Leopard 2A4 at eighty boxes, *Follow the model* turns 31 of its 69 boxes where it used to
turn 3, and the enclosed volume falls from 125 to 111 blocks³.

Turning costs the tracing pass coverage, and there is no arrangement in which it does not: the
upright box is the smallest square box round the geometry it holds, so a turned box that is
*smaller* is necessarily one that has let something go. Tracing the Leopard at eighty boxes gives
141 blocks³ at 92% covered square, and 122 blocks³ at 84% covered turned. Both are honest answers
and the panel prints which one you have got.

So: *by bone* for few boxes and good coverage; *trace* when coverage matters and you can spend the
boxes; *follow* when you want the tightest enclosure of the solid parts and know what you are
giving up. What any of them cost is on the other side of the file — every box is a `PartEntity` the
game tests against every shot and every mover, and it takes an entity id of its own from a run
reserved after the machine's. Vanilla's ender dragon claims nine of those. Forty is already a lot.

*(One box per cube, which is the other thing this could have meant, is not offered. The T-64 is
1303 cubes, 1186 of them turned; that is 1303 parts and 1303 consecutive entity ids for one tank,
and a turned box is searched for in the upright box that contains it, which at forty-five degrees
is twice the volume.)*

「モデルに合わせる」は逆の手順で、全キューブから最も安い組を統合し続けて上限個数まで減らします。
「輪郭をなぞる」はさらに別で、モデルをセルに焼いてそのセルを箱で拾い上げます。上限個数はセルを
粗くすることで満たすので、離れたものを1つの箱に入れることがなく、出来る箱はすべて機体に対して
正立します。

「回転で節約 %」は、回転した箱が正立の箱よりどれだけ小さければ回転を採用するか、です。既定は 2%
（＝少しでも得なら回す）。以前は 15% でしたが、このMODのモデルは回転キューブだらけ（T-64は1303中
1186が回転）なので、回さない下書きは傾斜装甲に正立の箱を被せることになり、その無駄も結局は毎弾の
コストです。0 にすればわずかでも得なら回し、15 に戻せば以前の挙動です。角度探索も3周・粗探索＋
細探索にしたので、27度の部品は27度で嵌まります。

なお「輪郭をなぞる」で回転を使うと、必ずカバー率が落ちます（正立の箱がその領域の最小の箱なので、
それより小さい回転箱は何かを取りこぼしている）。レオパルト80個で、回転なし141ブロック³/カバー92%、
回転あり122ブロック³/カバー84%。どちらも正しい答えで、どちらになったかはパネルに出ます。

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

### Shortcuts

<kbd>H</kbd> or <kbd>?</kbd> puts the whole list on screen. The ones worth knowing:

| | |
|---|---|
| <kbd>1</kbd>…<kbd>4</kbd> | one pane full — top, side, front, turntable. <kbd>0</kbd> for all four |
| <kbd>B</kbd> <kbd>P</kbd> <kbd>S</kbd> <kbd>C</kbd> | boxes · pylons · seats · points |
| <kbd>Tab</kbd> | the next thing in the list |
| arrows | nudge, <kbd>Shift</kbd> ten times as far, <kbd>Alt</kbd> a tenth. <kbd>,</kbd> <kbd>.</kbd> for the axis the pane cannot show you |
| <kbd>N</kbd> <kbd>Shift</kbd>+<kbd>D</kbd> <kbd>Delete</kbd> | add · copy · remove |
| <kbd>F</kbd> / <kbd>Shift</kbd>+<kbd>F</kbd> | fit the box to what is inside it / a box round the picked bone |
| <kbd>Shift</kbd>+<kbd>B</kbd> | a whole set of boxes off the model — <kbd>Enter</kbd> takes it, <kbd>Esc</kbd> throws it away |
| <kbd>Shift</kbd>+<kbd>M</kbd> <kbd>Y</kbd> | mirrored twin · keep twins in step |
| <kbd>G</kbd> | snap: 0.05 → 0.1 → 0.25 → 0.5 → 1 → off |
| <kbd>V</kbd> <kbd>L</kbd> <kbd>A</kbd> | model skin/solid/ghost/wire/off · names on and off · frame everything |
| <kbd>Q</kbd> <kbd>E</kbd> <kbd>[</kbd> <kbd>]</kbd> <kbd>K</kbd> | traverse · elevate · centre |
| <kbd>T</kbd> <kbd>\\</kbd> | the tool's colours: slate → noir → paper · hide the panels |
| <kbd>Ctrl</kbd>+<kbd>Z</kbd> | undo |
| <kbd>Ctrl</kbd>+<kbd>S</kbd> | the file, straight back into the folder it was read from |

### What the colours mean

| | |
|---|---|
| tinted solids | the model, a colour per bone, for fitting against |
| green | collision boxes on the hull |
| blue | collision boxes on the turret, swung about the ring |
| violet | a crew place, drawn as the crew: a seat is where their *feet* go, and whether their head is inside the roof is the only question anyone asks of one |
| pale blue, tied to a seat by a hairline | that seat's own first-person eye, if it has been given one |
| orange | the turret ring, and the circle its boxes sweep |
| yellow | the trunnion, the barrel and the wedge between full depression and full elevation |
| pale blue | the first-person eye |
| pink | the chase camera, and roughly what it can see |
| red | an aircraft's pylons |
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

One thing it shows that looks like a fault in the editor and is not: elevate the gun and the model's
barrel rises while the box marked *gun* stays flat. That is the mod. A box is bolted either to the
hull or to the turret — `VehicleShape.Mount` has no third case — so a gun's box traverses and never
elevates. It is worth knowing when you size one: a box drawn round a barrel at its resting angle is
the box the barrel is shot at through the whole of its travel.

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

**テクスチャ**: `.png` をドロップするか、フォルダー読み込みで自動的に見つけます。モデルを実際の
テクスチャで表示するモードで、<kbd>V</kbd> で切り替わります。ボックスを合わせるときは従来のボーン別
配色のほうが見やすく、座席や視点を置くときはテクスチャ表示のほうが分かりやすい、という使い分けです。
