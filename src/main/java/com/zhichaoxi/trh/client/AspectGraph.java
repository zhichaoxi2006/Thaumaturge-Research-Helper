package com.zhichaoxi.trh.client;

import com.leclowndu93150.thaumaturge.api.aspect.IAspect;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;

/**
 * The aspect tree of Thaumaturge, indexed for the note solver.
 *
 * <p>{@code NoteRules.connect(a, b)} treats two adjacent note cells as connected when one aspect is
 * a direct component of the other, so the only aspects that can sit next to {@code a} are
 *
 * <ul>
 *   <li>the components of {@code a} (its recipe parts), and</li>
 *   <li>the compounds that list {@code a} as a component (its "parents").</li>
 * </ul>
 *
 * <p>The registry is a DAG - six primal aspects, then compounds built from two components each - so
 * that set is small (a handful of entries) and always closes over the whole reachable neighbourhood.
 */
final class AspectGraph {
	private final List<Holder.Reference<IAspect>> all;
	private final Map<ResourceKey<IAspect>, Integer> indices;
	private final Map<ResourceKey<IAspect>, List<Holder<IAspect>>> parents;

	private AspectGraph(List<Holder.Reference<IAspect>> all,
			Map<ResourceKey<IAspect>, Integer> indices,
			Map<ResourceKey<IAspect>, List<Holder<IAspect>>> parents) {
		this.all = all;
		this.indices = indices;
		this.parents = parents;
	}

	/**
	 * Builds the index for one registry lookup. Depends only on the registry content, so callers are
	 * expected to build it once per screen (it never changes while the game is running).
	 */
	static AspectGraph of(HolderLookup.RegistryLookup<IAspect> lookup) {
		List<Holder.Reference<IAspect>> all = new ArrayList<>();
		lookup.listElements().forEach(all::add);

		Map<ResourceKey<IAspect>, Integer> indices = new HashMap<>(all.size() * 2);
		for (int i = 0; i < all.size(); i++) {
			indices.put(all.get(i).key(), i);
		}

		Map<ResourceKey<IAspect>, List<Holder<IAspect>>> parents = new HashMap<>(all.size() * 2);
		for (Holder.Reference<IAspect> compound : all) {
			for (Holder<IAspect> component : compound.value().components()) {
				// A component always comes from the same registry, so it carries a key.
				ResourceKey<IAspect> key = component.unwrapKey().orElse(null);
				if (key != null) {
					parents.computeIfAbsent(key, ignored -> new ArrayList<>()).add(compound);
				}
			}
		}

		return new AspectGraph(List.copyOf(all), Map.copyOf(indices), Map.copyOf(parents));
	}

	/** Every aspect of the registry, for seeding an {@link AspectBudget}. */
	List<Holder.Reference<IAspect>> all() {
		return this.all;
	}

	Holder<IAspect> byIndex(int index) {
		return this.all.get(index);
	}

	/**
	 * @return the dense index of {@code aspect}, or {@code -1} when it does not come from this
	 * registry (which would mean a broken data pack).
	 */
	int indexOf(Holder<IAspect> aspect) {
		ResourceKey<IAspect> key = aspect.unwrapKey().orElse(null);
		if (key == null) {
			return -1;
		}
		Integer index = this.indices.get(key);
		return index == null ? -1 : index;
	}

	/** Every aspect that {@code connects} to {@code aspect}: its components, plus its parents. */
	List<Holder<IAspect>> neighbours(Holder<IAspect> aspect) {
		IAspect value = aspect.value();
		List<Holder<IAspect>> components = value.components();
		ResourceKey<IAspect> key = aspect.unwrapKey().orElse(null);
		// A direct holder has no key, so it cannot be looked up in the parent index.
		List<Holder<IAspect>> parents = key == null ? List.of() : this.parents.getOrDefault(key, List.of());

		if (components.isEmpty()) {
			return parents;
		}
		if (parents.isEmpty()) {
			return components;
		}

		List<Holder<IAspect>> result = new ArrayList<>(components.size() + parents.size());
		result.addAll(components);
		result.addAll(parents);
		return Collections.unmodifiableList(result);
	}
}
