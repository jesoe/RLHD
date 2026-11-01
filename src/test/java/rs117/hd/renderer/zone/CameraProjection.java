package rs117.hd.renderer.zone;

import net.runelite.api.Projection;

/**
 * Perspective projection in the same shape as the client's: returns x and y scaled by zoom, and depth,
 * leaving the divide by depth to the caller.
 */
final class CameraProjection implements Projection {
	private final float cx, cy, cz, pitchSin, pitchCos, zoom;

	CameraProjection(float cx, float cy, float cz, float pitch, float zoom) {
		this.cx = cx;
		this.cy = cy;
		this.cz = cz;
		this.pitchSin = (float) Math.sin(pitch);
		this.pitchCos = (float) Math.cos(pitch);
		this.zoom = zoom;
	}

	@Override
	public float[] project(float x, float y, float z) {
		return project(x, y, z, new float[3]);
	}

	@Override
	public float[] project(float x, float y, float z, float[] out) {
		x -= cx;
		y -= cy;
		z -= cz;
		float ry = y * pitchCos - z * pitchSin;
		float rz = y * pitchSin + z * pitchCos;
		out[0] = x * zoom;
		out[1] = ry * zoom;
		out[2] = rz;
		return out;
	}
}
