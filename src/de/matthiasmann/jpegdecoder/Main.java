/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package de.matthiasmann.jpegdecoder;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 *
 * @author Matthias Mann
 */
public class Main {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        Jpeg jpeg = null;
        ByteBuffer[] buffer = null;
        int[] stride = null;

        for(int tries=0 ; tries<10 ; tries++) {
            long start = System.nanoTime();
            FileInputStream is = new FileInputStream("e:\\Saturn_Anzeige.jpg");
            try {
                jpeg = new Jpeg(is);
                try {
                    jpeg.decodeHeader();
                    if(buffer == null) {
                        int width = jpeg.getImageWidth();
                        int height = jpeg.getImageHeight();
                        System.out.println("width="+width+" height="+height);

                        int numComponents = jpeg.getNumComponents();
                        buffer = new ByteBuffer[numComponents];
                        stride = new int[numComponents];
                        for(int i=0 ; i<numComponents ; i++) {
                            Component c = jpeg.getComponent(i);
                            buffer[i] = ByteBuffer.allocateDirect(c.getMinReqWidth() * c.getMinReqHeight());
                            stride[i] = c.getMinReqWidth();
                        }
                    }
                    for(ByteBuffer bb : buffer) {
                        bb.clear();
                    }
                    jpeg.startDecode();
                    jpeg.decodeRAW(buffer, stride, jpeg.getNumMCURows());
                } catch(IOException ex) {
                    ex.printStackTrace();
                }
            } finally {
                is.close();
            }
            long end = System.nanoTime();
            System.out.println("Load in " + (end - start + 999)/1000 + " us");
        }

        for(int i=0 ; i<jpeg.getNumComponents() ; i++) {
            Component c = jpeg.getComponent(i);
            FileOutputStream fos = new FileOutputStream("bla"+c.id+".tga");
            fos.write(new byte[] {
                0, 0, 3,
                0, 0, 0, 0, 32,
                0, 0, 0, 0,
                (byte)(c.getMinReqWidth()),
                (byte)(c.getMinReqWidth() >> 8),
                (byte)(c.getHeight()),
                (byte)(c.getHeight() >> 8),
                8, 32
            });
            buffer[i].flip();
            fos.getChannel().write(buffer[i]);
            fos.close();
        }
    }

}
