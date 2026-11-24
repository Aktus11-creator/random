package dev.framegen.compat;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.backend.opengl.GlSurface;
import dev.framegen.FrameGenMod;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import java.io.IOException;
import java.io.InputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

/**
 * With FrameGen the game never calls {@link GlSurface#present()}; the presenter thread swaps the window instead.
 * Other mods hook that method to run code "once per presented frame". Sodium, for example, runs its driver and
 * injected-overlay checks there. This finds every mixin handler merged into {@code GlSurface} that injects into
 * {@code present} and calls it from the render thread at the equivalent point, so those mods keep working.
 *
 * <p>Mixin strips the {@code @Inject} annotation from merged handlers but keeps {@link MixinMerged}, which names the
 * mixin class; the injection target and point are read back from that class's bytecode.
 *
 * <p>Only plain {@code @Inject} handlers with a {@code (CallbackInfo)} signature at HEAD or RETURN are replayed.
 * Anything fancier is skipped and logged rather than guessed at.
 */
public final class PresentHookReplayer {
	private final List<Method> head = new ArrayList<>();
	private final List<Method> tail = new ArrayList<>();
	private final @Nullable Object receiver;
	private boolean disabled;

	public PresentHookReplayer(GpuSurface vanillaSurface) {
		this.receiver = findBackend(vanillaSurface);
		for (Method method : GlSurface.class.getDeclaredMethods()) {
			MixinMerged merged = method.getAnnotation(MixinMerged.class);
			if (merged == null || !method.getName().startsWith("handler$")) {
				continue;
			}
			// Mixin strips @Inject from merged handlers, so read it back from the mixin class's own bytecode.
			InjectInfo inject = readInject(merged.mixin(), method);
			if (inject == null || !inject.targetsPresent()) {
				continue;
			}
			Class<?>[] params = method.getParameterTypes();
			boolean isStatic = Modifier.isStatic(method.getModifiers());
			if (params.length != 1 || params[0] != CallbackInfo.class || (!isStatic && this.receiver == null)) {
				FrameGenMod.LOGGER.warn("Not replaying GlSurface.present hook {} from {}: unsupported signature", method.getName(), merged.mixin());
				continue;
			}
			String at = inject.at();
			List<Method> list = "HEAD".equals(at) ? this.head : "RETURN".equals(at) || "TAIL".equals(at) ? this.tail : null;
			if (list == null) {
				FrameGenMod.LOGGER.warn("Not replaying GlSurface.present hook {} from {}: injection point {}", method.getName(), merged.mixin(), at);
				continue;
			}
			method.setAccessible(true);
			list.add(method);
			FrameGenMod.LOGGER.info("Replaying {}'s GlSurface.present hook ({}) on frames presented by FrameGen", merged.mixin(), at);
		}
	}

	private record InjectInfo(List<String> methods, String at) {
		boolean targetsPresent() {
			for (String target : this.methods) {
				if (target.equals("present") || target.startsWith("present(")) return true;
			}
			return false;
		}
	}

	/**
	 * Finds the handler's original declaration in the mixin class (merged name: {@code handler$<id>$<prefix>$<name>})
	 * and returns the target methods and injection point of its {@code @Inject} annotation.
	 */
	private static @Nullable InjectInfo readInject(String mixinClass, Method merged) {
		String name = merged.getName();
		String original = name.substring(name.lastIndexOf('$') + 1);
		String desc = Type.getMethodDescriptor(merged);
		String resource = mixinClass.replace('.', '/') + ".class";
		try (InputStream in = PresentHookReplayer.class.getClassLoader().getResourceAsStream(resource)) {
			if (in == null) return null;
			ClassNode node = new ClassNode();
			new ClassReader(in).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			for (MethodNode m : node.methods) {
				if (!m.name.equals(original) || !m.desc.equals(desc)) continue;
				List<AnnotationNode> annotations = new ArrayList<>();
				if (m.visibleAnnotations != null) annotations.addAll(m.visibleAnnotations);
				if (m.invisibleAnnotations != null) annotations.addAll(m.invisibleAnnotations);
				for (AnnotationNode a : annotations) {
					if (a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) {
						return parseInject(a);
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			FrameGenMod.LOGGER.debug("Couldn't read mixin class {}", mixinClass, e);
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static InjectInfo parseInject(AnnotationNode inject) {
		List<String> methods = new ArrayList<>();
		String at = "";
		if (inject.values != null) {
			for (int i = 0; i + 1 < inject.values.size(); i += 2) {
				String key = (String) inject.values.get(i);
				Object value = inject.values.get(i + 1);
				if (key.equals("method") && value instanceof List<?> list) {
					for (Object o : list) methods.add(String.valueOf(o));
				} else if (key.equals("at") && value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof AnnotationNode atNode) {
					at = readAtValue(atNode);
				} else if (key.equals("at") && value instanceof AnnotationNode atNode) {
					at = readAtValue(atNode);
				}
			}
		}
		return new InjectInfo(methods, at);
	}

	private static String readAtValue(AnnotationNode atNode) {
		if (atNode.values != null) {
			for (int i = 0; i + 1 < atNode.values.size(); i += 2) {
				if ("value".equals(atNode.values.get(i))) return String.valueOf(atNode.values.get(i + 1));
			}
		}
		return "";
	}

	/** The {@code GlSurface} inside Minecraft's frontend surface wrapper, for non-static handlers. */
	private static @Nullable Object findBackend(GpuSurface surface) {
		if (surface instanceof GlSurface) return surface;
		for (Class<?> c = surface.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field field : c.getDeclaredFields()) {
				if (GlSurface.class.isAssignableFrom(field.getType()) || field.getName().equals("backend")) {
					try {
						field.setAccessible(true);
						Object value = field.get(surface);
						if (value instanceof GlSurface) return value;
					} catch (ReflectiveOperationException | RuntimeException ignored) {
					}
				}
			}
		}
		return null;
	}

	public boolean isEmpty() {
		return this.head.isEmpty() && this.tail.isEmpty();
	}

	public void beforePresent() {
		this.run(this.head);
	}

	public void afterPresent() {
		this.run(this.tail);
	}

	private void run(List<Method> hooks) {
		if (this.disabled) return;
		for (Method hook : hooks) {
			try {
				hook.invoke(Modifier.isStatic(hook.getModifiers()) ? null : this.receiver, new CallbackInfo("present", false));
			} catch (InvocationTargetException e) {
				FrameGenMod.LOGGER.error("A GlSurface.present hook ({}) threw; no longer replaying present hooks", hook.getName(), e.getCause());
				this.disabled = true;
			} catch (ReflectiveOperationException | RuntimeException e) {
				FrameGenMod.LOGGER.error("Couldn't replay GlSurface.present hook {}; no longer replaying present hooks", hook.getName(), e);
				this.disabled = true;
			}
		}
	}
}
