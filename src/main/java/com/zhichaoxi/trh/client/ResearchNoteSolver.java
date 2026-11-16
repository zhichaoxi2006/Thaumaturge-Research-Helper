package com.zhichaoxi.trh.client;

import com.leclowndu93150.thaumaturge.api.aspect.IAspect;
import com.leclowndu93150.thaumaturge.content.research.note.HexGrid;
import com.leclowndu93150.thaumaturge.content.research.note.NoteRules;
import com.leclowndu93150.thaumaturge.content.research.note.ResearchNoteData;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import org.jetbrains.annotations.Nullable;

/**
 * Solves a Thaumaturge research note.
 *
 * <h2>The puzzle</h2>
 * A note is a hexagonal grid of cells. Every cell is either a hole (absent), empty, or holds an
 * aspect. The cells marked as <em>roots</em> carry fixed aspects, and the note is finished once all
 * roots are connected, where two neighbouring cells are connected when one aspect is a direct
 * component of the other ({@code NoteRules#connects}).
 *
 * <p>So solving a note is connecting every root to every other root by filling empty cells with
 * aspects that chain together.
 *
 * <h2>How it is solved</h2>
 * <ol>
 *   <li>Group the currently filled cells into connected components.</li>
 *   <li>While more than one component holds a root, look for the cheapest way to join two of them.
 *       The cost is the number of aspects that have to be placed.</li>
 *   <li>That search is a shortest-path search over <em>(cell, aspect)</em> states: stepping onto an
 *       already filled cell is free, stepping onto an empty one costs the aspect that has to be put
 *       there. The only aspects that may follow {@code a} are its components and its parent
 *       compounds, see {@link AspectGraph#neighbours}.</li>
 *   <li>Place the aspects of the winning bridge and repeat, so every bridge is planned against the
 *       aspects that are actually left in the budget.</li>
 * </ol>
 *
 * <p>The result is an ordered list of {@link Plan.Op}s - combinations first, because a compound that
 * has to be combined out of two components must exist before it can be placed.
 */
final class ResearchNoteSolver {
	/** How many bridges one run may chain; a note never needs more than a handful. */
	private static final int MAX_BRIDGES = 48;
	/** Safety net so a pathological note cannot make the plan grow without bound. */
	private static final int MAX_OPS = 512;
	/** Safety net for the shortest-path search. */
	private static final int MAX_EXPANSIONS = 200_000;

	private ResearchNoteSolver() {
	}

	/** Why a plan came out empty. */
	enum Failure {
		/** Nothing left to do: every root is already connected. */
		NONE,
		/** The note has no root at all, so it cannot be completed in the first place. */
		NO_ROOT,
		/** No chain of aspects the player can afford connects the remaining anchors. */
		NO_ASPECTS,
		/** The note is larger than the solver is willing to handle. */
		TOO_COMPLEX
	}

	/**
	 * A plan, or the reason there is none.
	 *
	 * <p>{@code ops} is a runnable plan only when {@code failure} is {@link Failure#NONE}: a solve
	 * that gives up part way leaves the operations it had already decided in the list for
	 * diagnostics, but they are the remains of a solution that does not exist, so a caller must not
	 * run them.
	 */
	record Result(List<Plan.Op> ops, Failure failure) {
		boolean isEmpty() {
			return this.ops.isEmpty();
		}
	}

	/** One cell of a bridge, with the aspect that has to be put there. */
	private record Step(HexGrid.Hex hex, Holder<IAspect> aspect) {
	}

	/** A bridge between two components: the cells to fill, and what placing them costs. */
	private record Bridge(int cost, List<Step> steps) {
	}

	/** A node of the search: a cell holding one particular aspect, reached at a certain cost. */
	private record Node(int cost, long key) implements Comparable<Node> {
		@Override
		public int compareTo(Node other) {
			return Integer.compare(this.cost, other.cost);
		}
	}

	/**
	 * Builds a plan that connects every root of {@code data}.
	 *
	 * @param budget the aspects the run may spend; it is consumed while the plan is built, so the
	 * plan never asks for more than the player holds
	 * @param usable whether an aspect counts as known. A pair only connects when both of its aspects
	 * are known, and a compound produced by a combination counts as known because the grant that
	 * creates it also discovers it.
	 */
	static Result solve(ResearchNoteData data, AspectGraph graph, AspectBudget budget,
			Predicate<Holder<IAspect>> usable) {
		Map<HexGrid.Hex, ResearchNoteData.Cell> cells = data.cellMap();
		List<HexGrid.Hex> hexes = new ArrayList<>(cells.keySet());
		Map<HexGrid.Hex, Integer> index = new HashMap<>(hexes.size() * 2);
		for (int i = 0; i < hexes.size(); i++) {
			index.put(hexes.get(i), i);
		}

		List<HexGrid.Hex> roots = new ArrayList<>();
		for (ResearchNoteData.Cell cell : data.cells()) {
			if (cell.type() == ResearchNoteData.TYPE_ROOT) {
				if (!cell.active()) {
					// A root without an aspect can never connect to anything, so the note is broken
					// rather than merely unsolved. Without this it would silently be ignored below
					// and the note would be reported as already connected.
					return new Result(List.of(), Failure.NO_ROOT);
				}
				roots.add(cell.hex());
			}
		}
		if (roots.isEmpty()) {
			return new Result(List.of(), Failure.NO_ROOT);
		}

		// Cells this plan fills, so the next round sees them as part of the structure.
		Map<HexGrid.Hex, Holder<IAspect>> planned = new HashMap<>();
		List<Plan.Op> ops = new ArrayList<>();

		for (int round = 0; round < MAX_BRIDGES; round++) {
			// The cells this plan already fills hold aspects that are guaranteed to be known by the
			// time they are placed - either they were in the pool, or the combination that creates
			// them grants and discovers them in the same step. Without this, a compound produced by a
			// combination would look unknown to the round that has to build on top of it, because
			// the budget already spent its components and the client has not seen the grant yet.
			Predicate<Holder<IAspect>> known = knownIncluding(planned, usable);

			Map<HexGrid.Hex, Holder<IAspect>> active = new HashMap<>();
			Map<HexGrid.Hex, Integer> components = components(cells, planned, active, known);

			Set<Integer> rootComponents = new LinkedHashSet<>();
			for (HexGrid.Hex root : roots) {
				Integer id = components.get(root);
				if (id != null) {
					rootComponents.add(id);
				}
			}
			if (rootComponents.size() <= 1) {
				return new Result(ops, Failure.NONE);
			}

			List<Integer> ids = new ArrayList<>(rootComponents);
			Bridge best = null;
			for (int i = 0; i < ids.size(); i++) {
				for (int j = i + 1; j < ids.size(); j++) {
					Bridge candidate = bridge(ids.get(i), ids.get(j), cells, hexes, index, components,
							active, graph, budget, known);
					if (candidate != null && (best == null || candidate.cost() < best.cost())) {
						best = candidate;
					}
				}
			}
			if (best == null) {
				return new Result(ops, Failure.NO_ASPECTS);
			}
			if (ops.size() + best.steps().size() > MAX_OPS) {
				return new Result(ops, Failure.TOO_COMPLEX);
			}

			for (Step step : best.steps()) {
				if (!budget.take(step.aspect(), ops)) {
					// The search and the budget disagreed (a shared pool cannot be tracked per path);
					// the part of the plan that is already decided is still worth running.
					return new Result(ops, Failure.NO_ASPECTS);
				}
				ops.add(new Plan.Place(step.hex(), step.aspect()));
				planned.put(step.hex(), step.aspect());
			}
		}

		return new Result(ops, Failure.TOO_COMPLEX);
	}

	/**
	 * Widens {@code usable} with the aspects this plan already fills cells with.
	 *
	 * <p>Those aspects need no proof of being known: either they were in the pool, or the
	 * {@link Plan.Combine} that creates them grants <em>and discovers</em> them in the same step. The
	 * live predicate cannot see that yet - the combination has not run, and the budget has already
	 * spent the components that could recreate it - so without this the round that has to build on
	 * top of a planned compound would treat it as unknown and strand it.
	 */
	private static Predicate<Holder<IAspect>> knownIncluding(Map<HexGrid.Hex, Holder<IAspect>> planned,
			Predicate<Holder<IAspect>> usable) {
		if (planned.isEmpty()) {
			return usable;
		}

		Set<ResourceKey<IAspect>> plannedKeys = new HashSet<>(planned.size() * 2);
		for (Holder<IAspect> aspect : planned.values()) {
			ResourceKey<IAspect> key = aspect.unwrapKey().orElse(null);
			if (key != null) {
				plannedKeys.add(key);
			}
		}

		return aspect -> usable.test(aspect) || plannedKeys.contains(aspect.unwrapKey().orElse(null));
	}

	/**
	 * The aspect a cell currently holds, taking the cells this plan already fills into account.
	 *
	 * @return {@code null} for holes, empty cells and erased cells
	 */
	@Nullable
	private static Holder<IAspect> aspectAt(HexGrid.Hex hex, @Nullable ResearchNoteData.Cell cell,
			Map<HexGrid.Hex, Holder<IAspect>> planned) {
		Holder<IAspect> plannedAspect = planned.get(hex);
		if (plannedAspect != null) {
			return plannedAspect;
		}
		return cell != null && cell.active() ? cell.aspectOrNull() : null;
	}

	/**
	 * Groups the filled cells into connected components.
	 *
	 * @param activeOut receives the aspect of every filled cell
	 * @return component id per filled cell
	 */
	private static Map<HexGrid.Hex, Integer> components(Map<HexGrid.Hex, ResearchNoteData.Cell> cells,
			Map<HexGrid.Hex, Holder<IAspect>> planned,
			Map<HexGrid.Hex, Holder<IAspect>> activeOut,
			Predicate<Holder<IAspect>> usable) {
		Map<HexGrid.Hex, Integer> componentOf = new HashMap<>(cells.size() * 2);
		int next = 0;

		for (Map.Entry<HexGrid.Hex, ResearchNoteData.Cell> entry : cells.entrySet()) {
			HexGrid.Hex start = entry.getKey();
			Holder<IAspect> startAspect = aspectAt(start, entry.getValue(), planned);
			if (startAspect == null) {
				continue;
			}
			activeOut.put(start, startAspect);
			if (componentOf.containsKey(start)) {
				continue;
			}

			Deque<HexGrid.Hex> queue = new ArrayDeque<>();
			componentOf.put(start, next);
			queue.add(start);

			while (!queue.isEmpty()) {
				HexGrid.Hex current = queue.poll();
				Holder<IAspect> currentAspect = activeOut.get(current);
				for (int direction = 0; direction < 6; direction++) {
					HexGrid.Hex neighbour = current.neighbour(direction);
					if (componentOf.containsKey(neighbour)) {
						continue;
					}
					ResearchNoteData.Cell cell = cells.get(neighbour);
					if (cell == null) {
						continue;
					}
					Holder<IAspect> neighbourAspect = aspectAt(neighbour, cell, planned);
					if (neighbourAspect == null || !NoteRules.connects(currentAspect, neighbourAspect, usable)) {
						continue;
					}
					activeOut.put(neighbour, neighbourAspect);
					componentOf.put(neighbour, next);
					queue.add(neighbour);
				}
			}
			next++;
		}

		return componentOf;
	}

	/**
	 * Finds the cheapest set of cells that joins component {@code from} to component {@code to}.
	 *
	 * @return the bridge, or {@code null} when no chain of affordable aspects joins the two
	 */
	@Nullable
	private static Bridge bridge(int from, int to,
			Map<HexGrid.Hex, ResearchNoteData.Cell> cells,
			List<HexGrid.Hex> hexes,
			Map<HexGrid.Hex, Integer> index,
			Map<HexGrid.Hex, Integer> components,
			Map<HexGrid.Hex, Holder<IAspect>> active,
			AspectGraph graph,
			AspectBudget budget,
			Predicate<Holder<IAspect>> usable) {
		Map<Long, Integer> distance = new HashMap<>();
		Map<Long, Long> previous = new HashMap<>();
		Set<Long> filled = new HashSet<>();
		PriorityQueue<Node> queue = new PriorityQueue<>();

		for (Map.Entry<HexGrid.Hex, Holder<IAspect>> entry : active.entrySet()) {
			if (components.getOrDefault(entry.getKey(), -1) != from) {
				continue;
			}
			int aspectIndex = graph.indexOf(entry.getValue());
			if (aspectIndex < 0) {
				continue;
			}
			long key = key(index.get(entry.getKey()), aspectIndex);
			if (distance.containsKey(key)) {
				continue;
			}
			distance.put(key, 0);
			queue.add(new Node(0, key));
		}

		long goal = -1L;
		int expansions = 0;

		while (!queue.isEmpty() && expansions++ < MAX_EXPANSIONS) {
			Node node = queue.poll();
			Integer known = distance.get(node.key());
			if (known == null || node.cost() > known) {
				continue;
			}

			HexGrid.Hex hex = hexes.get((int) (node.key() >>> 32));
			if (components.getOrDefault(hex, -1) == to) {
				goal = node.key();
				break;
			}

			Holder<IAspect> aspect = graph.byIndex((int) node.key());
			for (int direction = 0; direction < 6; direction++) {
				HexGrid.Hex neighbour = hex.neighbour(direction);
				Integer neighbourIndex = index.get(neighbour);
				if (neighbourIndex == null) {
					// A hole punched out by the note generator: it is not a cell any more.
					continue;
				}

				Holder<IAspect> existing = active.get(neighbour);
				if (existing != null) {
					// Already filled: walking through it is free, but the aspects must still connect.
					if (!NoteRules.connects(aspect, existing, usable)) {
						continue;
					}
					int existingIndex = graph.indexOf(existing);
					if (existingIndex < 0) {
						continue;
					}
					long next = key(neighbourIndex, existingIndex);
					relax(distance, previous, filled, queue, next, node.cost(), node.key(), false);
					continue;
				}

				ResearchNoteData.Cell cell = cells.get(neighbour);
				if (cell == null || cell.type() != ResearchNoteData.TYPE_BLANK) {
					continue;
				}

				for (Holder<IAspect> candidate : graph.neighbours(aspect)) {
					if (!NoteRules.connects(aspect, candidate, usable)) {
						continue;
					}
					int cost = budget.placementCost(candidate);
					if (cost < 0) {
						continue;
					}
					int candidateIndex = graph.indexOf(candidate);
					if (candidateIndex < 0) {
						continue;
					}
					long next = key(neighbourIndex, candidateIndex);
					relax(distance, previous, filled, queue, next, node.cost() + cost, node.key(), true);
				}
			}
		}

		if (goal < 0L) {
			return null;
		}

		List<Step> steps = new ArrayList<>();
		long cursor = goal;
		while (previous.containsKey(cursor)) {
			if (filled.contains(cursor)) {
				steps.add(new Step(hexes.get((int) (cursor >>> 32)), graph.byIndex((int) cursor)));
			}
			cursor = previous.get(cursor);
		}
		Collections.reverse(steps);

		// A cell holds one aspect, so a bridge that wants to reuse a cell is not usable as it stands.
		Set<HexGrid.Hex> seen = new HashSet<>();
		for (Step step : steps) {
			if (!seen.add(step.hex())) {
				return null;
			}
		}

		return new Bridge(distance.get(goal), steps);
	}

	private static void relax(Map<Long, Integer> distance, Map<Long, Long> previous, Set<Long> filled,
			PriorityQueue<Node> queue, long key, int cost, long from, boolean wasBlank) {
		Integer known = distance.get(key);
		if (known != null && known <= cost) {
			return;
		}
		distance.put(key, cost);
		previous.put(key, from);
		if (wasBlank) {
			filled.add(key);
		} else {
			filled.remove(key);
		}
		queue.add(new Node(cost, key));
	}

	/** Packs a (cell, aspect) pair into one map key. */
	private static long key(int hexIndex, int aspectIndex) {
		return ((long) hexIndex << 32) | (aspectIndex & 0xFFFFFFFFL);
	}
}
