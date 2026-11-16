package com.zhichaoxi.trh.client;

import com.leclowndu93150.thaumaturge.api.aspect.IAspect;
import com.leclowndu93150.thaumaturge.content.research.note.HexGrid;
import net.minecraft.core.Holder;

/**
 * The operations the research table understands, in the same terms the server-side table uses.
 *
 * <p>Both are replayed through Thaumaturge's own packets, so the server never sees anything a player
 * could not have clicked by hand.
 */
final class Plan {
	private Plan() {
	}

	sealed interface Op permits Place, Combine {
	}

	/** Put {@code aspect} on the empty note cell {@code hex}. */
	record Place(HexGrid.Hex hex, Holder<IAspect> aspect) implements Op {
	}

	/** Combine {@code first} and {@code second} at the table, which yields their compound. */
	record Combine(Holder<IAspect> first, Holder<IAspect> second) implements Op {
	}
}
