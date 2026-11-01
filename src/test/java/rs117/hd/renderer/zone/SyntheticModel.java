package rs117.hd.renderer.zone;

import java.util.Random;
import net.runelite.api.AABB;
import net.runelite.api.Model;
import net.runelite.api.Node;

/**
 * A closed, player-sized capsule with smooth normals and per-vertex colours.
 */
final class SyntheticModel implements Model {
	private final float[] vx, vy, vz;
	private final int[] nx, ny, nz;
	private final int[] f1, f2, f3, c2, c3;
	int[] c1;
	private final byte[] priorities;
	private final int radius, diameter;

	short[] faceTextures;
	byte[] faceTransparencies;

	SyntheticModel(Random random, int rings, int segments) {
		int vertexCount = (rings - 1) * segments + 2;
		vx = new float[vertexCount];
		vy = new float[vertexCount];
		vz = new float[vertexCount];
		nx = new int[vertexCount];
		ny = new int[vertexCount];
		nz = new int[vertexCount];

		final float rx = 40, height = 100, rz = 25;
		int v = 0;
		vy[v] = -2 * height;
		ny[v++] = -256;
		for (int r = 1; r < rings; r++) {
			double phi = Math.PI * r / rings;
			for (int s = 0; s < segments; s++) {
				double theta = 2 * Math.PI * s / segments;
				double sx = Math.sin(phi) * Math.cos(theta), sy = -Math.cos(phi), sz = Math.sin(phi) * Math.sin(theta);
				vx[v] = (float) (sx * rx);
				vy[v] = (float) (sy * height - height);
				vz[v] = (float) (sz * rz);
				nx[v] = (int) (sx * 256);
				ny[v] = (int) (sy * 256);
				nz[v++] = (int) (sz * 256);
			}
		}
		vy[v] = 0;
		ny[v] = 256;

		int faceCount = 2 * segments * (rings - 1);
		f1 = new int[faceCount];
		f2 = new int[faceCount];
		f3 = new int[faceCount];
		c1 = new int[faceCount];
		c2 = new int[faceCount];
		c3 = new int[faceCount];
		priorities = new byte[faceCount];
		int f = 0;
		for (int s = 0; s < segments; s++) {
			int a = 1 + s, b = 1 + (s + 1) % segments;
			f = face(f, 0, b, a);
			int base = 1 + (rings - 2) * segments;
			f = face(f, vertexCount - 1, base + s, base + (s + 1) % segments);
		}
		for (int r = 0; r < rings - 2; r++) {
			for (int s = 0; s < segments; s++) {
				int a = 1 + r * segments + s;
				int b = 1 + r * segments + (s + 1) % segments;
				int c = a + segments;
				int d = b + segments;
				f = face(f, a, b, d);
				f = face(f, a, d, c);
			}
		}
		for (int i = 0; i < faceCount; i++) {
			int hsl = random.nextInt(0xFFFF);
			c1[i] = hsl;
			c2[i] = hsl + random.nextInt(8);
			c3[i] = hsl + random.nextInt(8);
			priorities[i] = (byte) random.nextInt(3);
		}

		radius = (int) Math.ceil(Math.sqrt(rx * rx + rz * rz));
		diameter = (int) Math.ceil(2 * Math.sqrt(radius * radius + 4 * height * height));
	}

	private int face(int f, int a, int b, int c) {
		f1[f] = a;
		f2[f] = b;
		f3[f] = c;
		return f + 1;
	}

	@Override public int getVerticesCount() { return vx.length; }
	@Override public float[] getVerticesX() { return vx; }
	@Override public float[] getVerticesY() { return vy; }
	@Override public float[] getVerticesZ() { return vz; }
	@Override public int getFaceCount() { return f1.length; }
	@Override public int[] getFaceIndices1() { return f1; }
	@Override public int[] getFaceIndices2() { return f2; }
	@Override public int[] getFaceIndices3() { return f3; }
	@Override public int[] getFaceColors1() { return c1; }
	@Override public int[] getFaceColors2() { return c2; }
	@Override public int[] getFaceColors3() { return c3; }
	@Override public short[] getUnlitFaceColors() { return null; }
	@Override public byte[] getFaceTransparencies() { return faceTransparencies; }
	@Override public short[] getFaceTextures() { return faceTextures; }
	@Override public byte[] getFaceRenderPriorities() { return priorities; }
	@Override public byte[] getFaceBias() { return null; }
	@Override public int[] getVertexNormalsX() { return nx; }
	@Override public int[] getVertexNormalsY() { return ny; }
	@Override public int[] getVertexNormalsZ() { return nz; }
	@Override public byte[] getTextureFaces() { return null; }
	@Override public int[] getTexIndices1() { return null; }
	@Override public int[] getTexIndices2() { return null; }
	@Override public int[] getTexIndices3() { return null; }
	@Override public int getRadius() { return radius; }
	@Override public int getDiameter() { return diameter; }
	@Override public int getXYZMag() { return radius; }
	@Override public int getBottomY() { return 0; }
	@Override public byte getTransparency() { return 0; }
	@Override public byte getOverrideAmount() { return 0; }
	@Override public byte getOverrideHue() { return 0; }
	@Override public byte getOverrideSaturation() { return 0; }
	@Override public byte getOverrideLuminance() { return 0; }
	@Override public boolean useBoundingBox() { return false; }
	@Override public int getSceneId() { return 0; }
	@Override public void setSceneId(int sceneId) {}
	@Override public int getBufferOffset() { return 0; }
	@Override public void setBufferOffset(int bufferOffset) {}
	@Override public int getUvBufferOffset() { return 0; }
	@Override public void setUvBufferOffset(int bufferOffset) {}
	@Override public void calculateBoundsCylinder() {}
	@Override public void calculateExtreme(int orientation) {}
	@Override public AABB getAABB(int orientation) { return null; }
	@Override public Model getUnskewedModel() { return this; }
	@Override public void drawFrustum(int zero, int xRotate, int yRotate, int zRotate, int xCamera, int yCamera, int zCamera) {}
	@Override public void drawOrtho(int zero, int xRotate, int yRotate, int zRotate, int xCamera, int yCamera, int zCamera, int zoom) {}
	@Override public Model rotateY90Ccw() { return this; }
	@Override public Model rotateY180Ccw() { return this; }
	@Override public Model rotateY270Ccw() { return this; }
	@Override public Model translate(int x, int y, int z) { return this; }
	@Override public Model scale(int x, int y, int z) { return this; }
	@Override public Model getModel() { return this; }
	@Override public int getModelHeight() { return 200; }
	@Override public void setModelHeight(int modelHeight) {}
	@Override public int getAnimationHeightOffset() { return 0; }
	@Override public int getRenderMode() { return 0; }
	@Override public Node getNext() { return null; }
	@Override public Node getPrevious() { return null; }
	@Override public long getHash() { return 0; }
}
