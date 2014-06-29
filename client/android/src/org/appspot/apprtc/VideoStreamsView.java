/*
 * libjingle
 * Copyright 2013, Google Inc.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *  3. The name of the author may not be used to endorse or promote products
 *     derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
 * EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.appspot.apprtc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.Rect;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.util.AttributeSet;
import android.util.Log;

import org.webrtc.VideoRenderer.I420Frame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.EnumMap;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * A GLSurfaceView{,.Renderer} that efficiently renders YUV frames from local &
 * remote VideoTracks using the GPU for CSC.  Clients will want to call the
 * constructor, setSize() and updateFrame() as appropriate, but none of the
 * other public methods of this class are of interest to clients (only to system
 * classes).
 */
public class VideoStreamsView
    extends GLSurfaceView
    implements GLSurfaceView.Renderer {

  /** Identify which of the two video streams is being addressed. */
  public static enum Endpoint { LOCAL, REMOTE };

  private final static String TAG = "VideoStreamsView";
  private EnumMap<Endpoint, Rect> rects =
      new EnumMap<Endpoint, Rect>(Endpoint.class);
  private Point screenDimensions;
  // [0] are local Y,U,V, [1] are remote Y,U,V.
  private int[][] yuvTextures = { { -1, -1, -1}, {-1, -1, -1 }};
  private int edgeTexture[] = {-1};
  private int posLocation = -1;
  private long lastFPSLogTime = System.nanoTime();
  private long numFramesSinceLastLog = 0;
  private Context m_context;
  private FramePool framePool = new FramePool();
  // Accessed on multiple threads!  Must be synchronized.
  private EnumMap<Endpoint, I420Frame> framesToRender =
      new EnumMap<Endpoint, I420Frame>(Endpoint.class);

  public VideoStreamsView(Context c, Point screenDimensions) {
    super(c);
    this.screenDimensions = screenDimensions;
    m_context = c;
    setPreserveEGLContextOnPause(true);
    setEGLContextClientVersion(2);
    setRenderer(this);
    setRenderMode(RENDERMODE_WHEN_DIRTY);
  }
  
	public VideoStreamsView(Context context, AttributeSet attrs) {
		super(context, attrs);
		 m_context = context;
	    setPreserveEGLContextOnPause(true);
	    setEGLContextClientVersion(2);
	    setRenderer(this);
	    setRenderMode(RENDERMODE_WHEN_DIRTY);
		// TODO Auto-generated constructor stu;
	}

  /** Queue |frame| to be uploaded. */
  public void queueFrame(final Endpoint stream, I420Frame frame) {
    // Paying for the copy of the YUV data here allows CSC and painting time
    // to get spent on the render thread instead of the UI thread.
    abortUnless(framePool.validateDimensions(frame), "Frame too large!");
    final I420Frame frameCopy = framePool.takeFrame(frame).copyFrom(frame);
    boolean needToScheduleRender;
    synchronized (framesToRender) {
      // A new render needs to be scheduled (via updateFrames()) iff there isn't
      // already a render scheduled, which is true iff framesToRender is empty.
      needToScheduleRender = framesToRender.isEmpty();
      I420Frame frameToDrop = framesToRender.put(stream, frameCopy);
      if (frameToDrop != null) {
        framePool.returnFrame(frameToDrop);
      }
    }
    if (needToScheduleRender) {
      queueEvent(new Runnable() {
          public void run() {
            updateFrames();
          }
        });
    }
  }

  // Upload the planes from |framesToRender| to the textures owned by this View.
  private void updateFrames() {
    I420Frame localFrame = null;
    I420Frame remoteFrame = null;
    synchronized (framesToRender) {
      localFrame = framesToRender.remove(Endpoint.LOCAL);
      remoteFrame = framesToRender.remove(Endpoint.REMOTE);
    }
    if (localFrame != null) {
      texImage2D(localFrame, yuvTextures[0]);
      framePool.returnFrame(localFrame);
    }
    if (remoteFrame != null) {
      texImage2D(remoteFrame, yuvTextures[1]);
      framePool.returnFrame(remoteFrame);
    }
    abortUnless(localFrame != null || remoteFrame != null,
                "Nothing to render!");
    requestRender();
  }

  /** Inform this View of the dimensions of frames coming from |stream|. */
  public void setSize(Endpoint stream, int width, int height) {
    // Generate 3 texture ids for Y/U/V and place them into |textures|,
    // allocating enough storage for |width|x|height| pixels.
    int[] textures = yuvTextures[stream == Endpoint.LOCAL ? 0 : 1];
    GLES20.glGenTextures(3, textures, 0);
    for (int i = 0; i < 3; ++i) {
      int w = i == 0 ? width : width / 2;
      int h = i == 0 ? height : height / 2;
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0+i);
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i]);
      GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, w, h, 0,
          GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null);
      GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
          GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
      GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
          GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
      GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
          GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
      GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
          GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }
  }

//  @Override
//  protected void onMeasure(int unusedX, int unusedY) {
//    // Go big or go home!
//    setMeasuredDimension(screenDimensions.x, screenDimensions.y);
//  }

  @Override
  public void onSurfaceChanged(GL10 unused, int width, int height) {
    GLES20.glViewport(0, 0, width, height);    
    checkNoGLES2Error();
  }

  @Override
  public void onDrawFrame(GL10 unused) {
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    drawRectangle(yuvTextures[1], remoteVertices);
    drawRectangle(yuvTextures[0], localVertices);
    drawLocalBorder();
    ++numFramesSinceLastLog;
    long now = System.nanoTime();
    if (lastFPSLogTime == -1 || now - lastFPSLogTime > 1e9) {
      double fps = numFramesSinceLastLog / ((now - lastFPSLogTime) / 1e9);
      Log.d(TAG, "Rendered FPS: " + fps);
      lastFPSLogTime = now;
      numFramesSinceLastLog = 1;
    }
    checkNoGLES2Error();
  }

  @Override
  public void onSurfaceCreated(GL10 unused, EGLConfig config) {
    program = GLES20.glCreateProgram();
    verticesShader = addShaderTo(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_STRING, program);
    fragmentShader = addShaderTo(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_STRING, program);
    //edgeShader = addShaderTo(GLES20.GL_FRAGMENT_SHADER,EDGE_SHADER_STRING, program);

    GLES20.glLinkProgram(program);
    int[] result = new int[] { GLES20.GL_FALSE };
    result[0] = GLES20.GL_FALSE;
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, result, 0);
    abortUnless(result[0] == GLES20.GL_TRUE,
        GLES20.glGetProgramInfoLog(program));
    GLES20.glUseProgram(program);

    GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "y_tex"), 0);
    GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "u_tex"), 1);
    GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "v_tex"), 2);

    // Actually set in drawRectangle(), but queried only once here.
    posLocation = GLES20.glGetAttribLocation(program, "in_pos");

    int tcLocation = GLES20.glGetAttribLocation(program, "in_tc");
    GLES20.glEnableVertexAttribArray(tcLocation);
    GLES20.glVertexAttribPointer(
        tcLocation, 2, GLES20.GL_FLOAT, false, 0, textureCoords);
    
    //GLES20.glc
 // 获取指向fragment shader的成员vColor的handle   
 //   color_handle = GLES20.glGetUniformLocation(program, "vColor");  

    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    
    m_edgeBitmap = BitmapFactory.decodeResource(m_context.getResources(), R.drawable.image);
    int bitmapFormat = m_edgeBitmap.getConfig() == Config.ARGB_8888 ? GLES20.GL_RGBA : GLES20.GL_RGB;
    GLES20.glGenTextures(1, edgeTexture, 0);
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, edgeTexture[0]);
    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmapFormat, m_edgeBitmap, 0);
    GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
        GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
        GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
        GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
        GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);    
    checkNoGLES2Error();
  }

  // Wrap a float[] in a direct FloatBuffer using native byte order.
  private static FloatBuffer directNativeFloatBuffer(float[] array) {
    FloatBuffer buffer = ByteBuffer.allocateDirect(array.length * 4).order(
        ByteOrder.nativeOrder()).asFloatBuffer();
    buffer.put(array);
    buffer.flip();
    return buffer;
  }

  // Upload the YUV planes from |frame| to |textures|.
  private void texImage2D(I420Frame frame, int[] textures) {
    for (int i = 0; i < 3; ++i) {
      ByteBuffer plane = frame.yuvPlanes[i];
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i);
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i]);
      int w = i == 0 ? frame.width : frame.width / 2;
      int h = i == 0 ? frame.height : frame.height / 2;
      abortUnless(w == frame.yuvStrides[i], frame.yuvStrides[i] + "!=" + w);
      GLES20.glTexImage2D(
          GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, w, h, 0,
          GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, plane);
    }
    checkNoGLES2Error();
  }

  // Draw |textures| using |vertices| (X,Y coordinates).
  private void drawRectangle(int[] textures, FloatBuffer vertices) {
    for (int i = 0; i < 3; ++i) {
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i);
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i]);
    }

    GLES20.glVertexAttribPointer(
        posLocation, 2, GLES20.GL_FLOAT, false, 0, vertices);
    GLES20.glEnableVertexAttribArray(posLocation);

    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    checkNoGLES2Error();
  }
  
  private void drawLocalBorder(){
	  GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, edgeTexture[0]);
	  GLES20.glVertexAttribPointer(posLocation, 3, GLES20.GL_FLOAT, false, 12, localBorderVerticesArray);
      GLES20.glEnableVertexAttribArray(posLocation);
      //GLES20.glUniform4fv(color_handle, 1, border_color, 0); 
      GLES20.glLineWidth(6);
      //GLES20.glColorMask(red, green, blue, alpha)
      GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 4);
  }

  // Compile & attach a |type| shader specified by |source| to |program|.
  private static int addShaderTo(
      int type, String source, int program) {
    int[] result = new int[] { GLES20.GL_FALSE };
    int shader = GLES20.glCreateShader(type);
    GLES20.glShaderSource(shader, source);
    GLES20.glCompileShader(shader);
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, result, 0);
    abortUnless(result[0] == GLES20.GL_TRUE,
        GLES20.glGetShaderInfoLog(shader) + ", source: " + source);
    GLES20.glAttachShader(program, shader);
    GLES20.glDeleteShader(shader);
    checkNoGLES2Error();
    return shader;
  }
  
  private static int loadShader(int type,String source,int program){
	  int[] result = new int[] { GLES20.GL_FALSE };
	    int shader = GLES20.glCreateShader(type);
	    GLES20.glShaderSource(shader, source);
	    GLES20.glCompileShader(shader);
	    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, result, 0);
	    abortUnless(result[0] == GLES20.GL_TRUE,
	        GLES20.glGetShaderInfoLog(shader) + ", source: " + source);
	    checkNoGLES2Error();
	    return shader;
  }

  // Poor-man's assert(): die with |msg| unless |condition| is true.
  private static void abortUnless(boolean condition, String msg) {
    if (!condition) {
      throw new RuntimeException(msg);
    }
  }

  // Assert that no OpenGL ES 2.0 error has been raised.
  private static void checkNoGLES2Error() {
    int error = GLES20.glGetError();
    abortUnless(error == GLES20.GL_NO_ERROR, "GLES20 error: " + error);
  }

  // Remote image should span the full screen. //landscapte
  //private static final FloatBuffer remoteVertices = directNativeFloatBuffer(
  //    new float[] { -1, 1, -1, -1, 1, 1, 1, -1 });
  
  // Remote image should span the full screen. portrait
  private static final FloatBuffer remoteVertices = directNativeFloatBuffer(
      new float[] { -1, -1, 1, -1, -1, 1, 1, 1 });
  
  // Local image should be thumbnailish. small landscapte;
  //private static final FloatBuffer localVertices = directNativeFloatBuffer(
  //    new float[] { 0.6f, 0.9f, 0.6f, 0.6f, 0.9f, 0.9f, 0.9f, 0.6f });
  
  // Local image should be thumbnailish. landscape;
  //private static final FloatBuffer localVertices = directNativeFloatBuffer(
  //    new float[] { 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 0.0f });
  
  //portrait coordinate;quarter of the screen 
  //private static final FloatBuffer localVertices = directNativeFloatBuffer(
  //      new float[] { 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f });

  
  //portrait coordinate;actual scene; 
  private static final FloatBuffer localVertices = directNativeFloatBuffer(
        new float[] { 0.60f, 0.65f, 0.90f, 0.65f, 0.60f, 0.90f, 0.90f, 0.90f });

  
  // Texture Coordinates mapping the entire texture.
  private static final FloatBuffer textureCoords = directNativeFloatBuffer(
      new float[] { 0, 0, 0, 1, 1, 0, 1, 1 });
  
  // local border for a quarter of the screen
  /*private static final FloatBuffer localBorderVerticesArray = directNativeFloatBuffer(
	  new float[] { -0.1f, 1.0f, 0.0f,
			  -0.1f, -0.1f, 0.0f,
			  1.0f, -0.1f, 0.0f,
			  1.0f, 1.0f, 0.0f
		  }); */
  private static final FloatBuffer localBorderVerticesArray = directNativeFloatBuffer(
  new float[] { 0.60f, 0.90f, 0.0f,
		  0.60f, 0.65f, 0.0f,
		  0.90f, 0.65f, 0.0f,
		  0.90f, 0.90f, 0.0f
	  }); 
  

  private int color_handle;
  private float[] borderColor={(float)(255.0/255.0),(float)(100.0/255.0),0.0f,0.0f};
  private int program;
  private int verticesShader,fragmentShader,edgeShader;
  protected Bitmap m_edgeBitmap;

  // Pass-through vertex shader.
  private static final String VERTEX_SHADER_STRING =
      "varying vec2 interp_tc;\n" +
      "\n" +
      "attribute vec4 in_pos;\n" +
      "attribute vec2 in_tc;\n" +
      "\n" +
      "void main() {\n" +
      "  gl_Position = in_pos;\n" +
      "  interp_tc = in_tc;\n" +
      "}\n";

  // YUV to RGB pixel shader. Loads a pixel from each plane and pass through the
  // matrix.
  private static final String FRAGMENT_SHADER_STRING =
      "precision mediump float;\n" +
      "varying vec2 interp_tc;\n" +
      "\n" +
      "uniform sampler2D y_tex;\n" +
      "uniform sampler2D u_tex;\n" +
      "uniform sampler2D v_tex;\n" +
      "\n" +
      "void main() {\n" +
      "  float y = texture2D(y_tex, interp_tc).r;\n" +
      "  float u = texture2D(u_tex, interp_tc).r - .5;\n" +
      "  float v = texture2D(v_tex, interp_tc).r - .5;\n" +
      // CSC according to http://www.fourcc.org/fccyvrgb.php
      "  gl_FragColor = vec4(y + 1.403 * v, " +
      "                      y - 0.344 * u - 0.714 * v, " +
      "                      y + 1.77 * u, 1);\n" +
      "}\n";
  
  private static final String EDGE_SHADER_STRING =
    "precision mediump float;  \n" +
        "void main(){              \n" +
        " gl_FragColor = vec4 (0.63671875, 0.76953125, 0.22265625, 1.0); \n" +
        "}\n";
}
