package com.zhichaoxi.trh.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * Client-only mod bus wiring.
 *
 * <p>{@link RegisterKeyMappingsEvent} is a mod bus event while the automation itself listens on the
 * game bus, so the key binding is hooked from the mod constructor instead of leaking a client class
 * into the common setup. Reached only behind a {@code Dist.CLIENT} check.
 */
public final class ClientSetup {
	private ClientSetup() {
	}

	public static void register(IEventBus modBus) {
		modBus.addListener((RegisterKeyMappingsEvent event) -> event.register(ResearchTableAutoResearch.toggleKey()));
	}
}
