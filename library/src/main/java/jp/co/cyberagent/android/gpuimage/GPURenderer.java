package jp.co.cyberagent.android.gpuimage;

import android.opengl.GLSurfaceView;

public interface GPURenderer extends GLSurfaceView.Renderer {
    void setFrameBuffer(int frameBuffer);
}
