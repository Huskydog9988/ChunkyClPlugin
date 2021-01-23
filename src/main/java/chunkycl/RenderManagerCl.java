package chunkycl;

import se.llbit.chunky.PersistentSettings;
import se.llbit.chunky.renderer.*;
import se.llbit.chunky.renderer.scene.Camera;
import se.llbit.chunky.renderer.scene.Scene;
import se.llbit.chunky.resources.BitmapImage;
import se.llbit.log.Log;
import se.llbit.math.Matrix3;
import se.llbit.math.Ray;
import se.llbit.math.Vector3;
import se.llbit.util.TaskTracker;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class RenderManagerCl extends Thread implements Renderer {
    private static final Repaintable EMPTY_CANVAS = () -> {};
    private Repaintable canvas = EMPTY_CANVAS;

    private Thread[] workers = {};
    private final Scene bufferedScene;
    private final boolean headless;
    private int numThreads;

    private RenderMode mode = RenderMode.PREVIEW;

    private SnapshotControl snapshotControl = SnapshotControl.DEFAULT;

    private boolean finalizeAllFrames = false;

    private RenderContext context;


    private int cpuLoad;
    private SceneProvider sceneProvider;

    private BiConsumer<Long, Integer> renderCompleteListener;
    private BiConsumer<Scene, Integer> frameCompleteListener;

    private Collection<RenderStatusListener> renderListeners = new ArrayList<>();
    private Collection<SceneStatusListener> sceneListeners = new ArrayList<>();

    private TaskTracker.Task renderTask;

    public static final OctreeIntersectCl intersectCl = new OctreeIntersectCl();

    public RenderManagerCl(RenderContext context, boolean headless) {
        super("Render Manager");

        numThreads = context.numRenderThreads();
        cpuLoad = PersistentSettings.getCPULoad();

        this.context = context;

        this.headless = headless;
        bufferedScene = context.getChunky().getSceneFactory().newScene();
    }

    public int getNumThreads() {
        return numThreads;
    }

    @Override public void setSceneProvider(SceneProvider sceneProvider) {
        this.sceneProvider = sceneProvider;
    }

    @Override public void setCanvas(Repaintable canvas) {
        this.canvas = canvas;
    }

    @Override public void setCPULoad(int loadPercent) {
        this.cpuLoad = loadPercent;
    }

    public int getCPULoad() {
        return cpuLoad;
    }

    @Override public void setOnRenderCompleted(BiConsumer<Long, Integer> listener) {
        renderCompleteListener = listener;
    }

    @Override public void setOnFrameCompleted(BiConsumer<Scene, Integer> listener) {
        frameCompleteListener = listener;
    }

    @Override public void setSnapshotControl(SnapshotControl callback) {
        snapshotControl = callback;
    }

    @Override public void setRenderTask(TaskTracker.Task task) {
        renderTask = task;
    }

    @Override public synchronized void addRenderListener(RenderStatusListener listener) {
        renderListeners.add(listener);
    }

    @Override public void removeRenderListener(RenderStatusListener listener) {
        renderListeners.remove(listener);
    }

    @Override public synchronized void addSceneStatusListener(SceneStatusListener listener) {
        sceneListeners.add(listener);
    }

    @Override public void removeSceneStatusListener(SceneStatusListener listener) {
        sceneListeners.remove(listener);
    }

    @Override public void withBufferedImage(Consumer<BitmapImage> consumer) {
        bufferedScene.withBufferedImage(consumer);
    }

    @Override public void withSampleBufferProtected(SampleBufferConsumer consumer) {
        synchronized (bufferedScene) {
            consumer.accept(bufferedScene.getSampleBuffer(), bufferedScene.width, bufferedScene.height);
        }
    }

    @Override public RenderStatus getRenderStatus() {
        RenderStatus status;
        synchronized (bufferedScene) {
            status = new RenderStatus(bufferedScene.renderTime, bufferedScene.spp);
        }
        return status;
    }

    @Override public void shutdown() {
        interrupt();
    }

    public Scene getBufferedScene() {
        return bufferedScene;
    }


    @Override public void run() {
        try {
            while (!isInterrupted()) {
                ResetReason reason = sceneProvider.awaitSceneStateChange();

                synchronized (bufferedScene) {
                    sceneProvider.withSceneProtected(scene -> {
                        if (reason.overwriteState()) {
                            bufferedScene.copyState(scene);
                        }
                        if (reason == ResetReason.MATERIALS_CHANGED || reason == ResetReason.SCENE_LOADED) {
                            scene.importMaterials();
                            intersectCl.load(bufferedScene);
                        }

                        bufferedScene.copyTransients(scene);
                        finalizeAllFrames = scene.shouldFinalizeBuffer();

                        if (reason == ResetReason.SCENE_LOADED) {
                            bufferedScene.swapBuffers();
                        }
                    });
                }

                System.out.println("Previewing");

                previewRender();

                renderTask.update("Preview", 1, 1, "");

                synchronized (bufferedScene) {
                    bufferedScene.swapBuffers();
                }

                canvas.repaint();

                if (headless) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            // 3D view was closed.
        } catch (Throwable e) {
            Log.error("Unchecked exception in render manager", e);
        }
    }

    private void previewRender() {
        int width = bufferedScene.canvasWidth();
        int height = bufferedScene.canvasHeight();

        double halfWidth = width / (2.0 * height);
        double invHeight = 1.0 / height;

        float[] rayDirs = new float[width * height * 3];

        Camera cam = bufferedScene.camera();
        Ray ray = new Ray();

        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                cam.calcViewRay(ray, -halfWidth + i*invHeight, -.5 +  j*invHeight);
                rayDirs[(i * height + j)*3 + 0] = (float) ray.d.x;
                rayDirs[(i * height + j)*3 + 1] = (float) ray.d.y;
                rayDirs[(i * height + j)*3 + 2] = (float) ray.d.z;
            }
        }

        Vector3 origin = ray.o;
        origin.x -= bufferedScene.getOrigin().x;
        origin.y -= bufferedScene.getOrigin().y;
        origin.z -= bufferedScene.getOrigin().z;

        float[] depthmap = intersectCl.intersect(rayDirs, origin);

        double[] samples = bufferedScene.getSampleBuffer();

        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                samples[(j * width + i) * 3 + 0] = 1 - depthmap[i * height + j] / 8.0;
                samples[(j * width + i) * 3 + 1] = 1 - depthmap[i * height + j] / 8.0;
                samples[(j * width + i) * 3 + 2] = 1 - depthmap[i * height + j] / 8.0;

                bufferedScene.finalizePixel(i, j);
            }
        }
    }
}
