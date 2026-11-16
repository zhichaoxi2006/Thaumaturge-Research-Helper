package com.zhichaoxi.trh.client;

import com.leclowndu93150.thaumaturge.api.aspect.IAspect;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import org.jetbrains.annotations.Nullable;

/**
 * How many of each aspect the run may still spend, and how to obtain one that is missing.
 *
 * <p>The research table lets a player combine two aspects into their compound, which both grants the
 * compound and discovers it - so an aspect that is not in the pool is still usable when the pool
 * holds its two components. {@link #take} models exactly that: it spends a component of the pair,
 * and when it has to combine first it hands the {@link Plan.Combine} operation to the caller so the
 * plan stays in an order the server accepts.
 *
 * <p>The budget is seeded from the player's aspect pool plus the aspects the table itself provides,
 * and only ever shrinks while a plan is built, so a plan can never ask for more than the player has
 * at the moment the plan is made.
 */
final class AspectBudget {
	private final Map<ResourceKey<IAspect>, Integer> counts = new HashMap<>();
	/**
	 * Answers of {@link #canTake} for the current contents. Every node of the solver's search asks
	 * this question for a handful of aspects, so it is worth keeping - and it is dropped as soon as
	 * the contents change.
	 */
	private final Map<ResourceKey<IAspect>, Boolean> reachable = new HashMap<>();

	AspectBudget() {
	}

	void set(Holder<IAspect> aspect, int amount) {
		ResourceKey<IAspect> key = aspect.unwrapKey().orElse(null);
		if (key != null) {
			this.counts.put(key, Math.max(0, amount));
			this.reachable.clear();
		}
	}

	int amount(Holder<IAspect> aspect) {
		ResourceKey<IAspect> key = aspect.unwrapKey().orElse(null);
		return key == null ? 0 : this.counts.getOrDefault(key, 0);
	}

	/** Whether a single unit of {@code aspect} can be obtained right now, combining if needed. */
	boolean canTake(Holder<IAspect> aspect) {
		ResourceKey<IAspect> key = aspect.unwrapKey().orElse(null);
		if (key == null) {
			return false;
		}

		Boolean cached = this.reachable.get(key);
		if (cached != null) {
			return cached;
		}

		boolean answer = takeInto(new HashMap<>(this.counts), aspect, null);
		this.reachable.put(key, answer);
		return answer;
	}

	/**
	 * The relative cost of placing {@code aspect} on the note: one unit when the pool holds it, a
	 * little more when it has to be combined out of two components first. {@code -1} = impossible.
	 *
	 * <p>An aspect that is in the pool has necessarily been discovered, and one that is combined out
	 * of its components is discovered by the grant that the combination performs, so no separate
	 * discovery check is needed here.
	 */
	int placementCost(Holder<IAspect> aspect) {
		if (amount(aspect) > 0) {
			return 1;
		}
		return canTake(aspect) ? 2 : -1;
	}

	/**
	 * Spends one unit of {@code aspect}, emitting any combinations that are needed to create it.
	 *
	 * @param sink receives the {@link Plan.Combine} operations, or {@code null} to only simulate
	 * @return {@code false} when the aspect cannot be obtained, in which case nothing was spent
	 */
	boolean take(Holder<IAspect> aspect, List<Plan.Op> sink) {
		boolean spent = takeInto(this.counts, aspect, sink);
		if (spent) {
			this.reachable.clear();
		}
		return spent;
	}

	/**
	 * Works out how to create one more unit of {@code aspect} in the pool.
	 *
	 * <p>This is {@link #take} with the last step left out: the result of the final combination is
	 * deliberately not spent, so the aspect stays in the pool - which is what a palette synthesis
	 * promises. Components that are missing are combined first, so a single call can describe a whole
	 * chain of combinations, in an order the table accepts.
	 *
	 * @return the operations to send, in order, or {@code null} when the aspect can never be
	 * created (a primal one) or its components cannot be paid for
	 */
	@Nullable
	List<Plan.Op> craft(Holder<IAspect> aspect) {
		// A primal aspect has no recipe; the palette offers it, but there is nothing to combine.
		if (aspect.value().isPrimal()) {
			return null;
		}

		List<Holder<IAspect>> components = aspect.value().components();
		if (components.size() != 2) {
			return null;
		}

		// A copy, so a chain that turns out to be unaffordable leaves the real contents untouched.
		Map<ResourceKey<IAspect>, Integer> pool = new HashMap<>(this.counts);
		List<Plan.Op> ops = new ArrayList<>(4);
		if (!takeInto(pool, components.get(0), ops) || !takeInto(pool, components.get(1), ops)) {
			return null;
		}

		ops.add(new Plan.Combine(components.get(0), components.get(1)));
		return List.copyOf(ops);
	}

	private boolean takeInto(Map<ResourceKey<IAspect>, Integer> pool, Holder<IAspect> aspect,
			List<Plan.Op> sink) {
		ResourceKey<IAspect> key = aspect.unwrapKey().orElse(null);
		if (key == null) {
			return false;
		}

		int have = pool.getOrDefault(key, 0);
		if (have > 0) {
			pool.put(key, have - 1);
			return true;
		}

		IAspect value = aspect.value();
		if (value.isPrimal()) {
			return false;
		}

		List<Holder<IAspect>> components = value.components();
		if (components.size() != 2) {
			return false;
		}

		// Snapshot so a half-spent pair can be put back: a failed attempt must not consume anything.
		Map<ResourceKey<IAspect>, Integer> snapshot = new HashMap<>(pool);
		if (!takeInto(pool, components.get(0), sink) || !takeInto(pool, components.get(1), sink)) {
			pool.clear();
			pool.putAll(snapshot);
			return false;
		}

		if (sink != null) {
			// The compound this combination produces is what the caller is spending, so it is never
			// added to the pool - it goes straight into the surrounding placement.
			sink.add(new Plan.Combine(components.get(0), components.get(1)));
		}
		return true;
	}
}
