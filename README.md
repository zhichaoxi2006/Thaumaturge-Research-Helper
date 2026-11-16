# Thaumaturge Research Helper

A small client-side quality-of-life addon for [Thaumaturge](https://www.mcmod.cn/class/31850.html)
on **Minecraft 1.21.1 / NeoForge**. It adds a green status LED to the top-left corner of the
research table: press it and the note puzzle is solved for you.

The lamp works like the one in *Forestry Utilities*' escritoire auto research - a green LED in a
steel socket, dark while idle, lit and breathing while a run is active, clickable, with a tooltip
that names the hotkey and says why something stopped.

Middle-clicking an aspect in the palette **synthesises one of it**, combining whatever components are
missing first.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.230 or newer |
| Thaumaturge | 1.1.0 (`thaumaturge`) |

Thaumaturge's own dependencies (Curios, Lithostitched) are needed by Thaumaturge, not by this mod.

## Using it

1. Open a research table (`Thaumaturge`'s, not the deconstruction table) with a research note in the
   note slot and usable scribing tools in the tools slot.
2. Click the **green LED** in the top-left corner of the window - or press the **`R`** key
   (rebindable in *Options > Controls*, category *Thaumaturge Research Helper*).
3. Click it again to stop. The tooltip explains the current state and the reason the last run ended.

The LED only reacts while the research table is open; nothing happens outside it.

### Synthesising an aspect

**Middle-click an aspect in the palette** (the 5×5 grid on the left) and one unit of it is combined
into your pool. If a component is missing, that component is combined first, all the way down to the
primal aspects - one click can stand for a whole recipe chain.

* The lamp turns **amber** while the combinations are sent, and its tooltip names the aspect and how
  many combinations are left.
* The palette cell is framed in amber while the burst runs, **green** when the aspect is in the pool,
  **red** when it could not be made (a primal aspect, or not enough components).
* The pool count in the palette goes up by one, and a compound you have never combined before is
  discovered by the same grant.

A synthesis burst and an auto-research run share one action queue, so starting either one stops the
other. Middle-clicking a primal aspect is refused with a message, since those have no recipe and must
come from items.

## How it works

The mod is **purely client-side**. It adds no block, item, menu, capability or packet, and the
server is never asked to do anything a player could not do by hand:

* placing an aspect sends Thaumaturge's own `ServerboundTablePlaceAspectPayload`, exactly like
  dropping an aspect from the palette onto a cell;
* combining two aspects sends `ServerboundTableCombinePayload`, exactly like the combine button.

Everything else is read from state the client already has: the note item's data component, the
aspect pool attachment and the research table block entity.

### The puzzle

A note is a hexagonal grid. *Root* cells hold fixed aspects, and the note is finished once every
root is connected, where two neighbouring cells are connected when one aspect is a **direct
component** of the other (`a` contains `b`, or `b` contains `a`).

So solving a note means filling empty cells with aspects that chain from one root to the next.

### The solver

`ResearchNoteSolver` repeatedly joins the connected components that hold roots:

1. Group the filled cells into connected components.
2. While more than one component holds a root, look for the cheapest way to join two of them, where
   the cost is the number of aspects that have to be placed.
3. That search is a shortest path over *(cell, aspect)* states. Walking onto a filled cell is free;
   walking onto an empty one costs the aspect placed there. The only aspects that may follow `a` are
   `a`'s components and the compounds that list `a` as a component - exactly the pairs
   `NoteRules.connects` accepts.
4. Place the winning bridge and repeat, so every bridge is planned against the aspects actually left.

`AspectBudget` tracks what the run may still spend (the player's pool plus the aspects the table
provides). When a needed compound is missing but its two components are on hand, the budget spends
the components and emits a `Combine` **before** the placement - which is also how such an aspect
becomes *discovered*, since combining grants and discovers in one step.

The plan is recomputed before every single interaction, and each interaction is confirmed against the
state the server reports back, so a plan can never drift from reality. A placement that does not land
stops the run with a reason instead of looping.

### Synthesis

A middle click asks the same `AspectBudget` for a plan that ends with the wanted aspect in the pool
instead of spent - the last `Combine` is simply not followed by a placement. Everything else is
shared with the research run: the same `ServerboundTableCombinePayload`, the same confirm-after-each
-step loop, the same timeouts.

The aspect under the cursor is not recomputed here: the screen's own hit test knows the current page,
the search filter and the column-major cell order, so it is looked up once by reflection rather than
copied, where it would silently drift.

## Building

The build is configured for an **offline** machine. It needs:

* JDK 21, and a Gradle user home that already holds the Gradle 9.2.1 distribution and the
  ModDevGradle plugin;
* a Maven-format mirror of the NeoForge artifacts, configured by `neoforge_local_mirror` in
  `gradle.properties` (this machine cannot reach `maven.neoforged.net`);
* the Thaumaturge jars in `libs/` (see below).

```powershell
.\gradlew.bat build --offline      # produces build/libs/thaumaturge_research_helper-1.21.1-1.0.0.jar
.\gradlew.bat runClient --offline  # development client with Thaumaturge loaded
```

### `libs/`

`compileOnly`/`runtimeOnly` jars copied from a modpack instance, because no reachable Maven
repository hosts them:

* `thaumaturge-1.21.1-NeoForge-1.1.0.jar`
* `lithostitched-1.8.1-neoforge-21.1.jar` (required by Thaumaturge)
* `curios-neoforge-9.5.1+1.21.1.jar` (required by Thaumaturge)

## Known limits

* The run stops instead of guessing when the aspects on hand cannot connect the anchors; the tooltip
  says so. Feeding the aspect pool (or using the table's own aspect storage) is the player's job.
* The scribing tools must have ink left: every placement wears them down, and the run stops when they
  are spent.
* The solver minimises the number of aspects placed, not the total aspect *cost* of the
  combinations - a bridge is cheap in placements even when it has to combine a compound first.
* A note whose anchor aspect the player has neither discovered nor can combine is not solvable and is
  reported as `no_aspects`.
* Synthesis needs the palette hit test of `ResearchTableScreen`, which is private. If a future
  Thaumaturge moves it, middle-click synthesis turns itself off with a log warning; auto research
  keeps working, since it never touches the palette.
