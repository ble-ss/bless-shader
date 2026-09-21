#!/usr/bin/env python3
# generator for the gallery data pack. run it from anywhere:
#   python3 rmls/gallery/build_gallery.py
# it rewrites pack.mcmeta and every function under data/rmls_gallery/function/.
# every block id is checked against the 26.2 client jar first; an unverified id
# aborts the run, and so does any fill of 32768 blocks or more.
import os, json, sys

ROOT = __import__("os").path.dirname(__import__("os").path.abspath(__file__))  # the pack is wherever this script lives
NS = "rmls_gallery"
FN = os.path.join(ROOT, "data", NS, "function")

# every block id is verified against the 26.2 client jar's blockstates before use.
JAR = os.path.expanduser("~/.gradle/caches/fabric-loom/26.2/minecraft-client.jar")
import zipfile
with zipfile.ZipFile(JAR) as z:
    VALID = {n.split("/")[-1][:-5] for n in z.namelist()
             if n.startswith("assets/minecraft/blockstates/") and n.endswith(".json")}

class Room:
    def __init__(self, name):
        self.name = name
        self.lines = []
        self.fills = 0
        self.sets = 0
        self.maxfill = 0
    def raw(self, s):
        self.lines.append(s)
    def _chk(self, b):
        bare = b.split("[")[0].split("{")[0].replace("minecraft:", "")
        if bare not in VALID and bare != "air":
            raise SystemExit("UNVERIFIED BLOCK ID: " + bare + "  (room " + self.name + ")")
    def fill(self, x1,y1,z1, x2,y2,z2, b):
        self._chk(b)
        v = (abs(x2-x1)+1)*(abs(y2-y1)+1)*(abs(z2-z1)+1)
        if v >= 32768:
            raise SystemExit("FILL TOO BIG %d in %s: %s" % (v, self.name, b))
        self.maxfill = max(self.maxfill, v)
        self.fills += 1
        self.lines.append("fill %d %d %d %d %d %d minecraft:%s" % (x1,y1,z1,x2,y2,z2,b))
    def sb(self, x,y,z, b):
        self._chk(b)
        self.sets += 1
        self.lines.append("setblock %d %d %d minecraft:%s" % (x,y,z,b))
    def air(self, x1,y1,z1, x2,y2,z2):
        self.fill(x1,y1,z1,x2,y2,z2,"air")
    def sign(self, x,y,z, rot, msgs):
        m = ",".join("'{\"text\":\"%s\"}'" % t for t in msgs)
        self.sets += 1
        self.lines.append("setblock %d %d %d minecraft:oak_sign[rotation=%d]{front_text:{messages:[%s]}}" % (x,y,z,rot,m))

rooms = []
def room(name):
    r = Room(name); rooms.append(r); return r

# ---------------------------------------------------------------- 1. hall
# shell x -1..77, z -6..6, floor y64, ceiling y80
h = room("hall")
h.air(-2, 60, -6, 77, 84, 6)
h.fill(-1, 64, -6, 77, 64, 6, "polished_deepslate")
h.fill(-1, 80, -6, 77, 80, 6, "smooth_stone")
h.fill(-1, 65, -6, -1, 79, 6, "smooth_stone")
h.fill(77, 65, -6, 77, 79, 6, "smooth_stone")
h.fill(0, 65, -6, 76, 79, -6, "smooth_stone")
h.fill(0, 65, 6, 76, 79, 6, "smooth_stone")
# skylights every eight blocks
for x in range(4, 77, 8):
    h.fill(x, 80, -1, x+1, 80, 1, "glass")
# pool
h.fill(31, 62, -3, 45, 63, 3, "polished_deepslate")
h.fill(32, 63, -2, 44, 63, 2, "water")
h.air(32, 64, -2, 44, 64, 2)
# copper and iron pillars down both sides
for i, x in enumerate(range(12, 73, 6)):
    b = "copper_block" if i % 2 == 0 else "iron_block"
    h.fill(x, 65, -4, x, 71, -4, b)
    h.fill(x, 65, 4, x, 71, 4, b)
# benches
h.fill(14, 65, 5, 22, 65, 5, "oak_stairs[facing=south,half=bottom,shape=straight]")
h.fill(56, 65, 5, 66, 65, 5, "oak_stairs[facing=south,half=bottom,shape=straight]")
h.fill(22, 65, -5, 30, 65, -5, "oak_stairs[facing=north,half=bottom,shape=straight]")
# stair up to the second storey, west end, rising east
for x in range(2, 10):
    y = 65 + (x - 2)
    if y > 65:
        h.fill(x, 65, -4, x, y-1, -2, "stone_bricks")
    h.fill(x, y, -4, x, y, -2, "stone_brick_stairs[facing=east,half=bottom,shape=straight]")
# doorways into the side rooms
for x in (10, 36, 54, 68):
    h.air(x, 65, -6, x+1, 67, -6)
for x in (10, 34, 71):
    h.air(x, 65, 6, x+1, 67, 6)
h.air(77, 65, -1, 77, 67, 0)
h.sign(1, 65, 2, 12, ["the hall", "skylights, pool,", "copper and iron", "rooms both sides"])
h.sign(9, 65, -5, 0, ["two suns", "glowstone west,", "sea lantern east", "stone shapes mid"])
h.sign(35, 65, -5, 0, ["the chapel", "stained glass,", "lanterns behind", "sun through roof"])
h.sign(53, 65, -5, 0, ["bounce box", "white room, one", "red wall, one", "green: bleed"])
h.sign(67, 65, -5, 0, ["materials", "metal pads, pool", "glass wall, and", "one line of rods"])
h.sign(9, 65, 5, 8, ["colour doors", "seven alcoves,", "red to magenta,", "over black stone"])
h.sign(33, 65, 5, 8, ["the living room", "spruce, white,", "froglight lamp,", "end rod lamp box"])
h.sign(70, 65, 5, 8, ["fog stairs", "down between", "stone. a lantern", "every six steps"])
h.sign(76, 65, 1, 4, ["mirror hall", "deepslate and", "glass by turns.", "rooms east"])

# ---------------------------------------------------------------- 2. two_suns
# shell x 0..20, z -24..-7 (hall wall z=-6 is its south side)
t = room("two_suns")
t.air(0, 60, -24, 20, 76, -7)
t.fill(0, 64, -24, 20, 64, -7, "polished_deepslate")
t.fill(0, 73, -24, 20, 73, -7, "polished_deepslate")
t.fill(0, 65, -23, 0, 72, -7, "glowstone")
t.fill(20, 65, -23, 20, 72, -7, "sea_lantern")
t.fill(0, 65, -24, 20, 72, -24, "polished_deepslate")
# grey stone shapes between the two walls
t.fill(9, 65, -18, 11, 69, -16, "smooth_stone")
t.fill(6, 65, -12, 6, 70, -12, "andesite")
t.fill(15, 65, -20, 15, 69, -20, "polished_andesite")
t.fill(13, 65, -12, 16, 65, -10, "stone_slab[type=top]")
t.fill(4, 65, -21, 6, 67, -19, "cobblestone")
t.fill(14, 65, -10, 16, 67, -8, "polished_diorite")
t.fill(8, 65, -22, 9, 66, -21, "stone")
t.fill(11, 65, -9, 12, 68, -9, "chiseled_stone_bricks")

# ---------------------------------------------------------------- 3. colour_doors
# shell x 0..22, z 7..24
c = room("colour_doors")
c.air(0, 60, 7, 22, 76, 24)
c.fill(0, 64, 7, 22, 64, 24, "polished_blackstone")
c.fill(0, 72, 7, 22, 72, 24, "black_concrete")
c.fill(0, 65, 7, 0, 71, 24, "black_concrete")
c.fill(22, 65, 7, 22, 71, 24, "black_concrete")
c.fill(1, 65, 24, 21, 71, 24, "black_concrete")
c.fill(1, 65, 22, 21, 71, 22, "black_concrete")
alcoves = [
    ((2,3),  "red_stained_glass", "glowstone"),
    ((5,6),  "shroomlight", None),
    ((8,9),  "ochre_froglight", None),
    ((11,12),"verdant_froglight", None),
    ((14,15),"sea_lantern", None),
    ((17,18),"blue_stained_glass", "glowstone"),
    ((20,21),"magenta_stained_glass", "glowstone"),
]
for (x1,x2), front, back in alcoves:
    c.air(x1, 65, 22, x2, 69, 22)          # the mouth, recessed one block
    c.fill(x1, 65, 23, x2, 69, 23, front)  # the lit face
    if back:
        c.fill(x1, 65, 24, x2, 69, 24, back)
c.fill(1, 65, 21, 21, 65, 21, "polished_blackstone")

# ---------------------------------------------------------------- 4. chapel
# shell x 22..46, z -28..-7. lantern wall x=23, cavity x=24, window wall x=25
ch = room("chapel")
ch.air(22, 60, -28, 46, 82, -7)
ch.fill(22, 64, -28, 46, 64, -7, "smooth_stone")
ch.fill(22, 78, -28, 46, 78, -7, "smooth_stone")
ch.fill(22, 65, -28, 22, 77, -7, "smooth_stone")
ch.fill(46, 65, -28, 46, 77, -7, "smooth_stone")
ch.fill(23, 65, -28, 45, 77, -28, "smooth_stone")
ch.fill(23, 65, -27, 23, 77, -7, "glowstone")
glass_cycle = ["red","orange","yellow","lime","cyan","light_blue","blue","purple","magenta","pink"]
gi = 0
for z in range(-27, -6):
    if z % 2 == 0:
        ch.fill(25, 65, z, 25, 67, z, "smooth_stone")
        ch.fill(25, 68, z, 25, 75, z, glass_cycle[gi % len(glass_cycle)] + "_stained_glass")
        ch.fill(25, 76, z, 25, 77, z, "smooth_stone")
        gi += 1
    else:
        ch.fill(25, 65, z, 25, 77, z, "oak_log[axis=y]")
for z in (-25, -20, -15, -10):
    ch.sb(24, 77, z, "lantern[hanging=true]")
# roof strips of stained glass, so noon paints the floor
for z, col in ((-25,"red"), (-21,"yellow"), (-17,"lime"), (-13,"light_blue"), (-9,"purple")):
    ch.fill(26, 78, z, 45, 78, z, col + "_stained_glass")
# pews and altar
for z in (-24, -21, -18, -15, -12):
    ch.fill(30, 65, z, 34, 65, z, "oak_stairs[facing=south,half=bottom,shape=straight]")
    ch.fill(37, 65, z, 41, 65, z, "oak_stairs[facing=south,half=bottom,shape=straight]")
    ch.fill(30, 66, z+1, 34, 66, z+1, "oak_fence")
    ch.fill(37, 66, z+1, 41, 66, z+1, "oak_fence")
ch.fill(34, 65, -26, 37, 65, -26, "quartz_block")
ch.fill(34, 66, -26, 37, 66, -26, "smooth_quartz_slab[type=bottom]")
ch.sb(35, 67, -26, "candle[candles=4,lit=true]")
ch.sb(36, 67, -26, "candle[candles=3,lit=true]")

# ---------------------------------------------------------------- 5. ikea_living
# shell x 24..45, z 7..26
lv = room("ikea_living")
lv.air(24, 60, 7, 45, 76, 26)
lv.fill(24, 64, 7, 45, 64, 26, "spruce_planks")
lv.fill(24, 72, 7, 45, 72, 26, "white_concrete")
lv.fill(24, 65, 7, 24, 71, 26, "white_concrete")
lv.fill(45, 65, 7, 45, 71, 26, "white_concrete")
lv.fill(25, 65, 26, 44, 71, 26, "white_concrete")
# window wall of panes, south side
lv.fill(28, 65, 26, 41, 69, 26, "glass_pane[east=true,west=true,north=false,south=false]")
# rug
lv.fill(31, 65, 13, 39, 65, 19, "white_carpet")
lv.fill(32, 65, 14, 38, 65, 18, "light_blue_carpet")
lv.fill(33, 65, 15, 37, 65, 17, "orange_carpet")
# sofa: quartz stairs seat, back to the north
lv.fill(32, 65, 21, 38, 65, 21, "quartz_stairs[facing=south,half=bottom,shape=straight]")
lv.fill(31, 65, 22, 39, 66, 22, "smooth_quartz")
lv.sb(31, 65, 21, "quartz_slab[type=bottom]")
lv.sb(39, 65, 21, "quartz_slab[type=bottom]")
# coffee table
for (tx, tz) in ((34,15),(36,15),(34,17),(36,17)):
    lv.sb(tx, 65, tz, "oak_fence")
lv.fill(34, 66, 15, 36, 66, 17, "oak_slab[type=bottom]")
# pendant of froglight over the table
lv.sb(35, 71, 16, "iron_chain[axis=y]")
lv.sb(35, 70, 16, "iron_chain[axis=y]")
lv.sb(35, 69, 16, "ochre_froglight")
# bookshelves along the west wall
lv.fill(25, 65, 10, 25, 67, 16, "bookshelf")
lv.sb(25, 68, 13, "chiseled_bookshelf[facing=east]")
# lamp corner: end rods in a glass box
lv.fill(41, 65, 22, 43, 67, 24, "glass")
lv.sb(42, 65, 23, "end_rod[facing=up]")
lv.sb(42, 65, 23, "end_rod[facing=up]")
lv.sb(26, 65, 9, "potted_fern")
lv.sb(44, 65, 9, "potted_bamboo")
lv.sb(30, 65, 24, "lectern[facing=north]")
lv.sign(44, 65, 12, 4, ["the kitchen", "through here:", "stone counters,", "metal backsplash"])

# ---------------------------------------------------------------- 6. ikea_kitchen
# shell x 45..62, z 7..20. north wall z=7 of its own, so the hall wall stays stone
ki = room("ikea_kitchen")
ki.air(45, 60, 7, 62, 76, 20)
ki.fill(45, 64, 7, 62, 64, 20, "white_concrete")
ki.fill(45, 72, 7, 62, 72, 20, "white_concrete")
ki.fill(45, 65, 7, 45, 71, 20, "white_concrete")
ki.fill(62, 65, 7, 62, 71, 20, "white_concrete")
ki.fill(46, 65, 7, 61, 71, 7, "white_concrete")
ki.fill(46, 65, 20, 61, 71, 20, "white_concrete")
ki.air(45, 65, 10, 45, 67, 11)   # opening back into the living room
# checker tile floor, two by two
for cx in range(46, 62, 2):
    for cz in range(8, 20, 2):
        if ((cx - 46)//2 + (cz - 8)//2) % 2 == 0:
            ki.fill(cx, 64, cz, cx+1, 64, cz+1, "light_gray_concrete")
# backsplash on the room's own north wall, alternating metal
for i, x in enumerate(range(46, 62, 2)):
    b = "copper_block" if i % 2 == 0 else "iron_block"
    ki.fill(x, 67, 7, x+1, 68, 7, b)
# counter run
ki.fill(46, 65, 8, 61, 65, 8, "smooth_stone")
ki.fill(46, 66, 8, 61, 66, 8, "smooth_stone_slab[type=bottom]")
ki.sb(50, 65, 8, "smoker[facing=south,lit=false]")
ki.sb(52, 65, 8, "blast_furnace[facing=south,lit=false]")
ki.sb(56, 65, 8, "water_cauldron[level=3]")
ki.sb(56, 66, 8, "air")
ki.sb(59, 65, 8, "barrel[facing=south,open=false]")
# wall cabinets with lanterns beneath
ki.fill(46, 68, 8, 61, 69, 8, "dark_oak_planks")
for x in (48, 52, 56, 60):
    ki.sb(x, 67, 8, "lantern[hanging=true]")
# island table
for (tx, tz) in ((52,14),(55,14),(52,16),(55,16)):
    ki.sb(tx, 65, tz, "oak_fence")
ki.fill(52, 66, 14, 55, 66, 16, "smooth_stone_slab[type=bottom]")
ki.sb(53, 67, 15, "candle[candles=2,lit=true]")
ki.sb(47, 65, 18, "composter")
ki.sb(49, 65, 18, "crafting_table")
ki.sign(51, 65, 19, 8, ["the bedroom", "through here:", "sea lamps and a", "warm ceiling"])

# ---------------------------------------------------------------- 7. ikea_bedroom
# shell x 45..62, z 21..30 (kitchen owns the z=20 wall)
bd = room("ikea_bedroom")
bd.air(45, 60, 21, 62, 76, 30)
bd.fill(45, 64, 21, 62, 64, 30, "oak_planks")
bd.fill(45, 72, 21, 62, 72, 30, "white_concrete")
bd.fill(45, 65, 21, 45, 71, 30, "light_gray_concrete")
bd.fill(62, 65, 21, 62, 71, 30, "light_gray_concrete")
bd.fill(46, 65, 30, 61, 71, 30, "light_gray_concrete")
bd.air(52, 65, 20, 53, 67, 20)   # opening back into the kitchen
# bed, two wide
for x in (52, 53):
    bd.sb(x, 65, 27, "white_bed[facing=south,part=foot,occupied=false]")
    bd.sb(x, 65, 28, "white_bed[facing=south,part=head,occupied=false]")
bd.fill(51, 66, 29, 54, 68, 29, "light_gray_wool")
# side lamps behind panes
for x in (50, 55):
    bd.sb(x, 65, 28, "dark_oak_planks")
    bd.sb(x, 66, 28, "sea_lantern")
    bd.sb(x, 66, 27, "glass_pane[north=true,south=true,east=false,west=false]")
# mirror of polished deepslate in a dark oak frame
bd.fill(45, 66, 23, 45, 69, 25, "polished_deepslate")
bd.fill(45, 65, 22, 45, 70, 22, "dark_oak_planks")
bd.fill(45, 65, 26, 45, 70, 26, "dark_oak_planks")
bd.fill(45, 65, 23, 45, 65, 25, "dark_oak_planks")
bd.fill(45, 70, 23, 45, 70, 25, "dark_oak_planks")
# window with wool curtains
bd.fill(62, 66, 23, 62, 69, 26, "glass")
bd.fill(61, 66, 23, 61, 69, 23, "white_wool")
bd.fill(61, 66, 26, 61, 69, 26, "white_wool")
# warm ceiling: a cross of shroomlight inside dark oak trapdoors
for (dx, dz) in ((0,0),(1,0),(-1,0),(0,1),(0,-1)):
    bd.sb(53+dx, 72, 25+dz, "shroomlight")
bd.sb(52, 71, 24, "dark_oak_trapdoor[facing=north,half=top,open=false]")
bd.sb(54, 71, 24, "dark_oak_trapdoor[facing=north,half=top,open=false]")
bd.sb(52, 71, 26, "dark_oak_trapdoor[facing=south,half=top,open=false]")
bd.sb(54, 71, 26, "dark_oak_trapdoor[facing=south,half=top,open=false]")
bd.fill(47, 65, 22, 48, 65, 24, "gray_carpet")
bd.sb(59, 65, 22, "barrel[facing=up,open=false]")

# ---------------------------------------------------------------- 8. bounce_box
# cornell box x 48..62, z -22..-8, plus a short connector to the hall
bb = room("bounce_box")
bb.air(48, 60, -22, 62, 80, -7)
bb.fill(48, 64, -22, 62, 64, -7, "white_concrete")
bb.fill(48, 78, -22, 62, 78, -8, "white_concrete")
bb.fill(48, 65, -22, 48, 77, -8, "white_concrete")
bb.fill(62, 65, -22, 62, 77, -8, "white_concrete")
bb.fill(49, 65, -22, 61, 77, -22, "white_concrete")
bb.fill(49, 65, -8, 61, 77, -8, "white_concrete")
bb.fill(49, 65, -21, 49, 77, -9, "red_concrete")
bb.fill(61, 65, -21, 61, 77, -9, "green_concrete")
bb.fill(53, 78, -17, 57, 78, -13, "glass")
bb.fill(51, 65, -18, 53, 70, -16, "white_concrete")
bb.fill(56, 65, -14, 59, 67, -11, "white_concrete")
# connector corridor from the hall
bb.fill(53, 64, -7, 56, 68, -7, "white_concrete")
bb.air(54, 65, -7, 55, 67, -7)
bb.air(54, 65, -8, 55, 67, -8)

# ---------------------------------------------------------------- 9. materials
# shell x 64..76, z -27..-7; glass wall at z=-25, cavity z=-26
mt = room("materials")
mt.air(64, 60, -27, 76, 80, -7)
mt.fill(64, 64, -27, 76, 64, -7, "polished_deepslate")
mt.fill(64, 77, -27, 76, 77, -7, "polished_deepslate")
mt.fill(64, 65, -27, 64, 76, -7, "smooth_stone")
mt.fill(76, 65, -27, 76, 76, -7, "smooth_stone")
mt.fill(65, 65, -27, 75, 76, -27, "polished_deepslate")
mt.fill(65, 65, -25, 75, 76, -25, "glass")
pads = ["iron_block","gold_block","copper_block","diamond_block",
        "netherite_block","emerald_block","lapis_block","waxed_cut_copper",
        "raw_iron_block","raw_gold_block","raw_copper_block","polished_deepslate"]
i = 0
for y in (65, 69, 73):
    for z in (-23, -19, -15, -11):
        mt.fill(64, y, z, 64, y+2, z+2, pads[i]); i += 1
# pool beside
mt.fill(69, 62, -24, 76, 63, -17, "polished_deepslate")
mt.fill(70, 63, -23, 75, 63, -18, "water")
mt.air(70, 64, -23, 75, 64, -18)
# one line of end rods
for z in range(-23, -7, 2):
    mt.sb(70, 76, z, "end_rod[facing=down]")
mt.sb(68, 65, -26, "end_rod[facing=up]")
mt.sb(72, 65, -26, "end_rod[facing=up]")

# ---------------------------------------------------------------- 10. fog_stairs
# a solid stone mass, carved into a descending stair
fs = room("fog_stairs")
fs.air(68, 38, 7, 76, 76, 30)
fs.fill(68, 38, 7, 76, 71, 30, "stone")
for z in range(7, 27):
    yf = 64 - (z - 7)
    fs.air(70, yf+1, z, 74, yf+6, z)
fs.air(70, 46, 27, 74, 51, 29)
fs.air(71, 69, 8, 73, 71, 9)     # the slit of sky at the top
for z, y in ((10, 67), (16, 61), (22, 55)):
    fs.sb(72, y, z, "lantern[hanging=true]")
fs.sb(72, 51, 28, "lantern[hanging=true]")
fs.sb(70, 46, 28, "sea_lantern")
fs.sb(74, 46, 28, "sea_lantern")
for z, y in ((13, 58), (19, 52)):
    fs.sb(70, y, z, "soul_lantern[hanging=true]")
    fs.sb(74, y, z, "soul_lantern[hanging=true]")

# ---------------------------------------------------------------- 11. mirror_hall
# corridor x 77..81, z -20..5; west wall rewrites the hall's east wall
mh = room("mirror_hall")
mh.air(78, 60, -20, 82, 75, 5)
mh.fill(77, 64, -20, 81, 64, 5, "polished_deepslate")
mh.fill(77, 71, -20, 81, 71, 5, "polished_deepslate")
mh.fill(77, 65, -20, 81, 70, -20, "polished_deepslate")
mh.fill(77, 65, 5, 81, 70, 5, "polished_deepslate")
mh.fill(82, 65, -19, 82, 70, 4, "polished_deepslate")
seg = 0
z = -19
while z <= 4:
    z2 = min(z + 2, 4)
    b = "polished_deepslate" if seg % 2 == 0 else "glass"
    mh.fill(77, 65, z, 77, 70, z2, b)
    mh.fill(81, 65, z, 81, 70, z2, b)
    seg += 1
    z = z2 + 1
mh.air(77, 65, -1, 77, 67, 0)    # door back into the hall
for z in (-17, -12, -7, -2, 3):
    mh.sb(79, 65, z, "candle[candles=4,lit=true]")
mh.sb(78, 65, -14, "white_candle[candles=3,lit=true]")
mh.sb(80, 65, -9, "white_candle[candles=2,lit=true]")
mh.sign(80, 65, -15, 4, ["rim window", "a figure against", "a bright wall.", "read inside"])
mh.sign(80, 65, -4, 4, ["lava light", "lava under a", "glass floor,", "magma walls"])
mh.sign(80, 65, -19, 0, ["the dark room", "one candle. wait", "thirty seconds,", "then step out"])

# ---------------------------------------------------------------- 12. rim_window
rw = room("rim_window")
rw.air(82, 60, -18, 89, 74, -8)
rw.fill(81, 64, -18, 89, 64, -8, "black_concrete")
rw.fill(81, 73, -18, 89, 73, -8, "black_concrete")
rw.fill(81, 65, -18, 81, 72, -8, "black_concrete")
rw.fill(89, 65, -18, 89, 72, -8, "black_concrete")
rw.fill(82, 65, -18, 88, 72, -18, "black_concrete")
rw.fill(82, 65, -8, 88, 72, -8, "black_concrete")
rw.fill(88, 65, -17, 88, 72, -9, "sea_lantern")
rw.fill(87, 65, -17, 87, 72, -9, "glass_pane[north=true,south=true,east=false,west=false]")
rw.air(81, 65, -14, 81, 67, -13)
# the figure
rw.fill(84, 65, -14, 84, 67, -14, "black_concrete")
rw.fill(84, 65, -12, 84, 67, -12, "black_concrete")
rw.fill(84, 68, -14, 84, 70, -12, "black_concrete")
rw.fill(84, 68, -15, 84, 70, -15, "black_concrete")
rw.fill(84, 68, -11, 84, 70, -11, "black_concrete")
rw.fill(84, 71, -13, 84, 72, -13, "black_concrete")
rw.sign(83, 65, -15, 4, ["rim light", "is not in the", "shader yet. this", "is its room"])

# ---------------------------------------------------------------- 13. lava_light
ll = room("lava_light")
ll.air(82, 58, -6, 89, 74, 2)
ll.fill(81, 64, -6, 89, 64, 2, "polished_deepslate")
ll.fill(81, 72, -6, 89, 72, 2, "polished_deepslate")
ll.fill(89, 65, -6, 89, 71, 2, "magma_block")
ll.fill(82, 65, -6, 88, 71, -6, "magma_block")
ll.fill(82, 65, 2, 88, 71, 2, "magma_block")
ll.fill(81, 61, -6, 89, 63, 2, "polished_deepslate")
ll.fill(82, 63, -5, 88, 63, 1, "lava")
ll.fill(81, 71, -6, 81, 71, 2, "polished_deepslate")
ll.fill(82, 64, -5, 88, 64, 1, "glass")
ll.air(81, 65, -3, 81, 67, -2)
ll.sb(85, 71, -2, "shroomlight")

# ---------------------------------------------------------------- 14. dark_room
dr = room("dark_room")
dr.air(77, 60, -24, 81, 72, -21)
dr.fill(77, 64, -24, 81, 64, -20, "black_concrete")
dr.fill(77, 68, -24, 81, 68, -20, "black_concrete")
dr.fill(77, 65, -24, 77, 67, -21, "black_concrete")
dr.fill(81, 65, -24, 81, 67, -21, "black_concrete")
dr.fill(78, 65, -24, 80, 67, -24, "black_concrete")
dr.air(78, 65, -20, 79, 65, -20)
dr.sb(79, 65, -22, "candle[candles=1,lit=true]")

# ---------------------------------------------------------------- 15. depth_gallery
dg = room("depth_gallery")
dg.air(10, 72, -5, 68, 79, -2)
dg.fill(10, 72, -5, 68, 72, -2, "smooth_stone")
for i, x in enumerate(range(12, 69, 4)):
    dg.fill(x, 73, -2, x, 79, -2, "quartz_block")
z = -2
for x in range(13, 68):
    if (x - 12) % 4 != 0:
        dg.sb(x, 73, -2, "oak_fence")
dg.sign(10, 73, -5, 4, ["depth gallery", "look east down", "the long hall.", "haze at the end"])

# ---------------------------------------------------------------- write
# build order, not tour order: mirror_hall lays the corridor walls that
# rim_window and lava_light then rebuild on their own side.
order = ["hall","two_suns","colour_doors","chapel","ikea_living","ikea_kitchen",
         "ikea_bedroom","bounce_box","materials","fog_stairs","mirror_hall",
         "rim_window","lava_light","dark_room","depth_gallery"]
by = {r.name: r for r in rooms}
assert set(order) == set(by), set(order) ^ set(by)

VIEWS = {
 "hall":          (3, 65, 0, -90, 0),
 "two_suns":      (10, 65, -9, 180, 0),
 "colour_doors":  (11, 65, 9, 0, 0),
 "chapel":        (42, 65, -17, 90, -8),
 "ikea_living":   (34, 65, 9, 0, 0),
 "ikea_kitchen":  (53, 65, 17, 180, 0),
 "ikea_bedroom":  (53, 65, 22, 0, 0),
 "bounce_box":    (55, 65, -10, 180, 0),
 "materials":     (74, 65, -16, 90, 0),
 "fog_stairs":    (72, 46, 28, 180, -18),
 "rim_window":    (82, 65, -13, -90, 0),
 "depth_gallery": (11, 73, -4, -90, -4),
 "lava_light":    (83, 65, -4, -90, -18),
 "mirror_hall":   (79, 65, 4, 180, 0),
 "dark_room":     (79, 65, -21, 180, 0),
}

os.makedirs(os.path.join(FN, "scene"), exist_ok=True)
os.makedirs(os.path.join(FN, "go"), exist_ok=True)
for r in rooms:
    with open(os.path.join(FN, "scene", r.name + ".mcfunction"), "w") as f:
        f.write("\n".join(r.lines) + "\n")
for name, (x,y,z,yaw,pitch) in VIEWS.items():
    with open(os.path.join(FN, "go", name + ".mcfunction"), "w") as f:
        f.write("tp @s %d %d %d %d %d\n" % (x,y,z,yaw,pitch))

build = ["say building the gallery..."]
for n in order:
    build.append("function %s:scene/%s" % (NS, n))
build.append("say the gallery is built. use /function %s:go/<room>" % NS)
with open(os.path.join(FN, "build.mcfunction"), "w") as f:
    f.write("\n".join(build) + "\n")

with open(os.path.join(ROOT, "pack.mcmeta"), "w") as f:
    f.write('{\n  "pack": {\n    "description": "rmls-client shader gallery - fifteen rooms of light",\n'
            '    "min_format": [107, 1],\n    "max_format": 107\n  }\n}\n')

print("%-16s %6s %9s %10s" % ("room", "fills", "setblocks", "max fill"))
tf = ts = 0
for n in order:
    r = by[n]
    tf += r.fills; ts += r.sets
    print("%-16s %6d %9d %10d" % (n, r.fills, r.sets, r.maxfill))
print("%-16s %6d %9d %10d" % ("TOTAL", tf, ts, max(r.maxfill for r in rooms)))
