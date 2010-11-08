/*
 * Copyright (c) 2008-2010, Matthias Mann
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     * Redistributions of source code must retain the above copyright notice,
 *       this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of Matthias Mann nor the names of its contributors may
 *       be used to endorse or promote products derived from this software
 *       without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package de.matthiasmann.jpegdecoder;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 *
 * @author Matthias Mann
 */
public class Jpeg {

    enum SCAN {
        LOAD,
        TYPE,
        HEADER
    }
    
    static final int MARKER_NONE = 0xFF;
    
    final InputStream is;
    final byte[] inputBuffer;
    int inputBufferPos;
    int inputBufferValid;

    final IDCT_2D idct2D;
    final short[] data;
    final Huffman[] huffDC;
    final Huffman[] huffAC;
    final short[][] dequant;

    Component[] components;
    int[] order;
    
    int codeBuffer;
    int codeBits;
    int marker = MARKER_NONE;
    int restartInterval;
    int todo;
    int mcuX;
    int mcuY;
    int imgX;
    int imgY;
    int imgHMax;
    int imgVMax;

    boolean nomore;

    public Jpeg(InputStream is) {
        this.is = is;
        this.inputBuffer = new byte[4096];
        
        this.idct2D = new IDCT_2D();
        this.data = new short[64];
        this.huffDC = new Huffman[4];
        this.huffAC = new Huffman[4];
        this.dequant = new short[4][64];
    }

    void fetch() throws IOException {
        inputBufferValid = 0;   // in case of exception
        inputBufferPos = 0;

        inputBufferValid = is.read(inputBuffer);

        if(inputBufferValid <= 0) {
            inputBufferValid = 0;
            throw new EOFException();
        }
    }

    void read(byte[] buf, int off, int len) throws IOException {
        while(len > 0) {
            int avail = inputBufferValid - inputBufferPos;
            if(avail == 0) {
                fetch();
                continue;
            }
            int copy = (avail > len) ? len : avail;
            System.arraycopy(inputBuffer, inputBufferPos, buf, off, len);
            off += copy;
            len -= copy;
            inputBufferPos += copy;
        }
    }

    int getU8() throws IOException {
        if(inputBufferPos == inputBufferValid) {
            fetch();
        }
        return inputBuffer[inputBufferPos++] & 255;
    }

    int getU16() throws IOException {
        int t = getU8();
        return (t << 8) | getU8();
    }

    void skip(int amount) throws IOException {
        while(amount > 0) {
            int inputBufferRemaining = inputBufferValid - inputBufferPos;
            if(amount > inputBufferRemaining) {
                amount -= inputBufferRemaining;
                fetch();
            } else {
                inputBufferPos += amount;
                return;
            }
        }
    }

    void growBufferCheckMarker() throws IOException {
        int c = getU8();
        if(c != 0) {
            marker = c;
            nomore = true;
        }
    }
    
    void growBufferUnsafe() throws IOException {
        do {
            int b = 0;
            if(!nomore) {
                b = getU8();
                if(b == 0xff) {
                    growBufferCheckMarker();
                }
            }
            codeBuffer |= b << (24 - codeBits);
            codeBits   += 8;
        } while(codeBits <= 24);
    }

    int decode(Huffman h) throws IOException {
        if(codeBits < 16) {
            growBufferUnsafe();
        }
        int c = (codeBuffer >>> (32 - Huffman.FAST_BITS)) & Huffman.FAST_MASK;
        int k = h.fast[c] & 255;
        if(k < 0xFF) {
            int s = h.size[k];
            if(s > codeBits) {
                return -1;
            }
            codeBuffer <<= s;
            codeBits    -= s;
            return h.values[k] & 255;
        }

        int temp = codeBuffer >>> 16;
        k = Huffman.FAST_BITS + 1;

        while(temp >= h.maxCode[k]) {
            k++;
        }

        if(k == 17) {
            codeBits -= 16;
            return -1;
        }

        if(k > codeBits) {
            return -1;
        }

        c = ((codeBuffer >>> (32 - k)) & ((1 << k) - 1)) + h.delta[k];
        codeBuffer <<= k;
        codeBits    -= k;

        return h.values[c] & 255;
    }

    int extendReceive(int n) throws IOException {
        if(codeBits < n) {
            growBufferUnsafe();
        }

        int k = (codeBuffer >>> (32 - n)) & ((1 << n) - 1);
        codeBuffer <<= n;
        codeBits    -= n;

        if(k < (1 << (n-1))) {
            return (-1 << n) + k + 1;
        }
        return k;
    }

    static final byte dezigzag[] = {
        0,  1,  8, 16,  9,  2,  3, 10,
       17, 24, 32, 25, 18, 11,  4,  5,
       12, 19, 26, 33, 40, 48, 41, 34,
       27, 20, 13,  6,  7, 14, 21, 28,
       35, 42, 49, 56, 57, 50, 43, 36,
       29, 22, 15, 23, 30, 37, 44, 51,
       58, 59, 52, 45, 38, 31, 39, 46,
       53, 60, 61, 54, 47, 55, 62, 63,
       // let corrupt input sample past end
       63, 63, 63, 63, 63, 63, 63, 63,
       63, 63, 63, 63, 63, 63, 63
    };

    void decodeBlock(short[] data, Component c) throws IOException {
        Arrays.fill(data, (short)0);

        int t = decode(huffDC[c.hd]);
        if(t < 0) {
            throwBadHuffmanCode();
        }

        int dc = c.dcPred;
        if(t > 0) {
            dc += extendReceive(t);
            c.dcPred = dc;
        }

        final Huffman hac = huffAC[c.ha];
        
        data[0] = (short)dc;
        int k = 1;
        do {
            int rs = decode(hac);
            if(rs < 0) {
                throwBadHuffmanCode();
            }
            int s = rs & 15;
            if(s != 0) {
                k += rs >> 4;
                data[dezigzag[k++]] = (short)extendReceive(s);
            } else {
                k += 16;
                if(rs != 0xF0) {
                    break;
                }
            }
        } while(k < 64);
    }

    private static void throwBadHuffmanCode() throws IOException {
        throw new IOException("Bad huffman code");
    }

    int getMarker() throws IOException {
        int m = marker;
        if(m != MARKER_NONE) {
            marker = MARKER_NONE;
            return m;
        }
        m = getU8();
        if(m != 0xFF) {
            return MARKER_NONE;
        }
        do {
            m = getU8();
        }while(m == 0xFF);
        return m;
    }

    static boolean isRestart(int marker) {
        return marker >= 0xD0 && marker <= 0xD7;
    }
    
    void reset() {
        codeBits = 0;
        codeBuffer = 0;
        nomore = false;
        marker = MARKER_NONE;

        if(restartInterval != 0) {
            todo = restartInterval;
        } else {
            todo = Integer.MAX_VALUE;
        }

        for(Component c : components) {
            c.dcPred = 0;
        }
    }

    boolean checkRestart() throws IOException {
        if(codeBits < 24) {
            growBufferUnsafe();
        }
        if(!isRestart(marker)) {
            return false;
        }
        reset();
        return true;
    }

    void parseEntropyCodedData() throws IOException {
        reset();
        if(order.length == 1) {
            int n = order[0];
            Component c = components[n];
            int w = (c.x + 7) >> 3;
            int h = (c.y + 7) >> 3;

            for(int j=0 ; j<h ; j++) {
                int outPos = c.outPos + c.outStride*j*8;
                for(int i=0 ; i<w ; i++,outPos+=8) {
                    decodeBlock(data, c);
                    idct2D.compute(c.out, outPos, c.outStride, data, dequant[c.tq]);
                    if(--todo <= 0) {
                        if(!checkRestart()) {
                            return;
                        }
                    }
                }
            }
        } else {
            for(int j=0 ; j<mcuY ; j++) {
                for(int i=0 ; i<mcuX ; i++) {
                    for(int k=0 ; k<order.length ; k++) {
                        int n = order[k];
                        Component c = components[n];
                        int y2 = j*c.v*8;
                        
                        for(int y=0 ; y<c.v ; y++,y2+=8) {
                            int outPos = c.outPos + i*c.h*8 + y2*c.outStride;
                            for(int x=0 ; x<c.h ; x++,outPos+=8) {
                                decodeBlock(data, c);
                                idct2D.compute(c.out, outPos, c.outStride, data, dequant[c.tq]);
                            }
                        }
                    }
                    if(--todo <= 0) {
                        if(!checkRestart()) {
                            return;
                        }
                    }
                }
            }
        }
    }

    boolean processMarker(int marker) throws IOException {
        switch(marker) {
            case MARKER_NONE:
                throw new IOException("Expected marker");

            case 0xC2:      // SOF - progressive
                throw new IOException("Progressive JPEG not supported");

            case 0xDD:      // DRI - specify restart interval
                if(getU16() != 4) {
                    throw new IOException("bad DRI length");
                }
                restartInterval = getU16();
                return true;

            case 0xDB: {    // DQT - define dequant table
                int l = getU16() - 2;
                while(l > 0) {
                    int q = getU8();
                    int p = q >> 4;
                    int t = q & 15;
                    if(p != 0) {
                        throw new IOException("bad DQT type");
                    }
                    if(t > 3) {
                        throw new IOException("bad DQT table");
                    }
                    for(int i=0 ; i<64 ; i++) {
                        dequant[t][dezigzag[i]] = (short)getU8();
                    }
                    l -= 65;
                }
                return l == 0;
            }

            case 0xC4: {    // DHT - define huffman table
                int l = getU16() - 2;
                while(l > 0) {
                    int q = getU8();
                    int tc = q >> 4;
                    int th = q & 15;
                    if(tc > 1 || th > 3) {
                        throw new IOException("bad DHT header");
                    }
                    int[] tmp = idct2D.tmp2D;   // reuse memory
                    for(int i=0 ; i<16 ; i++) {
                        tmp[i] = getU8();
                    }
                    Huffman h = new Huffman(tmp);
                    int m = h.values.length;
                    l -= 17 + m;
                    read(h.values, 0, m);
                    if(tc == 0) {
                        huffDC[th] = h;
                    } else {
                        huffAC[th] = h;
                    }
                }
                return l == 0;
            }

            default:
                if((marker >= 0xE0 && marker <= 0xEF) || marker == 0xFE) {
                    skip(getU16() - 2);
                    return true;
                }
                return false;
        }
    }

    boolean processScanHeader() throws IOException {
        int ls = getU16();
        int scanN = getU8();

        if(scanN < 1 || scanN > 4) {
            throw new IOException("bad SOS component count");
        }
        if(ls != 6+2*scanN) {
            throw new IOException("bad SOS length");
        }

        order = new int[scanN];
        for(int i=0 ; i<scanN ; i++) {
            int id = getU8();
            int q = getU8();
            int n;
            for(n=components.length ; n-->0 ;) {
                if(components[n].id == id) {
                    break;
                }
            }
            if(n < 0) {
                return false;
            }
            components[n].hd = q >> 4;
            components[n].ha = q & 15;
            if(components[n].hd > 3 || components[n].ha > 3) {
                throw new IOException("bad huffman table index");
            }
            order[i] = n;
        }
        
        if(getU8() != 0) {
            throw new IOException("bad SOS");
        }
        getU8();
        if(getU8() != 0) {
            throw new IOException("bad SOS");
        }

        return true;
    }

    boolean processFrameHeader(SCAN scan) throws IOException {
        int lf = getU16();
        if(lf < 11) {
            throw new IOException("bad SOF length");
        }

        if(getU8() != 8) {
            throw new IOException("only 8 bit JPEG supported");
        }

        imgY = getU16();
        imgX = getU16();

        if(imgX <= 0 || imgY <= 0) {
            throw new IOException("Invalid image size");
        }

        int numComps = getU8();
        if(numComps != 3 && numComps != 1) {
            throw new IOException("bad component count");
        }

        if(lf != 8+3*numComps) {
            throw new IOException("bad SOF length");
        }

        components = new Component[numComps];
        for(int i=0 ; i<numComps ; i++) {
            Component c = new Component(getU8());
            int q = getU8();
            c.h = q >> 4;
            c.v = q & 15;
            c.tq = getU8();

            if(c.h == 0 || c.h > 4) {
                throw new IOException("bad H");
            }
            if(c.v == 0 || c.v > 4) {
                throw new IOException("bad V");
            }
            if(c.tq > 3) {
                throw new IOException("bad TQ");
            }

            components[i] = c;
        }

        if(scan != SCAN.LOAD) {
            return true;
        }

        int hMax = 1;
        int vMax = 1;

        for(int i=0 ; i<numComps ; i++) {
            hMax = Math.max(hMax, components[i].h);
            vMax = Math.max(vMax, components[i].v);
        }

        int mcuW = hMax * 8;
        int mcuH = vMax * 8;

        imgHMax = hMax;
        imgVMax = vMax;
        mcuX = (imgX + mcuW - 1) / mcuW;
        mcuY = (imgY + mcuH - 1) / mcuH;

        for(int i=0 ; i<numComps ; i++) {
            Component c = components[i];
            c.x = (imgX * c.h + hMax - 1) / hMax;
            c.y = (imgY * c.v + vMax - 1) / vMax;
            c.outStride = mcuX * c.h * 8;
            c.out = ByteBuffer.allocateDirect(c.outStride * mcuY * c.v * 8);
        }

        return true;
    }

    boolean decodeJpegHeader(SCAN scan) throws IOException {
        marker = MARKER_NONE;
        int m = getMarker();
        if(m != 0xD8) {
            throw new IOException("no SOI");
        }
        if(scan == SCAN.TYPE) {
            return true;
        }
        m = getMarker();
        while(m != 0xC0 && m != 0xC1) { // SOF
            if(!processMarker(m)) {
                return false;
            }
            m = getMarker();
            while(m == MARKER_NONE) {
                m = getMarker();
            }
        }
        return processFrameHeader(scan);
    }

    boolean decodeJpegImage() throws IOException {
        restartInterval = 0;
        if(!decodeJpegHeader(SCAN.LOAD)) {
            return false;
        }
        int m = getMarker();
        while(m != 0xD9) {  // DOI
            if(m == 0xDA) { // SOS
                if(!processScanHeader()) {
                    return false;
                }
                parseEntropyCodedData();
                if(marker == MARKER_NONE) {
                    try {
                        for(;;) {
                            int x = getU8();
                            if(x == 0xFF) {
                                marker = getU8();
                                break;
                            }
                            if(x != 0) {
                                return false;
                            }
                        }
                    } catch (EOFException ex) {
                    }
                }
            } else if(!processMarker(m)) {
                return false;
            }
            m = getMarker();
        }
        return true;
    }
}
