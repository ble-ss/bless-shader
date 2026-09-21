package dev.bless.ui;

import dev.bless.ClientConfig;
import dev.bless.ClientConfig.OptionSpec;
import dev.bless.RmlsClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * every bless knob in one scrollable list, grouped colour then depth (ClientConfig.OPTIONS
 * owns the grouping and the ranges -- this screen never carries a number of its own). closing the
 * screen, by "done" or escape, writes the whole config and reloads it the same way F3+T does.
 */
public final class RmlsSettingsScreen extends Screen {
	private static final Logger LOGGER = LoggerFactory.getLogger("bless");

	private final Screen parent;
	private final ClientConfig loaded;
	private final Map<String, Object> values = new LinkedHashMap<>();
	private boolean saveFailed;

	public RmlsSettingsScreen(Screen parent) {
		super(Component.literal("bless settings"));
		this.parent = parent;
		ClientConfig current;
		try {
			current = ClientConfig.read(RmlsClient.configPath());
		} catch (IOException failure) {
			LOGGER.warn("bless: settings screen could not read the config, opening empty", failure);
			current = null;
		}
		this.loaded = current;
		if (loaded != null) for (OptionSpec option : ClientConfig.OPTIONS) values.put(option.key(), ClientConfig.value(loaded, option.key()));
	}

	@Override
	protected void init() {
		ConfigList list = new ConfigList(minecraft, width, height - 36, 24, 22);
		String currentGroup = null;
		for (OptionSpec option : ClientConfig.OPTIONS) {
			if (!option.group().equals(currentGroup)) {
				currentGroup = option.group();
				list.addHeader(currentGroup);
			}
			list.addOption(widgetFor(option));
		}
		addRenderableWidget(list);
		int buttonWidth = Math.min(200, width - 32);
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
			.bounds((width - buttonWidth) / 2, height - 28, buttonWidth, 20).build());
	}

	@SuppressWarnings("unchecked")
	private AbstractWidget widgetFor(OptionSpec option) {
		String key = option.key();
		return switch (option.kind()) {
			case MODE -> CycleButton.builder((String mode) -> Component.literal(mode), (String) values.get(key))
				.withValues(ClientConfig.MODE_VALUES)
				.create(0, 0, 200, 20, Component.literal(option.label()), (button, value) -> values.put(key, value));
			case BOOLEAN -> CycleButton.onOffBuilder((Boolean) values.get(key))
				.create(0, 0, 200, 20, Component.literal(option.label()), (button, value) -> values.put(key, value));
			case FLOAT -> new ConfigSlider(0, 0, 200, 20, option.label(), option.min(), option.max(),
				(Float) values.get(key), false, value -> values.put(key, (float) value));
			case INT -> new ConfigSlider(0, 0, 200, 20, option.label(), option.min(), option.max(),
				((Integer) values.get(key)).floatValue(), true, value -> values.put(key, (int) Math.round(value)));
		};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 6, 0xFFFFFFFF);
		if (loaded == null) {
			graphics.centeredText(font, Component.literal("config/bless.json could not be read"), width / 2, height / 2, 0xFFFF7777);
		} else if (saveFailed) {
			graphics.centeredText(font, Component.literal("could not save the config"), width / 2, height - 46, 0xFFFF7777);
		}
	}

	@Override
	public void onClose() {
		if (loaded != null) {
			try {
				ClientConfig.write(RmlsClient.configPath(), values, loaded.diagnosticsPath(), loaded.hazeColor(),
					ClientConfig.peekShadowDebug(RmlsClient.configPath()));
				minecraft.reloadResourcePacks();
			} catch (IOException failure) {
				LOGGER.warn("bless: settings screen could not save the config", failure);
				saveFailed = true;
			}
		}
		minecraft.setScreenAndShow(parent);
	}

	private static final class ConfigList extends ContainerObjectSelectionList<ConfigList.Row> {
		ConfigList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
			super(minecraft, width, height, y, itemHeight);
		}

		void addHeader(String text) { addEntry(new Row(text, null)); }
		void addOption(AbstractWidget widget) { addEntry(new Row(null, widget)); }

		@Override
		public int getRowWidth() { return Math.min(400, width - 12); }

		static final class Row extends ContainerObjectSelectionList.Entry<Row> {
			private final String heading;
			private final AbstractWidget widget;

			Row(String heading, AbstractWidget widget) {
				this.heading = heading;
				this.widget = widget;
			}

			@Override public void setX(int x) { super.setX(x); reposition(); }
			@Override public void setY(int y) { super.setY(y); reposition(); }
			@Override public void setWidth(int width) { super.setWidth(width); reposition(); }
			@Override public void setHeight(int height) { super.setHeight(height); reposition(); }

			private void reposition() {
				if (widget == null) return;
				widget.setX(getContentX());
				widget.setY(getContentY());
				widget.setWidth(getContentWidth());
				widget.setHeight(getContentHeight());
			}

			@Override public List<? extends GuiEventListener> children() { return widget == null ? List.of() : List.of(widget); }
			@Override public List<? extends NarratableEntry> narratables() { return widget == null ? List.of() : List.of(widget); }

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				if (heading != null) {
					graphics.centeredText(Minecraft.getInstance().font, heading, getContentXMiddle(), getContentYMiddle() - 4, 0xFFE0C060);
				} else if (widget != null) {
					widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
				}
			}
		}
	}
}
