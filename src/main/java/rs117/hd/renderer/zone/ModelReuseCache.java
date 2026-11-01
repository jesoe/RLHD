package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.*;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.collections.PrimitiveIntArray;

/**
 * Keeps the vertices written for each actor last time it was drawn, so an actor whose pose, position and camera
 * haven't changed can be copied into this frame's buffers instead of being projected, culled, sorted and written again.
 * <p>
 * The client advances animations every 20 ms game cycle, and most animation frames last several cycles,
 * so at high frame rates most frames draw most actors in exactly the same pose as the frame before.
 */
final class ModelReuseCache {
	// Entries not drawn for this many frames are dropped
	private static final int EVICT_AFTER_FRAMES = 64;
	// Typically 0.1 to 0.3 MB per entry, so at most a few tens of MB
	private static final int MAX_ENTRIES = 128;

	static final class Entry {
		private final AtomicInteger claimedFrame = new AtomicInteger(Integer.MIN_VALUE);
		final PrimitiveIntArray vertices = new PrimitiveIntArray();
		final PrimitiveIntArray faces = new PrimitiveIntArray();
		// Index of the first face in the face buffer when the vertices were written, which vertices refer to
		int faceIndexBase;
		// Transparent faces, written to their own buffers when the model is drawn with an alpha model
		final PrimitiveIntArray alphaVertices = new PrimitiveIntArray();
		final PrimitiveIntArray alphaFaces = new PrimitiveIntArray();
		int alphaFaceIndexBase;
		// Faces hidden from the camera that still cast shadows, written to the shadow buffers
		final PrimitiveIntArray shadowVertices = new PrimitiveIntArray();
		final PrimitiveIntArray shadowFaces = new PrimitiveIntArray();
		int shadowFaceIndexBase;
		long hash;
		int cameraEpoch;
		int vaoType;
		boolean hasAlphaModel;
		volatile boolean valid;

		// Definition arrays the cached definition hash was computed from, compared by reference
		private final Object[] definitionArrays = new Object[DEFINITION_ARRAY_COUNT];
		private long definitionHash;
		private boolean hasDefinitionHash;

		/**
		 * Same inputs as {@link ModelReuseCache#hash}, but the arrays that come from the model definition are only
		 * hashed again when the model hands out different arrays, which in practice only happens when the
		 * actor's appearance changes. Vertices and face transparencies are always hashed, since animations
		 * write those into shared buffers.
		 */
		long hash(
			Model m,
			ModelOverride modelOverride,
			boolean unlitFaceColors,
			boolean isModelPartiallyVisible,
			int orientation,
			float x, float y, float z
		) {
			if (!hasDefinitionHash || !sameDefinitionArrays(m, unlitFaceColors)) {
				definitionHash = definitionHash(m, unlitFaceColors);
				storeDefinitionArrays(m, unlitFaceColors);
				hasDefinitionHash = true;
			}
			return frameHash(m, modelOverride, isModelPartiallyVisible, orientation, x, y, z) ^ definitionHash;
		}

		private boolean sameDefinitionArrays(Model m, boolean unlitFaceColors) {
			final Object[] a = definitionArrays;
			return a[0] == m.getFaceIndices1() &&
				a[1] == m.getFaceColors1() &&
				a[2] == m.getFaceColors3() &&
				a[3] == m.getFaceRenderPriorities() &&
				a[4] == m.getFaceTextures() &&
				a[5] == m.getTextureFaces() &&
				a[6] == m.getTexIndices1() &&
				a[7] == m.getTexIndices2() &&
				a[8] == m.getTexIndices3() &&
				a[9] == (unlitFaceColors ? m.getUnlitFaceColors() : null);
		}

		private void storeDefinitionArrays(Model m, boolean unlitFaceColors) {
			final Object[] a = definitionArrays;
			a[0] = m.getFaceIndices1();
			a[1] = m.getFaceColors1();
			a[2] = m.getFaceColors3();
			a[3] = m.getFaceRenderPriorities();
			a[4] = m.getFaceTextures();
			a[5] = m.getTextureFaces();
			a[6] = m.getTexIndices1();
			a[7] = m.getTexIndices2();
			a[8] = m.getTexIndices3();
			a[9] = unlitFaceColors ? m.getUnlitFaceColors() : null;
		}

		/**
		 * Only one draw of the same actor per frame may use or fill an entry.
		 */
		boolean claim(int frame) {
			int prev = claimedFrame.get();
			return prev != frame && claimedFrame.compareAndSet(prev, frame);
		}

		/**
		 * Start over, to be filled by the upload that follows.
		 */
		void invalidate() {
			valid = false;
			vertices.reset();
			faces.reset();
			alphaVertices.reset();
			alphaFaces.reset();
			shadowVertices.reset();
			shadowFaces.reset();
		}

		boolean matches(long hash, int cameraEpoch, int vaoType, boolean hasAlphaModel) {
			return valid &&
				this.hash == hash &&
				this.cameraEpoch == cameraEpoch &&
				this.vaoType == vaoType &&
				this.hasAlphaModel == hasAlphaModel;
		}

		/**
		 * Copy the saved vertices and faces into the given buffers, pointing vertices at the faces' new location.
		 */
		void replay(IntBuffer vbo, IntBuffer tbo, int newFaceIndexBase) {
			faceIndexBase = replay(vertices, faces, faceIndexBase, vbo, tbo, newFaceIndexBase);
		}

		/**
		 * Like {@link #replay}, for the transparent faces.
		 */
		void replayAlpha(IntBuffer vbo, IntBuffer tbo, int newFaceIndexBase) {
			alphaFaceIndexBase = replay(alphaVertices, alphaFaces, alphaFaceIndexBase, vbo, tbo, newFaceIndexBase);
		}

		/**
		 * Like {@link #replay}, for the shadow-only faces.
		 */
		void replayShadow(IntBuffer vbo, IntBuffer tbo, int newFaceIndexBase) {
			shadowFaceIndexBase = replay(shadowVertices, shadowFaces, shadowFaceIndexBase, vbo, tbo, newFaceIndexBase);
		}

		private static int replay(
			PrimitiveIntArray vertices,
			PrimitiveIntArray faces,
			int faceIndexBase,
			IntBuffer vbo,
			IntBuffer tbo,
			int newFaceIndexBase
		) {
			final int delta = newFaceIndexBase - faceIndexBase;
			if (delta != 0) {
				// Every 8th int of the dynamic vertex format is the index of its face
				final int[] v = vertices.array;
				for (int i = 7; i < vertices.length; i += 8)
					v[i] += delta;
			}
			vbo.put(vertices.array, 0, vertices.length);
			tbo.put(faces.array, 0, faces.length);
			return newFaceIndexBase;
		}
	}

	private final ConcurrentHashMap<Renderable, Entry> entries = new ConcurrentHashMap<>();

	Entry claim(Renderable renderable, int frame) {
		Entry entry = entries.get(renderable);
		if (entry == null) {
			// Each entry keeps a full copy of the model's vertex data, so cap how many actors are kept
			if (entries.size() >= MAX_ENTRIES)
				return null;
			entry = entries.computeIfAbsent(renderable, k -> new Entry());
		}
		return entry.claim(frame) ? entry : null;
	}

	void evictStale(int frame) {
		entries.values().removeIf(e -> frame - e.claimedFrame.get() > EVICT_AFTER_FRAMES);
	}

	void clear() {
		entries.clear();
	}

	int size() {
		return entries.size();
	}

	private static final int DEFINITION_ARRAY_COUNT = 10;

	/**
	 * Hashes everything about a model that the temp model upload reads, apart from the camera.
	 */
	static long hash(
		Model m,
		ModelOverride modelOverride,
		boolean unlitFaceColors,
		boolean isModelPartiallyVisible,
		int orientation,
		float x, float y, float z
	) {
		return frameHash(m, modelOverride, isModelPartiallyVisible, orientation, x, y, z) ^ definitionHash(m, unlitFaceColors);
	}

	/**
	 * The parts that can change from one frame to the next without the model handing out different arrays.
	 */
	private static long frameHash(
		Model m,
		ModelOverride modelOverride,
		boolean isModelPartiallyVisible,
		int orientation,
		float x, float y, float z
	) {
		final int vertexCount = m.getVerticesCount();
		final int faceCount = m.getFaceCount();

		long h = 0x9E3779B97F4A7C15L;
		h = mix(h, vertexCount);
		h = mix(h, faceCount);
		h = mix(h, System.identityHashCode(modelOverride));
		h = mix(h, orientation);
		h = mix(h, Float.floatToRawIntBits(x));
		h = mix(h, Float.floatToRawIntBits(y));
		h = mix(h, Float.floatToRawIntBits(z));
		h = mix(h, isModelPartiallyVisible ? 1 : 0);
		h = mix(h, m.getRadius());
		h = mix(h, m.getDiameter());
		h = mix(h, m.getRenderMode());
		h = mix(h, m.getTransparency());
		h = mix(h, m.getOverrideAmount() << 24 | (m.getOverrideHue() & 0xFF) << 16 | (m.getOverrideSaturation() & 0xFF) << 8 | m.getOverrideLuminance() & 0xFF);

		// Animation only moves vertices, and some animations fade faces in and out.
		// Normals, the other two face indices, the second colour and the depth bias come from the model definition
		// and only change along with the definition arrays, so they are left out to save time.
		h = hash(h, m.getVerticesX(), vertexCount);
		h = hash(h, m.getVerticesY(), vertexCount);
		h = hash(h, m.getVerticesZ(), vertexCount);
		h = hash(h, m.getFaceTransparencies(), faceCount);
		return h;
	}

	/**
	 * The parts that come from the model definition.
	 */
	private static long definitionHash(Model m, boolean unlitFaceColors) {
		final int faceCount = m.getFaceCount();
		long h = 0x2545F4914F6CDD1DL;
		h = hash(h, m.getFaceIndices1(), faceCount);
		h = hash(h, m.getFaceColors1(), faceCount);
		h = hash(h, m.getFaceColors3(), faceCount);
		h = hash(h, m.getFaceRenderPriorities(), faceCount);
		if (unlitFaceColors)
			h = hash(h, m.getUnlitFaceColors(), faceCount);

		final short[] faceTextures = m.getFaceTextures();
		if (faceTextures != null) {
			h = hash(h, faceTextures, faceCount);
			h = hash(h, m.getTextureFaces(), faceCount);
			final int[] texIndices1 = m.getTexIndices1();
			if (texIndices1 != null) {
				h = hash(h, texIndices1, texIndices1.length);
				h = hash(h, m.getTexIndices2(), texIndices1.length);
				h = hash(h, m.getTexIndices3(), texIndices1.length);
			}
		}
		// Keep the two halves from cancelling out when combined
		return h * 0xD6E8FEB86659FD93L;
	}

	private static long mix(long h, int v) {
		return (h ^ v) * 0x100000001B3L;
	}

	private static long finish(long h, long h0, long h1, long h2, long h3) {
		h ^= h0;
		h = (h ^ Long.rotateLeft(h1, 17)) * 0x9E3779B97F4A7C15L;
		h = (h ^ Long.rotateLeft(h2, 31)) * 0x9E3779B97F4A7C15L;
		h = (h ^ Long.rotateLeft(h3, 47)) * 0x9E3779B97F4A7C15L;
		return h ^ (h >>> 29);
	}

	// Four independent lanes let the CPU overlap the multiplies

	private static long hash(long h, int[] a, int n) {
		if (a == null)
			return mix(h, -1);
		n = Math.min(n, a.length);
		long h0 = h, h1 = h + 1, h2 = h + 2, h3 = h + 3;
		int i = 0;
		for (; i + 3 < n; i += 4) {
			h0 = (h0 ^ a[i]) * 0x9E3779B97F4A7C15L;
			h1 = (h1 ^ a[i + 1]) * 0x9E3779B97F4A7C15L;
			h2 = (h2 ^ a[i + 2]) * 0x9E3779B97F4A7C15L;
			h3 = (h3 ^ a[i + 3]) * 0x9E3779B97F4A7C15L;
		}
		for (; i < n; i++)
			h0 = (h0 ^ a[i]) * 0x9E3779B97F4A7C15L;
		return finish(mix(h, n), h0, h1, h2, h3);
	}

	private static long hash(long h, float[] a, int n) {
		if (a == null)
			return mix(h, -1);
		n = Math.min(n, a.length);
		long h0 = h, h1 = h + 1, h2 = h + 2, h3 = h + 3;
		int i = 0;
		for (; i + 3 < n; i += 4) {
			h0 = (h0 ^ Float.floatToRawIntBits(a[i])) * 0x9E3779B97F4A7C15L;
			h1 = (h1 ^ Float.floatToRawIntBits(a[i + 1])) * 0x9E3779B97F4A7C15L;
			h2 = (h2 ^ Float.floatToRawIntBits(a[i + 2])) * 0x9E3779B97F4A7C15L;
			h3 = (h3 ^ Float.floatToRawIntBits(a[i + 3])) * 0x9E3779B97F4A7C15L;
		}
		for (; i < n; i++)
			h0 = (h0 ^ Float.floatToRawIntBits(a[i])) * 0x9E3779B97F4A7C15L;
		return finish(mix(h, n), h0, h1, h2, h3);
	}

	private static long hash(long h, short[] a, int n) {
		if (a == null)
			return mix(h, -1);
		n = Math.min(n, a.length);
		long h0 = h, h1 = h + 1;
		int i = 0;
		for (; i + 1 < n; i += 2) {
			h0 = (h0 ^ a[i]) * 0x9E3779B97F4A7C15L;
			h1 = (h1 ^ a[i + 1]) * 0x9E3779B97F4A7C15L;
		}
		for (; i < n; i++)
			h0 = (h0 ^ a[i]) * 0x9E3779B97F4A7C15L;
		return finish(mix(h, n), h0, h1, 0, 0);
	}

	private static long hash(long h, byte[] a, int n) {
		if (a == null)
			return mix(h, -1);
		n = Math.min(n, a.length);
		long h0 = h, h1 = h + 1;
		int i = 0;
		// Pack four bytes per step
		for (; i + 7 < n; i += 8) {
			h0 = (h0 ^ (a[i] & 0xFF | (a[i + 1] & 0xFF) << 8 | (a[i + 2] & 0xFF) << 16 | a[i + 3] << 24)) * 0x9E3779B97F4A7C15L;
			h1 = (h1 ^ (a[i + 4] & 0xFF | (a[i + 5] & 0xFF) << 8 | (a[i + 6] & 0xFF) << 16 | a[i + 7] << 24)) * 0x9E3779B97F4A7C15L;
		}
		for (; i < n; i++)
			h0 = (h0 ^ a[i]) * 0x9E3779B97F4A7C15L;
		return finish(mix(h, n), h0, h1, 0, 0);
	}
}
