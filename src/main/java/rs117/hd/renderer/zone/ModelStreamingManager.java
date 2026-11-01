package rs117.hd.renderer.zone;

import com.google.inject.Injector;
import java.util.ArrayList;
import java.util.Arrays;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.hooks.*;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.ShadowMode;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.collections.PooledArrayType;
import rs117.hd.utils.collections.PrimitiveCharArray;

import static net.runelite.api.Perspective.*;
import static net.runelite.api.hooks.DrawCallbacks.*;
import static rs117.hd.HdPlugin.PROCESSOR_COUNT;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_ALPHA;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_OPAQUE;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_PLAYER;
import static rs117.hd.renderer.zone.WorldViewContext.VAO_SHADOW;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class ModelStreamingManager {
	public static final int RL_RENDER_THREADS = 2;

	private static final ConcurrentPool<PrimitiveCharArray> FACE_INDICES = new ConcurrentPool<>(PrimitiveCharArray::new);

	@Inject
	private Injector injector;

	@Inject
	private Client client;

	@Inject
	private EventBus eventBus;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private SceneManager sceneManager;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private ZoneRenderer renderer;

	private final ArrayList<AsyncCachedModel> pending = new ArrayList<>();
	private final AsyncCachedModel.UploadModelFunc uploadTempModelAsync = this::uploadTempModelAsync;
	private final AsyncCachedModel.ReplayModelFunc replayModelAsync = this::replayModelAsync;
	private final ModelReuseCache reuseCache = new ModelReuseCache();
	private final float[] lastViewProjMatrix = new float[16];
	private int cameraEpoch;
	private final StreamingContext[] streamingContexts = new StreamingContext[RL_RENDER_THREADS + 1];
	private int numRenderThreads = -1;

	static final class StreamingContext {
		final int[] worldPos = new int[3];
		final float[] objectWorldPos = new float[4];
		int renderableCount;
	}

	public void initialize() {
		for (int i = 0; i < streamingContexts.length; i++)
			streamingContexts[i] = injector.getInstance(StreamingContext.class);

		if (useMultithreading())
			AsyncCachedModel.initialize(injector);

		eventBus.register(this);
		updateRenderThreads();
	}

	public void destroy() {
		ensureAsyncUploadsComplete(null);

		eventBus.unregister(this);
		AsyncCachedModel.destroy();
		Arrays.fill(streamingContexts, null);
		numRenderThreads = -1;
	}

	public void reinitialize() {
		destroy();
		initialize();
	}

	StreamingContext context() {
		return streamingContexts[0];
	}

	StreamingContext context(int renderThreadId) {
		return streamingContexts[renderThreadId + 1];
	}

	private boolean useMultithreading() {
		return config.multithreadedModelProcessing() && PROCESSOR_COUNT > 1;
	}

	private void updateRenderThreads() {
		assert client.isClientThread();
		// Render threads will act as suppliers into the job system, so RL_RENDER_THREADS + the client thread
		int renderThreads = useMultithreading() && !plugin.isPowerSaving ? RL_RENDER_THREADS : 0;
		if (renderThreads != numRenderThreads) {
			int gpuFlags = plugin.gpuFlags & ~DrawCallbacks.RENDER_THREADS(RENDER_THREADS_MASK);
			client.setGpuFlags(gpuFlags | DrawCallbacks.RENDER_THREADS(renderThreads));
			numRenderThreads = renderThreads;
		}
	}

	/**
	 * Called once per frame with the scene camera's matrix, before any models are drawn.
	 */
	void updateCamera(float[] viewProjMatrix) {
		if (!Arrays.equals(viewProjMatrix, lastViewProjMatrix)) {
			System.arraycopy(viewProjMatrix, 0, lastViewProjMatrix, 0, lastViewProjMatrix.length);
			cameraEpoch++;
		}
	}

	public void clearReuseCache() {
		reuseCache.clear();
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event) {
		if ((plugin.frame & 63) == 0)
			reuseCache.evictStale(plugin.frame);

		for (int i = 0; i < streamingContexts.length; i++)
			streamingContexts[i].renderableCount = 0;

		updateRenderThreads();
	}

	public int getDrawnDynamicRenderableCount() {
		int count = 0;
		for (int i = 0; i < streamingContexts.length; i++)
			count += streamingContexts[i].renderableCount;
		return count;
	}

	private boolean isAlphaModel(Model m) {
		if (m.getTransparency() != 0)
			return true;

		// Most player and NPC models carry a transparency array that is all zeros. Values from 253 up are never drawn.
		final byte[] faceTransparencies = m.getFaceTransparencies();
		if (faceTransparencies != null) {
			final int faceCount = Math.min(m.getFaceCount(), faceTransparencies.length);
			for (int f = 0; f < faceCount; f++) {
				final int t = faceTransparencies[f] & 0xFF;
				if (t != 0 && t < 253)
					return true;
			}
		}

		final short[] faceTextures = m.getFaceTextures();
		if (faceTextures != null) {
			int faceCount = m.getFaceCount();
			for (int f = 0; f < faceCount; f++)
				if (Material.hasVanillaTransparency(faceTextures[f]))
					return true;
		}

		return false;
	}

	public void drawTemp(
		int renderThreadId,
		Projection projection,
		Scene scene,
		TileObject tileObject,
		Renderable r,
		Model m,
		int orient,
		int x,
		int y,
		int z
	) {
		WorldViewContext root = sceneManager.getRoot();
		WorldViewContext ctx = sceneManager.getContext(scene);
		if (root == null || ctx == null ||
			!sceneManager.isRoot(ctx) && ctx.isLoading ||
			plugin.configSimpleBoatSilhouettes && ctx.isSquashed ||
			!renderCallbackManager.drawObject(scene, tileObject))
			return;

		int offset = ctx.sceneContext.sceneOffset >> 3;
		int zx = (x >> 10) + offset;
		int zz = (z >> 10) + offset;
		Zone zone = ctx.zones[zx][zz];

		if (!zone.initialized)
			return;

		final StreamingContext streamingContext = context(renderThreadId);
		final float[] worldPos = vec4(streamingContext.objectWorldPos, x, y, z, 1.0f);
		if (ctx.projection != null)
			Mat4.mulVec(worldPos, ctx.projection, worldPos);

		final int uuid;
		if (r instanceof DynamicObject) {
			int id = tileObject.getId();
			int impostorId = root.sceneContext.animatedDynamicObjectImpostors.getOrDefault(id, id);
			uuid = ModelHash.packUuid(ModelHash.getType(tileObject.getHash()), impostorId);

			// Cull dynamic models based on detail draw distance
			float squaredDistance = renderer.sceneCamera.squaredDistanceTo(worldPos[0], worldPos[1], worldPos[2]);
			int detailDrawDistanceTiles = plugin.configDetailDrawDistance * LOCAL_TILE_SIZE;
			if (squaredDistance > detailDrawDistanceTiles * detailDrawDistanceTiles && modelOverrideManager.allowDetailCulling(uuid))
				return;
		} else {
			uuid = ModelHash.generateUuid(client, tileObject.getHash(), r);
		}

		// Hide everything outside the current area if area hiding is enabled
		if (ctx.sceneContext.currentArea != null && scene.getWorldViewId() == WorldView.TOPLEVEL) {
			var base = ctx.sceneContext.sceneBase;
			assert base != null;
			boolean inArea = ctx.sceneContext.currentArea.containsPoint(
				base[0] + ((int) worldPos[0] >> Perspective.LOCAL_COORD_BITS),
				base[1] + ((int) worldPos[2] >> Perspective.LOCAL_COORD_BITS),
				base[2] + tileObject.getPlane()
			);
			if (!inArea)
				return;
		}

		ctx.sceneContext.localToWorld(x, z, tileObject.getPlane(), streamingContext.worldPos);
		ModelOverride modelOverride = modelOverrideManager.getOverride(uuid, streamingContext.worldPos);
		if (modelOverride.hide)
			return;

		m.calculateBoundsCylinder();

		final int modelClassification = renderer.sceneCamera.classifySphere(worldPos[0], worldPos[1], worldPos[2], m.getRadius());
		boolean isOffScreen = modelClassification == -1;
		// Additional culling checks to help reduce dynamic object perf impact when off-screen
		if (isOffScreen && (
			!modelOverride.castShadows ||
			!renderer.directionalCamera.intersectsSphere(worldPos[0], worldPos[1], worldPos[2], m.getRadius()) ||
			!renderer.directionalShadowCasterVolume.intersectsSphere(worldPos[0], worldPos[1], worldPos[2], m.getRadius())
		)) {
			return;
		}
		streamingContext.renderableCount++;

		final boolean hasAlpha =
			(modelOverride.mightHaveTransparency || isAlphaModel(m)) &&
			(!sceneManager.isRoot(ctx) || zone.inSceneFrustum);
		final Zone.AlphaModel alphaModel = hasAlpha ?
			zone.requestTempAlphaModel(
				modelOverride,
				Math.min(ctx.maxLevel, tileObject.getPlane()),
				x & 1023,
				y - (r instanceof Actor ? r.getModelHeight() : 0), // order players over objects?
				z & 1023
			) : null;

		final int drawIndex = renderThreadId != -1 ? -1 : ctx.obtainDrawIndex(r instanceof Player ? VAO_PLAYER : VAO_OPAQUE);
		final boolean isModelPartiallyVisible = sceneManager.isRoot(ctx) && modelClassification == 0;

		// Players and NPCs whose pose, position and camera are unchanged since their last draw reuse what was written then,
		// including the faces hidden from the camera that only cast shadows
		ModelReuseCache.Entry reuse = null;
		long reuseHash = 0;
		if (plugin.configModelReuse &&
			r instanceof Actor &&
			sceneManager.isRoot(ctx) &&
			!ctx.isSquashed
		) {
			reuse = reuseCache.claim(r, plugin.frame);
			if (reuse != null) {
				final int vaoType = r instanceof Player ? VAO_PLAYER : VAO_OPAQUE;
				reuseHash = reuse.hash(m, modelOverride, plugin.configUnlitFaceColors, isModelPartiallyVisible, orient, x, y, z);
				// Whether shadow faces get written depends on the shadow frustum, not just the model
				if (castsTempShadow(modelOverride, zone))
					reuseHash = ~reuseHash * 0x9E3779B97F4A7C15L;
				final boolean hasAlphaModel = alphaModel != null;
				if (reuse.matches(reuseHash, cameraEpoch, vaoType, hasAlphaModel)) {
					// Nothing needs copying out of the model, so the client can move on right away
					final AsyncCachedModel replayJob = obtainReplayJob();
					if (replayJob != null) {
						replayJob.queueReplay(ctx, reuse, vaoType, drawIndex, zone, alphaModel, replayModelAsync);
					} else {
						replayModel(ctx, reuse, vaoType, drawIndex, alphaModel);
					}
					return;
				}
				reuse.invalidate();
			}
		}

		final AsyncCachedModel asyncModelCache = obtainAvailableAsyncCachedModel(m);
		if (asyncModelCache != null) {
			// Fast path, buffer the model into the job queue to unblock rl internals
			asyncModelCache.queue(
				ctx,
				projection,
				tileObject,
				r,
				modelOverride,
				m,
				zone,
				alphaModel,
				isModelPartiallyVisible,
				drawIndex,
				orient,
				x, y, z,
				reuse,
				reuseHash,
				uploadTempModelAsync
			);
			return;
		}

		uploadTempModel(
			ctx,
			projection,
			tileObject,
			r,
			modelOverride,
			m,
			zone,
			alphaModel,
			isModelPartiallyVisible,
			-1,
			drawIndex,
			orient,
			x, y, z,
			reuse,
			reuseHash
		);
	}

	private void uploadTempModelAsync(
		WorldViewContext ctx,
		Projection projection,
		TileObject tileObject,
		Renderable renderable,
		ModelOverride modelOverride,
		Model model,
		Zone zone,
		Zone.AlphaModel alphaModel,
		boolean isModelPartiallyVisible,
		int drawIndex,
		int orientation,
		int x, int y, int z,
		ModelReuseCache.Entry reuse,
		long reuseHash
	) {
		final long t = System.nanoTime();
		uploadTempModel(
			ctx,
			projection,
			tileObject,
			renderable,
			modelOverride,
			model,
			zone,
			alphaModel,
			isModelPartiallyVisible,
			-1,
			drawIndex,
			orientation,
			x, y, z,
			reuse,
			reuseHash
		);
		frameTimer.add(renderable instanceof Actor ? Timer.DRAW_TEMP_ASYNC : Timer.DRAW_DYNAMIC_ASYNC, System.nanoTime() - t);
	}

	private void replayModelAsync(
		WorldViewContext ctx,
		ModelReuseCache.Entry entry,
		int vaoType,
		int drawIndex,
		@Nullable Zone.AlphaModel alphaModel
	) {
		final long t = System.nanoTime();
		replayModel(ctx, entry, vaoType, drawIndex, alphaModel);
		frameTimer.add(Timer.DRAW_TEMP_ASYNC, System.nanoTime() - t);
	}

	public void uploadTempModel(
		WorldViewContext ctx,
		Projection projection,
		@Nullable TileObject tileObject,
		Renderable renderable,
		ModelOverride modelOverride,
		Model m,
		@Nullable Zone zone,
		@Nullable Zone.AlphaModel alphaModel,
		boolean isModelPartiallyVisible,
		int vaoType,
		int drawIndex,
		int orient,
		float x, float y, float z,
		@Nullable ModelReuseCache.Entry reuse,
		long reuseHash
	) {
		boolean isActor = renderable instanceof Actor;
		boolean isPlayer = renderable instanceof Player;

		// opaque player faces have their own vao and are drawn in a separate pass from normal opaque faces
		// because they are not depth tested. transparent player faces don't need their own vao because normal
		// transparent faces are already not depth tested
		if (vaoType == -1)
			vaoType = isPlayer ? VAO_PLAYER : VAO_OPAQUE;

		final PrimitiveCharArray visibleFaces = FACE_INDICES.acquire();
		final PrimitiveCharArray culledFaces = FACE_INDICES.acquire();
		final int renderMode = renderable.getRenderMode();
		boolean shouldSort =
			m.getTransparency() != 0 ||
			m.getFaceTransparencies() != null ||
			modelOverride.mightHaveTransparency ||
			renderable instanceof Player ||
			(
				renderMode != Renderable.RENDERMODE_UNSORTED &&
				renderMode != Renderable.RENDERMODE_DEFAULT &&
				renderMode != Renderable.RENDERMODE_UNSORTED_NO_DEPTH
			);

		try (
			SceneUploader sceneUploader = SceneUploader.POOL.acquire();
			FacePrioritySorter facePrioritySorter = shouldSort ? FacePrioritySorter.POOL.acquire() : null
		) {
			final int[] faceDistances = shouldSort ? PooledArrayType.INT.borrow(m.getFaceCount()) : null;
			shouldSort &= sceneUploader.preprocessTempModel(
				projection,
				plugin.cameraFrustum,
				faceDistances,
				visibleFaces,
				culledFaces,
				isModelPartiallyVisible,
				modelOverride,
				m,
				isPlayer,
				orient,
				x, y, z
			);

			final int preOrientation = HDUtils.getModelPreOrientation(HDUtils.getObjectConfig(tileObject));
			if (shouldSort && !ctx.isSquashed)
				facePrioritySorter.sortModelFaces(visibleFaces, m, faceDistances, !isActor);

			if (facePrioritySorter != null)
				PooledArrayType.INT.release(faceDistances);

			if (culledFaces.length > 0 &&
				(sceneManager.isRoot(ctx) ? castsTempShadow(modelOverride, zone) :
					modelOverride.castShadows && plugin.configShadowMode != ShadowMode.OFF)
			) {
				final DynamicModelVAO.View shadowView = ctx.beginDraw(VAO_SHADOW, culledFaces.length);
				if (reuse != null) {
					sceneUploader.captureVertices = reuse.shadowVertices;
					sceneUploader.captureFaces = reuse.shadowFaces;
				}
				sceneUploader.uploadTempModel(
					culledFaces,
					m,
					modelOverride,
					preOrientation,
					orient,
					true,
					shadowView,
					shadowView
				);
				if (reuse != null) {
					reuse.shadowFaceIndexBase = sceneUploader.capturedFaceIndexBase;
					sceneUploader.captureVertices = null;
					sceneUploader.captureFaces = null;
				}
				shadowView.end();
			}

			if (visibleFaces.length > 0) {
				final int alphaFaceCount = alphaModel != null ? sceneUploader.tempModelAlphaFaces : 0;
				final int opaqueFaceCount = visibleFaces.length - alphaFaceCount;
				assert opaqueFaceCount >= 0 && alphaFaceCount >= 0 : "Invalid face counts: " + opaqueFaceCount + ", " + alphaFaceCount;

				final DynamicModelVAO.View opaqueView = ctx.beginDraw(vaoType, drawIndex, opaqueFaceCount);
				final DynamicModelVAO.View alphaView = alphaFaceCount > 0 ? ctx.beginDraw(VAO_ALPHA, alphaFaceCount) : opaqueView;

				if (reuse != null) {
					sceneUploader.captureVertices = reuse.vertices;
					sceneUploader.captureFaces = reuse.faces;
					sceneUploader.captureAlphaVertices = reuse.alphaVertices;
					sceneUploader.captureAlphaFaces = reuse.alphaFaces;
				}
				sceneUploader.uploadTempModel(
					visibleFaces,
					m,
					modelOverride,
					preOrientation,
					orient,
					ctx.isSquashed,
					opaqueView,
					alphaView
				);
				if (reuse != null) {
					reuse.faceIndexBase = sceneUploader.capturedFaceIndexBase;
					reuse.alphaFaceIndexBase = sceneUploader.capturedAlphaFaceIndexBase;
				}

				if (opaqueView != alphaView && alphaView.getEndOffset() > alphaView.getStartOffset()) {
					alphaModel.setView(alphaView);
					alphaView.end();
				}
				opaqueView.end();
			}

			if (reuse != null) {
				reuse.hash = reuseHash;
				reuse.cameraEpoch = cameraEpoch;
				reuse.vaoType = vaoType;
				reuse.hasAlphaModel = alphaModel != null;
				reuse.valid = true;
			}
		} catch (Exception e) {
			log.error("Error rendering dynamic object", e);
		} finally {
			FACE_INDICES.recycle(visibleFaces);
			FACE_INDICES.recycle(culledFaces);
		}
	}

	/** Whether a temp model in the root scene writes its camera-culled faces to the shadow buffers. */
	private boolean castsTempShadow(ModelOverride modelOverride, @Nullable Zone zone) {
		return modelOverride.castShadows &&
			plugin.configShadowMode != ShadowMode.OFF &&
			zone != null && zone.inShadowFrustum;
	}

	private void replayModel(
		WorldViewContext ctx,
		ModelReuseCache.Entry entry,
		int vaoType,
		int drawIndex,
		@Nullable Zone.AlphaModel alphaModel
	) {
		final int faceCount = entry.faces.length / 9;
		assert entry.vertices.length == faceCount * 3 * DynamicModelVAO.VERT_SIZE_INTS;
		final int alphaFaceCount = entry.alphaFaces.length / 9;
		assert entry.alphaVertices.length == alphaFaceCount * 3 * DynamicModelVAO.VERT_SIZE_INTS;
		final int shadowFaceCount = entry.shadowFaces.length / 9;
		assert entry.shadowVertices.length == shadowFaceCount * 3 * DynamicModelVAO.VERT_SIZE_INTS;

		if (shadowFaceCount > 0) {
			final DynamicModelVAO.View shadowView = ctx.beginDraw(VAO_SHADOW, shadowFaceCount);
			var shadowFaces = shadowView.tbo.getBuffer();
			entry.replayShadow(shadowView.vbo.getBuffer(), shadowFaces, (shadowView.tbo.getPositionOffsetInts() + shadowFaces.position()) / 3);
			shadowView.end();
		}

		if (faceCount + alphaFaceCount == 0)
			return;

		// Reserve and write in the same order as uploadTempModel
		final DynamicModelVAO.View view = ctx.beginDraw(vaoType, drawIndex, faceCount);
		final DynamicModelVAO.View alphaView = alphaFaceCount > 0 && alphaModel != null ? ctx.beginDraw(VAO_ALPHA, alphaFaceCount) : null;

		var faces = view.tbo.getBuffer();
		entry.replay(view.vbo.getBuffer(), faces, (view.tbo.getPositionOffsetInts() + faces.position()) / 3);
		if (alphaView != null) {
			faces = alphaView.tbo.getBuffer();
			entry.replayAlpha(alphaView.vbo.getBuffer(), faces, (alphaView.tbo.getPositionOffsetInts() + faces.position()) / 3);
			alphaModel.setView(alphaView);
			alphaView.end();
		}
		view.end();
	}

	public void ensureAsyncUploadsComplete(@Nullable Zone zone) {
		if (AsyncCachedModel.POOL == null)
			return;

		frameTimer.begin(Timer.MODEL_UPLOAD_COMPLETE);
		pending.clear();
		AsyncCachedModel model;
		while ((model = (zone != null ? zone.pendingModelJobs.poll() : AsyncCachedModel.INFLIGHT.poll())) != null)
			pending.add(model);

		AsyncCachedModel pendingModel;
		boolean shouldBlock = false, hasStolen = false;
		int idx = 0;
		while (!pending.isEmpty()) {
			pendingModel = pending.get(idx);

			if (pendingModel.isQueued() && pendingModel.canStart() && pendingModel.processModel()) {
				pending.remove(idx);
				hasStolen = true;
			}

			if (shouldBlock && pendingModel.waitForCompletion(10))
				pending.remove(idx);

			if (pending.isEmpty())
				break;

			idx = (idx + 1) % pending.size();
			if (idx == 0 && !shouldBlock) {
				// We've wrapped around to the start, check if any work was stolen.
				// If no work was stolen, the next iteration we should block since we'll never be able to steal
				shouldBlock = !hasStolen;
				hasStolen = false;
			}
		}

		frameTimer.end(Timer.MODEL_UPLOAD_COMPLETE);
	}


	@Nullable
	private synchronized AsyncCachedModel obtainReplayJob() {
		if (AsyncCachedModel.POOL == null || numRenderThreads <= 0)
			return null;

		AsyncCachedModel result = AsyncCachedModel.POOL.acquire();
		if (result != null)
			result.setupReplay();
		return result;
	}

	private synchronized AsyncCachedModel obtainAvailableAsyncCachedModel(Model model) {
		if (AsyncCachedModel.POOL == null || numRenderThreads <= 0)
			return null;

		AsyncCachedModel result = AsyncCachedModel.POOL.acquire();
		if (result == null)
			return null;

		if (result.setup(model))
			return result;

		// We failed to reserve space to cache the model, so return the model back to the pool
		AsyncCachedModel.POOL.recycle(result);

		return null;
	}
}
