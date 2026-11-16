package com.zhichaoxi.trh.client;

import com.leclowndu93150.thaumaturge.api.aspect.AspectComponents;
import com.leclowndu93150.thaumaturge.api.aspect.IAspect;
import com.leclowndu93150.thaumaturge.client.screen.research.ResearchTableScreen;
import com.leclowndu93150.thaumaturge.content.aspect.AspectCombinations;
import com.leclowndu93150.thaumaturge.content.research.note.HexGrid;
import com.leclowndu93150.thaumaturge.content.research.note.ResearchNoteData;
import com.leclowndu93150.thaumaturge.content.research.note.ResearchNotes;
import com.leclowndu93150.thaumaturge.content.research.pool.AspectPools;
import com.leclowndu93150.thaumaturge.content.research.table.BlockEntityResearchTable;
import com.leclowndu93150.thaumaturge.content.research.table.MenuResearchTable;
import com.leclowndu93150.thaumaturge.network.ServerboundTableCombinePayload;
import com.leclowndu93150.thaumaturge.network.ServerboundTablePlaceAspectPayload;
import com.zhichaoxi.trh.ThaumaturgeResearchHelper;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side "auto research" for Thaumaturge's research table.
 *
 * <p>Everything happens on the physical client: no block, item, menu or packet of this mod exists,
 * and the server is never asked to do anything a player could not do by hand. The automation only
 * replays what the screen itself does:
 *
 * <ul>
 *   <li>placing an aspect - {@code ServerboundTablePlaceAspectPayload}, exactly like dropping an
 *       aspect from the palette onto a cell;</li>
 *   <li>combining two aspects - {@code ServerboundTableCombinePayload}, exactly like the combine
 *       button.</li>
 * </ul>
 *
 * <p>The run is started and stopped by clicking the status lamp in the top-left corner of the
 * research table frame, or with a key binding while the table is open. Green means a research run
 * is active, amber means aspects are being combined, dark means the helper is idle; the tooltip
 * names the key and, when something ended on its own, the reason.
 *
 * <p>A middle click on an aspect of the palette synthesises one of it. The components that are
 * missing are combined first, so a compound the pool does not hold yet can be produced in a single
 * gesture, and the last operation leaves it in the pool. Nothing is invented: the same two
 * operations are replayed, in an order the table accepts.
 */
@EventBusSubscriber(modid = ThaumaturgeResearchHelper.MOD_ID, value = Dist.CLIENT)
public final class ResearchTableAutoResearch {
	/**
	 * Geometry of the status lamp. The research table frame leaves a narrow strip of bare trim in its
	 * top-left corner, left of the scribing-tools slot, which is exactly the size of the lamp, so the
	 * indicator never covers a slot, an item or another widget at any GUI scale.
	 */
	private static final int LED_SIZE = 7;
	/**
	 * Offset of the lamp inside the frame. The frame's artwork starts at x=6 (the blit at x=0..5 is
	 * transparent, i.e. would show the world behind the window), so the lamp cannot sit any further
	 * left, and the next widget - the scribing-tools slot - starts at x=14.
	 */
	private static final int LED_X = 6;
	private static final int LED_Y = 3;

	/**
	 * The lamp is small, so its clickable and hovering area is grown on every side. The right side
	 * only grows by one pixel: at x=14 the scribing-tools slot begins, and it must keep its own
	 * clicks.
	 */
	private static final int LED_HIT_LEFT = 3;
	private static final int LED_HIT_RIGHT = 1;
	private static final int LED_HIT_TOP = 3;
	private static final int LED_HIT_BOTTOM = 3;

	/**
	 * Geometry of the aspect palette, taken from the research table screen: five columns of five
	 * sixteen-pixel cells, filled column by column. Only used to place the marks this mod draws;
	 * which aspect sits in a cell is asked from the screen itself.
	 */
	private static final int PALETTE_X = 10;
	private static final int PALETTE_Y = 40;
	private static final int PALETTE_CELL = 16;

	/** How long the coloured frame on a palette cell stays after a burst of combinations. */
	private static final int CRAFT_FLASH_TICKS = 18;

	/** Ticks between two interactions, so the writing animation stays readable. */
	private static final int TICKS_PER_ACTION = 3;

	/** How long the client waits for the server to confirm a placement or a combination. */
	private static final int PLACE_TIMEOUT_TICKS = 60;
	private static final int COMBINE_TIMEOUT_TICKS = 60;

	/** Speed of the breathing glow of a lit lamp. */
	private static final float GLOW_SPEED = 0.157F;

	// A green indicator lamp in a steel socket, i.e. what a Thaumaturge machine would use for
	// "running". The same palette as Forestry Utilities' escritoire auto research.
	private static final int COLOR_SOCKET = 0xFF171008;
	private static final int COLOR_RING = 0xFF6B5C4A;
	private static final int COLOR_RING_HI = 0xFF9A8A72;
	private static final int COLOR_RING_LIT = 0xFF5E7A48;
	private static final int COLOR_RING_LIT_HI = 0xFF8FB573;
	private static final int COLOR_LAMP_OFF = 0xFF26331F;
	private static final int COLOR_LAMP_OFF_HI = 0xFF32402A;
	private static final int COLOR_LAMP_OFF_LOW = 0xFF1B2415;
	private static final int COLOR_LAMP_ON = 0xFF3BDB1E;
	private static final int COLOR_LAMP_ON_HI = 0xFFC4FFA0;
	private static final int COLOR_LAMP_ON_LOW = 0xFF1B8A0B;
	/** RGB of the glow around a lit lamp; the alpha is animated. */
	private static final int COLOR_GLOW_RESEARCH = 0x6BE83C;

	// The same lamp while aspects are combined: the warm amber of a device that is working rather
	// than running a job. Green would claim a research run is in progress, which it is not.
	private static final int COLOR_RING_CRAFT = 0xFF7A5A2E;
	private static final int COLOR_RING_CRAFT_HI = 0xFFB08A4E;
	private static final int COLOR_LAMP_CRAFT = 0xFFE8912A;
	private static final int COLOR_LAMP_CRAFT_HI = 0xFFFFD79A;
	private static final int COLOR_LAMP_CRAFT_LOW = 0xFF8A500B;
	private static final int COLOR_GLOW_CRAFT = 0xE8912A;

	/** Mark on a palette cell: what a middle click can synthesise, and how it went. */
	private static final int COLOR_MARK_CRAFT = 0xD08A2E;
	private static final int COLOR_MARK_DONE = 0x3BDB1E;
	private static final int COLOR_MARK_BAD = 0xE03A2A;

	private static final KeyMapping TOGGLE_KEY = new KeyMapping(
			"key.thaumaturge_research_helper.auto_research",
			KeyConflictContext.GUI,
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_R,
			"key.categories.thaumaturge_research_helper");

	private static boolean running;
	/** Ticks since the mod started, used to animate the glow of the lamp. */
	private static int pulseTicks;
	/** Ticks left before the next interaction. */
	private static int cooldown;
	private static StopReason stopReason = StopReason.NONE;
	/** The action whose result the client is waiting for, or {@code null} when ready for the next. */
	@Nullable
	private static Pending pending;
	/** Ticks left before {@link #pending} counts as failed. */
	private static int pendingTicks;
	/** Cached aspect tree; it never changes while the game runs, but is dropped with the screen. */
	@Nullable
	private static AspectGraph graph;

	/**
	 * The combinations a middle click still has to send, or {@code null} when nothing is being
	 * synthesised. The list is complete and ordered, so the burst runs from {@link #craftIndex} to
	 * its end without being planned again.
	 */
	@Nullable
	private static List<Plan.Op> craftOps;
	private static int craftIndex;
	/** The aspect a burst is producing, kept for the status tooltip. */
	@Nullable
	private static Holder<IAspect> craftTarget;
	/** Palette cell of the burst, kept for the mark while it runs and for the flash after it. */
	private static int craftCol;
	private static int craftRow;
	/** Ticks left of the coloured frame that reports how a burst ended. */
	private static int flashTicks;
	private static boolean flashBad;

	/** The screen's private palette hit test, looked up once; {@code null} when it is not there. */
	@Nullable
	private static Method paletteHitTest;
	private static boolean paletteHitTestLookedUp;
	/** Whether the startup diagnostic about {@link #paletteHitTest} has been written. */
	private static boolean paletteAccessReported;

	private ResearchTableAutoResearch() {
	}

	/** Called from the mod bus, because {@code RegisterKeyMappingsEvent} is not a game bus event. */
	static KeyMapping toggleKey() {
		return TOGGLE_KEY;
	}

	/* ---------------------------------------------------------------- events */

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		pulseTicks++;
		if (flashTicks > 0) {
			flashTicks--;
		}

		if (!paletteAccessReported) {
			// Once the game is up, so the screen class and its registry lookups are certainly ready.
			paletteAccessReported = true;
			reportPaletteAccess();
		}

		Minecraft minecraft = Minecraft.getInstance();
		Screen screen = minecraft.screen;
		if (!(screen instanceof ResearchTableScreen table)) {
			// The table was closed: a run only ever exists while its screen is open.
			forget();
			return;
		}

		if ((!running && craftOps == null) || minecraft.player == null || minecraft.level == null) {
			return;
		}

		tick(table, minecraft);
	}

	@SubscribeEvent
	public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
		if (!(event.getScreen() instanceof ResearchTableScreen screen)) {
			return;
		}
		// The research table has a search box in its aspect palette: never steal typing from it.
		if (textFieldFocused(screen)) {
			return;
		}

		InputConstants.Key key = InputConstants.getKey(event.getKeyCode(), event.getScanCode());
		if (!TOGGLE_KEY.isActiveAndMatches(key)) {
			return;
		}

		toggle();
		event.setCanceled(true);
	}

	/**
	 * Clicking the lamp toggles the run, which is the mouse equivalent of the key binding. The
	 * binding itself may also be put on a mouse button; that case is handled afterwards.
	 */
	@SubscribeEvent
	public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
		if (!(event.getScreen() instanceof ResearchTableScreen screen)) {
			return;
		}

		if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT
				&& isOverLamp(screen, event.getMouseX(), event.getMouseY())) {
			toggle();
			event.setCanceled(true);
			return;
		}

		// A middle click on the palette asks for one unit of that aspect. It is handled before the
		// search box check, because a middle click never types into it.
		if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE
				&& synthFromPalette(screen, event.getMouseX(), event.getMouseY())) {
			event.setCanceled(true);
			return;
		}

		if (textFieldFocused(screen)) {
			return;
		}

		InputConstants.Key key = InputConstants.Type.MOUSE.getOrCreate(event.getButton());
		if (!TOGGLE_KEY.isActiveAndMatches(key)) {
			return;
		}

		toggle();
		event.setCanceled(true);
	}

	@SubscribeEvent
	public static void onScreenRender(ScreenEvent.Render.Post event) {
		if (!(event.getScreen() instanceof ResearchTableScreen screen)) {
			return;
		}

		GuiGraphics graphics = event.getGuiGraphics();
		renderLamp(graphics, screen, event.getMouseX(), event.getMouseY(), event.getPartialTick());
		renderPaletteMarks(graphics, screen);
	}

	/* ---------------------------------------------------------------- run control */

	/**
	 * Starts a research run, or stops whatever the helper is doing. A synthesis burst counts as
	 * something in progress, so the same gesture ends it.
	 */
	private static void toggle() {
		if (running || craftOps != null) {
			stop(StopReason.NONE);
			return;
		}

		stop(StopReason.NONE);
		running = true;
	}

	private static void stop(StopReason reason) {
		running = false;
		cooldown = 0;
		pending = null;
		pendingTicks = 0;
		craftOps = null;
		craftIndex = 0;
		craftTarget = null;
		stopReason = reason;
	}

	private static void forget() {
		stop(StopReason.NONE);
		graph = null;
		flashTicks = 0;
	}

	/* ---------------------------------------------------------------- synthesis from the palette */

	/**
	 * Middle click on the aspect palette: produce one unit of the aspect under the mouse.
	 *
	 * @return whether the click was on the palette, i.e. whether it was used
	 */
	private static boolean synthFromPalette(ResearchTableScreen screen, double mouseX, double mouseY) {
		Holder<IAspect> target = paletteAspectAt(screen, mouseX, mouseY);
		if (target == null) {
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null) {
			return false;
		}

		double localX = mouseX - screen.getGuiLeft() - PALETTE_X;
		double localY = mouseY - screen.getGuiTop() - PALETTE_Y;
		startCraft(minecraft, screen, target, (int) (localX / PALETTE_CELL), (int) (localY / PALETTE_CELL));
		return true;
	}

	/**
	 * Plans a burst of combinations that leaves one unit of {@code target} in the pool and starts
	 * sending it. The research run is cancelled first: both features send the same packets and share
	 * one queue, and finishing the note would take far longer than the burst the player just asked
	 * for.
	 */
	private static void startCraft(Minecraft minecraft, ResearchTableScreen screen, Holder<IAspect> target,
			int col, int row) {
		stop(StopReason.NONE);

		craftCol = col;
		craftRow = row;
		flashTicks = 0;
		craftTarget = target;

		AspectBudget budget = budgetFor(minecraft, screen.getMenu().blockEntity());
		List<Plan.Op> ops = budget.craft(target);
		if (ops == null) {
			// A primal aspect has no recipe at all; anything else failed on missing components.
			endCraft(target.value().isPrimal() ? StopReason.NO_RECIPE : StopReason.NO_COMPONENTS);
			return;
		}

		craftOps = ops;
		craftIndex = 0;
		cooldown = TICKS_PER_ACTION;
	}

	/**
	 * Ends the burst, keeping only the coloured frame on its palette cell: green means the aspect is
	 * in the pool now, red means it is not.
	 */
	private static void endCraft(StopReason reason) {
		stop(reason);
		flashTicks = CRAFT_FLASH_TICKS;
		flashBad = reason != StopReason.CRAFT_DONE && reason != StopReason.NONE;
	}

	private static void fail(boolean craft, StopReason reason) {
		if (craft) {
			endCraft(reason);
		} else {
			stop(reason);
		}
	}

	/* ---------------------------------------------------------------- state machine */

	private static void tick(ResearchTableScreen screen, Minecraft minecraft) {
		// A sent action is confirmed (or given up on) before the next one is decided, so the plan is
		// always built against the state the server really has.
		if (pending != null) {
			// Settling a placement reads the note; a combination is settled from the pool alone, and
			// a burst must keep working with no note on the table at all.
			ResearchNoteData data = pending.craft() ? null : noteData(screen);
			if (settled(pending, data, minecraft.player)) {
				pending = null;
				cooldown = TICKS_PER_ACTION;
			} else if (--pendingTicks <= 0) {
				fail(pending.craft(), pending.op() instanceof Plan.Place
						? StopReason.PLACE_FAILED
						: StopReason.COMBINE_FAILED);
			}
			return;
		}

		if (cooldown > 0) {
			cooldown--;
			return;
		}

		if (craftOps != null) {
			tickCraft(screen, minecraft);
			return;
		}

		tickResearch(screen, minecraft);
	}

	/** Sends the next combination of the burst, or closes it once the last one is confirmed. */
	private static void tickCraft(ResearchTableScreen screen, Minecraft minecraft) {
		List<Plan.Op> ops = craftOps;
		if (ops == null) {
			return;
		}
		if (craftIndex >= ops.size()) {
			endCraft(StopReason.CRAFT_DONE);
			return;
		}

		execute(ops.get(craftIndex++), screen.getMenu(), minecraft, true);
	}

	private static void tickResearch(ResearchTableScreen screen, Minecraft minecraft) {
		ResearchNoteData data = noteData(screen);
		if (data == null) {
			stop(StopReason.NO_NOTE);
			return;
		}
		if (data.complete()) {
			stop(StopReason.DONE);
			return;
		}

		MenuResearchTable menu = screen.getMenu();
		BlockEntityResearchTable table = menu.blockEntity();
		if (table == null) {
			stop(StopReason.NONE);
			return;
		}
		if (!table.hasInkReady()) {
			// Every placement wears down the scribing tools; without them the note cannot change.
			stop(StopReason.NO_INK);
			return;
		}

		ResearchNoteSolver.Result result = plan(minecraft, data, table);
		if (result.failure() != ResearchNoteSolver.Failure.NONE) {
			// The solver could not finish the note. The operations it did work out are only the part
			// of a solution that no longer exists, so nothing is spent on them.
			stop(mapFailure(result.failure()));
			return;
		}
		if (result.isEmpty()) {
			// Every anchor is connected already; the server completes the note on its own.
			stop(StopReason.DONE);
			return;
		}

		execute(result.ops().get(0), menu, minecraft, false);
	}

	private static ResearchNoteSolver.Result plan(Minecraft minecraft, ResearchNoteData data,
			BlockEntityResearchTable table) {
		Player player = minecraft.player;
		AspectGraph aspects = graphFor(minecraft);
		AspectBudget budget = budgetFor(minecraft, table);

		// Connectivity needs both aspects of a pair to be known. "Known" therefore means discovered
		// *or* obtainable: combining an aspect out of its components grants and discovers it in the
		// same step, so a compound that is only reachable through a combination will be known by the
		// time it is placed.
		Predicate<Holder<IAspect>> usable = aspect -> AspectPools.isDiscovered(player, aspect)
				|| budget.canTake(aspect);
		return ResearchNoteSolver.solve(data, aspects, budget, usable);
	}

	/**
	 * What the player can spend right now: their own aspect pool plus the aspects the table itself
	 * offers. The table can stand in for the pool, so both count.
	 */
	private static AspectBudget budgetFor(Minecraft minecraft, @Nullable BlockEntityResearchTable table) {
		AspectGraph aspects = graphFor(minecraft);
		AspectBudget budget = new AspectBudget();

		Player player = minecraft.player;
		for (Holder.Reference<IAspect> aspect : aspects.all()) {
			int fromTable = table == null ? 0 : table.bonusAspects().amountOf(aspect);
			budget.set(aspect, AspectPools.amount(player, aspect) + fromTable);
		}
		return budget;
	}

	/** Sends one action to the server and stores what its result will look like. */
	private static void execute(Plan.Op op, MenuResearchTable menu, Minecraft minecraft, boolean craft) {
		Player player = minecraft.player;
		BlockPos pos = menu.pos();

		if (op instanceof Plan.Place place) {
			ResourceLocation id = AspectPools.idOf(place.aspect());
			PacketDistributor.sendToServer(
					new ServerboundTablePlaceAspectPayload(pos, place.hex().q(), place.hex().r(), Optional.of(id)));
			pending = new Pending(op, place.hex(), place.aspect(), -1, false);
			pendingTicks = PLACE_TIMEOUT_TICKS;
			return;
		}

		Plan.Combine combine = (Plan.Combine) op;
		Holder<IAspect> produced = AspectCombinations.result(player.level().registryAccess(),
				combine.first(), combine.second());
		if (produced == null) {
			// Not a real recipe: the plan and the registry disagree, which must never happen.
			fail(craft, StopReason.COMBINE_FAILED);
			return;
		}

		BlockEntityResearchTable table = menu.blockEntity();
		boolean bonusFirst = isBonusSource(player, table, combine.first());
		// The table's own aspects stand in for the pool, one input at a time: when both inputs are
		// the same aspect, the second has to be looked at after the first has been paid for.
		int sparePool = AspectPools.amount(player, combine.second())
				- (combine.first().equals(combine.second()) && !bonusFirst ? 1 : 0);
		boolean bonusSecond = table != null
				&& sparePool <= 0
				&& table.bonusAspects().amountOf(combine.second()) > 0;

		PacketDistributor.sendToServer(new ServerboundTableCombinePayload(pos,
				AspectPools.idOf(combine.first()), AspectPools.idOf(combine.second()), bonusFirst, bonusSecond));
		pending = new Pending(op, null, produced, AspectPools.amount(player, produced), craft);
		pendingTicks = COMBINE_TIMEOUT_TICKS;
	}

	/** Whether the aspect of a combination has to come from the table's own pool. */
	private static boolean isBonusSource(Player player, @Nullable BlockEntityResearchTable table,
			Holder<IAspect> aspect) {
		return table != null
				&& AspectPools.amount(player, aspect) <= 0
				&& table.bonusAspects().amountOf(aspect) > 0;
	}

	/** Whether the server has reported the result of the pending action. */
	private static boolean settled(Pending action, @Nullable ResearchNoteData data, Player player) {
		if (action.op() instanceof Plan.Place) {
			ResearchNoteData.Cell cell = data == null ? null : data.cellAt(action.hex());
			Holder<IAspect> placed = cell == null ? null : cell.aspectOrNull();
			return cell != null
					&& cell.type() == ResearchNoteData.TYPE_PLACED
					&& placed != null
					&& AspectPools.idOf(placed).equals(AspectPools.idOf(action.expected()));
		}

		return AspectPools.amount(player, action.expected()) > action.before();
	}

	private static StopReason mapFailure(ResearchNoteSolver.Failure failure) {
		return switch (failure) {
			case NONE -> StopReason.DONE;
			case NO_ASPECTS -> StopReason.NO_ASPECTS;
			case NO_ROOT -> StopReason.NO_ROOT;
			case TOO_COMPLEX -> StopReason.NO_ASPECTS;
		};
	}

	/* ---------------------------------------------------------------- screen access */

	@Nullable
	private static ResearchNoteData noteData(ResearchTableScreen screen) {
		// The screen reads the note from the second table slot, and so does this.
		return ResearchNotes.dataOf(screen.getMenu().getSlot(1).getItem());
	}

	/**
	 * The aspect the palette shows under the mouse, or {@code null} when there is none.
	 *
	 * <p>The screen already answers exactly this question - it knows the current page, the search
	 * filter and that the cells are filled column by column - but the method is private, so it is
	 * looked up once instead of being copied, where it would silently drift from the real one. When
	 * a future version of Thaumaturge moves it, a middle click simply does nothing.
	 */
	@Nullable
	@SuppressWarnings("unchecked")
	private static Holder<IAspect> paletteAspectAt(ResearchTableScreen screen, double mouseX, double mouseY) {
		Method method = paletteHitTestMethod();
		if (method == null) {
			return null;
		}

		try {
			return (Holder<IAspect>) method.invoke(screen, mouseX, mouseY);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			return null;
		}
	}

	@Nullable
	private static Method paletteHitTestMethod() {
		if (paletteHitTestLookedUp) {
			return paletteHitTest;
		}
		paletteHitTestLookedUp = true;

		try {
			Method method = ResearchTableScreen.class.getDeclaredMethod("paletteAspectAt", double.class, double.class);
			method.setAccessible(true);
			paletteHitTest = method;
		} catch (NoSuchMethodException | RuntimeException exception) {
			// Not fatal: the research automation does not depend on the palette at all.
			LogUtils.getLogger().warn("[thaumaturge_research_helper] palette hit test not available, "
					+ "middle-click synthesis is disabled", exception);
			paletteHitTest = null;
		}
		return paletteHitTest;
	}

	/**
	 * One line in the log about whether a middle click can find the aspect under the mouse. Called
	 * once while the client starts, so a report of "the middle click does nothing" can be answered
	 * from the log alone.
	 */
	static void reportPaletteAccess() {
		if (paletteHitTestMethod() != null) {
			LogUtils.getLogger().info("[thaumaturge_research_helper] palette hit test resolved, "
					+ "middle-click synthesis is available");
		}
	}

	private static AspectGraph graphFor(Minecraft minecraft) {
		AspectGraph cached = graph;
		if (cached != null) {
			return cached;
		}

		HolderLookup.RegistryLookup<IAspect> lookup = minecraft.level.registryAccess()
				.lookupOrThrow(IAspect.REGISTRY_KEY);
		AspectGraph built = AspectGraph.of(lookup);
		graph = built;
		return built;
	}

	private static boolean textFieldFocused(Screen screen) {
		return screen.getFocused() instanceof EditBox;
	}

	/* ---------------------------------------------------------------- status lamp */

	/** What the lamp is reporting: nothing, a research run, or a burst of combinations. */
	private enum LampState {
		IDLE,
		RESEARCH,
		CRAFT
	}

	private static LampState lampState() {
		if (craftOps != null) {
			return LampState.CRAFT;
		}
		return running ? LampState.RESEARCH : LampState.IDLE;
	}

	/**
	 * Draws the status lamp into the free strip of the frame's top-left corner, plus its tooltip.
	 * Green means a research run is active, amber means aspects are being combined, dark means idle.
	 */
	private static void renderLamp(GuiGraphics graphics, ResearchTableScreen screen, int mouseX, int mouseY,
			float partialTick) {
		int x = screen.getGuiLeft() + LED_X;
		int y = screen.getGuiTop() + LED_Y;
		int right = x + LED_SIZE;
		int bottom = y + LED_SIZE;
		LampState state = lampState();

		if (state != LampState.IDLE) {
			// A short breathing glow, so an active run can be spotted without watching the note.
			float glow = (float) (0.5 - 0.5 * Math.cos((pulseTicks + partialTick) * GLOW_SPEED));
			int rgb = state == LampState.CRAFT ? COLOR_GLOW_CRAFT : COLOR_GLOW_RESEARCH;
			graphics.fill(x - 1, y - 1, right + 1, bottom + 1, glowColor(rgb, 0x14 + (int) (glow * 0x2A)));
		}

		int ring;
		int ringHi;
		int lamp;
		int lampHi;
		int lampLow;
		switch (state) {
			case RESEARCH -> {
				ring = COLOR_RING_LIT;
				ringHi = COLOR_RING_LIT_HI;
				lamp = COLOR_LAMP_ON;
				lampHi = COLOR_LAMP_ON_HI;
				lampLow = COLOR_LAMP_ON_LOW;
			}
			case CRAFT -> {
				ring = COLOR_RING_CRAFT;
				ringHi = COLOR_RING_CRAFT_HI;
				lamp = COLOR_LAMP_CRAFT;
				lampHi = COLOR_LAMP_CRAFT_HI;
				lampLow = COLOR_LAMP_CRAFT_LOW;
			}
			default -> {
				ring = COLOR_RING;
				ringHi = COLOR_RING_HI;
				lamp = COLOR_LAMP_OFF;
				lampHi = COLOR_LAMP_OFF_HI;
				lampLow = COLOR_LAMP_OFF_LOW;
			}
		}

		// Socket, then the lamp itself: an LED with a lit upper left corner.
		graphics.fill(x, y, right, bottom, COLOR_SOCKET);
		graphics.fill(x + 1, y + 1, right - 1, bottom - 1, ring);
		graphics.fill(x + 1, y + 1, right - 1, y + 2, ringHi);

		graphics.fill(x + 2, y + 2, right - 2, bottom - 2, lamp);
		graphics.fill(x + 2, y + 2, right - 2, y + 3, lampHi);
		graphics.fill(x + 2, y + 2, x + 3, bottom - 2, lampHi);
		graphics.fill(right - 3, y + 3, right - 2, bottom - 2, lampLow);
		graphics.fill(x + 3, bottom - 3, right - 3, bottom - 2, lampLow);

		if (isOverLamp(screen, mouseX, mouseY)) {
			renderTooltip(graphics, mouseX, mouseY);
		}
	}

	/**
	 * The clickable (and hovering) area of the lamp: the lamp itself grown by the padding above.
	 * It stops one pixel short of the scribing-tools slot on the right.
	 */
	private static boolean isOverLamp(ResearchTableScreen screen, double mouseX, double mouseY) {
		int x = screen.getGuiLeft() + LED_X - LED_HIT_LEFT;
		int y = screen.getGuiTop() + LED_Y - LED_HIT_TOP;
		int right = x + LED_SIZE + LED_HIT_LEFT + LED_HIT_RIGHT;
		int bottom = y + LED_SIZE + LED_HIT_TOP + LED_HIT_BOTTOM;
		return mouseX >= x && mouseX < right && mouseY >= y && mouseY < bottom;
	}

	/* ---------------------------------------------------------------- palette marks */

	/**
	 * Marks on the aspect palette: a pulsing frame on the aspect being synthesised, and a short flash
	 * reporting how the last burst ended.
	 *
	 * <p>Nothing is drawn on hover: the palette shows its own tooltip right at the cursor, so a frame
	 * there would only be painted over it. How the feature is used is said by the status tooltip.
	 */
	private static void renderPaletteMarks(GuiGraphics graphics, ResearchTableScreen screen) {
		if (flashTicks <= 0 && craftOps == null) {
			return;
		}

		int baseX = screen.getGuiLeft() + PALETTE_X;
		int baseY = screen.getGuiTop() + PALETTE_Y;
		int x = baseX + craftCol * PALETTE_CELL;
		int y = baseY + craftRow * PALETTE_CELL;

		if (flashTicks > 0) {
			// Fades out, so a burst that just ended is still easy to notice.
			int alpha = 0x30 + 0xCF * flashTicks / CRAFT_FLASH_TICKS;
			int rgb = flashBad ? COLOR_MARK_BAD : COLOR_MARK_DONE;
			frame(graphics, x, y, (alpha << 24) | rgb);
		}

		if (craftOps != null) {
			// A breathing frame tells which aspect the burst is working on.
			float glow = (float) (0.5 - 0.5 * Math.cos(pulseTicks * GLOW_SPEED * 2.0));
			int alpha = 0x60 + (int) (glow * 0x9F);
			frame(graphics, x, y, (alpha << 24) | COLOR_MARK_CRAFT);
		}
	}

	/** Draws a one-pixel frame around a palette cell; a grown cell, so neighbouring sprites stay clear. */
	private static void frame(GuiGraphics graphics, int x, int y, int color) {
		int right = x + PALETTE_CELL;
		int bottom = y + PALETTE_CELL;
		graphics.fill(x - 1, y - 1, right + 1, y, color);
		graphics.fill(x - 1, bottom, right + 1, bottom + 1, color);
		graphics.fill(x - 1, y, x, bottom, color);
		graphics.fill(right, y, right + 1, bottom, color);
	}

	/** State, how to toggle it, what a burst is doing, and - only when it matters - why it ended. */
	private static void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
		List<Component> lines = new ArrayList<>(4);
		LampState state = lampState();

		lines.add(Component.translatable(switch (state) {
			case IDLE -> "gui.thaumaturge_research_helper.auto_research.state.off";
			case RESEARCH -> "gui.thaumaturge_research_helper.auto_research.state.on";
			case CRAFT -> "gui.thaumaturge_research_helper.auto_research.state.craft";
		}));
		lines.add(Component.translatable("gui.thaumaturge_research_helper.auto_research.key",
				TOGGLE_KEY.getTranslatedKeyMessage()));

		Holder<IAspect> target = craftTarget;
		List<Plan.Op> ops = craftOps;
		if (state == LampState.CRAFT && target != null && ops != null) {
			lines.add(Component.translatable("gui.thaumaturge_research_helper.craft.progress",
					AspectComponents.name(target), Math.max(1, ops.size() - craftIndex)));
		} else if (state == LampState.IDLE) {
			Component reason = stopReason.tooltip();
			if (reason != null) {
				lines.add(reason.copy().withStyle(ChatFormatting.GRAY));
			}
		}

		lines.add(Component.translatable("gui.thaumaturge_research_helper.craft.hint")
				.withStyle(ChatFormatting.DARK_GRAY));

		Font font = Minecraft.getInstance().font;
		graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
	}

	private static int glowColor(int rgb, int alpha) {
		return (Math.max(0, Math.min(0xFF, alpha)) << 24) | (rgb & 0xFFFFFF);
	}

	/** The action whose result is being awaited; {@code craft} marks the ones of a synthesis burst. */
	private record Pending(Plan.Op op, @Nullable HexGrid.Hex hex, Holder<IAspect> expected, int before,
			boolean craft) {
	}

	private enum StopReason {
		/** Stopped by the player, or nothing to report. */
		NONE(null),
		/** No research note on the table. */
		NO_NOTE("no_note"),
		/** The scribing tools are worn out. */
		NO_INK("no_ink"),
		/** No chain of affordable aspects connects the anchors. */
		NO_ASPECTS("no_aspects"),
		/** The note has no anchor cell at all. */
		NO_ROOT("no_root"),
		/** The server refused a placement. */
		PLACE_FAILED("place_failed"),
		/** The server refused a combination. */
		COMBINE_FAILED("combine_failed"),
		/** The note is finished. */
		DONE("done"),
		/** A primal aspect has no recipe, so it can never be synthesised. */
		NO_RECIPE("no_recipe"),
		/** The components of the requested aspect cannot be paid for. */
		NO_COMPONENTS("no_components"),
		/** A synthesis burst finished and its aspect is in the pool. */
		CRAFT_DONE("craft_done");

		@Nullable
		private final String key;

		StopReason(@Nullable String key) {
			this.key = key;
		}

		@Nullable
		Component tooltip() {
			return this.key == null
					? null
					: Component.translatable("gui.thaumaturge_research_helper.auto_research.stopped." + this.key);
		}
	}
}
