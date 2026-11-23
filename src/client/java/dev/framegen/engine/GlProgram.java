package dev.framegen.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;

/** Minimal raw-GL shader program wrapper for the presenter context (Minecraft's pipeline system lives in the other context). */
final class GlProgram implements AutoCloseable {
	private final int program;
	private final Map<String, Integer> locations = new HashMap<>();

	GlProgram(String name, String vertexResource, String fragmentResource) {
		int vs = compile(GL33C.GL_VERTEX_SHADER, vertexResource);
		int fs = compile(GL33C.GL_FRAGMENT_SHADER, fragmentResource);
		this.program = GL33C.glCreateProgram();
		GL33C.glAttachShader(this.program, vs);
		GL33C.glAttachShader(this.program, fs);
		GL33C.glBindFragDataLocation(this.program, 0, "fragColor");
		GL33C.glLinkProgram(this.program);
		GL33C.glDetachShader(this.program, vs);
		GL33C.glDetachShader(this.program, fs);
		GL33C.glDeleteShader(vs);
		GL33C.glDeleteShader(fs);
		if (GL33C.glGetProgrami(this.program, GL33C.GL_LINK_STATUS) == 0) {
			String log = GL33C.glGetProgramInfoLog(this.program);
			GL33C.glDeleteProgram(this.program);
			throw new IllegalStateException("Failed to link presenter program " + name + ": " + log);
		}
	}

	private static int compile(int type, String resource) {
		String source = load(resource);
		int shader = GL33C.glCreateShader(type);
		GL33C.glShaderSource(shader, source);
		GL33C.glCompileShader(shader);
		if (GL33C.glGetShaderi(shader, GL33C.GL_COMPILE_STATUS) == 0) {
			String log = GL33C.glGetShaderInfoLog(shader);
			GL33C.glDeleteShader(shader);
			throw new IllegalStateException("Failed to compile " + resource + ": " + log);
		}
		return shader;
	}

	private static String load(String resource) {
		String path = "/assets/framegen/presenter/" + resource;
		try (InputStream in = GlProgram.class.getResourceAsStream(path)) {
			if (in == null) throw new IllegalStateException("Missing shader resource " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Couldn't read " + path, e);
		}
	}

	void use() {
		GL33C.glUseProgram(this.program);
	}

	int loc(String name) {
		return this.locations.computeIfAbsent(name, n -> GL33C.glGetUniformLocation(this.program, n));
	}

	void setInt(String name, int value) {
		int l = this.loc(name);
		if (l >= 0) GL33C.glUniform1i(l, value);
	}

	void setFloat(String name, float value) {
		int l = this.loc(name);
		if (l >= 0) GL33C.glUniform1f(l, value);
	}

	void setVec2(String name, float x, float y) {
		int l = this.loc(name);
		if (l >= 0) GL33C.glUniform2f(l, x, y);
	}

	void setVec3(String name, float x, float y, float z) {
		int l = this.loc(name);
		if (l >= 0) GL33C.glUniform3f(l, x, y, z);
	}

	void setVec4(String name, float x, float y, float z, float w) {
		int l = this.loc(name);
		if (l >= 0) GL33C.glUniform4f(l, x, y, z, w);
	}

	void setMat4(String name, org.joml.Matrix4f m) {
		int l = this.loc(name);
		if (l < 0) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			FloatBuffer fb = stack.mallocFloat(16);
			m.get(fb);
			GL33C.glUniformMatrix4fv(l, false, fb);
		}
	}

	@Override
	public void close() {
		GL33C.glDeleteProgram(this.program);
	}
}
