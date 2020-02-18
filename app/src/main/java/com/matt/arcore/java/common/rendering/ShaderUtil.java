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

import android.content.Context;
import android.content.res.AssetManager;
import android.opengl.GLES11Ext;
import android.opengl.GLES32;
import android.opengl.GLU;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Shader helper functions.
 */
public class ShaderUtil {

    public static String matrix2str(float[] matrix){
        String matstr = "";
        for(float f: matrix){
            matstr = matstr + Float.toString(f) + " ";
        }
        return matstr;
    }



    /**
     * Converts a raw text file, saved as a resource, into an OpenGL ES shader.
     *
     * @param type     The type of shader we will be creating.
     * @param filename The filename of the asset file about to be turned into a shader.
     * @return The shader object handler.
     */

    public static int loadGLShader(String tag, AssetManager assetManager, int type, String filename)
            throws IOException {
        String code = readShaderFileFromAssets(assetManager, filename);
        int shader = GLES32.glCreateShader(type);
        GLES32.glShaderSource(shader, code);
        GLES32.glCompileShader(shader);

        // Get the compilation status.
        final int[] compileStatus = new int[1];
        GLES32.glGetShaderiv(shader, GLES32.GL_COMPILE_STATUS, compileStatus, 0);

        // If the compilation failed, delete the shader.
        if (compileStatus[0] == 0) {
            Log.e(tag, "Error compiling shader: " + GLES32.glGetShaderInfoLog(shader));
            GLES32.glDeleteShader(shader);
            shader = 0;
        }

        if (shader == 0) {
            throw new RuntimeException("Error creating shader.");
        }

        return shader;
    }

    /**
     * Checks if we've had an error inside of OpenGL ES, and if so what that error is.
     *
     * @param label Label to report in case of error.
     * @throws RuntimeException If an OpenGL error is detected.
     */
    public static void checkGLError(String tag, String label) {
        int lastError = GLES32.GL_NO_ERROR;
        // Drain the queue of all errors.
        int error;
        while ((error = GLES32.glGetError()) != GLES32.GL_NO_ERROR) {
            Log.e(tag, label + ": glError " + error + " : " + GLU.gluErrorString(error));
            lastError = error;
        }
        if (lastError != GLES32.GL_NO_ERROR) {
            throw new RuntimeException(label + ": glError " + lastError + " : " + GLU.gluErrorString(lastError));
        }
    }

    public static void checkProgramLinkStatus(String TAG, String label, int programId) {
        // check if program was successfully linked.
        int[] linkSuccessful = new int[1];
        GLES32.glGetProgramiv(programId, GLES32.GL_LINK_STATUS, linkSuccessful, 0);
        if (linkSuccessful[0] != 1) {
            Log.v(TAG, label + ": glLinkProgram failed");
            Log.e(TAG, label + ": glLinkProgram failed");
            String programLog = GLES32.glGetProgramInfoLog(programId);
            Log.v(TAG, label + ": log: " + programLog);
            Log.e(TAG, label + ": log: " + programLog);
        } else {
            Log.v(TAG, "computeProgram: glLinkProgram success");
        }
    }


    public static void printComputeShaderLimits(String TAG) {
        int[] params;
        params = new int[1];
        //The number of invocations in a single local work group (i.e., the product of the three dimensions) that may be dispatched to a compute shader
        GLES32.glGetIntegerv(GLES32.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, params, 0);
        Log.v(TAG, "compute shaders: MAX_COMPUTE_WORK_GROUP_INVOCATIONS: " + params[0]);

        params = new int[1];
        //The maximum total storage size in bytes of all variables declared as shared in all compute shaders linked into a single program object.
        GLES32.glGetIntegerv(GLES32.GL_MAX_COMPUTE_SHARED_MEMORY_SIZE, params, 0);
        Log.v(TAG, "compute shaders: MAX_COMPUTE_SHARED_MEMORY_SIZE: " + params[0]);

        params = new int[3];
        //The maximum number of work groups that may be dispatched to a compute shader. Accepted by the indexed versions of glGet. Indices 0, 1, and 2 correspond to the X, Y and Z dimensions, respectively.
        GLES32.glGetIntegeri_v(GLES32.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0, params, 0);
        GLES32.glGetIntegeri_v(GLES32.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 1, params, 1);
        GLES32.glGetIntegeri_v(GLES32.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 2, params, 2);
        Log.v(TAG, "compute shaders: MAX_COMPUTE_WORK_GROUP_COUNT: " + params[0] + "," + params[1] + "," + params[2]);

        params = new int[3];
        //The maximum size of a work groups that may be used during compilation of a compute shader. Accepted by the indexed versions of glGet. Indices 0, 1, and 2 correspond to the X, Y and Z dimensions, respectively.
        GLES32.glGetIntegeri_v(GLES32.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 0, params, 0);
        GLES32.glGetIntegeri_v(GLES32.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 1, params, 1);
        GLES32.glGetIntegeri_v(GLES32.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 2, params, 2);
        Log.v(TAG, "compute shaders: MAX_COMPUTE_WORK_GROUP_SIZE: " + params[0] + "," + params[1] + "," + params[2]);

    }

    /**
     * Converts a raw shader file into a string.
     *
     * @param filename The filename of the shader file about to be turned into a shader.
     * @return The context of the text file, or null in case of error.
     */
    private static String readShaderFileFromAssets(AssetManager assetManager, String filename)
            throws IOException {
        try (InputStream inputStream = assetManager.open(filename);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] tokens = line.split(" ", -1);
                if (tokens[0].equals("#include")) {
                    String includeFilename = tokens[1];
                    includeFilename = includeFilename.replace("\"", "");
                    if (includeFilename.equals(filename)) {
                        throw new IOException("Do not include the calling file.");
                    }
                    sb.append(readShaderFileFromAssets(assetManager, includeFilename));
                } else {
                    sb.append(line).append("\n");
                }
            }
            return sb.toString();
        }
    }

    public static int sampler2OpenGLTarget(String samplerType) {
        int textureTarget = 0;

        samplerType = samplerType.replace("image", "sampler");
        String firstChar = samplerType.substring(0,1);

        if(!firstChar.equals("s")){ samplerType = samplerType.substring(1); }

        switch (samplerType) {
            case "sampler2D":
            case "sampler2DShadow":
                textureTarget = GLES32.GL_TEXTURE_2D;
                break;
            case "sampler3D":
                textureTarget = GLES32.GL_TEXTURE_3D;
                break;
            case "samplerCube":
            case "samplerCubeShadow":
                textureTarget = GLES32.GL_TEXTURE_CUBE_MAP;
                break;
            case "sampler2DArray":
            case "sampler2DArrayShadow":
                textureTarget = GLES32.GL_TEXTURE_2D_ARRAY;
                break;
            case "samplerCubeArray":
                textureTarget = GLES32.GL_TEXTURE_CUBE_MAP_ARRAY;
                break;
            case "samplerBuffer":
                textureTarget = GLES32.GL_TEXTURE_BUFFER;
                break;
            case "sampler2DMS":
                textureTarget = GLES32.GL_TEXTURE_2D_MULTISAMPLE;
                break;
            case "sampler2DMSArray":
                textureTarget = GLES32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY;
                break;
            case "samplerCubeArrayShadow":
                textureTarget = GLES32.GL_TEXTURE_CUBE_MAP_ARRAY;
                break;
            case "samplerExternalOES":
                textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
                break;
        }

        return textureTarget;
    }
}
