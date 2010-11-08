/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package de.matthiasmann.jpegdecoder;

import java.nio.ByteBuffer;

/**
 *
 * @author Matthias Mann
 */
public class Component {

    final int id;

    int dcPred;
    Huffman huffDC;
    Huffman huffAC;
    byte[] dequant;
    int v;
    int h;
    int x;
    int y;

    ByteBuffer out;
    int outPos;
    int outStride;

    public Component(int id) {
        this.id = id;
    }
    
}
