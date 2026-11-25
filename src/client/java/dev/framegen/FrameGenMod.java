package dev.framegen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.framegen.engine.FrameGenEngine;
import dev.framegen.engine.FrameGenPipelines;
import dev.framegen.engine.Presenter;
import dev.framegen.gui.FrameGenScreen;
import java.util.Locale;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FrameGenMod implements ClientModInitializer {
	public static final String MOD_ID = "framegen";
	public static final Logger LOGGER = LoggerFactory.getLogger("FrameGen");

	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
	private static KeyMapping toggleKey;
	private static KeyMapping modeKey;
	private static KeyMapping statsKey;
	private static KeyMapping settingsKey;
	private static KeyMapping snapshotKey;

	@Override
	public void onInitializeClient() {
		FrameGenConfig.get();
		FrameGenPipelines.register();

		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.framegen.toggle", InputConstants.KEY_F8, CATEGORY));
		modeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.framegen.mode", InputConstants.UNKNOWN.getValue(), CATEGORY));
		statsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.framegen.stats", InputConstants.UNKNOWN.getValue(), CATEGORY));
		settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.framegen.settings", InputConstants.UNKNOWN.getValue(), CATEGORY));
		snapshotKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.framegen.snapshot", InputConstants.KEY_F9, CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(FrameGenMod::handleKeys);

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "stats"), (graphics, deltaTracker) -> {
			FrameGenConfig config = FrameGenConfig.get();
			Minecraft mc = Minecraft.getInstance();
			if (!config.showStats || mc.debugEntries.isOverlayVisible()) return;
			graphics.text(mc.font, statsLine(), 2, 2, 0xFFFFFFFF, true);
		});
	}

	private static void handleKeys(Minecraft mc) {
		FrameGenConfig config = FrameGenConfig.get();
		while (toggleKey.consumeClick()) {
			config.enabled = !config.enabled;
			config.save();
			notify(mc, Component.translatable(config.enabled ? "framegen.message.enabled" : "framegen.message.disabled"));
		}
		while (modeKey.consumeClick()) {
			config.mode = config.mode == FrameGenConfig.Mode.INTERPOLATE ? FrameGenConfig.Mode.EXTRAPOLATE : FrameGenConfig.Mode.INTERPOLATE;
			config.save();
			notify(mc, Component.translatable("framegen.message.mode", modeName(config.mode)));
		}
		while (statsKey.consumeClick()) {
			config.showStats = !config.showStats;
			config.save();
		}
		while (snapshotKey.consumeClick()) {
			var capture = FrameGenEngine.capture();
			if (capture != null) {
				capture.requestSnapshot();
			} else {
				notify(mc, Component.translatable("framegen.message.snapshot_unavailable"));
			}
		}
		while (settingsKey.consumeClick()) {
			mc.gui.setScreen(new FrameGenScreen(mc.gui.screen()));
		}
	}

	private static void notify(Minecraft mc, Component message) {
		if (mc.player != null) {
			mc.player.sendOverlayMessage(message);
		}
	}

	public static Component modeName(FrameGenConfig.Mode mode) {
		return Component.translatable("framegen.mode." + mode.name().toLowerCase(Locale.ROOT));
	}

	/** One-line status for the HUD and the settings screen. */
	public static Component statsLine() {
		FrameGenConfig config = FrameGenConfig.get();
		Presenter p = FrameGenEngine.presenter();
		switch (FrameGenEngine.status()) {
			case UNSUPPORTED_BACKEND:
				return Component.translatable("framegen.status.unsupported_backend").withStyle(ChatFormatting.RED);
			case FAILED:
			case NOT_STARTED:
				return Component.translatable("framegen.status.failed").withStyle(ChatFormatting.RED);
			default:
				break;
		}
		if (p == null) return Component.empty();
		if (!config.enabled) {
			return Component.translatable("framegen.stats.off", fmt(p.realFps()));
		}
		var capture = FrameGenEngine.capture();
		if (capture != null && capture.isRedirected()) {
			return Component.translatable("framegen.stats.redirected", fmt(p.realFps()));
		}
		if (p.isNative()) {
			return Component.translatable("framegen.stats.native", fmt(p.realFps()));
		}
		if (!p.isGenerating()) {
			return Component.translatable("framegen.stats.idle", fmt(p.realFps()));
		}
		return Component.translatable("framegen.stats.on",
			fmt(p.outputFps()), fmt(p.realFps()), modeName(config.mode), String.format(Locale.ROOT, "%.1f", p.latencyMs()),
			String.format(Locale.ROOT, "%.2f", p.presentCostMs()));
	}

	private static String fmt(float v) {
		return Integer.toString(Math.round(v));
	}
}
