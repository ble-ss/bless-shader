package dev.bless.ui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

// nothing else references this class -- a shelf with no mod menu installed never loads it, so
// the settings screen (and the "bless settings" keybind) still work without mod menu present.
public final class RmlsModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return RmlsSettingsScreen::new;
	}
}
