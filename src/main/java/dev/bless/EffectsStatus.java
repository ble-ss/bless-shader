package dev.bless;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/** optional lifecycle snapshots; a single pending snapshot bounds both memory and background work. */
final class EffectsStatus {
	private static final Gson JSON = new GsonBuilder().serializeNulls().create();
	private final Path destination;
	private final Thread writer;
	private Map<String, Object> pending;
	private boolean stopping;
	private volatile Throwable error;

	EffectsStatus(Path destination) {
		this.destination = destination;
		if (destination == null) {
			writer = null;
		} else {
			writer = new Thread(this::writeLoop, "bless-status");
			writer.setDaemon(true);
			writer.start();
		}
	}

	Throwable error() { return error; }

	synchronized void publish(Map<String, Object> snapshot) {
		if (writer == null || stopping) return;
		pending = snapshot;
		notifyAll();
	}

	void close(Map<String, Object> finalSnapshot) throws InterruptedException, IOException {
		if (writer == null) return;
		synchronized (this) {
			pending = finalSnapshot;
			stopping = true;
			notifyAll();
		}
		// only shutdown waits for disk; this is outside the measured rendering window.
		writer.join(5000);
		if (writer.isAlive()) throw new IOException("bless status writer did not finish within 5 seconds");
		if (error != null) throw new IOException("bless status writer failed", error);
	}

	private void writeLoop() {
		while (true) {
			Map<String, Object> snapshot;
			try {
				synchronized (this) {
					while (pending == null && !stopping) wait();
					if (pending == null) return;
					snapshot = pending;
					pending = null;
				}
				write(snapshot);
			} catch (Throwable failure) {
				if (error == null) {
					error = failure;
					RmlsClient.LOGGER.error("bless diagnostics failed", failure);
				}
				if (failure instanceof InterruptedException) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	private void write(Map<String, Object> snapshot) throws IOException {
		Files.createDirectories(destination.getParent());
		Path temporary = Files.createTempFile(destination.getParent(), "bless-status-", ".tmp");
		try {
			Files.writeString(temporary, JSON.toJson(snapshot));
			try {
				Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException unsupported) {
				Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}
}
