/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package de.matthiasmann.jpegdecoder;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 *
 * @author Matthias Mann
 */
public class Main {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        Jpeg jpeg = new Jpeg(new FileInputStream("e:\\upgrades.jpg"));
        try {
            jpeg.decodeJpegImage();
        } catch(IOException ex) {
            ex.printStackTrace();
        }

        for(Component c : jpeg.components) {
            FileOutputStream fos = new FileOutputStream("bla"+c.id+".tga");
            fos.write(new byte[] {
                0, 0, 3,
                0, 0, 0, 0, 32,
                0, 0, 0, 0,
                (byte)(c.outStride),
                (byte)(c.outStride >> 8),
                (byte)(c.y),
                (byte)(c.y >> 8),
                8, 32
            });
            fos.getChannel().write(c.out);
            fos.close();
        }
    }

}
