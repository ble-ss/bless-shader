# the gallery

a second test map for the rmls-client shader mod, after `../showcase`. where the showcase is fifteen separate plots strung along a path, the gallery is one building: a central hall with fifteen rooms opening off it, all connected, all walkable. vanilla blocks only, no mod dependency of its own.

footprint 91 by 59 blocks, x -1..89 by z -28..30, floors at y 64, built from origin 0 64 0.

## install

1. start (or make) a superflat or void creative world.
2. copy this `gallery` folder into that world's `datapacks/` folder, so the path reads `<world>/datapacks/gallery/pack.mcmeta`.
3. in world, run `/reload`.
4. stand near spawn (0 64 0) and run `/function rmls_gallery:build`.
5. run any `/function rmls_gallery:go/<room>` below to land at that room's best viewpoint, or just walk: every room is reachable on foot from the hall.

each room function clears its own footprint before placing anything, so re-running `build` (or a single `scene/<room>`) after an edit is safe.

## pack format

data pack format is **107, minor 1**, the same two-part major/minor schema the showcase documents. `pack.mcmeta` copies vanilla's own bundled feature-pack shape: `"min_format": [107, 1]`, `"max_format": 107`, no `pack_format` key. function folder is singular `function`, suffix `.mcfunction`.

## the rooms

walk them in this order and the building reads west to east down the hall, north side and south side by turns.

| # | room | go command | what to look for |
|---|---|---|---|
| 1 | hall | `/function rmls_gallery:go/hall` | the spine: skylights every eight blocks, a sunken pool, copper and iron pillars on a polished deepslate floor. sun shafts, reflections, and the whole length of the volumetric air |
| 2 | two suns | `/function rmls_gallery:go/two_suns` | a sealed dark room, a glowstone wall facing a sea lantern wall, grey stone shapes between them. every shape is lit warm on one side and cold on the other |
| 3 | colour doors | `/function rmls_gallery:go/colour_doors` | seven lit alcoves recessed into a black wall over polished blackstone, red through magenta. coloured light spill and bloom, and the floor takes each colour |
| 4 | the chapel | `/function rmls_gallery:go/chapel` | ten tall stained glass windows in oak mullions, lit from a glowstone wall in a sealed cavity outside them. stained glass strips in the roof too, so run `/time set 6000` and watch noon paint the nave |
| 5 | living room | `/function rmls_gallery:go/ikea_living` | white walls, spruce floor, a quartz sofa, a coffee table on a layered rug, bookshelves, an ochre froglight pendant on a chain, and a corner lamp of end rods inside a glass box |
| 6 | kitchen | `/function rmls_gallery:go/ikea_kitchen` | smooth stone counters with a cauldron sink, a smoker and blast furnace, a copper and iron backsplash for reflections, lanterns under the cabinets, a two-by-two tiled floor |
| 7 | bedroom | `/function rmls_gallery:go/ikea_bedroom` | sea lantern side lamps behind panes, a polished deepslate mirror in a dark oak frame, wool curtains over a real window, and a cross of shroomlight in the ceiling inside dark oak trapdoors |
| 8 | bounce box | `/function rmls_gallery:go/bounce_box` | the cornell box: a white room, one red wall, one green wall, one skylight, two white boxes. the classic two-bounce test, colour bleeding onto white |
| 9 | materials | `/function rmls_gallery:go/materials` | twelve three-by-three metal pads in one wall, a pool beside them, a glass wall behind, all lit by a single line of end rods. iron, gold, copper, diamond, netherite, emerald, lapis, waxed cut copper, the three raw metals, polished deepslate |
| 10 | fog stairs | `/function rmls_gallery:go/fog_stairs` | a twenty-step stair cut down through stone from y 64 to y 45, a lantern every six steps, a slit of sky at the top. stand at the bottom and look up: volumetric air, and sun shafts down the shaft at `/time set 1000` |
| 11 | rim window | `/function rmls_gallery:go/rim_window` | a dark room, one wall of sea lanterns behind panes, a black concrete figure standing in front of it. **rim light is not in the shader yet** - the sign inside says so. this is the room that will show it when it lands |
| 12 | depth gallery | `/function rmls_gallery:go/depth_gallery` | the second storey: a balcony at y 72 over the hall, reached by the stone brick stair at the hall's west end, with a quartz pillar every four blocks. look east down fifty-eight blocks of hall for depth, haze and the far end |
| 13 | lava light | `/function rmls_gallery:go/lava_light` | a small chamber floored in glass over a lava pool, magma on every wall. emissive orange from below and the bounce off it |
| 14 | mirror hall | `/function rmls_gallery:go/mirror_hall` | a corridor of polished deepslate and glass by turns, lit by candles on the floor. reflections of reflections, and the glass panels look straight through into the hall and the lava room |
| 15 | the dark room | `/function rmls_gallery:go/dark_room` | a sealed three-by-three closet with one candle. stand thirty seconds, then step out into the corridor: that is eye adaptation |

every room has a sign at its entrance repeating this in-game. the hall carries nine of them, the mirror hall three, and the living room, kitchen and depth gallery one each; the rim window carries a second sign inside, on the floor, about rim light.

## how the building fits together

- the **hall** runs x 0..76 at z -5..5, ceiling at y 80, and holds every doorway. its east wall opens into the **mirror hall**, which runs north up the east side and feeds the **rim window**, the **lava light** room and the **dark room**.
- north side, west to east: **two suns**, **chapel**, **bounce box** (through a short connector, so the cornell box stays sealed), **materials**.
- south side, west to east: **colour doors**, the ikea suite, **fog stairs**. the **living room** opens into the **kitchen**, which opens into the **bedroom**: none of those three has its own hall door except the living room.
- the **depth gallery** is the only second storey, a balcony inside the hall itself.

## regenerating

`build_gallery.py` beside this file writes every function and `pack.mcmeta` from one coordinate plan. run `python3 rmls/gallery/build_gallery.py` after editing it. it reads the 26.2 client jar's blockstate list at startup and aborts on an unverified block id, and aborts on any fill of 32768 blocks or more, so a broken edit cannot reach a function file.

## verification

- **block ids**: every id used was checked against `assets/minecraft/blockstates/<id>.json` inside the deobfuscated 26.2 client jar (`~/.gradle/caches/fabric-loom/26.2/minecraft-client.jar`), the way the showcase README documents. the generator fails hard on an unverified id, so nothing unverified can reach a function file. **block ids that could not be verified: none.**
  - one rename caught this way: `chain` does not exist in 26.2. it is `iron_chain` now, beside `copper_chain` and the weathered variants. the living room pendant uses `iron_chain`.
  - `froglight` alone is not a block; only `ochre_froglight`, `verdant_froglight` and `pearlescent_froglight` are.
- **fill sizes**: every `fill` is under the 32768-block limit. the largest is the hall's own footprint-clearing air fill at 80 x 25 x 13 = 26,000. the largest non-air fill is the fog stairs' solid stone mass at 9 x 34 x 24 = 7,344.
- **the envelope is tight**: the finished build was simulated block for block and flood-filled from outside. with the fog stairs' sky slit plugged for the test, the outside reaches no room. the slit is the one deliberate opening, and it is what the room is for.
- **every room connects**: a flood from the hall viewpoint reaches all fifteen rooms on foot.
- **every `go` viewpoint** stands on a solid floor with two blocks of head room. none of them lands inside a block or over a hole.

## fill counts

per room, air clears included:

| room | footprint | fills | setblocks |
|---|---|---|---|
| hall | 79 x 13 | 68 | 9 |
| two_suns | 21 x 18 | 14 | 0 |
| colour_doors | 23 x 18 | 25 | 0 |
| chapel | 25 x 22 | 75 | 6 |
| ikea_living | 22 x 20 | 15 | 16 |
| ikea_kitchen | 18 x 14 | 44 | 17 |
| ikea_bedroom | 18 x 10 | 17 | 20 |
| bounce_box | 15 x 16 | 15 | 0 |
| materials | 13 x 21 | 22 | 10 |
| fog_stairs | 9 x 24 | 24 | 10 |
| mirror_hall | 6 x 26 | 23 | 10 |
| rim_window | 9 x 11 | 16 | 1 |
| lava_light | 9 x 9 | 11 | 1 |
| dark_room | 5 x 5 | 7 | 1 |
| depth_gallery | 59 x 4 | 17 | 43 |
| **total** | **91 x 59** | **393** | **144** |

25,247 blocks stand when the build finishes.

## notes

- **build order is not tour order.** `build.mcfunction` runs the mirror hall before the rim window and the lava light, because the mirror hall lays the corridor's east wall in alternating deepslate and glass segments, and those two rooms then rebuild their own side of it. the depth gallery runs last, since its balcony sits inside the hall's air. the room table above is the walking order; the function order is the dependency order.
- **the doorways are open.** unlike the showcase, which sealed each dark room behind an iron door, this is one building and you are meant to walk it. the dark rooms that matter are still sealed from the sky: two suns, colour doors, the bounce box (skylight aside), the rim window, the mirror hall, the lava light and the dark room have no opening to daylight, only to the hall.
- **rim light is the one thing the shader cannot do yet.** the rim window is built anyway, and says so on its sign, so the room is ready the day the feature lands.
- **glass pane walls carry their connection states.** a pane placed by `setblock` or `fill` keeps whatever state the command gives it and never gets a neighbour update, so a default pane renders as a lone post. the living room windows are filled as `glass_pane[east=true,west=true]` and the rim window's as `[north=true,south=true]`, or they would read as a row of gaps.
- **candles and lanterns stay lit** for the same reason: `/setblock` writes the exact state and nothing nearby fires a neighbour update to recompute it.
- **two water bodies and one lava body** are each enclosed in a solid basin one block larger than the liquid on every side, so nothing flows out into the void: the hall pool, the materials pool, and the lava under the glass floor.
- **the chapel's windows run down its long wall**, not across its end: ten one-block windows in the west wall, separated by eleven oak log mullions, with the glowstone lantern wall sealed in a cavity one block behind them. a row of hanging lanterns sits in that cavity too.
- `stone_slab[type=top]`, `smooth_quartz_slab`, `smooth_stone_slab` and `quartz_slab` are all used as furniture tops. slabs read as half-height to the shader's depth pass, which is part of what the materials and ikea rooms are for.
