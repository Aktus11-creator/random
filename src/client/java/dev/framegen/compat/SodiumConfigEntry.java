package dev.framegen.compat;

import dev.framegen.FrameGenConfig;
import dev.framegen.FrameGenMod;
import dev.framegen.engine.FrameGenEngine;
import java.util.Locale;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Adds a "FrameGen" page to Sodium's video settings (the same way Nvidium adds its page). Only loaded by Sodium,
 * through the {@code sodium:config_api_user} entrypoint, so FrameGen has no hard dependency on it.
 */
public final class SodiumConfigEntry implements ConfigEntryPoint {
	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(FrameGenMod.MOD_ID, path);
	}

	private static Component tooltip(String key) {
		return Component.translatable(key + ".tooltip");
	}

	@Override
	public void registerConfigLate(ConfigBuilder builder) {
		FrameGenConfig c = FrameGenConfig.get();
		StorageEventHandler save = c::save;
		Identifier enabledId = id("enabled");

		OptionGroupBuilder general = builder.createOptionGroup()
			.addOption(builder.createBooleanOption(enabledId)
				.setName(Component.translatable("framegen.option.enabled"))
				// The tooltip doubles as a live status readout (backend support, current frame rates).
				.setTooltip(v -> Component.empty()
					.append(tooltip("framegen.option.enabled"))
					.append("\n\n")
					.append(FrameGenMod.statsLine().copy().withStyle(ChatFormatting.GRAY)))
				.setImpact(OptionImpact.MEDIUM)
				.setDefaultValue(true)
				.setEnabled(FrameGenEngine.status() == FrameGenEngine.Status.ACTIVE)
				.setBinding(v -> c.enabled = v, () -> c.enabled)
				.setStorageHandler(save))
			.addOption(builder.createEnumOption(id("mode"), FrameGenConfig.Mode.class)
				.setName(Component.translatable("framegen.option.mode"))
				.setTooltip(tooltip("framegen.option.mode"))
				.setImpact(OptionImpact.LOW)
				.setDefaultValue(FrameGenConfig.Mode.INTERPOLATE)
				.setElementNameProvider(FrameGenMod::modeName)
				.setEnabledProvider(s -> s.readBooleanOption(enabledId), enabledId)
				.setBinding(v -> c.mode = v, () -> c.mode)
				.setStorageHandler(save))
			.addOption(builder.createEnumOption(id("quality"), FrameGenConfig.Quality.class)
				.setName(Component.translatable("framegen.option.quality"))
				.setTooltip(tooltip("framegen.option.quality"))
				.setImpact(OptionImpact.LOW)
				.setDefaultValue(FrameGenConfig.Quality.HIGH)
				.setElementNameProvider(q -> Component.translatable("framegen.quality." + q.name().toLowerCase(Locale.ROOT)))
				.setEnabledProvider(s -> s.readBooleanOption(enabledId), enabledId)
				.setBinding(v -> c.quality = v, () -> c.quality)
				.setStorageHandler(save));

		OptionGroupBuilder pacing = builder.createOptionGroup()
			.addOption(builder.createIntegerOption(id("multiplier"))
				.setName(Component.translatable("framegen.option.multiplier"))
				.setTooltip(tooltip("framegen.option.multiplier"))
				.setImpact(OptionImpact.HIGH)
				.setRange(0, 4, 1)
				.setDefaultValue(2)
				.setValueFormatter(v -> v == 0 ? Component.translatable("framegen.multiplier.off") : Component.translatable("framegen.multiplier.value", v))
				.setEnabledProvider(s -> s.readBooleanOption(enabledId), enabledId)
				.setBinding(v -> c.multiplier = v, () -> c.multiplier)
				.setStorageHandler(save))
			.addOption(builder.createIntegerOption(id("output_fps"))
				.setName(Component.translatable("framegen.option.output_fps"))
				.setTooltip(tooltip("framegen.option.output_fps"))
				.setImpact(OptionImpact.MEDIUM)
				// Slider position 0 = display refresh rate, then 30..360 fps in steps of 5 (same as FrameGen's own screen).
				.setRange(0, 67, 1)
				.setDefaultValue(0)
				.setValueFormatter(v -> v == 0 ? Component.translatable("framegen.output_fps.display") : Component.literal(Integer.toString(30 + (v - 1) * 5)))
				.setEnabledProvider(s -> s.readBooleanOption(enabledId), enabledId)
				.setBinding(v -> c.targetOutputFps = v == 0 ? 0 : 30 + (v - 1) * 5,
					() -> c.targetOutputFps <= 0 ? 0 : Math.max(1, Math.min(67, (c.targetOutputFps - 30) / 5 + 1)))
				.setStorageHandler(save))
			.addOption(builder.createIntegerOption(id("extrapolation"))
				.setName(Component.translatable("framegen.option.extrapolation"))
				.setTooltip(tooltip("framegen.option.extrapolation"))
				.setImpact(OptionImpact.LOW)
				.setRange(0, 6, 1)
				.setDefaultValue(4)
				.setValueFormatter(v -> Component.translatable("framegen.extrapolation.value", String.format(Locale.ROOT, "%.2f", v / 4.0)))
				.setEnabledProvider(s -> s.readBooleanOption(enabledId), enabledId)
				.setBinding(v -> c.maxExtrapolation = v / 4.0f, () -> Math.round(c.maxExtrapolation * 4.0f))
				.setStorageHandler(save))
			.addOption(builder.createBooleanOption(id("low_latency"))
				.setName(Component.translatable("framegen.option.low_latency"))
				.setTooltip(tooltip("framegen.option.low_latency"))
				.setImpact(OptionImpact.LOW)
				.setDefaultValue(true)
				.setEnabledProvider(s -> s.readBooleanOption(enabledId), enabledId)
				.setBinding(v -> c.lowLatencyPresent = v, () -> c.lowLatencyPresent)
				.setStorageHandler(save));

		OptionGroupBuilder display = builder.createOptionGroup()
			.addOption(builder.createBooleanOption(id("sharp_hud"))
				.setName(Component.translatable("framegen.option.sharp_hud"))
				.setTooltip(tooltip("framegen.option.sharp_hud"))
				.setImpact(OptionImpact.LOW)
				.setDefaultValue(true)
				.setEnabledProvider(s -> s.readBooleanOption(enabledId), enabledId)
				.setBinding(v -> c.sharpHud = v, () -> c.sharpHud)
				.setStorageHandler(save))
			.addOption(builder.createBooleanOption(id("stats"))
				.setName(Component.translatable("framegen.option.stats"))
				.setTooltip(tooltip("framegen.option.stats"))
				.setImpact(OptionImpact.LOW)
				.setDefaultValue(false)
				.setBinding(v -> c.showStats = v, () -> c.showStats)
				.setStorageHandler(save))
			.addOption(builder.createBooleanOption(id("debug"))
				.setName(Component.translatable("framegen.option.debug"))
				.setTooltip(tooltip("framegen.option.debug"))
				.setImpact(OptionImpact.LOW)
				.setDefaultValue(false)
				.setEnabledProvider(s -> s.readBooleanOption(enabledId), enabledId)
				.setBinding(v -> c.debugView = v, () -> c.debugView)
				.setStorageHandler(save));

		OptionPageBuilder page = builder.createOptionPage()
			.setName(Component.translatable("framegen.settings.title"))
			.addOptionGroup(general)
			.addOptionGroup(pacing)
			.addOptionGroup(display);

		builder.registerOwnModOptions()
			.setNonTintedIcon(Identifier.fromNamespaceAndPath(FrameGenMod.MOD_ID, "icon.png"))
			.setColorTheme(builder.createColorTheme().setBaseThemeRGB(0x78C8FF))
			.addPage(page);
	}
}
