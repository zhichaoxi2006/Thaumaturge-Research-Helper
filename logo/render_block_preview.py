"""Render Thaumaturge's research table the way the game draws it.

The item model of `thaumaturge:research_table` is `builtin/entity`, so the inventory icon is
drawn by `ResearchTableItemSpecialRenderer`: the `research_table_main` block, the
`research_table_ext` block one block to the north (rotated 180 degrees), and the five parchment
sheets from `ResearchTableTop`.  This script rebuilds exactly that geometry out of the mod's
block models and draws it with a software rasteriser, because the game itself cannot render
without a window.

Optionally two "props" can be added on top of that faithful preview:

* `--inkwell` draws the block entity props of a table that has scribing tools in it, exactly as
  `ResearchTableRenderer` does: the `research_inkwell.ttmesh` mesh (glass, brass, ink parts) and
  the flat `tablequill` sprite, both through `ResearchTableTop.pose`.
* `--led` draws this mod's status lamp as a physical part of the table: a steel socket with the
  green LED of `ResearchTableAutoResearch`'s palette, placed on the top-left corner of the
  tabletop and glowing onto the wood around it.  The game draws that lamp into the GUI, not into
  the world, so its 3D shape here is an invention - the colours are not.

Block model semantics copied from Minecraft 1.21.1:

* `FaceBakery.bakeQuad` / `FaceInfo` decide which corner of the element each of the four
  vertices uses, and `BlockFaceUV.getU/getV` decide which corner of the uv rect it samples.
* Element rotations are unsupported here (the research table uses none); model y-rotations are
  applied around the block centre (0.5, 0.5, 0.5).
* Constant face shading (up 1.0, north/south 0.8, east/west 0.6, down 0.5) as in
  `BlockModelRenderer`; the parchment sheets are drawn with an entity render type, which the
  game lights flat, so they get no face shading.

Usage:
    python logo/render_block_preview.py --out out.png [--led] [--inkwell] [--yaw 225] [--pitch 30]
"""

from __future__ import annotations

import argparse
import json
import os

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ASSET_ROOT = os.path.join(ROOT, ".work", "dec", "thaumaturge", "assets", "thaumaturge")
MODEL_DIR = os.path.join(ASSET_ROOT, "models", "block")
TEXTURE_DIR = os.path.join(ASSET_ROOT, "textures")

# FaceInfo.fromFacing: per direction, the four quad vertices as (x, y, z) corner selectors.
FACES = {
    "down": (("min", "min", "max"), ("min", "min", "min"), ("max", "min", "min"), ("max", "min", "max")),
    "up": (("min", "max", "min"), ("min", "max", "max"), ("max", "max", "max"), ("max", "max", "min")),
    "north": (("max", "max", "min"), ("max", "min", "min"), ("min", "min", "min"), ("min", "max", "min")),
    "south": (("min", "max", "max"), ("min", "min", "max"), ("max", "min", "max"), ("max", "max", "max")),
    "west": (("min", "max", "min"), ("min", "min", "min"), ("min", "min", "max"), ("min", "max", "max")),
    "east": (("max", "max", "max"), ("max", "min", "max"), ("max", "min", "min"), ("max", "max", "min")),
}

# BlockModelRenderer face brightness.
SHADE = {
    "up": 1.0,
    "down": 0.5,
    "north": 0.8,
    "south": 0.8,
    "east": 0.6,
    "west": 0.6,
}

# ResearchTableTop: where the five parchment sheets lie on top of the main block.
SHEET_TILTS = (-6.0, 8.0, 14.0, -3.0, 11.0)
SHEET_BASE_HEIGHT = 1.008
SHEET_STEP = 0.013

# ResearchTableRenderer: the block entity props, both drawn inside ResearchTableTop.pose(north).
INKWELL_MESH = os.path.join(ASSET_ROOT, "models", "mesh", "research_inkwell.ttmesh")
INKWELL_TEXTURE = "thaumaturge:entity/research_inkwell"
INKWELL_OFFSET = (-0.28125, 0.0, 0.28125)
QUILL_TEXTURE = "thaumaturge:entity/tablequill"
QUILL_THICKNESS = 0.025
QUILL_TRANSLATE = (-0.226, -0.095, 0.076)
QUILL_YAW = -105.0
QUILL_SCALE = 0.5

# ResearchTableAutoResearch: the status lamp's palette (a green LED in a steel socket).
LAMP_SOCKET = "FF171008"
LAMP_RING = "FF6B5C4A"
LAMP_ON = "FF3BDB1E"
LAMP_ON_HI = "FFC4FFA0"
LAMP_ON_LOW = "FF2FB41A"
LAMP_GLOW = "FF6BE83C"

# Half width, in supersampled pixels, that every triangle is grown by so that T-junctions between
# touching elements do not leave a one pixel crack of background showing through.
CRACK_FILL = 1.0


# --------------------------------------------------------------------------------------- maths


def translate(x: float, y: float, z: float) -> np.ndarray:
    m = np.eye(4)
    m[0, 3], m[1, 3], m[2, 3] = x, y, z
    return m


def rot_x(deg: float) -> np.ndarray:
    c, s = np.cos(np.radians(deg)), np.sin(np.radians(deg))
    m = np.eye(4)
    m[1, 1], m[1, 2], m[2, 1], m[2, 2] = c, -s, s, c
    return m


def rot_y(deg: float) -> np.ndarray:
    c, s = np.cos(np.radians(deg)), np.sin(np.radians(deg))
    m = np.eye(4)
    m[0, 0], m[0, 2], m[2, 0], m[2, 2] = c, s, -s, c
    return m


def scale(x: float, y: float, z: float) -> np.ndarray:
    m = np.eye(4)
    m[0, 0], m[1, 1], m[2, 2] = x, y, z
    return m


def transform(points: np.ndarray, matrix: np.ndarray) -> np.ndarray:
    homogeneous = np.concatenate([points, np.ones((len(points), 1))], axis=1)
    return (homogeneous @ matrix.T)[:, :3]


def colour_of(hex_argb: str) -> tuple[float, float, float]:
    value = int(hex_argb, 16)
    return ((value >> 16 & 0xFF) / 255.0, (value >> 8 & 0xFF) / 255.0, (value & 0xFF) / 255.0)


def normal_shade(positions: np.ndarray) -> float:
    """Face brightness from the quad's own normal, for geometry that carries no face name."""
    normal = np.cross(positions[1] - positions[0], positions[2] - positions[0])
    length = np.linalg.norm(normal)
    if length < 1e-9:
        return 1.0
    normal = normal / length
    axis = int(np.argmax(np.abs(normal)))
    name = ("east", "up", "south")[axis] if normal[axis] > 0 else ("west", "down", "north")[axis]
    return SHADE[name]


# ------------------------------------------------------------------------------ model loading


def load_texture(name: str) -> np.ndarray:
    path = os.path.join(TEXTURE_DIR, name[len("thaumaturge:"):] + ".png")
    image = Image.open(path).convert("RGBA")
    width, height = image.size
    if height > width:  # animated texture: keep the first frame
        image = image.crop((0, 0, width, width))
    return np.asarray(image, dtype=np.float32) / 255.0


def resolve_texture(reference: str, textures: dict[str, str]) -> str:
    seen = 0
    while reference.startswith("#") and seen < 8:
        reference = textures[reference[1:]]
        seen += 1
    return reference


def load_model(name: str) -> tuple[list[dict], dict[str, np.ndarray]]:
    with open(os.path.join(MODEL_DIR, name + ".json"), "r", encoding="utf-8") as handle:
        model = json.load(handle)
    textures = model.get("textures", {})
    quads: list[dict] = []
    images: dict[str, np.ndarray] = {}

    for element in model.get("elements", []):
        if "rotation" in element:
            raise SystemExit(f"{name}: element rotation is not supported by this renderer")
        low = np.array(element["from"], dtype=np.float64) / 16.0
        high = np.array(element["to"], dtype=np.float64) / 16.0
        for face_name, face in element["faces"].items():
            uv = face.get("uv")
            if uv is None:
                raise SystemExit(f"{name}: face without explicit uv is not supported")
            rotation = int(face.get("rotation", 0))
            texture = resolve_texture(face["texture"], textures)
            if texture not in images:
                images[texture] = load_texture(texture)

            positions = []
            texcoords = []
            for index, (sx, sy, sz) in enumerate(FACES[face_name]):
                positions.append([
                    low[0] if sx == "min" else high[0],
                    low[1] if sy == "min" else high[1],
                    low[2] if sz == "min" else high[2],
                ])
                shifted = (index + rotation // 90) % 4
                u = uv[0] if shifted in (0, 1) else uv[2]
                v = uv[1] if shifted in (0, 3) else uv[3]
                texcoords.append([u / 16.0, v / 16.0])

            quads.append({
                "positions": np.array(positions, dtype=np.float64),
                "uvs": np.array(texcoords, dtype=np.float64),
                "texture": texture,
                "shade": SHADE[face_name],
                "translucent": False,
            })
    return quads, images


def place(quads: list[dict], matrix: np.ndarray) -> list[dict]:
    out = []
    for quad in quads:
        placed = dict(quad)
        placed["positions"] = transform(quad["positions"], matrix)
        out.append(placed)
    return out


def sheet_quads() -> list[dict]:
    """The five parchment sheets of ResearchTableTop.renderSheets, for facing north."""
    base = (
        translate(0.5, 1.0, 0.5) @ rot_y(90.0) @ rot_x(180.0)
        @ translate(0.36, 0.0, 0.04) @ rot_y(180.0) @ translate(-0.36, 0.0, -0.04)
    )
    corners = np.array([[0.0, 1.0, 0.0], [1.0, 1.0, 0.0], [1.0, 0.0, 0.0], [0.0, 0.0, 0.0]])
    uvs = np.array([[0.0, 1.0], [1.0, 1.0], [1.0, 0.0], [0.0, 0.0]])
    quads = []
    for index, tilt in enumerate(SHEET_TILTS):
        matrix = (
            base
            @ translate(0.12, 1.0 - (SHEET_BASE_HEIGHT + index * SHEET_STEP), 0.32)
            @ rot_y(tilt) @ rot_x(-90.0) @ scale(0.48, 0.56, 1.0)
        )
        quads.append({
            "positions": transform(corners, matrix),
            "uvs": uvs.copy(),
            "texture": "thaumaturge:misc/parchment",
            "shade": 1.0,
            "translucent": True,
        })
    return quads


def load_mesh_quads(path: str, texture: str) -> list[dict]:
    """A `thaumaturge_mesh` file: one quad per four vertices, v flipped as GolemMeshes does."""
    with open(path, "r", encoding="utf-8") as handle:
        mesh = json.load(handle)
    if mesh.get("format") != "thaumaturge_mesh" or mesh.get("version") != 1:
        raise SystemExit(f"{path}: unsupported mesh format")

    quads = []
    for part in mesh["parts"]:
        stride = 0
        offsets = {}
        for attribute in part["attributes"]:
            offsets[attribute] = stride
            stride += {"position": 3, "uv": 2, "normal": 3}[attribute]
        if "position" not in offsets:
            raise SystemExit(f"{path}: part without positions")
        for values in part["quads"]:
            positions, uvs = [], []
            for corner in range(4):
                base = corner * stride
                positions.append(values[base + offsets["position"]:base + offsets["position"] + 3])
                if "uv" in offsets:
                    u = values[base + offsets["uv"]]
                    v = values[base + offsets["uv"] + 1]
                    uvs.append([u, 1.0 - v])
                else:
                    uvs.append([0.0, 0.0])
            positions = np.array(positions, dtype=np.float64)
            quads.append({
                "positions": positions,
                "uvs": np.array(uvs, dtype=np.float64),
                "texture": texture,
                "shade": normal_shade(positions),
                "translucent": False,
            })
    return quads


def inkwell_quads() -> tuple[list[dict], dict[str, np.ndarray]]:
    """ResearchTableRenderer: the inkwell mesh and the quill sprite of a table with tools in it."""
    top = translate(0.5, 1.0, 0.5) @ rot_y(90.0) @ rot_x(180.0)
    images = {
        INKWELL_TEXTURE: load_texture(INKWELL_TEXTURE),
        QUILL_TEXTURE: load_texture(QUILL_TEXTURE),
    }

    quads = place(
        load_mesh_quads(INKWELL_MESH, INKWELL_TEXTURE),
        top @ translate(*INKWELL_OFFSET) @ rot_x(180.0),
    )

    quill_pose = (
        top @ translate(*QUILL_TRANSLATE) @ rot_y(QUILL_YAW) @ rot_x(180.0)
        @ scale(QUILL_SCALE, QUILL_SCALE, QUILL_SCALE)
    )
    corners = np.array([[0.0, 0.0, 0.0], [1.0, 0.0, 0.0], [1.0, 1.0, 0.0], [0.0, 1.0, 0.0]])
    texcoords = np.array([[1.0, 1.0], [0.0, 1.0], [0.0, 0.0], [1.0, 0.0]])
    for depth, flip in ((0.0, False), (-QUILL_THICKNESS, True)):
        offset = np.array([[0.0, 0.0, depth]] * 4)
        positions = transform(corners + offset, quill_pose)
        quads.append({
            "positions": positions,
            "uvs": texcoords.copy(),
            "texture": QUILL_TEXTURE,
            "shade": normal_shade(positions),
            "translucent": False,
        })
    return quads, images


def solid_quad(positions: np.ndarray, colour: tuple[float, float, float], shade: float,
               role: str | None = None) -> dict:
    return {
        "positions": np.asarray(positions, dtype=np.float64),
        "uvs": None,
        "texture": None,
        "colour": colour,
        "shade": shade,
        "translucent": False,
        "role": role,
    }


def solid_box(low, high, colours: dict[str, tuple[float, float, float]], role: str | None = None,
              emissive: bool = False) -> list[dict]:
    low = np.asarray(low, dtype=np.float64)
    high = np.asarray(high, dtype=np.float64)
    quads = []
    for face_name, selectors in FACES.items():
        if face_name not in colours:
            continue
        positions = np.array([
            [low[0] if sx == "min" else high[0],
             low[1] if sy == "min" else high[1],
             low[2] if sz == "min" else high[2]]
            for sx, sy, sz in selectors
        ], dtype=np.float64)
        shade = 1.0 if emissive else SHADE[face_name]
        quads.append(solid_quad(positions, colours[face_name], shade, role))
    return quads


def lamp_quads(anchor_x: float, anchor_z: float, top: float) -> tuple[list[dict], list[dict]]:
    """The status lamp as a physical part of the tabletop: steel socket, then the green LED."""
    socket, ring = colour_of(LAMP_SOCKET), colour_of(LAMP_RING)
    lamp, lamp_hi = colour_of(LAMP_ON), colour_of(LAMP_ON_HI)
    lamp_side = colour_of(LAMP_ON_LOW)
    steel = colour_of("FF8A7A62")

    # Two stacked plates, so the socket reads as a machined fitting and not as a flat pad.
    flange = 3.25 / 16.0
    flange_top = top + 0.375 / 16.0
    socket_box = solid_box(
        (anchor_x - flange, top, anchor_z - flange),
        (anchor_x + flange, flange_top, anchor_z + flange),
        {"up": ring, "north": socket, "south": socket, "east": socket, "west": socket},
        role="lamp_socket",
    )

    collar = 2.25 / 16.0
    collar_top = top + 0.75 / 16.0
    socket_box += solid_box(
        (anchor_x - collar, flange_top, anchor_z - collar),
        (anchor_x + collar, collar_top, anchor_z + collar),
        {"up": steel, "north": socket, "south": socket, "east": socket, "west": socket},
        role="lamp_socket",
    )

    dome = 1.5 / 16.0
    dome_top = top + 2.75 / 16.0
    lamp_box = solid_box(
        (anchor_x - dome, collar_top, anchor_z - dome),
        (anchor_x + dome, dome_top, anchor_z + dome),
        {"up": lamp_hi, "north": lamp, "south": lamp, "east": lamp_side, "west": lamp_side},
        role="lamp_dome",
        emissive=True,
    )
    return socket_box, lamp_box


def research_table_quads() -> tuple[list[dict], dict[str, np.ndarray]]:
    """Main block, then the ext block one block north rotated a half turn, then the sheets."""
    main, images = load_model("research_table_main")
    ext, ext_images = load_model("research_table_ext")
    images.update(ext_images)

    about_centre = translate(0.5, 0.5, 0.5) @ rot_y(180.0) @ translate(-0.5, -0.5, -0.5)
    quads = place(main, np.eye(4)) + place(ext, translate(0.0, 0.0, -1.0) @ about_centre)
    quads += sheet_quads()
    images.setdefault("thaumaturge:misc/parchment", load_texture("thaumaturge:misc/parchment"))
    return quads, images


# ------------------------------------------------------------------------------- projection


def fit_camera(quads: list[dict], yaw: float, pitch: float, size: int, margin: float) -> dict:
    """Orthographic isometric camera fitted to the silhouette, in supersampled pixels."""
    camera_matrix = rot_x(pitch) @ rot_y(yaw)
    centre = np.array([0.5, 0.5, 0.0])
    for quad in quads:
        quad["view"] = transform(quad["positions"] - centre, camera_matrix)

    all_view = np.concatenate([quad["view"] for quad in quads])
    span_x = all_view[:, 0].max() - all_view[:, 0].min()
    span_y = all_view[:, 1].max() - all_view[:, 1].min()
    scale_px = size * (1.0 - 2.0 * margin) / max(span_x, span_y)
    offset = np.array([
        size / 2.0 - (all_view[:, 0].max() + all_view[:, 0].min()) / 2.0 * scale_px,
        size / 2.0 + (all_view[:, 1].max() + all_view[:, 1].min()) / 2.0 * scale_px,
    ])
    return {"scale": scale_px, "offset": offset, "yaw": yaw, "pitch": pitch}


def apply_camera(quads: list[dict], camera: dict) -> None:
    scale_px, offset = camera["scale"], camera["offset"]
    for quad in quads:
        view = quad["view"]
        quad["screen"] = np.stack([
            view[:, 0] * scale_px + offset[0],
            -view[:, 1] * scale_px + offset[1],
        ], axis=1)
        quad["depth"] = -view[:, 2]


def project(quads: list[dict], yaw: float, pitch: float, size: int, margin: float) -> dict:
    camera = fit_camera(quads, yaw, pitch, size, margin)
    apply_camera(quads, camera)
    return camera


def lamp_anchor(camera: dict, top: float = 1.0) -> tuple[float, float]:
    """The tabletop corner that ends up furthest up and to the left of the picture."""
    corners = [(x, top, z) for x in (0.0, 1.0) for z in (-1.0, 1.0)]
    view = transform(np.array(corners), rot_x(camera["pitch"]) @ rot_y(camera["yaw"]))
    screen = np.stack([view[:, 0] * camera["scale"] + camera["offset"][0],
                       -view[:, 1] * camera["scale"] + camera["offset"][1]], axis=1)
    best = int(np.argmax(-screen[:, 0] - screen[:, 1]))
    x, _, z = corners[best]
    inset = 4.5 / 16.0
    return (x - inset if x > 0.5 else x + inset), (z - inset if z > 0.5 else z + inset)


# --------------------------------------------------------------------------------- rasteriser


def rasterise(
    quads: list[dict],
    images: dict[str, np.ndarray],
    size: int,
    ambient: float = 0.0,
    glow_blockers: tuple[str, ...] = (),
) -> tuple[np.ndarray, np.ndarray]:
    colour = np.zeros((size, size, 3), dtype=np.float32)
    alpha = np.zeros((size, size), dtype=np.float32)
    blocked = np.zeros((size, size), dtype=bool)
    depth = np.full((size, size), np.inf, dtype=np.float32)

    # Painter's order (farthest first) plus a z test; translucent sheets do not write depth.
    order = sorted(range(len(quads)), key=lambda index: quads[index]["depth"].mean(), reverse=True)
    for index in order:
        quad = quads[index]
        texture = images[quad["texture"]] if quad["texture"] is not None else None
        if texture is not None:
            height, width = texture.shape[:2]
        translucent = quad["translucent"]
        blocks_glow = quad.get("role") in glow_blockers

        for triangle in ((0, 1, 2), (0, 2, 3)):
            xy = quad["screen"][list(triangle)]
            uv = quad["uvs"][list(triangle)] if quad["uvs"] is not None else None
            zs = quad["depth"][list(triangle)]

            matrix = np.array([
                [xy[0, 0], xy[0, 1], 1.0],
                [xy[1, 0], xy[1, 1], 1.0],
                [xy[2, 0], xy[2, 1], 1.0],
            ])
            if abs(np.linalg.det(matrix)) < 1e-9:
                continue
            inverse = np.linalg.inv(matrix)

            x0 = max(int(np.floor(xy[:, 0].min())), 0)
            x1 = min(int(np.ceil(xy[:, 0].max())) + 1, size)
            y0 = max(int(np.floor(xy[:, 1].min())), 0)
            y1 = min(int(np.ceil(xy[:, 1].max())) + 1, size)
            if x0 >= x1 or y0 >= y1:
                continue

            xs = np.arange(x0, x1, dtype=np.float64) + 0.5
            ys = np.arange(y0, y1, dtype=np.float64) + 0.5
            grid_x, grid_y = np.meshgrid(xs, ys)
            weights = np.stack([
                inverse[0, 0] * grid_x + inverse[1, 0] * grid_y + inverse[2, 0],
                inverse[0, 1] * grid_x + inverse[1, 1] * grid_y + inverse[2, 1],
                inverse[0, 2] * grid_x + inverse[1, 2] * grid_y + inverse[2, 2],
            ], axis=-1)
            # Conservative coverage: a barycentric weight of -w means the sample is w * (2A / edge)
            # pixels outside that edge, so allow a small outset to close T-junction cracks where
            # two elements of the model abut but do not share vertices.
            area = abs(np.linalg.det(matrix))
            edges = (np.linalg.norm(xy[1] - xy[2]), np.linalg.norm(xy[2] - xy[0]), np.linalg.norm(xy[0] - xy[1]))
            outset = np.array([CRACK_FILL * edge / area for edge in edges])
            inside = (weights >= -outset).all(axis=-1)
            if not inside.any():
                continue

            z = weights[..., 0] * zs[0] + weights[..., 1] * zs[1] + weights[..., 2] * zs[2]
            brightness = ambient + (1.0 - ambient) * quad["shade"]

            if texture is None:
                base = np.array(quad["colour"], dtype=np.float32)
                source_rgb = np.broadcast_to(base * brightness, weights.shape[:2] + (3,))
                source_a = np.full(weights.shape[:2], 1.0, dtype=np.float32)
            else:
                u = weights[..., 0] * uv[0, 0] + weights[..., 1] * uv[1, 0] + weights[..., 2] * uv[2, 0]
                v = weights[..., 0] * uv[0, 1] + weights[..., 1] * uv[1, 1] + weights[..., 2] * uv[2, 1]
                texel_x = np.clip((u * width).astype(np.int32), 0, width - 1)
                texel_y = np.clip((v * height).astype(np.int32), 0, height - 1)
                texel = texture[texel_y, texel_x]
                source_rgb = texel[..., :3] * brightness
                source_a = texel[..., 3]

            region_depth = depth[y0:y1, x0:x1]
            visible = inside & (z <= region_depth + 1e-6)
            if not visible.any():
                continue

            region_rgb = colour[y0:y1, x0:x1]
            region_a = alpha[y0:y1, x0:x1]

            if translucent:
                blend = visible & (source_a > 0.0)
                source_alpha = source_a[blend][:, None]
                region_rgb[blend] = source_rgb[blend] * source_alpha + region_rgb[blend] * (1.0 - source_alpha)
                region_a[blend] = source_a[blend] + region_a[blend] * (1.0 - source_a[blend])
            else:
                # Block models render with alpha cutout: texels below the 0.1 threshold vanish.
                blend = visible & (source_a >= 0.1)
                region_rgb[blend] = source_rgb[blend]
                region_a[blend] = 1.0
                region_depth[blend] = z[blend]
                if blocks_glow:
                    blocked[y0:y1, x0:x1][blend] = True

    rgba = np.zeros((size, size, 4), dtype=np.float32)
    rgba[..., :3] = np.clip(colour, 0.0, 1.0)
    rgba[..., 3] = np.clip(alpha, 0.0, 1.0)
    return rgba, blocked


def add_glow(rgba: np.ndarray, centre, radius: float, rgb, strength: float,
             blocked: np.ndarray | None = None) -> None:
    """Additive falloff on the pixels the lamp lights, so the wood around it picks up the LED."""
    if radius <= 0.0 or strength <= 0.0:
        return
    size = rgba.shape[0]
    x0 = max(int(centre[0] - radius), 0)
    x1 = min(int(centre[0] + radius) + 1, size)
    y0 = max(int(centre[1] - radius), 0)
    y1 = min(int(centre[1] + radius) + 1, size)
    if x0 >= x1 or y0 >= y1:
        return
    grid_x, grid_y = np.meshgrid(
        np.arange(x0, x1, dtype=np.float64) + 0.5,
        np.arange(y0, y1, dtype=np.float64) + 0.5,
    )
    distance = np.sqrt((grid_x - centre[0]) ** 2 + (grid_y - centre[1]) ** 2) / radius
    falloff = np.clip(1.0 - distance, 0.0, 1.0) ** 2
    mask = (rgba[y0:y1, x0:x1, 3] > 0.02).astype(np.float64)
    if blocked is not None:
        mask = mask * (~blocked[y0:y1, x0:x1])
    light = falloff * mask * strength
    rgba[y0:y1, x0:x1, :3] = np.clip(
        rgba[y0:y1, x0:x1, :3] + light[..., None] * np.array(rgb, dtype=np.float64), 0.0, 1.0
    )


def render(
    yaw: float,
    pitch: float,
    size: int,
    supersample: int,
    margin: float,
    ambient: float = 0.0,
    led: bool = False,
    inkwell: bool = False,
    glow: float = 0.6,
) -> Image.Image:
    quads, images = research_table_quads()
    if inkwell:
        prop_quads, prop_images = inkwell_quads()
        quads += prop_quads
        images.update(prop_images)

    big = size * supersample
    camera = project(quads, yaw, pitch, big, margin)

    glow_centre_radius = None
    if led:
        anchor_x, anchor_z = lamp_anchor(camera)
        socket_quads, dome_quads = lamp_quads(anchor_x, anchor_z, 1.0)
        extra = transform_quads(socket_quads + dome_quads, camera)
        apply_camera(extra, camera)
        quads += extra
        dome_screen = np.concatenate([quad["screen"] for quad in extra if quad.get("role") == "lamp_dome"])
        glow_centre_radius = (dome_screen.mean(axis=0), 0.45 * camera["scale"])

    rgba, blocked = rasterise(quads, images, big, ambient, ("lamp_socket", "lamp_dome"))
    if glow_centre_radius is not None:
        add_glow(rgba, glow_centre_radius[0], glow_centre_radius[1], colour_of(LAMP_GLOW), glow,
                 blocked)

    # Box downsample in premultiplied alpha, so that the transparent background does not darken
    # the antialiased silhouette.
    premultiplied = rgba[..., :3] * rgba[..., 3:4]

    def box(array: np.ndarray) -> np.ndarray:
        return array.reshape(size, supersample, size, supersample, array.shape[-1]).mean(axis=(1, 3))

    premultiplied = box(premultiplied)
    averaged_alpha = box(rgba[..., 3:4])
    safe_alpha = np.maximum(averaged_alpha, 1e-6)
    straight = np.where(averaged_alpha > 1e-6, premultiplied / safe_alpha, 0.0)
    out = np.concatenate([straight, averaged_alpha], axis=-1)
    return Image.fromarray((np.clip(out, 0.0, 1.0) * 255.0).round().astype(np.uint8), mode="RGBA")


def transform_quads(quads: list[dict], camera: dict) -> list[dict]:
    """Place quads that were built after the camera was fitted (they have no `view` yet)."""
    matrix = rot_x(camera["pitch"]) @ rot_y(camera["yaw"])
    centre = np.array([0.5, 0.5, 0.0])
    for quad in quads:
        quad["view"] = transform(quad["positions"] - centre, matrix)
    return quads


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--yaw", type=float, default=225.0)
    parser.add_argument("--pitch", type=float, default=30.0)
    parser.add_argument("--size", type=int, default=1024)
    parser.add_argument("--ss", type=int, default=2)
    parser.add_argument("--margin", type=float, default=0.06)
    parser.add_argument("--ambient", type=float, default=0.0,
                        help="0 keeps Minecraft's face shading; higher values lift the shadows")
    parser.add_argument("--led", action="store_true", help="add this mod's green status lamp")
    parser.add_argument("--inkwell", action="store_true", help="add the inkwell and quill")
    parser.add_argument("--glow", type=float, default=0.6, help="strength of the lamp's glow")
    parser.add_argument("--out", required=True)
    parser.add_argument("--background", default="transparent")
    args = parser.parse_args()

    image = render(args.yaw, args.pitch, args.size, args.ss, args.margin, args.ambient,
                   args.led, args.inkwell, args.glow)
    if args.background != "transparent":
        flat = Image.new("RGBA", image.size, args.background)
        flat.alpha_composite(image)
        image = flat
    image.save(args.out)
    print(f"wrote {args.out} ({image.width}x{image.height})")


if __name__ == "__main__":
    main()
