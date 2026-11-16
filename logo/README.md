# Logo source: the research table block preview

`thaumaturge:research_table` is a **two block** structure whose item model is `builtin/entity`, so
the inventory icon is not a texture: `ResearchTableItemSpecialRenderer` draws the
`research_table_main` block, the `research_table_ext` block one block to the north (a half turn
around it) and the five parchment sheets from `ResearchTableTop`.

These PNGs are that exact geometry, drawn off-line by `render_block_preview.py` with Minecraft's own
model rules (element boxes, `FaceBakery`/`FaceInfo` vertex order, `BlockFaceUV` uv corners, uv
rotation, model y-rotation about the block centre, constant face shading 1.0/0.8/0.6/0.5, alpha
cutout for block textures, translucent parchment). The camera is the isometric view Minecraft uses
for block item icons: 30° down, 225° around.

Two sets are produced by the same renderer.

## `research_table_block_*` - the plain block preview

| file | size |
|---|---|
| `research_table_block_2048.png` | 2048², master render, transparent background |
| `research_table_block_1024.png` | 1024², downsampled from the master, premultiplied alpha |
| `research_table_block_512.png` | 512² |
| `research_table_block_128.png` | 128², mod list / thumbnail size |
| `research_table_block_1024_alt.png` | 1024², the same table from the other side (yaw 135°) |

## `research_table_logo_*` - the same table with the props

The same files and sizes, plus two things that the inventory icon does not have:

* **the green status lamp** - this mod's signature. `ResearchTableAutoResearch` paints it into the
  GUI; here it is built as a physical fitting on the tabletop: a dark socket, a steel collar and the
  LED itself in the mod's own palette (`#3BDB1E` body, `#C4FFA0` lit top). It always sits on the
  corner of the tabletop that lands top-left in the picture, and its light is added to the wood
  around it - but not to the socket, which stays steel.
* **the inkwell, ink and quill** - exactly what `ResearchTableRenderer` draws for a table that has
  scribing tools in it: the `research_inkwell.ttmesh` mesh (glass / brass / ink parts, uv flipped as
  `GolemMeshes.renderPart` does) and the flat `tablequill` sprite, both inside
  `ResearchTableTop.pose`, where the block entity renderer puts them.

The table is two blocks long, so the square canvases are fitted to the diagonal silhouette with a 6%
margin: the object is centred and never touches the edge.

## Re-rendering

Requires Python 3 with `numpy` and `Pillow`, and the unpacked Thaumaturge assets in
`.work/dec/thaumaturge/assets/thaumaturge` (the models, meshes and textures are read from there).

```powershell
python logo\build_logo.py                                                    # rebuilds both tables above
python logo\render_block_preview.py --out logo\custom.png --led --inkwell --size 2048 --ss 2
python logo\render_block_preview.py --out logo\plain.png --yaw 225 --pitch 30       # no props
python logo\render_block_preview.py --out logo\bright.png --led --ambient 0.25      # lighter shadows
```

`--led` adds the lamp, `--inkwell` the inkwell and quill, `--glow` sets the strength of the lamp's
light (`0` disables it). `--ambient` mixes Minecraft's face shading towards flat light: `0` is the
game's look, `0.25` is noticeably brighter and reads better on dark backgrounds. `--yaw`/`--pitch`
move the camera, `--ss` is the supersampling factor (rendering happens at `size * ss` and is box
filtered down), `--background` fills the canvas instead of leaving it transparent.
