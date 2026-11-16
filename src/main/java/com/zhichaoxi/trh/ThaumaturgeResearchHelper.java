package com.zhichaoxi.trh;

import com.zhichaoxi.trh.client.ClientSetup;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side quality-of-life addon for Thaumaturge: a green status LED in the top-left corner of
 * the research table that runs the note puzzle automatically.
 *
 * <p>The feature itself lives in {@code com.zhichaoxi.trh.client} and is never loaded on
 * a dedicated server - it only replays what the research table screen already does.
 */
@Mod(ThaumaturgeResearchHelper.MOD_ID)
public final class ThaumaturgeResearchHelper {
	public static final String MOD_ID = "thaumaturge_research_helper";
	public static final Logger LOGGER = LoggerFactory.getLogger("ThaumaturgeResearchHelper");

	public ThaumaturgeResearchHelper(IEventBus modBus) {
		// The key binding is a mod bus listener, so it is hooked here; the class itself is client-only.
		if (FMLEnvironment.dist.isClient()) {
			ClientSetup.register(modBus);
		}
	}
}
