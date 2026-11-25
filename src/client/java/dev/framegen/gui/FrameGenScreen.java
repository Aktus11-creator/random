package dev.framegen.gui;

import com.mojang.serialization.Codec;
import dev.framegen.FrameGenConfig;
import dev.framegen.FrameGenMod;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Settings screen, built from vanilla option widgets so it looks and behaves like the video settings. */
public final class FrameGenScreen extends OptionsSubScreen {
	private static final Component TITLE = Component.translatable("framegen.settings.title");
	private @Nullable StringWidget status;
	private long lastStatusUpdate;

	public FrameGenScreen(@Nullable Screen lastScreen) {
		super(lastScreen, Minecraft.getInstance().options, TITLE);
	}

	@Override
	protected void addTitle() {
		LinearLayout header = LinearLayout.vertical().spacing(4);
		header.defaultCellSetting().alignHorizontallyCenter();
		header.addChild(new StringWidget(TITLE, this.font));
		this.status = header.addChild(new StringWidget(300, 9, FrameGenMod.statsLine(), this.font));
		this.layout.addToHeader(header);
		this.layout.setHeaderHeight(44);
	}

	@Override
	protected void addOptions() {
		FrameGenConfig c = FrameGenConfig.get();
		this.list.addBig(bool("framegen.option.enabled", c.enabled, v -> c.enabled = v));
		this.list.addSmall(new OptionInstance[]{
			enumOption("framegen.option.mode", FrameGenConfig.Mode.class, c.mode, v -> c.mode = v, FrameGenMod::modeName),
			enumOption("framegen.option.quality", FrameGenConfig.Quality.class, c.quality, v -> c.quality = v,
				v -> Component.translatable("framegen.quality." + v.name().toLowerCase(Locale.ROOT))),
			multiplierOption(c),
			outputFpsOption(c),
			bool("framegen.option.sharp_hud", c.sharpHud, v -> c.sharpHud = v),
			extrapolationOption(c),
			bool("framegen.option.low_latency", c.lowLatencyPresent, v -> c.lowLatencyPresent = v),
			bool("framegen.option.stats", c.showStats, v -> c.showStats = v),
			bool("framegen.option.debug", c.debugView, v -> c.debugView = v)
		});
	}

	@Override
	public void tick() {
		super.tick();
		long now = System.currentTimeMillis();
		if (this.status != null && now - this.lastStatusUpdate > 250L) {
			this.lastStatusUpdate = now;
			this.status.setMessage(FrameGenMod.statsLine());
		}
	}

	@Override
	public void removed() {
		super.removed();
		FrameGenConfig.get().save();
	}

	// ---- Option factories ------------------------------------------------------------------------------------

	private static <T> OptionInstance.TooltipSupplier<T> tooltip(String key) {
		Tooltip tooltip = Tooltip.create(Component.translatable(key + ".tooltip"));
		return value -> tooltip;
	}

	private static OptionInstance<Boolean> bool(String key, boolean initial, Consumer<Boolean> setter) {
		return OptionInstance.createBoolean(key, tooltip(key), initial, v -> {
			setter.accept(v);
			FrameGenConfig.get().save();
		});
	}

	private static <E extends Enum<E>> OptionInstance<E> enumOption(String key, Class<E> type, E initial, Consumer<E> setter,
		java.util.function.Function<E, Component> names) {
		Codec<E> codec = Codec.STRING.xmap(s -> Enum.valueOf(type, s), Enum::name);
		// Cycle buttons prefix the caption themselves.
		return new OptionInstance<>(key, tooltip(key), (caption, value) -> names.apply(value),
			new OptionInstance.Enum<>(Arrays.asList(type.getEnumConstants()), codec), initial, v -> {
				setter.accept(v);
				FrameGenConfig.get().save();
			});
	}

	private static OptionInstance<Integer> multiplierOption(FrameGenConfig c) {
		String key = "framegen.option.multiplier";
		return new OptionInstance<>(key, tooltip(key), (caption, value) -> Options.genericValueLabel(caption,
			value == 0 ? Component.translatable("framegen.multiplier.off") : value == 1 ? Component.translatable("framegen.multiplier.display") : Component.translatable("framegen.multiplier.value", value)),
			new OptionInstance.IntRange(0, 4), c.multiplier, v -> {
				c.multiplier = v;
				c.save();
			});
	}

	/** Slider positions: 0 = display refresh rate, then 30..360 fps in steps of 5. */
	private static OptionInstance<Integer> outputFpsOption(FrameGenConfig c) {
		String key = "framegen.option.output_fps";
		int initial = c.targetOutputFps <= 0 ? 0 : Math.max(1, Math.min(67, (c.targetOutputFps - 30) / 5 + 1));
		return new OptionInstance<>(key, tooltip(key), (caption, value) -> Options.genericValueLabel(caption,
			value == 0 ? Component.translatable("framegen.output_fps.display") : Component.literal(Integer.toString(30 + (value - 1) * 5))),
			new OptionInstance.IntRange(0, 67), initial, v -> {
				c.targetOutputFps = v == 0 ? 0 : 30 + (v - 1) * 5;
				c.save();
			});
	}

	/** In quarters of a real frame interval, 0..1.5 frames. */
	private static OptionInstance<Integer> extrapolationOption(FrameGenConfig c) {
		String key = "framegen.option.extrapolation";
		return new OptionInstance<>(key, tooltip(key), (caption, value) -> Options.genericValueLabel(caption,
			Component.translatable("framegen.extrapolation.value", String.format(Locale.ROOT, "%.2f", value / 4.0))),
			new OptionInstance.IntRange(0, 6), Math.round(c.maxExtrapolation * 4.0f), v -> {
				c.maxExtrapolation = v / 4.0f;
				c.save();
			});
	}
}
