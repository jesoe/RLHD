package rs117.hd.renderer.zone;

import java.lang.reflect.Field;
import java.nio.IntBuffer;
import java.util.Random;
import rs117.hd.HdPlugin;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.buffer.GLMappedBufferIntWriter.ReservedView;
import rs117.hd.utils.collections.PooledArrayType;
import rs117.hd.utils.collections.PrimitiveCharArray;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class ModelReuseCacheTest {
	private static final int ORIENTATION = 300;
	private static final float X = 100, Y = 0, Z = 200;

	/**
	 * Replaying a saved model at another place in the buffers must write what a fresh upload there would.
	 */
	@Test
	public void replayMatchesFreshUpload() throws Exception {
		var model = new SyntheticModel(new Random(2), 24, 40);
		var projection = new CameraProjection(0, -900, -2600, 0.35f, 900);
		var entry = new ModelReuseCache.Entry();

		// First draw writes at the start of the buffers and saves a copy
		var first = view(0, 0);
		upload(model, projection, first, entry);
		assertTrue("expected some faces to be written", entry.faces.length > 0);
		assertArrayEquals(written(first.vbo.getBuffer(), 0), entry.vertices.array.length == entry.vertices.length ?
			entry.vertices.array : java.util.Arrays.copyOf(entry.vertices.array, entry.vertices.length));

		// A fresh upload after other models have been written, versus replaying the saved copy there
		int vboOffset = 24 * 1000, tboOffset = 9 * 1000;
		var fresh = view(vboOffset, tboOffset);
		upload(model, projection, fresh, null);

		var replayed = view(vboOffset, tboOffset);
		var tbo = replayed.tbo.getBuffer();
		entry.replay(replayed.vbo.getBuffer(), tbo, (replayed.tbo.getPositionOffsetInts() + tbo.position()) / 3);

		assertArrayEquals("vertex data", written(fresh.vbo.getBuffer(), vboOffset), written(replayed.vbo.getBuffer(), vboOffset));
		assertArrayEquals("face data", written(fresh.tbo.getBuffer(), tboOffset), written(replayed.tbo.getBuffer(), tboOffset));

		// Replaying again at the start must undo the shift
		var again = view(0, 0);
		tbo = again.tbo.getBuffer();
		entry.replay(again.vbo.getBuffer(), tbo, tbo.position() / 3);
		assertArrayEquals("vertex data after second replay", written(first.vbo.getBuffer(), 0), written(again.vbo.getBuffer(), 0));
	}

	/**
	 * The faces hidden from the camera that only cast shadows must replay the same way.
	 */
	@Test
	public void shadowReplayMatchesFreshUpload() throws Exception {
		var model = new SyntheticModel(new Random(5), 24, 40);
		var projection = new CameraProjection(0, -900, -2600, 0.35f, 900);
		var entry = new ModelReuseCache.Entry();

		var first = view(0, 0);
		uploadShadow(model, projection, first, entry);
		assertTrue("expected some shadow-only faces to be written", entry.shadowFaces.length > 0);

		int vboOffset = 24 * 500, tboOffset = 9 * 500;
		var fresh = view(vboOffset, tboOffset);
		uploadShadow(model, projection, fresh, null);

		var replayed = view(vboOffset, tboOffset);
		var tbo = replayed.tbo.getBuffer();
		entry.replayShadow(replayed.vbo.getBuffer(), tbo, (replayed.tbo.getPositionOffsetInts() + tbo.position()) / 3);

		assertArrayEquals("shadow vertex data", written(fresh.vbo.getBuffer(), vboOffset), written(replayed.vbo.getBuffer(), vboOffset));
		assertArrayEquals("shadow face data", written(fresh.tbo.getBuffer(), tboOffset), written(replayed.tbo.getBuffer(), tboOffset));
	}

	@Test
	public void hashChangesWithPose() {
		var model = new SyntheticModel(new Random(3), 20, 32);
		long base = hash(model, ORIENTATION, X);
		assertEquals("same model, same hash", base, hash(model, ORIENTATION, X));
		assertNotEquals("moved", base, hash(model, ORIENTATION, X + 1));
		assertNotEquals("turned", base, hash(model, ORIENTATION + 1, X));

		model.getVerticesY()[model.getVerticesCount() / 2] += 1;
		assertNotEquals("one vertex animated", base, hash(model, ORIENTATION, X));
		model.getVerticesY()[model.getVerticesCount() / 2] -= 1;

		model.getFaceColors1()[7] ^= 1;
		assertNotEquals("one face recoloured", base, hash(model, ORIENTATION, X));
	}

	@Test
	public void entryHashMatchesFullHash() {
		var model = new SyntheticModel(new Random(5), 20, 32);
		var entry = new ModelReuseCache.Entry();
		assertEquals("first hash", hash(model, ORIENTATION, X), entryHash(entry, model));
		assertEquals("cached definition", hash(model, ORIENTATION, X), entryHash(entry, model));

		model.getVerticesX()[3] += 1;
		assertEquals("animated", hash(model, ORIENTATION, X), entryHash(entry, model));

		model.faceTransparencies = new byte[model.getFaceCount()];
		long opaque = entryHash(entry, model);
		model.faceTransparencies[5] = 100;
		assertNotEquals("face faded in place", opaque, entryHash(entry, model));
		assertEquals(hash(model, ORIENTATION, X), entryHash(entry, model));

		// A different appearance hands out different arrays
		long before = entryHash(entry, model);
		model.c1 = model.c1.clone();
		model.c1[9] ^= 1;
		assertNotEquals("recoloured with new arrays", before, entryHash(entry, model));
		assertEquals(hash(model, ORIENTATION, X), entryHash(entry, model));
	}

	@Test
	public void oneDrawPerFrame() {
		var cache = new ModelReuseCache();
		var renderable = mock(net.runelite.api.Renderable.class);
		var entry = cache.claim(renderable, 10);
		assertTrue(entry != null);
		assertEquals("second draw in the same frame", null, cache.claim(renderable, 10));
		assertEquals("next frame", entry, cache.claim(renderable, 11));
		cache.evictStale(11 + 65);
		assertEquals(0, cache.size());
	}

	@Test
	public void entryCountIsCapped() {
		var cache = new ModelReuseCache();
		int claimed = 0;
		for (int i = 0; i < 1000; i++) {
			if (cache.claim(mock(net.runelite.api.Renderable.class), 1) != null)
				claimed++;
		}
		assertTrue("expected a cap below 1000 actors, got " + claimed, claimed < 1000);
		assertEquals(claimed, cache.size());
	}

	private static long entryHash(ModelReuseCache.Entry entry, SyntheticModel model) {
		return entry.hash(model, ModelOverride.NONE, false, false, ORIENTATION, X, Y, Z);
	}

	private static long hash(SyntheticModel model, int orientation, float x) {
		return ModelReuseCache.hash(model, ModelOverride.NONE, false, false, orientation, x, Y, Z);
	}

	private static DynamicModelVAO.View view(int vboOffset, int tboOffset) throws Exception {
		var view = new DynamicModelVAO("Test", false).new View();
		view.vbo = reservedView(1 << 20);
		view.tbo = reservedView(1 << 18);
		view.vbo.getBuffer().position(vboOffset);
		view.tbo.getBuffer().position(tboOffset);
		return view;
	}

	private static void upload(SyntheticModel model, CameraProjection projection, DynamicModelVAO.View view, ModelReuseCache.Entry capture) throws Exception {
		var uploader = new SceneUploader();
		setField(SceneUploader.class, uploader, "plugin", mock(HdPlugin.class));
		var visibleFaces = new PrimitiveCharArray();
		var culledFaces = new PrimitiveCharArray();
		int[] faceDistances = PooledArrayType.INT.borrow(model.getFaceCount());
		boolean shouldSort = uploader.preprocessTempModel(
			projection, null, faceDistances, visibleFaces, culledFaces,
			false, ModelOverride.NONE, model, true, ORIENTATION, X, Y, Z
		);
		if (shouldSort)
			new FacePrioritySorter().sortModelFaces(visibleFaces, model, faceDistances, false);
		if (capture != null) {
			uploader.captureVertices = capture.vertices;
			uploader.captureFaces = capture.faces;
		}
		uploader.uploadTempModel(visibleFaces, model, ModelOverride.NONE, 0, ORIENTATION, false, view, view);
		if (capture != null)
			capture.faceIndexBase = uploader.capturedFaceIndexBase;
		PooledArrayType.INT.release(faceDistances);
	}

	private static void uploadShadow(SyntheticModel model, CameraProjection projection, DynamicModelVAO.View view, ModelReuseCache.Entry capture) throws Exception {
		var uploader = new SceneUploader();
		setField(SceneUploader.class, uploader, "plugin", mock(HdPlugin.class));
		var visibleFaces = new PrimitiveCharArray();
		var culledFaces = new PrimitiveCharArray();
		uploader.preprocessTempModel(
			projection, null, null, visibleFaces, culledFaces,
			false, ModelOverride.NONE, model, true, ORIENTATION, X, Y, Z
		);
		if (capture != null) {
			uploader.captureVertices = capture.shadowVertices;
			uploader.captureFaces = capture.shadowFaces;
		}
		uploader.uploadTempModel(culledFaces, model, ModelOverride.NONE, 0, ORIENTATION, true, view, view);
		if (capture != null)
			capture.shadowFaceIndexBase = uploader.capturedFaceIndexBase;
	}

	private static int[] written(IntBuffer buffer, int from) {
		int[] out = new int[buffer.position() - from];
		buffer.duplicate().position(from).limit(buffer.position()).slice().get(out);
		return out;
	}

	private static ReservedView reservedView(int ints) throws Exception {
		var view = new ReservedView();
		setField(ReservedView.class, view, "buffer", IntBuffer.allocate(ints));
		return view;
	}

	private static void setField(Class<?> type, Object target, String name, Object value) throws Exception {
		Field f = type.getDeclaredField(name);
		f.setAccessible(true);
		f.set(target, value);
	}
}
