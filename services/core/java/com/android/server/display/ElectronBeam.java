/*
 * Copyright (C) 2012 The Android Open Source Project
 * Copyright (C) 2018 The Dirty Unicorns Project
 * Copyright (C) 2022 The Potato Open Sauce Project
 * Copyright (C) 2026 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.display;

import static com.android.internal.policy.TransitionAnimation.hasProtectedContent;

import android.content.Context;
import android.graphics.BLASTBufferQueue;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayManagerInternal.DisplayTransactionListener;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.window.ScreenCaptureInternal;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.display.utils.DebugUtils;
import com.android.server.policy.WindowManagerPolicy;

import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * <p>
 * Animates a screen transition from on to off by applying a CRT-style
 * ("electron beam") collapse or a scale-down to a screenshot.
 * </p><p>
 * This is the classic KitKat ElectronBeam effect, ported to the same surface and EGL
 * plumbing as {@link ColorFade}: a container layer with a BLAST child for GL rendering,
 * a color layer for {@link #MODE_FADE}, and OpenGL ES 2.0 shaders instead of the
 * fixed-function GLES 1.x pipeline (which is not available on ANGLE-backed devices
 * and cannot be combined with protected contexts on most drivers).
 * </p><p>
 * This component must only be created or accessed by the {@link Looper} thread
 * that belongs to the {@link DisplayPowerController}.
 * </p>
 */
final class ElectronBeam implements ScreenStateAnimator {
    private static final String TAG = "ElectronBeam";

    // To enable these logs, run:
    // 'adb shell setprop persist.log.tag.ElectronBeam DEBUG && adb reboot'
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);

    // The layer for the electron beam surface.
    // This is currently hardcoded to be one layer above the boot animation.
    private static final int ELECTRON_BEAM_LAYER = WindowManagerPolicy.COLOR_FADE_LAYER;

    // The relative proportion of the animation to spend performing
    // the horizontal stretch effect.  The remainder is spent performing
    // the vertical stretch effect.
    private static final float HSTRETCH_DURATION = 0.5f;
    private static final float VSTRETCH_DURATION = 1.0f - HSTRETCH_DURATION;

    // The number of frames to draw when preparing the animation so that it will
    // be ready to run smoothly.  We use 3 frames because we are triple-buffered.
    private static final int DEJANK_FRAMES = 3;

    private static final int EGL_GL_COLORSPACE_KHR = 0x309D;
    private static final int EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT = 0x3490;
    private static final int EGL_PROTECTED_CONTENT_EXT = 0x32C0;

    // Shaders are kept inline so the feature does not need new framework raw resources.
    private static final String TEX_VERTEX_SHADER =
            "uniform mat4 u_proj;\n"
            + "uniform mat4 u_tex;\n"
            + "attribute vec2 a_pos;\n"
            + "attribute vec2 a_uv;\n"
            + "varying vec2 v_uv;\n"
            + "void main() {\n"
            + "    v_uv = (u_tex * vec4(a_uv, 0.0, 1.0)).xy;\n"
            + "    gl_Position = u_proj * vec4(a_pos, 0.0, 1.0);\n"
            + "}\n";

    private static final String TEX_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
            + "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
            + "precision highp float;\n"
            + "#else\n"
            + "precision mediump float;\n"
            + "#endif\n"
            + "uniform samplerExternalOES u_texture;\n"
            + "uniform float u_brightness;\n"
            + "varying vec2 v_uv;\n"
            + "void main() {\n"
            + "    gl_FragColor = vec4(texture2D(u_texture, v_uv).rgb * u_brightness, 1.0);\n"
            + "}\n";

    private static final String SOLID_VERTEX_SHADER =
            "uniform mat4 u_proj;\n"
            + "attribute vec2 a_pos;\n"
            + "void main() {\n"
            + "    gl_Position = u_proj * vec4(a_pos, 0.0, 1.0);\n"
            + "}\n";

    private static final String SOLID_FRAGMENT_SHADER =
            "precision mediump float;\n"
            + "uniform vec4 u_color;\n"
            + "void main() {\n"
            + "    gl_FragColor = u_color;\n"
            + "}\n";

    private final int mDisplayId;

    // Set to true when the animation context has been fully prepared.
    private boolean mPrepared;
    private boolean mCreatedResources;
    private int mMode;

    private final DisplayManagerInternal mDisplayManagerInternal;
    private int mDisplayLayerStack; // layer stack associated with primary display
    private int mDisplayWidth;      // real width, not rotated
    private int mDisplayHeight;     // real height, not rotated
    // True when the display is rotated by 90/270 degrees, so the "user vertical" axis
    // is the natural horizontal one. Keeps the CRT line horizontal from the user's view.
    private boolean mSwapAxes;
    private SurfaceControl mSurfaceControl;
    private Surface mSurface;
    private SurfaceControl mBLASTSurfaceControl;
    private BLASTBufferQueue mBLASTBufferQueue;
    private NaturalSurfaceLayout mSurfaceLayout;
    private EGLDisplay mEglDisplay;
    private EGLConfig mEglConfig;
    private EGLContext mEglContext;
    private EGLSurface mEglSurface;
    private boolean mSurfaceVisible;
    private float mSurfaceAlpha;
    private boolean mLastWasWideColor;
    private boolean mLastWasProtectedContent;

    // Texture names.  We only use one texture, which contains the screenshot.
    private final int[] mTexNames = new int[1];
    private boolean mTexNamesGenerated;
    private final float[] mTexMatrix = new float[16];
    private final float[] mProjMatrix = new float[16];

    // Textured program.
    private int mTexProgram;
    private int mTexPosLoc, mTexUvLoc, mTexProjLoc, mTexMatrixLoc, mTexUnitLoc, mBrightnessLoc;

    // Solid colour program.
    private int mSolidProgram;
    private int mSolidPosLoc, mSolidProjLoc, mSolidColorLoc;

    // Vertex and corresponding texture coordinates.
    // We have 4 2D vertices, so 8 elements.  The vertices form a quad.
    private final FloatBuffer mVertexBuffer = createNativeFloatBuffer(8);
    private final FloatBuffer mTexCoordBuffer = createNativeFloatBuffer(8);

    private final Transaction mTransaction = new Transaction();

    public ElectronBeam(int displayId) {
        this(displayId, LocalServices.getService(DisplayManagerInternal.class));
    }

    @VisibleForTesting
    ElectronBeam(int displayId, DisplayManagerInternal displayManagerInternal) {
        mDisplayId = displayId;
        mDisplayManagerInternal = displayManagerInternal;
    }

    /**
     * Warms up the electron beam in preparation for turning on or off.
     * This method prepares a GL context, and captures a screen shot.
     *
     * @param mode The desired mode for the upcoming animation.
     * @return True if the electron beam is ready, false if it is uncontrollable.
     */
    @Override
    public boolean prepare(Context context, int mode) {
        if (DEBUG) {
            Slog.d(TAG, "prepare: mode=" + mode);
        }

        mMode = mode;

        DisplayInfo displayInfo = mDisplayManagerInternal.getDisplayInfo(mDisplayId);
        if (displayInfo == null) {
            // displayInfo can be null if the associated display has been removed. There
            // is a delay between the display being removed and ElectronBeam being dismissed.
            return false;
        }

        // Get the display size and layer stack.
        // This is not expected to change while the electron beam surface is showing.
        mDisplayLayerStack = displayInfo.layerStack;
        mDisplayWidth = displayInfo.getNaturalWidth();
        mDisplayHeight = displayInfo.getNaturalHeight();
        mSwapAxes = displayInfo.rotation == Surface.ROTATION_90
                || displayInfo.rotation == Surface.ROTATION_270;

        final boolean isWideColor = displayInfo.colorMode == Display.COLOR_MODE_DISPLAY_P3;
        // Set mPrepared here so if initialization fails, resources can be cleaned up.
        mPrepared = true;

        final ScreenCaptureInternal.ScreenshotHardwareBuffer hardwareBuffer = captureScreen();
        if (hardwareBuffer == null) {
            dismiss();
            return false;
        }

        final boolean isProtected = hasProtectedContent(hardwareBuffer.getHardwareBuffer());
        if (!createSurfaceControl(hardwareBuffer.containsSecureLayers())) {
            dismiss();
            return false;
        }

        // A surface left over from a previous MODE_FADE transition is a colour layer with
        // no BLAST child, so it cannot host GL rendering. Rather than tearing down the black
        // cover (which could leak a frame of content), degrade this transition to a fade.
        if (mMode != MODE_FADE && mBLASTSurfaceControl == null) {
            Slog.w(TAG, "Existing surface is a color layer, falling back to MODE_FADE");
            mMode = MODE_FADE;
        }

        // MODE_FADE use ColorLayer to implement.
        if (mMode == MODE_FADE) {
            return true;
        }

        // Don't stack GL objects if a previous GL transition was never dismissed.
        if (mCreatedResources) {
            dismissResources();
        }

        if (!(createEglContext(isProtected) && createEglSurface(isProtected, isWideColor)
                && setScreenshotTextureAndSetViewport(hardwareBuffer, displayInfo.rotation))) {
            dismiss();
            return false;
        }

        // Init GL
        if (!attachEglContext()) {
            dismiss();
            return false;
        }
        boolean glOk;
        try {
            glOk = initGLShaders() && !checkGlErrors("prepare");
        } finally {
            detachEglContext();
        }
        if (!glOk) {
            dismiss();
            return false;
        }

        // Done.
        mCreatedResources = true;
        mLastWasProtectedContent = isProtected;
        mLastWasWideColor = isWideColor;

        // Dejanking optimization.
        // Some GL drivers can introduce a lot of lag in the first few frames as they
        // initialize their state and allocate graphics buffers for rendering.
        // Work around this problem by rendering the first frame of the animation a few
        // times.  The rest of the animation should run smoothly thereafter.
        // The frames we draw here aren't visible because we are essentially just
        // painting the screenshot as-is.
        if (mMode == MODE_COOL_DOWN || mMode == MODE_SCALE_DOWN) {
            for (int i = 0; i < DEJANK_FRAMES; i++) {
                draw(1.0f);
            }
        }
        return true;
    }

    private static int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        if (shader == 0) {
            Slog.e(TAG, "glCreateShader failed for type " + type);
            return 0;
        }
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Slog.e(TAG, "Could not compile shader " + shader + ", " + type + ":");
            Slog.e(TAG, GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            shader = 0;
        }
        return shader;
    }

    private static int createProgram(String vertexSource, String fragmentSource) {
        final int vshader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        final int fshader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (vshader == 0 || fshader == 0) {
            if (vshader != 0) GLES20.glDeleteShader(vshader);
            if (fshader != 0) GLES20.glDeleteShader(fshader);
            return 0;
        }

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vshader);
        GLES20.glAttachShader(program, fshader);
        GLES20.glLinkProgram(program);
        // Flag shaders for deletion; they are freed together with the program.
        GLES20.glDeleteShader(vshader);
        GLES20.glDeleteShader(fshader);

        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            Slog.e(TAG, "Could not link program: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        return program;
    }

    private boolean initGLShaders() {
        mTexProgram = createProgram(TEX_VERTEX_SHADER, TEX_FRAGMENT_SHADER);
        mSolidProgram = createProgram(SOLID_VERTEX_SHADER, SOLID_FRAGMENT_SHADER);
        GLES20.glReleaseShaderCompiler();
        if (mTexProgram == 0 || mSolidProgram == 0) {
            return false;
        }

        mTexPosLoc = GLES20.glGetAttribLocation(mTexProgram, "a_pos");
        mTexUvLoc = GLES20.glGetAttribLocation(mTexProgram, "a_uv");
        mTexProjLoc = GLES20.glGetUniformLocation(mTexProgram, "u_proj");
        mTexMatrixLoc = GLES20.glGetUniformLocation(mTexProgram, "u_tex");
        mTexUnitLoc = GLES20.glGetUniformLocation(mTexProgram, "u_texture");
        mBrightnessLoc = GLES20.glGetUniformLocation(mTexProgram, "u_brightness");

        mSolidPosLoc = GLES20.glGetAttribLocation(mSolidProgram, "a_pos");
        mSolidProjLoc = GLES20.glGetUniformLocation(mSolidProgram, "u_proj");
        mSolidColorLoc = GLES20.glGetUniformLocation(mSolidProgram, "u_color");

        GLES20.glUseProgram(mTexProgram);
        GLES20.glUniform1i(mTexUnitLoc, 0);
        GLES20.glUseProgram(0);
        return true;
    }

    private void destroyGLShaders() {
        if (mTexProgram != 0) {
            GLES20.glDeleteProgram(mTexProgram);
            mTexProgram = 0;
        }
        if (mSolidProgram != 0) {
            GLES20.glDeleteProgram(mSolidProgram);
            mSolidProgram = 0;
        }
        checkGlErrors("glDeleteProgram");
    }

    /**
     * Dismisses the electron beam animation resources.
     *
     * This function destroys the GL resources and the EGL surface created for the
     * animation but does not clean up the surface (the black cover stays on screen).
     */
    @Override
    public void dismissResources() {
        if (DEBUG) {
            Slog.d(TAG, "dismissResources");
        }

        // Also handles partially-initialised state from a failed prepare(): the EGL
        // surface must never outlive the BLAST Surface it was created on.
        if (mEglSurface != null) {
            if (attachEglContext()) {
                try {
                    destroyScreenshotTexture();
                    destroyGLShaders();
                } finally {
                    detachEglContext();
                }
            }
            destroyEglSurface();
        }
        // Anything we could not delete is owned by the context and goes away with it.
        mTexNamesGenerated = false;
        mTexProgram = 0;
        mSolidProgram = 0;
        mCreatedResources = false;
    }

    /**
     * Dismisses the electron beam animation surface and cleans up.
     *
     * To prevent stray photons from leaking out after the electron beam has been
     * turned off, it is a good idea to defer dismissing the animation until the
     * electron beam has been turned back on fully.
     */
    @Override
    public void dismiss() {
        if (DEBUG) {
            Slog.d(TAG, "dismiss");
        }

        if (mPrepared) {
            dismissResources();
            destroySurface();
            mPrepared = false;
        }
    }

    /**
     * Destroys electron beam animation and its resources, including the EGL context.
     *
     * This method should be called when the electron beam is no longer in use; i.e. when
     * the {@link #mDisplayId display} has been removed or the animator was replaced.
     */
    @Override
    public void destroy() {
        if (DEBUG) {
            Slog.d(TAG, "destroy");
        }
        dismiss();
        dismissResources();
        destroyEglContext();
    }

    /**
     * Draws an animation frame showing the electron beam activated at the
     * specified level.
     *
     * @param level The electron beam level. 1.0 is fully on, 0.0 is fully off.
     * @return True if successful.
     */
    @Override
    public boolean draw(float level) {
        if (DEBUG) {
            Slog.d(TAG, "drawFrame: level=" + level);
        }

        if (!mPrepared) {
            return false;
        }

        if (mMode == MODE_FADE) {
            return showSurface(1.0f - level);
        }

        if (!attachEglContext()) {
            return false;
        }
        try {
            // Clear frame to opaque black. The BLAST layer is TRANSLUCENT, so a non-1 alpha
            // here would let the real screen content show through around the effect.
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            // Draw the frame.
            if (mMode == MODE_WARM_UP || mMode == MODE_COOL_DOWN) {
                if (level < HSTRETCH_DURATION) {
                    drawHStretch(1.0f - (level / HSTRETCH_DURATION));
                } else {
                    drawVStretch(1.0f - ((level - HSTRETCH_DURATION) / VSTRETCH_DURATION));
                }
            } else if (mMode == MODE_SCALE_DOWN) {
                drawScaled(level);
            }

            if (checkGlErrors("drawFrame")) {
                return false;
            }

            EGL14.eglSwapBuffers(mEglDisplay, mEglSurface);
        } finally {
            detachEglContext();
        }
        return showSurface(1.0f);
    }

    /**
     * Draws a frame where the content shrinks into the centre of the screen while dimming.
     *
     * @param level 1.0 is full size / full brightness, 0.0 is collapsed / black.
     */
    private void drawScaled(float level) {
        final float s = scurve(level, 8.0f);
        if (DEBUG) {
            Slog.d(TAG, "drawScaled: level=" + level + ", s=" + s);
        }
        final float w = mDisplayWidth * s;
        final float h = mDisplayHeight * s;
        setQuad(mVertexBuffer, (mDisplayWidth - w) * 0.5f, (mDisplayHeight - h) * 0.5f, w, h);
        // Dimming is done in the shader: no blending, so the output stays fully opaque.
        drawTexturedQuad(s);
    }

    /**
     * Draws a frame where the content of the electron beam is collapsing inwards upon
     * itself vertically with red / green / blue channels dispersing and eventually
     * merging down to a single horizontal line.
     *
     * @param stretch The stretch factor.  0.0 is no collapse, 1.0 is full collapse.
     */
    private void drawVStretch(float stretch) {
        // compute interpolation scale factors for each color channel
        final float ar = scurve(stretch, 7.5f);
        final float ag = scurve(stretch, 8.0f);
        final float ab = scurve(stretch, 8.5f);
        if (DEBUG) {
            Slog.d(TAG, "drawVStretch: stretch=" + stretch
                    + ", ar=" + ar + ", ag=" + ag + ", ab=" + ab);
        }

        // additive blending so the three channel planes recombine
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);

        // draw the red plane
        setVStretchQuad(ar);
        GLES20.glColorMask(true, false, false, true);
        drawTexturedQuad(1.0f);

        // draw the green plane
        setVStretchQuad(ag);
        GLES20.glColorMask(false, true, false, true);
        drawTexturedQuad(1.0f);

        // draw the blue plane
        setVStretchQuad(ab);
        GLES20.glColorMask(false, false, true, true);
        drawTexturedQuad(1.0f);

        GLES20.glColorMask(true, true, true, true);

        // draw the white highlight (we use the last vertices)
        if (mMode == MODE_COOL_DOWN) {
            drawSolidQuad(ag, ag, ag, 1.0f);
        }

        GLES20.glDisable(GLES20.GL_BLEND);
    }

    /**
     * Draws a frame where the electron beam has been stretched out into
     * a thin white horizontal line that fades as it collapses inwards.
     *
     * @param stretch The stretch factor.  0.0 is maximum stretch / no fade,
     * 1.0 is collapsed / maximum fade.
     */
    private void drawHStretch(float stretch) {
        // compute interpolation scale factor
        final float ag = scurve(stretch, 8.0f);
        if (DEBUG) {
            Slog.d(TAG, "drawHStretch: stretch=" + stretch + ", ag=" + ag);
        }

        if (stretch < 1.0f) {
            // draw narrow fading white line
            setHStretchQuad(ag);
            final float c = 1.0f - ag * 0.75f;
            drawSolidQuad(c, c, c, 1.0f);
        }
    }

    private void drawTexturedQuad(float brightness) {
        GLES20.glUseProgram(mTexProgram);
        GLES20.glUniformMatrix4fv(mTexProjLoc, 1, false, mProjMatrix, 0);
        GLES20.glUniformMatrix4fv(mTexMatrixLoc, 1, false, mTexMatrix, 0);
        GLES20.glUniform1f(mBrightnessLoc, brightness);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTexNames[0]);

        // Client-side arrays: the quad geometry changes every frame.
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        mVertexBuffer.position(0);
        mTexCoordBuffer.position(0);
        GLES20.glEnableVertexAttribArray(mTexPosLoc);
        GLES20.glVertexAttribPointer(mTexPosLoc, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer);
        GLES20.glEnableVertexAttribArray(mTexUvLoc);
        GLES20.glVertexAttribPointer(mTexUvLoc, 2, GLES20.GL_FLOAT, false, 0, mTexCoordBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);

        GLES20.glDisableVertexAttribArray(mTexPosLoc);
        GLES20.glDisableVertexAttribArray(mTexUvLoc);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        GLES20.glUseProgram(0);
    }

    private void drawSolidQuad(float r, float g, float b, float a) {
        GLES20.glUseProgram(mSolidProgram);
        GLES20.glUniformMatrix4fv(mSolidProjLoc, 1, false, mProjMatrix, 0);
        GLES20.glUniform4f(mSolidColorLoc, r, g, b, a);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        mVertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(mSolidPosLoc);
        GLES20.glVertexAttribPointer(mSolidPosLoc, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);

        GLES20.glDisableVertexAttribArray(mSolidPosLoc);
        GLES20.glUseProgram(0);
    }

    /**
     * Collapses vertically (from the user's point of view) while widening horizontally.
     */
    private void setVStretchQuad(float a) {
        final float userW = mSwapAxes ? mDisplayHeight : mDisplayWidth;
        final float userH = mSwapAxes ? mDisplayWidth : mDisplayHeight;
        setCenteredUserQuad(userW + (userW * a), userH - (userH * a));
    }

    /**
     * A one pixel high line (from the user's point of view) that shrinks horizontally.
     */
    private void setHStretchQuad(float a) {
        final float userW = mSwapAxes ? mDisplayHeight : mDisplayWidth;
        setCenteredUserQuad(2 * userW * (1.0f - a), 1.0f);
    }

    /**
     * Sets a quad centred on the display whose extents are given in the user's
     * (rotated) frame of reference, converting them to natural display coordinates.
     */
    private void setCenteredUserQuad(float userExtentW, float userExtentH) {
        final float w = mSwapAxes ? userExtentH : userExtentW;
        final float h = mSwapAxes ? userExtentW : userExtentH;
        setQuad(mVertexBuffer, (mDisplayWidth - w) * 0.5f, (mDisplayHeight - h) * 0.5f, w, h);
    }

    private static void setQuad(FloatBuffer vtx, float x, float y, float w, float h) {
        if (DEBUG) {
            Slog.d(TAG, "setQuad: x=" + x + ", y=" + y + ", w=" + w + ", h=" + h);
        }
        vtx.put(0, x);
        vtx.put(1, y);
        vtx.put(2, x);
        vtx.put(3, y + h);
        vtx.put(4, x + w);
        vtx.put(5, y + h);
        vtx.put(6, x + w);
        vtx.put(7, y);
    }

    private void ortho(float left, float right, float bottom, float top, float znear, float zfar) {
        mProjMatrix[0] = 2f / (right - left);
        mProjMatrix[1] = 0;
        mProjMatrix[2] = 0;
        mProjMatrix[3] = 0;
        mProjMatrix[4] = 0;
        mProjMatrix[5] = 2f / (top - bottom);
        mProjMatrix[6] = 0;
        mProjMatrix[7] = 0;
        mProjMatrix[8] = 0;
        mProjMatrix[9] = 0;
        mProjMatrix[10] = -2f / (zfar - znear);
        mProjMatrix[11] = 0;
        mProjMatrix[12] = -(right + left) / (right - left);
        mProjMatrix[13] = -(top + bottom) / (top - bottom);
        mProjMatrix[14] = -(zfar + znear) / (zfar - znear);
        mProjMatrix[15] = 1f;
    }

    private boolean setScreenshotTextureAndSetViewport(
            ScreenCaptureInternal.ScreenshotHardwareBuffer screenshotBuffer,
            @Surface.Rotation int rotation) {
        if (!attachEglContext()) {
            return false;
        }
        try {
            if (!mTexNamesGenerated) {
                GLES20.glGenTextures(1, mTexNames, 0);
                if (checkGlErrors("glGenTextures")) {
                    return false;
                }
                mTexNamesGenerated = true;
            }

            final SurfaceTexture st = new SurfaceTexture(mTexNames[0]);
            final Surface s = new Surface(st);
            try {
                s.attachAndQueueBufferWithColorSpace(screenshotBuffer.getHardwareBuffer(),
                        screenshotBuffer.getColorSpace());

                st.updateTexImage();
                st.getTransformMatrix(mTexMatrix);
            } finally {
                s.release();
                st.release();
            }

            // Linear filtering: the quads are stretched/scaled, unlike ColorFade's 1:1 blit.
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTexNames[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

            // if screen is rotated, map texture starting different corner
            int indexDelta = (rotation == Surface.ROTATION_90) ? 2
                            : (rotation == Surface.ROTATION_180) ? 4
                            : (rotation == Surface.ROTATION_270) ? 6 : 0;

            // Set up texture coordinates for a quad.
            // We might need to change this if the texture ends up being
            // a different size from the display for some reason.
            mTexCoordBuffer.put(indexDelta, 0f);
            mTexCoordBuffer.put(indexDelta + 1, 0f);
            mTexCoordBuffer.put((indexDelta + 2) % 8, 0f);
            mTexCoordBuffer.put((indexDelta + 3) % 8, 1f);
            mTexCoordBuffer.put((indexDelta + 4) % 8, 1f);
            mTexCoordBuffer.put((indexDelta + 5) % 8, 1f);
            mTexCoordBuffer.put((indexDelta + 6) % 8, 1f);
            mTexCoordBuffer.put((indexDelta + 7) % 8, 0f);

            // Set up our viewport.
            GLES20.glViewport(0, 0, mDisplayWidth, mDisplayHeight);
            ortho(0, mDisplayWidth, 0, mDisplayHeight, -1, 1);
        } finally {
            detachEglContext();
        }
        return true;
    }

    private void destroyScreenshotTexture() {
        // Must be called with the EGL context attached.
        if (mTexNamesGenerated) {
            mTexNamesGenerated = false;
            GLES20.glDeleteTextures(1, mTexNames, 0);
            checkGlErrors("glDeleteTextures");
        }
    }

    private ScreenCaptureInternal.ScreenshotHardwareBuffer captureScreen() {
        ScreenCaptureInternal.ScreenshotHardwareBuffer screenshotBuffer =
                mDisplayManagerInternal.systemScreenshot(mDisplayId);
        if (screenshotBuffer == null) {
            Slog.e(TAG, "Failed to take screenshot. Buffer is null");
            return null;
        }
        return screenshotBuffer;
    }

    private boolean createSurfaceControl(boolean isSecure) {
        if (mSurfaceControl != null) {
            mTransaction.setSecure(mSurfaceControl, isSecure);
            if (mBLASTSurfaceControl != null) {
                mTransaction.setSecure(mBLASTSurfaceControl, isSecure);
            }
            mTransaction.apply();
            return true;
        }

        try {
            final SurfaceControl.Builder builder = new SurfaceControl.Builder()
                    .setName("ElectronBeam")
                    .setSecure(isSecure)
                    .setCallsite("ElectronBeam.createSurface");
            if (mMode == MODE_FADE) {
                builder.setColorLayer();
            } else {
                builder.setContainerLayer();
            }
            mSurfaceControl = builder.build();
        } catch (OutOfResourcesException ex) {
            Slog.e(TAG, "Unable to create surface.", ex);
            return false;
        }

        mTransaction.setLayerStack(mSurfaceControl, mDisplayLayerStack);
        mTransaction.setWindowCrop(mSurfaceControl, mDisplayWidth, mDisplayHeight);
        mSurfaceLayout = new NaturalSurfaceLayout(mDisplayManagerInternal, mDisplayId,
                mSurfaceControl);
        mSurfaceLayout.onDisplayTransaction(mTransaction);
        mTransaction.apply();

        if (mMode != MODE_FADE) {
            final SurfaceControl.Builder b = new SurfaceControl.Builder()
                    .setName("ElectronBeam BLAST")
                    .setParent(mSurfaceControl)
                    .setHidden(false)
                    .setSecure(isSecure)
                    .setBLASTLayer();
            mBLASTSurfaceControl = b.build();
            mBLASTBufferQueue = new BLASTBufferQueue("ElectronBeam",
                    /*updateDestinationFrame*/ true);
            mBLASTBufferQueue.update(mBLASTSurfaceControl, mDisplayWidth, mDisplayHeight,
                    PixelFormat.TRANSLUCENT);
            mSurface = mBLASTBufferQueue.createSurface();
        }
        return true;
    }

    private boolean createEglContext(boolean isProtected) {
        if (mEglDisplay == null) {
            mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (mEglDisplay == EGL14.EGL_NO_DISPLAY) {
                mEglDisplay = null;
                logEglError("eglGetDisplay");
                return false;
            }

            int[] version = new int[2];
            if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) {
                mEglDisplay = null;
                logEglError("eglInitialize");
                return false;
            }
        }

        if (mEglConfig == null) {
            int[] eglConfigAttribList = new int[] {
                    EGL14.EGL_RENDERABLE_TYPE,
                    EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
            };
            int[] numEglConfigs = new int[1];
            EGLConfig[] eglConfigs = new EGLConfig[1];
            if (!EGL14.eglChooseConfig(mEglDisplay, eglConfigAttribList, 0,
                    eglConfigs, 0, eglConfigs.length, numEglConfigs, 0)) {
                logEglError("eglChooseConfig");
                return false;
            }
            if (numEglConfigs[0] <= 0) {
                Slog.e(TAG, "no valid config found");
                return false;
            }

            mEglConfig = eglConfigs[0];
        }

        // The old context needs to be destroyed if the protected flag has changed. The context will
        // be recreated based on the protected flag
        if (mEglContext != null && isProtected != mLastWasProtectedContent) {
            EGL14.eglDestroyContext(mEglDisplay, mEglContext);
            mEglContext = null;
        }

        if (mEglContext == null) {
            // { CLIENT_VERSION, 2, [PROTECTED_CONTENT_EXT, TRUE,] NONE }
            int[] eglContextAttribList = new int[] {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE, EGL14.EGL_NONE,
                    EGL14.EGL_NONE
            };
            if (isProtected) {
                eglContextAttribList[2] = EGL_PROTECTED_CONTENT_EXT;
                eglContextAttribList[3] = EGL14.EGL_TRUE;
            }
            mEglContext = EGL14.eglCreateContext(mEglDisplay, mEglConfig, EGL14.EGL_NO_CONTEXT,
                    eglContextAttribList, 0);
            // EGL14 returns EGL_NO_CONTEXT (not null) on failure.
            if (mEglContext == null || mEglContext == EGL14.EGL_NO_CONTEXT) {
                mEglContext = null;
                logEglError("eglCreateContext");
                return false;
            }
        }
        return true;
    }

    private boolean createEglSurface(boolean isProtected, boolean isWideColor) {
        // The old surface needs to be destroyed if either the protected flag or wide color flag has
        // changed. The surface will be recreated based on the new flags.
        boolean didContentAttributesChange =
                isProtected != mLastWasProtectedContent || isWideColor != mLastWasWideColor;
        if (mEglSurface != null && didContentAttributesChange) {
            EGL14.eglDestroySurface(mEglDisplay, mEglSurface);
            mEglSurface = null;
        }

        if (mEglSurface == null) {
            int[] eglSurfaceAttribList = new int[] {
                    EGL14.EGL_NONE,
                    EGL14.EGL_NONE,
                    EGL14.EGL_NONE,
                    EGL14.EGL_NONE,
                    EGL14.EGL_NONE
            };

            int index = 0;
            // If the current display is in wide color, then so is the screenshot.
            if (isWideColor) {
                eglSurfaceAttribList[index++] = EGL_GL_COLORSPACE_KHR;
                eglSurfaceAttribList[index++] = EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT;
            }
            if (isProtected) {
                eglSurfaceAttribList[index++] = EGL_PROTECTED_CONTENT_EXT;
                eglSurfaceAttribList[index] = EGL14.EGL_TRUE;
            }
            // turn our SurfaceControl into a Surface
            mEglSurface = EGL14.eglCreateWindowSurface(mEglDisplay, mEglConfig, mSurface,
                    eglSurfaceAttribList, 0);
            if (mEglSurface == null || mEglSurface == EGL14.EGL_NO_SURFACE) {
                mEglSurface = null;
                logEglError("eglCreateWindowSurface");
                return false;
            }
        }
        return true;
    }

    private void destroyEglSurface() {
        if (mEglSurface != null) {
            if (!EGL14.eglDestroySurface(mEglDisplay, mEglSurface)) {
                logEglError("eglDestroySurface");
            }
            mEglSurface = null;
        }
    }

    private void destroySurface() {
        if (mSurfaceControl != null) {
            mSurfaceLayout.dispose();
            mSurfaceLayout = null;
            mTransaction.remove(mSurfaceControl).apply();
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }

            if (mBLASTSurfaceControl != null) {
                mBLASTSurfaceControl.release();
                mBLASTSurfaceControl = null;
                mBLASTBufferQueue.destroy();
                mBLASTBufferQueue = null;
            }

            mSurfaceControl = null;
            mSurfaceVisible = false;
            mSurfaceAlpha = 0f;
        }
    }

    private boolean showSurface(float alpha) {
        if (mSurfaceControl == null) {
            return false;
        }
        if (!mSurfaceVisible || mSurfaceAlpha != alpha) {
            mTransaction.setLayer(mSurfaceControl, ELECTRON_BEAM_LAYER)
                    .setAlpha(mSurfaceControl, alpha)
                    .show(mSurfaceControl)
                    .apply();
            mSurfaceVisible = true;
            mSurfaceAlpha = alpha;
        }
        return true;
    }

    private boolean attachEglContext() {
        if (mEglSurface == null || mEglContext == null) {
            return false;
        }
        if (!EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
            logEglError("eglMakeCurrent");
            return false;
        }
        return true;
    }

    private void detachEglContext() {
        if (mEglDisplay != null) {
            EGL14.eglMakeCurrent(mEglDisplay,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        }
    }

    private void destroyEglContext() {
        if (mEglDisplay != null && mEglContext != null) {
            EGL14.eglDestroyContext(mEglDisplay, mEglContext);
            mEglContext = null;
        }
    }

    /**
     * Interpolates a value in the range 0 .. 1 along a sigmoid curve
     * yielding a result in the range 0 .. 1 scaled such that:
     * scurve(0) == 0, scurve(0.5) == 0.5, scurve(1) == 1.
     */
    private static float scurve(float value, float s) {
        // A basic sigmoid has the form y = 1.0f / FloatMap.exp(-x * s).
        // Here we take the input datum and shift it by 0.5 so that the
        // domain spans the range -0.5 .. 0.5 instead of 0 .. 1.
        final float x = value - 0.5f;

        // Next apply the sigmoid function to the scaled value
        // which produces a value in the range 0 .. 1 so we subtract
        // 0.5 to get a value in the range -0.5 .. 0.5 instead.
        final float y = sigmoid(x, s) - 0.5f;

        // To obtain the desired boundary conditions we need to scale
        // the result so that it fills a range of -1 .. 1.
        final float v = sigmoid(0.5f, s) - 0.5f;

        // And finally remap the value back to a range of 0 .. 1.
        return y / v * 0.5f + 0.5f;
    }

    private static float sigmoid(float x, float s) {
        return 1.0f / (1.0f + (float) Math.exp(-x * s));
    }

    private static FloatBuffer createNativeFloatBuffer(int size) {
        ByteBuffer bb = ByteBuffer.allocateDirect(size * 4);
        bb.order(ByteOrder.nativeOrder());
        return bb.asFloatBuffer();
    }

    private static void logEglError(String func) {
        Slog.e(TAG, func + " failed: error " + EGL14.eglGetError(), new Throwable());
    }

    private static boolean checkGlErrors(String func) {
        boolean hadError = false;
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Slog.e(TAG, func + " failed: error " + error, new Throwable());
            hadError = true;
        }
        return hadError;
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("Electron Beam State:");
        pw.println("  mPrepared=" + mPrepared);
        pw.println("  mCreatedResources=" + mCreatedResources);
        pw.println("  mMode=" + mMode);
        pw.println("  mDisplayLayerStack=" + mDisplayLayerStack);
        pw.println("  mDisplayWidth=" + mDisplayWidth);
        pw.println("  mDisplayHeight=" + mDisplayHeight);
        pw.println("  mSwapAxes=" + mSwapAxes);
        pw.println("  mSurfaceVisible=" + mSurfaceVisible);
        pw.println("  mSurfaceAlpha=" + mSurfaceAlpha);
    }

    /**
     * Keeps a surface aligned with the natural orientation of the device.
     * Updates the position and transformation of the matrix whenever the display
     * is rotated.  This is a little tricky because the display transaction
     * callback can be invoked on any thread, not necessarily the thread that
     * owns the electron beam.
     */
    private static final class NaturalSurfaceLayout implements DisplayTransactionListener {
        private final DisplayManagerInternal mDisplayManagerInternal;
        private final int mDisplayId;
        private SurfaceControl mSurfaceControl;

        public NaturalSurfaceLayout(DisplayManagerInternal displayManagerInternal,
                int displayId, SurfaceControl surfaceControl) {
            mDisplayManagerInternal = displayManagerInternal;
            mDisplayId = displayId;
            mSurfaceControl = surfaceControl;
            mDisplayManagerInternal.registerDisplayTransactionListener(this);
        }

        public void dispose() {
            synchronized (this) {
                mSurfaceControl = null;
            }
            mDisplayManagerInternal.unregisterDisplayTransactionListener(this);
        }

        @Override
        public void onDisplayTransaction(Transaction t) {
            synchronized (this) {
                if (mSurfaceControl == null) {
                    return;
                }

                DisplayInfo displayInfo = mDisplayManagerInternal.getDisplayInfo(mDisplayId);
                if (displayInfo == null) {
                    // displayInfo can be null if the associated display has been removed. There
                    // is a delay between the display being removed and ElectronBeam being
                    // dismissed.
                    return;
                }

                switch (displayInfo.rotation) {
                    case Surface.ROTATION_0:
                        t.setPosition(mSurfaceControl, 0, 0);
                        t.setMatrix(mSurfaceControl, 1, 0, 0, 1);
                        break;
                    case Surface.ROTATION_90:
                        t.setPosition(mSurfaceControl, 0, displayInfo.logicalHeight);
                        t.setMatrix(mSurfaceControl, 0, -1, 1, 0);
                        break;
                    case Surface.ROTATION_180:
                        t.setPosition(mSurfaceControl, displayInfo.logicalWidth,
                                displayInfo.logicalHeight);
                        t.setMatrix(mSurfaceControl, -1, 0, 0, -1);
                        break;
                    case Surface.ROTATION_270:
                        t.setPosition(mSurfaceControl, displayInfo.logicalWidth, 0);
                        t.setMatrix(mSurfaceControl, 0, 1, -1, 0);
                        break;
                }
            }
        }
    }
}
