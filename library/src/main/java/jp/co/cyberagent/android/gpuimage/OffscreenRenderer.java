package jp.co.cyberagent.android.gpuimage;

public interface OffscreenRenderer {
    void onSurfaceCreated();

    void onSurfaceChanged(int width, int height);

    void onDrawFrame();

    void setFrameBuffer(int frameBuffer);
}
