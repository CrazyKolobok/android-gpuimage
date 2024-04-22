package jp.co.cyberagent.android.gpuimage;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Log;

public class OffscreenPixelBuffer {
	private static final boolean LIST_CONFIGS = true;
	private static final boolean LIST_OPEN_GL_CONTEXT_VALUES = true;
	private static final String TAG = "OffscreenPixelBuffer";

	private OffscreenRenderer renderer;
	private final int width;
	private final int height;
	private volatile boolean initialized = false;

	private EGLDisplay eglDisplay;
	private EGLSurface eglSurface;
	private EGLContext eglContext;

	private int[] frameBuffers;
	private int[] textures;

	private String mThreadOwner;

	public OffscreenPixelBuffer(final int width, final int height) {
		Log.d(TAG, "Create pixel buffer for offscreen rendering. width = " + width + ", height = " + height);
		this.width = width;
		this.height = height;
	}

	public synchronized boolean initialize() {
		if (!initialized) {
			initialized = initializeEGL() && createFrameBufferObject();
		}

		return initialized;
	}

	public void destroy() {
		destroyFrameBufferObject();
		destroyEGL();
	}

	public Bitmap getBitmap() {
		// Do we have a renderer?
		if (renderer == null) {
			Log.e(TAG, "getBitmap: Renderer was not set.");
			return null;
		}

		// Does this thread own the OpenGL context?
		if (!Thread.currentThread().getName().equals(mThreadOwner)) {
			Log.e(TAG, "getBitmap: This thread does not own the OpenGL context.");
			return null;
		}

		// Call the renderer draw routine (it seems that some filters do not
		// work if this is only called once)
		renderer.onDrawFrame();
		renderer.onDrawFrame();

		return convertToBitmap();
	}

	public void setRenderer(final OffscreenRenderer renderer) {
		// Does this thread own the OpenGL context?
		if (!Thread.currentThread().getName().equals(mThreadOwner)) {
			Log.e(TAG, "setRenderer: This thread does not own the OpenGL context.");
			return;
		}

		this.renderer = renderer;
		this.renderer.setFrameBuffer(getFrameBufferObject());

		// Call the renderer initialization routines
		this.renderer.onSurfaceCreated();
		this.renderer.onSurfaceChanged(width, height);
	}

	private Bitmap convertToBitmap() {
		Bitmap emptyBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
		Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
		GPUImageNativeLibrary.adjustBitmap(result);

		return result.sameAs(emptyBitmap) ? null : result;
	}

	private boolean createFrameBufferObject() {
		destroyFrameBufferObject();

		if (createFrameBufferTexture()) {
			Log.d(TAG, "init framebuffer object (width = " + width + ", height = " + height + ")");

			frameBuffers = new int[1];
			GLES20.glGenFramebuffers(1, frameBuffers, 0);

			Log.d(TAG, "texture = " + getTexture());
			Log.d(TAG, "framebuffer = " + getFrameBufferObject());

			GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, getFrameBufferObject());
			GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, getTexture(), 0);
			Log.d(TAG, "framebuffer object initialization error status: " + GLES20.glGetError());

			int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
			if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
				Log.e(TAG, "framebuffer object initialization failed, status: " + status);
				destroyFrameBufferObject();
				return false;
			}

			return true;
		}

		return false;
	}

	private boolean createFrameBufferTexture() {
		destroyFrameBufferTexture();

		textures = new int[1];
		GLES20.glGenTextures(1, textures, 0);
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, getTexture());
		GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);

		int errorStatus = GLES20.glGetError();
		Log.d(TAG, "framebuffer texture initialization error status: " + errorStatus);
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

		if (errorStatus != GLES20.GL_NO_ERROR) {
			destroyFrameBufferTexture();
			return false;
		}

		return true;
	}

	private void destroyEGL() {
		if (eglDisplay != null) {
			if (eglSurface != null) {
				EGL14.eglDestroySurface(eglDisplay, eglSurface);
				eglSurface = null;
			}

			if (eglContext != null) {
				EGL14.eglDestroyContext(eglDisplay, eglContext);
				eglContext = null;
			}

			EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
			EGL14.eglTerminate(eglDisplay);
			eglDisplay = null;
		}

		mThreadOwner = null;
	}

	private void destroyFrameBufferObject() {
		destroyFrameBufferTexture();

		GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

		if (frameBuffers != null) {
			Log.d(TAG, "delete framebuffer object");
			GLES20.glDeleteFramebuffers(1, frameBuffers, 0);
			frameBuffers = null;
		}
	}

	private void destroyFrameBufferTexture() {
		if (textures != null) {
			Log.d(TAG, "delete framebuffer texture");
			GLES20.glDeleteTextures(1, textures, 0);
			textures = null;
		}
	}

	private EGLConfig getEGLConfig() {
		int[] configAttributes = new int[] {
				EGL14.EGL_DEPTH_SIZE, 0,
				EGL14.EGL_STENCIL_SIZE, 0,
				EGL14.EGL_RED_SIZE, 8,
				EGL14.EGL_GREEN_SIZE, 8,
				EGL14.EGL_BLUE_SIZE, 8,
				EGL14.EGL_ALPHA_SIZE, 8,
				EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
				EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
				EGL14.EGL_NONE
		};

		int[] configsCounts = new int[1];
		if (EGL14.eglChooseConfig(eglDisplay, configAttributes, 0, null, 0, 0,
				configsCounts, 0)) {
			int count = configsCounts[0];
			if (count > 0) {
				EGLConfig[] configs = new EGLConfig[count];
				if (EGL14.eglChooseConfig(eglDisplay, configAttributes, 0, configs, 0, count,
						configsCounts, 0)) {
					if (LIST_CONFIGS) listConfigs(configs);
					return configs[0];
				}
			}
		}

		return null;
	}

	private int getEGLConfigAttribute(final EGLConfig config, final int attribute) {
		int[] values = new int[1];
		return EGL14.eglGetConfigAttrib(eglDisplay, config, attribute, values, 0) ? values[0] : 0;
	}

	private int getGLES20Attribute(final int attribute) {
		int[] values = new int[1];
		GLES20.glGetIntegerv(attribute, values, 0);
		return values[0];
	}

	private int[] getGLES20MaxViewportsDimensions() {
		int[] values = new int[2];
		GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, values, 0);
		return values;
	}

	private int getFrameBufferObject() {
		return frameBuffers == null ? 0 : frameBuffers[0];
	}

	private int getTexture() {
		return textures == null ? 0 : textures[0];
	}

	private boolean initializeEGL() {
		destroyEGL();

		Log.d(TAG, "Initialize OpenGL context");
		eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
		if (eglDisplay != null) {
			int[] versions = new int[2];
			if (EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) {
				EGLConfig config = getEGLConfig();
				if (config != null) {
					int[] contextAttributes = new int[] {
							EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
							EGL14.EGL_NONE
					};

					eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT,
							contextAttributes, 0);

					int[] surfaceAttributes = new int[] {
							EGL14.EGL_WIDTH, width,
							EGL14.EGL_HEIGHT, height,
							EGL14.EGL_NONE
					};

					eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, surfaceAttributes, 0);

					if (eglContext != null && eglSurface != null) {
						EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);

						// Record thread owner of OpenGL context
						mThreadOwner = Thread.currentThread().getName();

						if (LIST_OPEN_GL_CONTEXT_VALUES) listOpenGLContextValues();

						Log.d(TAG, "OpenGL context initialized");
						return true;
					}
				}
			}
		}

		Log.e(TAG, "OpenGL context not initialized");
		return false;
	}

	private void listConfigs(EGLConfig[] configs) {
		if (configs == null) return;

		StringBuilder builder = new StringBuilder();
		builder.append("Config List {");

		for (EGLConfig config : configs) {
			builder.append("    <d,s,r,g,b,a> = <")
					.append(getEGLConfigAttribute(config, EGL14.EGL_DEPTH_SIZE))
					.append(", ")
					.append(getEGLConfigAttribute(config, EGL14.EGL_STENCIL_SIZE))
					.append(", ")
					.append(getEGLConfigAttribute(config, EGL14.EGL_RED_SIZE))
					.append(", ")
					.append(getEGLConfigAttribute(config, EGL14.EGL_GREEN_SIZE))
					.append(", ")
					.append(getEGLConfigAttribute(config, EGL14.EGL_BLUE_SIZE))
					.append(", ")
					.append(getEGLConfigAttribute(config, EGL14.EGL_ALPHA_SIZE))
					.append(">");
		}

		builder.append("}");

		Log.d(TAG, builder.toString());
	}

	private void listOpenGLContextValues() {
		StringBuilder builder = new StringBuilder();
		builder.append("OpenGL context values: ")
				.append("maxTextureSize = ").append(getGLES20Attribute(GLES20.GL_MAX_TEXTURE_SIZE))
				.append(", maxRenderBufferSize = ").append(getGLES20Attribute(GLES20.GL_MAX_RENDERBUFFER_SIZE))
				.append(", maxTextureImageUnits = ").append(getGLES20Attribute(GLES20.GL_MAX_TEXTURE_IMAGE_UNITS));

		int[] maxDimensions = getGLES20MaxViewportsDimensions();
		builder.append(", maxViewportDimensions = (")
				.append(maxDimensions.length > 0 ? maxDimensions[0] : 0)
				.append(", ")
				.append(maxDimensions.length > 1 ? maxDimensions[1] : 0)
				.append(")");

		Log.d(TAG, builder.toString());
	}
}

