package com.matt.arcore.java.common.helpers;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.os.Environment;
import android.util.Log;

import androidx.core.app.ActivityCompat;


import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Random;




public class MiscUtils {

    public static int frameCount = 0;

    // Return thread info data include thread id, name and priority.
    public static String getThreadInfo(Thread thread)
    {
        long threadId = thread.getId();
        String threadName = thread.getName();
        int threadPriority = thread.getPriority();

        StringBuffer buffer = new StringBuffer();
        buffer.append(" id = ");
        buffer.append(threadId);
        buffer.append(" , name = ");
        buffer.append(threadName);
        buffer.append(" , priority = ");
        buffer.append(threadPriority);

        return buffer.toString();
    }

    public static byte [] ShortToByte(short [] input)
    {
        int index;
        int iterations = input.length;

        ByteBuffer bb = ByteBuffer.allocate(input.length * 2);

        for(index = 0; index != iterations; ++index)
        {
            bb.putShort(input[index]);
        }

        return bb.array();
    }



    public static int[] IntBuftoArray(IntBuffer b) {
        if(b.hasArray()) {
            if(b.arrayOffset() == 0)
                return b.array();

            return Arrays.copyOfRange(b.array(), b.arrayOffset(), b.array().length);
        }

        b.rewind();
        int[] foo = new int[b.remaining()];
        b.get(foo);

        return foo;
    }


    // create a direct clone of a ByteBuffer
    public static ByteBuffer byteBufferCloneDirect(ByteBuffer original) {
        ByteBuffer clone = ByteBuffer.allocateDirect(original.capacity());
        original.rewind();//copy from the beginning
        clone.put(original);
        original.rewind();
        clone.flip();
        return clone;
    }

    public static FloatBuffer cloneFloatBuffer(FloatBuffer original) {
        final ByteBuffer byteClone = (original.isDirect()) ?
                //multiplying by 4 and adding 3 so the capacity is the same
                //when converted to FloatBuffer
                ByteBuffer.allocateDirect(original.capacity() *4 + 3) :
                ByteBuffer.allocate(original.capacity() * 4 + 3);

        final FloatBuffer clone = byteClone.asFloatBuffer();
        final FloatBuffer readOnlyCopy = original.asReadOnlyBuffer();

        readOnlyCopy.rewind();
        clone.put(readOnlyCopy);
        clone.position(original.position());
        clone.limit(original.limit());
        return clone;
    }


    // get storage permissions
    public static void verifyStoragePermissions(Activity activity) {
        // Check if we have write permission
        int permission = ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE);

        if (permission != PackageManager.PERMISSION_GRANTED) {
            // We don't have permission so prompt the user
            ActivityCompat.requestPermissions( activity, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE} , 2);
        }
    }




    public  static void writeStringToFile(String fileName, String data) {

        File sd = Environment.getExternalStorageDirectory();
        //File dest = new File(sd, "/");
        File dest = sd;
        dest.mkdirs();
        File file = new File (dest, fileName);
        if (file.exists ()) file.delete ();

        try {
            FileOutputStream out = new FileOutputStream(file, false);
            out.write(data.getBytes("UTF-8"));
            out.flush();
            out.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
        Log.d("writeStringToFile", " " + file.getAbsolutePath());
    }


    public  static void appendStringToFile(String fileName, String data) {

        File sd = Environment.getExternalStorageDirectory();
        //File dest = new File(sd, "/");
        File dest = sd;
        dest.mkdirs();
        File file = new File (dest, fileName);
        if (file.exists ()) file.delete ();

        try {
            FileOutputStream out = new FileOutputStream(file, true);
            out.write(data.getBytes("UTF-8"));
            out.flush();
            out.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
        //Log.d("writeStringToFile", " " + file.getAbsolutePath());
    }


    public static void deleteFiles(File folder, String regEx){
        final File[] files = folder.listFiles( new FilenameFilter() {
            @Override
            public boolean accept( final File dir,
                                   final String name ) {
                return name.matches( regEx );
            }
        } );
        if(files == null) return;

        for ( final File file : files ) {
            if ( !file.delete() ) {
                Log.e( "deleteFiles: ", "Can't remove " + file.getAbsolutePath() );
            }
        }
    }



    // get list of IP addresses for phone
    public String[] getLocalIpAddress()
    {
        ArrayList<String> addresses = new ArrayList<String>();
        try
        {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();)
            {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();)
                {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress()) {
                        addresses.add(inetAddress.getHostAddress());
                        Log.d("ip_address: ", inetAddress.getHostAddress());
                    }
                }
            }

        }
        catch (SocketException ex)
        {
            String LOG_TAG = null;
            Log.e(LOG_TAG, ex.toString());
        }
        return addresses.toArray(new String[0]);
    }

}
