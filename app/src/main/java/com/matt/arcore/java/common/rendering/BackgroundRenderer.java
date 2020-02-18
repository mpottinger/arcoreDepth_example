/*
 * Copyright 2017 Google Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.matt.arcore.java.common.rendering;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.opengl.GLES11Ext;
import android.opengl.GLES32;
import android.util.Log;

import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.matt.arcore.java.sharedcamera_example.SharedCameraActivity;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * This class renders the AR background from camera feed. It creates and hosts the texture given to
 * ARCore to be filled with the camera image.
 */
public class BackgroundRenderer {

  private SharedCameraActivity parentActivity;
  private static final String TAG = BackgroundRenderer.class.getSimpleName();

  // Shader names.
  private static final String VERTEX_SHADER_NAME = "shaders/screenquad.vert";
  private static final String FRAGMENT_SHADER_NAME = "shaders/screenquad.frag";

  private static final int COORDS_PER_VERTEX = 2;
  private static final int TEXCOORDS_PER_VERTEX = 2;
  private static final int FLOAT_SIZE = 4;

  private FloatBuffer quadCoords;
  private FloatBuffer quadTexCoords;

  private int quadProgram;

  // uniforms for cropping/scaling depth data.
  private int u_Depth_y_offset;
  private int u_Depth_x_scale_factor;
  private int u_Depth_y_scale_factor;

  private int quadPositionParam;
  private int quadTexCoordParam;
  
  private int u_vizMode;
  private int u_DepthThresh;
  private int u_ScreenResolution;

  private int frameCount;

  private int cameraTextureId = -1;
  private int depthTextureId = -1;

  public int getCameraTextureId() {
    return cameraTextureId;
  }

  public BackgroundRenderer(SharedCameraActivity parent) {
    super();
    this.parentActivity = parent;
  }

  /**
   * Allocates and initializes OpenGL resources needed by the background renderer. Must be called on
   * the OpenGL thread, typically in {link GLSurfaceView.Renderer#onSurfaceCreated(GL10,
   * EGLConfig)}.
   *
   * @param assetManager Needed to access shader source.
   */

  public void createOnGlThread(AssetManager assetManager) throws IOException {
    int textures[];
    String glslVer = GLES32.glGetString(GLES32.GL_SHADING_LANGUAGE_VERSION);
    Log.v(TAG + "graphics:", "supported GLSL: " + glslVer);

    // ************** QUAD COORDINATES for background image **********************************
    int numVertices = 4;
    if (numVertices != QUAD_COORDS.length / COORDS_PER_VERTEX) {
      throw new RuntimeException("Unexpected number of vertices in BackgroundRenderer.");
    }

    ByteBuffer bbCoords = ByteBuffer.allocateDirect(QUAD_COORDS.length * FLOAT_SIZE);
    bbCoords.order(ByteOrder.nativeOrder());
    quadCoords = bbCoords.asFloatBuffer();
    quadCoords.put(QUAD_COORDS);
    quadCoords.position(0);

    ByteBuffer bbTexCoordsTransformed =
            ByteBuffer.allocateDirect(numVertices * TEXCOORDS_PER_VERTEX * FLOAT_SIZE);
    bbTexCoordsTransformed.order(ByteOrder.nativeOrder());
    quadTexCoords = bbTexCoordsTransformed.asFloatBuffer();


    // Generate the external camera OES texture. ( not used for rendering right now, using CPU image instead )
    ShaderUtil.checkGLError(TAG, "Before OES camera texture creation");
    GLES32.glActiveTexture(GLES32.GL_TEXTURE0);
    textures = new int[1];
    GLES32.glGenTextures(1, textures, 0);
    cameraTextureId = textures[0];
    GLES32.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
    GLES32.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES32.GL_TEXTURE_WRAP_S, GLES32.GL_CLAMP_TO_EDGE);
    GLES32.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES32.GL_TEXTURE_WRAP_T, GLES32.GL_CLAMP_TO_EDGE);
    GLES32.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_NEAREST);
    GLES32.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_NEAREST);
    ShaderUtil.checkGLError(TAG, "After OES camera texture creation");


    // Generate the Depth  texture.
    GLES32.glActiveTexture(GLES32.GL_TEXTURE1);
    textures = new int[1];
    GLES32.glGenTextures(1, textures, 0);
    depthTextureId = textures[0];
    GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, depthTextureId);
    GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_S, GLES32.GL_CLAMP_TO_EDGE);
    GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_T, GLES32.GL_CLAMP_TO_EDGE);
    GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_NEAREST);
    GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_NEAREST);
    ShaderUtil.checkGLError(TAG, "depth texture creation");

    // ******************* Load shader program
    int vertexShader = ShaderUtil.loadGLShader(TAG, assetManager, GLES32.GL_VERTEX_SHADER, VERTEX_SHADER_NAME);
    int fragmentShader = ShaderUtil.loadGLShader(TAG, assetManager, GLES32.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_NAME);

    quadProgram = GLES32.glCreateProgram();
    GLES32.glAttachShader(quadProgram, vertexShader);
    GLES32.glAttachShader(quadProgram, fragmentShader);
    GLES32.glLinkProgram(quadProgram);
    GLES32.glUseProgram(quadProgram);
    ShaderUtil.checkProgramLinkStatus(TAG, "quadProgram", quadProgram);
    ShaderUtil.checkGLError(TAG, "Program creation");

    quadPositionParam = GLES32.glGetAttribLocation(quadProgram, "a_Position");
    quadTexCoordParam = GLES32.glGetAttribLocation(quadProgram, "a_TexCoord");
    ShaderUtil.checkGLError(TAG, "vertex Program parameters");

    u_DepthThresh = GLES32.glGetUniformLocation(quadProgram, "u_DepthThresh");
    u_vizMode = GLES32.glGetUniformLocation(quadProgram, "u_vizMode");
    u_ScreenResolution = GLES32.glGetUniformLocation(quadProgram, "u_ScreenResolution");

    u_Depth_y_offset = GLES32.glGetUniformLocation(quadProgram, "u_Depth_y_offset");
    u_Depth_x_scale_factor = GLES32.glGetUniformLocation(quadProgram, "u_Depth_x_scale_factor");
    u_Depth_y_scale_factor = GLES32.glGetUniformLocation(quadProgram, "u_Depth_y_scale_factor");

    ShaderUtil.checkGLError(TAG, "frag Program parameters");

  }

  /**
   * Draws the AR background image.
   * @param frame The current {@code Frame} as returned by {link Session#update()}.
  **/

  public void draw(Frame frame, int vizMode, int depth_thresh) {

    ShaderUtil.checkGLError(TAG, "before draw");
    if(frame==null){ return ; }


    if (frame.hasDisplayGeometryChanged() | frameCount == 0) {
      frame.transformCoordinates2d(
              Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
              quadCoords,
              Coordinates2d.TEXTURE_NORMALIZED,
              quadTexCoords);
    }

    if (frame.getTimestamp() == 0) {
      // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
      // drawing possible leftover data from previous sessions if the texture is reused.
      return;
    }

    frameCount++;
    GLES32.glDepthMask(false);
    GLES32.glDisable(GLES32.GL_DEPTH_TEST);


    // ########### DEPTH TEXTURE UPLOAD
    GLES32.glActiveTexture(GLES32.GL_TEXTURE1);
    GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, depthTextureId);
    ShaderUtil.checkGLError(TAG, "before upload");
    GLES32.glTexImage2D(GLES32.GL_TEXTURE_2D,0,GLES32.GL_R16UI,parentActivity.TOFImageReader.WIDTH,
            parentActivity.TOFImageReader.HEIGHT,0,GLES32.GL_RED_INTEGER,
            GLES32.GL_UNSIGNED_SHORT, parentActivity.TOFImageReader.depth16_raw);
    ShaderUtil.checkGLError(TAG, "after upload");

    Log.v(TAG, "depth width: " + parentActivity.TOFImageReader.WIDTH + " height: " + parentActivity.TOFImageReader.HEIGHT);
    // ***************************************

    // *************************************BEGIN Shader program input variables *************************************************
    GLES32.glUseProgram(quadProgram);
    ShaderUtil.checkGLError(TAG, "before set up variables");
    GLES32.glVertexAttribPointer(quadPositionParam, COORDS_PER_VERTEX, GLES32.GL_FLOAT, false, 0, quadCoords);
    GLES32.glVertexAttribPointer(quadTexCoordParam, TEXCOORDS_PER_VERTEX, GLES32.GL_FLOAT, false, 0, quadTexCoords);
    GLES32.glEnableVertexAttribArray(quadPositionParam);
    GLES32.glEnableVertexAttribArray(quadTexCoordParam);

    GLES32.glUniform1f(u_vizMode, (float) vizMode);
    GLES32.glUniform1f(u_DepthThresh, (depth_thresh / 100.0f));
    GLES32.glUniform2f(u_ScreenResolution, parentActivity.screenResolution.x, parentActivity.screenResolution.y);

    float landscape_aspect = parentActivity.screenResolution.y / parentActivity.screenResolution.x;
    float new_width = parentActivity.TOFImageReader.WIDTH;
    float new_height = landscape_aspect * new_width;
    float y_offset = (parentActivity.TOFImageReader.HEIGHT - new_height) / 2.0f;
    float x_scale_factor = new_width / parentActivity.screenResolution.x;
    float y_scale_factor = new_height / parentActivity.screenResolution.y;

    GLES32.glUniform1f(u_Depth_y_offset, y_offset);
    GLES32.glUniform1f(u_Depth_x_scale_factor, x_scale_factor);
    GLES32.glUniform1f(u_Depth_y_scale_factor, y_scale_factor);

    ShaderUtil.checkGLError(TAG, "after set up variables");
    // ************************************* END Shader program input variables *************************************************

    //***********************************************
    // run the shader
    ShaderUtil.checkGLError(TAG, "before run shader");
    GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0, 4);
    ShaderUtil.checkGLError(TAG, "after run shader");
    //***********************************************

    GLES32.glDisableVertexAttribArray(quadPositionParam);
    GLES32.glDisableVertexAttribArray(quadTexCoordParam);

    GLES32.glDepthMask(true);
    GLES32.glEnable(GLES32.GL_DEPTH_TEST);

    ShaderUtil.checkGLError(TAG, "after draw");

  }


  public static Bitmap readPixels(int w, int h){
    int b[]=new int[w*h];
    int bt[]=new int[w*h];
    IntBuffer ib = IntBuffer.wrap(b);
    ib.position(0);
    GLES32.glReadPixels(0, 0, w, h, GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE, ib);

    for(int i=0, k=0; i<h; i++, k++)
    {//remember, that OpenGL bitmap is incompatible with Android bitmap
      //and so, some correction need.
      for(int j=0; j<w; j++)
      {
        int pix=b[i*w+j];
        int pb=(pix>>16)&0xff;
        int pr=(pix<<16)&0x00ff0000;
        int pix1=(pix&0xff00ff00) | pr | pb;
        bt[(h-k-1)*w+j]=pix1;
      }
    }
    Bitmap sb=Bitmap.createBitmap(bt, w, h, Bitmap.Config.ARGB_8888);
    return sb;
  }

  private static final float[] QUAD_COORDS =
          new float[] {
                  -1.0f, -1.0f, -1.0f, +1.0f, +1.0f, -1.0f, +1.0f, +1.0f,
          };
  
}