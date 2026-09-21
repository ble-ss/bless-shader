# bless shader showcase

a test map for the bless shader mod: fifteen scenes, each built to show one lighting feature at the angle it reads best. built with vanilla blocks only, no mod dependency of its own.

## install

1. start (or make) a superflat or void creative world.
2. copy this `showcase` folder into that world's `datapacks/` folder, so the path reads `<world>/datapacks/showcase/pack.mcmeta`.
3. in world, run `/reload`.
4. stand near spawn (0 64 0) and run `/function rmls_showcase:build`.
5. run any `/function rmls_showcase:go/<scene>` command below to jump straight to a scene at its best viewing angle, or just walk the stone path west to east - it runs past every scene entrance in order.

the build clears its own footprint before placing anything, so it is safe to re-run `build` (or a single `scene/<name>` function) after editing.

## pack format

data pack format is **107, minor 1** (2-part major/minor, not the old single integer). read straight from the deobfuscated 26.2 client jar:

- `net.minecraft.SharedConstants.DATA_PACK_FORMAT_MAJOR` = `107`, `DATA_PACK_FORMAT_MINOR` = `1` (confirmed via `javap -v`, `ConstantValue` attributes on the merged jar's classes).
- vanilla's own bundled feature packs (`data/minecraft/datapacks/trade_rebalance/pack.mcmeta` inside the client jar) use the modern schema: `"min_format": [107, 1]`, `"max_format": 107` - no `pack_format` key. this pack's `pack.mcmeta` copies that exact shape.
- `net.minecraft.server.packs.metadata.pack.PackMetadataSection` confirmed the schema is a `PackFormat(major, minor)` `InclusiveRange`, not a flat int - matches the vanilla example above.
- `net.minecraft.server.ServerFunctionLibrary`'s constant pool confirmed the folder name is singular **`function`** (not `functions`) and the file suffix is `.mcfunction` - both read directly off the class's `Utf8` constants.

## scenes

| scene | go command | what to look for |
|---|---|---|
| sundial | `/function rmls_showcase:go/sundial` | five pillars of different heights and a 2-wide wall; run `/time set 1000`, `6000`, `12000`, or `18000` and watch the shadows swing |
| lantern gallery | `/function rmls_showcase:go/lantern_gallery` | a dark hall with 17 alcoved emitters (torch through amethyst cluster) - coloured light, bounce, and each emitter's hot-spot |
| mirror pool | `/function rmls_showcase:go/mirror_pool` | a water pool plus iron, gold, diamond, copper, and polished deepslate pads - water and metal reflections, the glint |
| glass house | `/function rmls_showcase:go/glass_house` | a glass room with a tinted wall and white/red/blue stained-glass accents - how light passes through instead of bouncing |
| bounce room | `/function rmls_showcase:go/bounce_room` | red and blue concrete walls in a white room with one skylight - watch colour bleed onto the white floor |
| the blinding corridor | `/function rmls_showcase:go/blinding_corridor` | a diamond-block tunnel lined with verdant froglight - the exposure test |
| the cave | `/function rmls_showcase:go/cave` | a dug room under a stone hill, one lava pool, glow lichen, amethyst, a single torch - bounce and emissive light in the dark |
| the grove | `/function rmls_showcase:go/grove` | oak and spruce trees, grass, flowers, a small pond - canopy shadows and the outdoor look |
| fog gully | `/function rmls_showcase:go/fog_gully` | a 40-block trench lined with lanterns - volumetric air and sun rays looking north along it |
| the nether stage | `/function rmls_showcase:go/nether_stage` | netherrack, soul fire, a lava fall, glowstone, magma, crimson/warped stems, shroomlights - the nether look without leaving the overworld |
| two suns | `/function rmls_showcase:go/two_suns` | a sealed polished-deepslate room, glowstone west wall against sea-lantern east wall, four stone-family pillars in the middle to catch both colours |
| colour doors | `/function rmls_showcase:go/colour_doors` | a sealed black room, seven glowing alcoves along the north wall running red through magenta - red and blue are stained-glass fronts over a hidden glowstone backing |
| brick parlour | `/function rmls_showcase:go/brick_parlour` | a sealed brick room, four flush pearlescent-froglight ceiling panels, white-concrete boxes, a pink-concrete bench, a moss/terracotta chequer corner |
| stained chapel | `/function rmls_showcase:go/stained_chapel` | a sealed smooth-stone hall, seven stained-glass windows in oak-log mullions on the north wall lit from outside by a sea-lantern wall standing in for the sun, oak-fence pews, soul + redstone torches on the mullions |
| slit hall | `/function rmls_showcase:go/slit_hall` | a sealed sandstone room open only on its west wall's vertical slits; run `/time set 23500` (sunrise) or `13000` (sunset) and watch the low sun stripe the floor and hit the east wall |

each scene has a sign at its entrance repeating this description in-game. the path is stone, running west to east at z -3 to -1; each plot sits north of it, 24 blocks per scene.

## closed rooms

every scene that tests block light in the dark is a fully sealed box - floor, four walls, roof, no gaps to the sky - so sky light can never wash out the emitters being tested. sealed rooms: `lantern_gallery`, `bounce_room`, `blinding_corridor`, `cave`, `two_suns`, `colour_doors`, `brick_parlour`, `stained_chapel`, `slit_hall` (slit hall is the one exception: its west wall is intentionally slit open to the sky, by design, to let the low sun stripe the floor).

each sealed room has exactly one entrance, a plain `iron_door` on the path side. iron doors need redstone to open from the outside - there is no button or lever - so getting in means noclip or breaking the door, which is expected: these rooms are built to be watched from inside via the `go/<scene>` teleport, not walked into.

specifics: `lantern_gallery` gained a south wall it never had (it was open to the path); `bounce_room` had its roof skylight filled back in and its open south doorway doored; `blinding_corridor` was rebuilt as a solid diamond-block box with both tunnel ends capped (it used to be open-ended and open-topped); `cave`'s hill front face is now solid stone/deepslate with a single doored opening (it used to be open across the whole 12x5 mouth). every `go/<scene>` teleport still lands inside its box.

## fill counts

every `fill` in every scene function is under the 32768-block limit (the largest, the per-scene footprint-clearing air fill, is 24x21x29 = 14,616 blocks). the largest non-air placement fill is the cave's hill at 3,072 blocks. per-scene command counts (fill / setblock):

- sundial: 8 fill, 1 setblock
- lantern gallery: 26 fill, 20 setblock
- mirror pool: 14 fill, 1 setblock
- glass house: 5 fill, 8 setblock
- bounce room: 9 fill, 4 setblock
- blinding corridor: 4 fill, 9 setblock
- the cave: 8 fill, 10 setblock
- the grove: 18 fill, 19 setblock
- fog gully: 13 fill, 5 setblock
- the nether stage: 7 fill, 10 setblock
- two suns: 8 fill, 8 setblock
- colour doors: 16 fill, 3 setblock
- brick parlour: 16 fill, 15 setblock
- stained chapel: 25 fill, 7 setblock
- slit hall: 19 fill, 3 setblock

## block ids that could not be verified

none. every block id used (including the color-collection ones like `white_concrete` and `blue_stained_glass_pane`, whose java-side `Blocks` fields moved to a `ColorCollection<Block>` wrapper in 26.2) was checked against `assets/minecraft/blockstates/<id>.json` inside the deobfuscated 26.2 client jar before use. `sandstone_bricks`, floated as a possible id for slit hall's east wall, does not exist in 26.2 - `smooth_sandstone` was verified and used instead.

## notes

- `redstone_lamp[lit=true]` and the four `candle[candles=4,lit=true]` stay lit permanently: `/setblock` sets the exact state given and nothing nearby ever fires a neighbor update to recompute it.
- "glass fence" in the brief is built as `glass_pane`, since vanilla has no fence variant of glass.
- "glass panels" in the glass house are stained-glass-pane accents set into the tinted wall, not full stained-glass blocks - they read better as windows within the window.
- stained chapel's brief asked for a 14-block-wide hall, but seven 1-wide windows separated by seven 1-wide oak-log mullions need 13 columns at minimum with no room to spare against the corner walls - the room is built 15 wide (one wider than asked) so the windows read as distinct panes instead of touching.
- `iron_door` needs both `half=lower` and `half=upper` set with matching `facing`/`hinge`, or the top half renders as the wrong texture; every door in this pack is placed as a pair for that reason.
