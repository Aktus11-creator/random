package dev.framegen.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.framegen.gui.FrameGenScreen;

/** Optional: only loaded by Mod Menu when it is installed. */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return FrameGenScreen::new;
	}
}
