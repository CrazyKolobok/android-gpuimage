/*
 * Copyright (C) 2018 CyberAgent, Inc.
 * Copyright (C) 2010 jsemler
 *
 * Original publication without License
 * http://www.anddev.org/android-2d-3d-graphics-opengl-tutorials-f2/possible-to-do-opengl-off-screen-rendering-in-android-t13232.html#p41662
 */

package jp.co.cyberagent.android.gpuimage;

import android.graphics.Bitmap;
import android.opengl.GLSurfaceView;
import android.opengl.GLES20;
import android.util.Log;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL10;

import static javax.microedition.khronos.egl.EGL10.EGL_ALPHA_SIZE;
import static javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE;
import static javax.microedition.khronos.egl.EGL10.EGL_DEFAULT_DISPLAY;
import static javax.microedition.khronos.egl.EGL10.EGL_DEPTH_SIZE;
import static javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE;
import static javax.microedition.khronos.egl.EGL10.EGL_HEIGHT;
import static javax.microedition.khronos.egl.EGL10.EGL_NONE;
import static javax.microedition.khronos.egl.EGL10.EGL_NO_CONTEXT;
import static javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE;
import static javax.microedition.khronos.egl.EGL10.EGL_STENCIL_SIZE;
import static javax.microedition.khronos.egl.EGL10.EGL_WIDTH;

public class PixelBuffer {
    private final static String TAG = "PixelBuffer";
    private final static boolean LIST_CONFIGS = false;

    private GLSurfaceView.Renderer renderer; // borrow this interface
    private int width, height;
    private Bitmap bitmap;

    private EGL10 egl10;
    private EGLDisplay eglDisplay;
    private EGLConfig[] eglConfigs;
    private EGLConfig eglConfig;
    private EGLContext eglContext;
    private EGLSurface eglSurface;
    private GL10 gl10;

    private int[] frameBuffers;
    private int[] textures;

    private String mThreadOwner;

    public PixelBuffer(final int width, final int height) {
        this.width = width;
        this.height = height;

        int[] version = new int[2];
        int[] attribList = new int[]{
                EGL_WIDTH, this.width,
                EGL_HEIGHT, this.height,
                EGL_NONE
        };

        // No error checking performed, minimum required code to elucidate logic
        egl10 = (EGL10) EGLContext.getEGL();
        eglDisplay = egl10.eglGetDisplay(EGL_DEFAULT_DISPLAY);
        egl10.eglInitialize(eglDisplay, version);
        eglConfig = chooseConfig(); // Choosing a config is a little more
        // complicated

        // eglContext = egl10.eglCreateContext(eglDisplay, eglConfig,
        // EGL_NO_CONTEXT, null);
        int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
        int[] attrib_list = {
                EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL10.EGL_NONE
        };
        eglContext = egl10.eglCreateContext(eglDisplay, eglConfig, EGL_NO_CONTEXT, attrib_list);

        eglSurface = egl10.eglCreatePbufferSurface(eglDisplay, eglConfig, attribList);
        egl10.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);

        gl10 = (GL10) eglContext.getGL();

        // Record thread owner of OpenGL context
        mThreadOwner = Thread.currentThread().getName();

        createFrameBufferObject(width, height);
    }

    public void setRenderer(final GLSurfaceView.Renderer renderer) {
        this.renderer = renderer;

        // Does this thread own the OpenGL context?
        if (!Thread.currentThread().getName().equals(mThreadOwner)) {
            Log.e(TAG, "setRenderer: This thread does not own the OpenGL context.");
            return;
        }

        // Call the renderer initialization routines
        this.renderer.onSurfaceCreated(gl10, eglConfig);
        this.renderer.onSurfaceChanged(gl10, width, height);
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
        renderer.onDrawFrame(gl10);
        renderer.onDrawFrame(gl10);
        convertToBitmap();
        return bitmap;
    }

    public void destroy() {
        renderer.onDrawFrame(gl10);
        renderer.onDrawFrame(gl10);

        destroyFrameBufferObject();

        egl10.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE,
                EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);

        egl10.eglDestroySurface(eglDisplay, eglSurface);
        egl10.eglDestroyContext(eglDisplay, eglContext);
        egl10.eglTerminate(eglDisplay);
    }

    private EGLConfig chooseConfig() {
        int[] attribList = new int[]{
                EGL_DEPTH_SIZE, 0,
                EGL_STENCIL_SIZE, 0,
                EGL_RED_SIZE, 8,
                EGL_GREEN_SIZE, 8,
                EGL_BLUE_SIZE, 8,
                EGL_ALPHA_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, 4,
                EGL_NONE
        };

        // No error checking performed, minimum required code to elucidate logic
        // Expand on this logic to be more selective in choosing a configuration
        int[] numConfig = new int[1];
        egl10.eglChooseConfig(eglDisplay, attribList, null, 0, numConfig);
        int configSize = numConfig[0];
        eglConfigs = new EGLConfig[configSize];
        egl10.eglChooseConfig(eglDisplay, attribList, eglConfigs, configSize, numConfig);

        if (LIST_CONFIGS) {
            listConfig();
        }

        return eglConfigs[0]; // Best match is probably the first configuration
    }

    private void listConfig() {
        Log.i(TAG, "Config List {");

        for (EGLConfig config : eglConfigs) {
            int d, s, r, g, b, a;

            // Expand on this logic to dump other attributes
            d = getConfigAttrib(config, EGL_DEPTH_SIZE);
            s = getConfigAttrib(config, EGL_STENCIL_SIZE);
            r = getConfigAttrib(config, EGL_RED_SIZE);
            g = getConfigAttrib(config, EGL_GREEN_SIZE);
            b = getConfigAttrib(config, EGL_BLUE_SIZE);
            a = getConfigAttrib(config, EGL_ALPHA_SIZE);
            Log.i(TAG, "    <d,s,r,g,b,a> = <" + d + "," + s + "," +
                    r + "," + g + "," + b + "," + a + ">");
        }

        Log.i(TAG, "}");
    }

    private int getConfigAttrib(final EGLConfig config, final int attribute) {
        int[] value = new int[1];
        return egl10.eglGetConfigAttrib(eglDisplay, config,
                attribute, value) ? value[0] : 0;
    }

    private void convertToBitmap() {
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        GPUImageNativeLibrary.adjustBitmap(bitmap);
    }

    private void createFrameBufferObject(int width, int height) {
        destroyFrameBufferObject();

        Log.d(TAG, "init framebuffer object (width = " + width + ", height = " + height + ")");
        createFrameBufferTexture(width, height);

        frameBuffers = new int[1];
        GLES20.glGenFramebuffers(1, frameBuffers, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, getFrameBufferObject());
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, getTexture(), 0);
        Log.d(TAG, "framebuffer object initialization error status: " + GLES20.glGetError());

        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "framebuffer object initialization failed, status: " + status);
            destroyFrameBufferObject();
        }
    }

    private void createFrameBufferTexture(int width, int height) {
        destroyFrameBufferTexture();

        textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        Log.d(TAG, "framebuffer texture initialization error status: " + GLES20.glGetError());
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

    private int getFrameBufferObject() {
        return frameBuffers == null ? 0 : frameBuffers[0];
    }

    private int getTexture() {
        return textures == null ? 0 : textures[0];
    }
}
