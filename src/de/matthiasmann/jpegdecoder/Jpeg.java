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
    
    private final InputStream is;
    private final byte[] inputBuffer;
    private int inputBufferPos;
    private int inputBufferValid;
    private boolean ignoreIOerror;

    final IDCT_2D idct2D;
    final short[] data;
    final Huffman[] huffDC;
    final Huffman[] huffAC;
    final byte[][] dequant;

    Component[] components;
    Component[] order;
    
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
        this.dequant = new byte[4][64];
    }

    public boolean isIgnoreIOerror() {
        return ignoreIOerror;
    }

    public void setIgnoreIOerror(boolean ignoreIOerror) {
        this.ignoreIOerror = ignoreIOerror;
    }

    private void fetch() throws IOException {
        try {
            inputBufferPos = 0;
            inputBufferValid = is.read(inputBuffer);
            
            if(inputBufferValid <= 0) {
                throw new EOFException();
            }
        } catch (IOException ex) {
            inputBufferValid = 2;
            inputBuffer[0] = (byte)0xFF;
            inputBuffer[1] = (byte)0xD9;    // DOI

            if(!ignoreIOerror) {
                throw ex;
            }
        }
    }

    private void read(byte[] buf, int off, int len) throws IOException {
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

    private int getU8() throws IOException {
        if(inputBufferPos == inputBufferValid) {
            fetch();
        }
        return inputBuffer[inputBufferPos++] & 255;
    }

    private int getU16() throws IOException {
        int t = getU8();
        return (t << 8) | getU8();
    }

    private void skip(int amount) throws IOException {
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

    private void growBufferCheckMarker() throws IOException {
        int c = getU8();
        if(c != 0) {
            marker = c;
            nomore = true;
        }
    }
    
    private void growBufferUnsafe() throws IOException {
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

    private int decode(Huffman h) throws IOException {
        if(codeBits < 16) {
            growBufferUnsafe();
        }
        int k = h.fast[codeBuffer >>> (32 - Huffman.FAST_BITS)] & 255;
        if(k < 0xFF) {
            int s = h.size[k];
            codeBuffer <<= s;
            codeBits    -= s;
            return h.values[k] & 255;
        }
        return decodeSlow(h);
    }

    private int decodeSlow(Huffman h) throws IOException {
        int temp = codeBuffer >>> 16;
        int s = Huffman.FAST_BITS + 1;

        while(temp >= h.maxCode[s]) {
            s++;
        }

        int k = (temp >>> (16 - s)) + h.delta[s];
        codeBuffer <<= s;
        codeBits    -= s;
        return h.values[k] & 255;
    }

    private int extendReceive(int n) throws IOException {
        if(codeBits < 24) {
            growBufferUnsafe();
        }

        int k = codeBuffer >>> (32 - n);
        codeBuffer <<= n;
        codeBits    -= n;

        int limit = 1 << (n-1);
        if(k < limit) {
            k -= limit*2 - 1;
        }
        return k;
    }

    private void decodeBlock(short[] data, Component c) throws IOException {
        Arrays.fill(data, (short)0);

        {
            int t = decode(c.huffDC);
            int dc = c.dcPred;
            if(t > 0) {
                dc += extendReceive(t);
                c.dcPred = dc;
            }

            data[0] = (short)dc;
        }

        final Huffman hac = c.huffAC;
        final byte[] dq = c.dequant;

        int k = 1;
        do {
            int rs = decode(hac);
            k += rs >> 4;
            int s = rs & 15;
            if(s != 0) {
                int v = extendReceive(s) * (dq[k] & 0xFF);
                data[dezigzag[k]] = (short)v;
            } else if(rs != 0xF0) {
                break;
            }
        } while(++k < 64);
    }

    private static void throwBadHuffmanCode() throws IOException {
        throw new IOException("Bad huffman code");
    }

    private int getMarker() throws IOException {
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

    private void reset() {
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

    private boolean checkRestart() throws IOException {
        if(codeBits < 24) {
            growBufferUnsafe();
        }
        if(marker >= 0xD0 && marker <= 0xD7) {
            reset();
            return true;
        }
        return false;
    }

    private void parseEntropyCodedDataPlanar(Component c) throws IOException {
        int w = (c.x + 7) >> 3;
        int h = (c.y + 7) >> 3;

        for(int j=0 ; j<h ; j++) {
            int outPos = c.outPos + c.outStride*j*8;
            for(int i=0 ; i<w ; i++,outPos+=8) {
                try {
                    decodeBlock(data, c);
                } catch (ArrayIndexOutOfBoundsException ex) {
                    throwBadHuffmanCode();
                }
                idct2D.compute(c.out, outPos, c.outStride, data);
                if(--todo <= 0) {
                    if(!checkRestart()) {
                        return;
                    }
                }
            }
        }
    }

    private void parseEntropyCodedDataMCU() throws IOException {
        for(int j=0 ; j<mcuY ; j++) {
            for(int i=0 ; i<mcuX ; i++) {
                for(Component c : order) {
                    int outPosY = c.outPos + i*c.h*8 + j*c.v*8*c.outStride;

                    for(int y=0 ; y<c.v ; y++,outPosY+=8*c.outStride) {
                        int outPos = outPosY;
                        for(int x=0 ; x<c.h ; x++,outPos+=8) {
                            try {
                                decodeBlock(data, c);
                            } catch (ArrayIndexOutOfBoundsException ex) {
                                throwBadHuffmanCode();
                            }
                            idct2D.compute(c.out, outPos, c.outStride, data);
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

    private void parseEntropyCodedData() throws IOException {
        reset();
        if(order.length == 1) {
            parseEntropyCodedDataPlanar(order[0]);
        } else {
            parseEntropyCodedDataMCU();
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
                    read(dequant[t], 0, 64);
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

        order = new Component[scanN];
        for(int i=0 ; i<scanN ; i++) {
            int id = getU8();
            int q = getU8();
            for(Component c : components) {
                if(c.id == id) {
                    int hd = q >> 4;
                    int ha = q & 15;
                    if(hd > 3 || ha > 3) {
                        throw new IOException("bad huffman table index");
                    }
                    c.huffDC = huffDC[ha];
                    c.huffAC = huffAC[ha];
                    order[i] = c;
                    break;
                }
            }
            if(order[i] == null) {
                return false;
            }
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
            int tq = getU8();

            c.h = q >> 4;
            c.v = q & 15;

            if(c.h == 0 || c.h > 4) {
                throw new IOException("bad H");
            }
            if(c.v == 0 || c.v > 4) {
                throw new IOException("bad V");
            }
            if(tq > 3) {
                throw new IOException("bad TQ");
            }
            c.dequant = dequant[tq];

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

    static final char dezigzag[] = (
        "\0\1\10\20\11\2\3\12" +
        "\21\30\40\31\22\13\4\5" +
        "\14\23\32\41\50\60\51\42" +
        "\33\24\15\6\7\16\25\34" +
        "\43\52\61\70\71\62\53\44" +
        "\35\26\17\27\36\45\54\63" +
        "\72\73\64\55\46\37\47\56" +
        "\65\74\75\66\57\67\76\77" +
        "\77\77\77\77\77\77\77\77" +
        "\77\77\77\77\77\77\77").toCharArray();
    
}
