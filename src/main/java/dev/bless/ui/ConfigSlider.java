package dev.bless.ui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;

// a slider over one option's own min/max span (see ClientConfig.OPTIONS). it formats its own
// "label: value" message, the same convention CycleButton uses, so the row draws nothing else.
final class ConfigSlider extends AbstractSliderButton {
	private final String label;
	private final float min;
	private final float max;
	private final boolean whole;
	private final DoubleConsumer onChange;
	private float current;

	ConfigSlider(int x, int y, int width, int height, String label, float min, float max, float current,
			boolean whole, DoubleConsumer onChange) {
		super(x, y, width, height, Component.empty(), clamp(min, max, current));
		this.label = label;
		this.min = min;
		this.max = max;
		this.whole = whole;
		this.current = current;
		this.onChange = onChange;
		updateMessage();
	}

	private static double clamp(float min, float max, float current) {
		if (max <= min) return 0.0;
		return Math.max(0.0, Math.min(1.0, (current - min) / (max - min)));
	}

	@Override
	protected void updateMessage() {
		String shown = whole ? Integer.toString(Math.round(current)) : String.format("%.2f", current);
		setMessage(Component.literal(label + ": " + shown));
	}

	@Override
	protected void applyValue() {
		current = min + (float) value * (max - min);
		if (whole) current = Math.round(current);
		updateMessage();
		onChange.accept(current);
	}
}
