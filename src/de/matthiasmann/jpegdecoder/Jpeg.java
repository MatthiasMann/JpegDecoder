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
    
    static final int MARKER_NONE = 0xFF;
    
    private final InputStream is;
    private final byte[] inputBuffer;
    private int inputBufferPos;
    private int inputBufferValid;
    private boolean ignoreIOerror;

    private boolean headerDecoded;
    private boolean insideSOS;
    private boolean foundEOI;
    private int currentMCURow;
    
    private final IDCT_2D idct2D;
    private final short[] data;
    private final Huffman[] huffmanTables;
    private final byte[][] dequant;

    private Component[] components;
    private Component[] order;
    
    int codeBuffer;
    int codeBits;
    int marker = MARKER_NONE;
    int restartInterval;
    int todo;
    int mcuCountX;
    int mcuCountY;
    int imageWidth;
    int imageHeight;
    int imgHMax;
    int imgVMax;

    boolean nomore;

    public Jpeg(InputStream is) {
        this.is = is;
        this.inputBuffer = new byte[4096];
        
        this.idct2D = new IDCT_2D();
        this.data = new short[64];
        this.huffmanTables = new Huffman[8];
        this.dequant = new byte[4][64];
    }

    public boolean isIgnoreIOerror() {
        return ignoreIOerror;
    }

    public void setIgnoreIOerror(boolean ignoreIOerror) {
        this.ignoreIOerror = ignoreIOerror;
    }

    public void decodeHeader() throws IOException {
        if(!headerDecoded) {
            headerDecoded = true;

            int m = getMarker();
            if(m != 0xD8) {
                throw new IOException("no SOI");
            }
            m = getMarker();
            while(m != 0xC0 && m != 0xC1) { // SOF
                processMarker(m);
                m = getMarker();
                while(m == MARKER_NONE) {
                    m = getMarker();
                }
            }

            imageWidth();
        }
    }


    public int getImageWidth() {
        ensureHeaderDecoded();
        return imageWidth;
    }

    public int getImageHeight() {
        ensureHeaderDecoded();
        return imageHeight;
    }

    public int getNumComponents() {
        ensureHeaderDecoded();
        return components.length;
    }

    public Component getComponent(int idx) {
        ensureHeaderDecoded();
        return components[idx];
    }

    public int getMCURowHeight() {
        ensureHeaderDecoded();
        return imgVMax * 8;
    }

    public int getNumMCURows() {
        return mcuCountY;
    }
    
    public boolean startDecode() throws IOException {
        if(insideSOS) {
            throw new IllegalStateException("decode already started");
        }
        if(foundEOI) {
            return false;
        }

        decodeHeader();
        int m = getMarker();
        while(m != 0xD9) {  // EOI
            if(m == 0xDA) { // SOS
                processScanHeader();
                insideSOS = true;
                currentMCURow = 0;
                reset();
                return true;
            } else {
                processMarker(m);
            }
            m = getMarker();
        }

        foundEOI = true;
        return false;
    }
    
    public void decodeRAW(ByteBuffer[] buffer, int[] strides, int numMCURows) throws IOException {
        if(!insideSOS) {
            throw new IllegalStateException("decode not started");
        }

        if(numMCURows <= 0 || currentMCURow + numMCURows > mcuCountY) {
            throw new IllegalArgumentException("numMCURows");
        }
        
        int scanN = order.length;
        if(scanN != components.length) {
            throw new UnsupportedOperationException("for raw decode all components need to be decoded at once");
        }
        if(scanN > buffer.length || scanN > strides.length) {
            throw new IllegalArgumentException("not enough buffers");
        }

        for(int compIdx=0 ; compIdx<scanN ; compIdx++) {
            order[compIdx].outPos = buffer[compIdx].position();
        }

        for(int j=0 ; j<numMCURows ; j++) {
            ++currentMCURow;
            for(int i=0 ; i<mcuCountX ; i++) {
                for(int compIdx=0 ; compIdx<scanN ; compIdx++) {
                    Component c = order[compIdx];
                    int outStride = strides[compIdx];
                    int outPosY = c.outPos + 8*(i*c.blocksPerMCUHorz + j*c.blocksPerMCUVert*outStride);

                    for(int y=0 ; y<c.blocksPerMCUVert ; y++,outPosY+=8*outStride) {
                        for(int x=0,outPos=outPosY ; x<c.blocksPerMCUHorz ; x++,outPos+=8) {
                            try {
                                decodeBlock(data, c);
                            } catch (ArrayIndexOutOfBoundsException ex) {
                                throwBadHuffmanCode();
                            }
                            idct2D.compute(buffer[compIdx], outPos, outStride, data);
                        }
                    }
                }
                if(--todo <= 0) {
                    if(!checkRestart()) {
                        break;
                    }
                }
            }
        }

        if(currentMCURow >= mcuCountY) {
            insideSOS = false;
            if(marker == MARKER_NONE) {
                skipPadding();
            }
        }

        for(int compIdx=0 ; compIdx<scanN ; compIdx++) {
            Component c = order[compIdx];
            buffer[compIdx].position(c.outPos + numMCURows * c.blocksPerMCUVert * 8 * strides[compIdx]);
        }
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
            inputBuffer[1] = (byte)0xD9;    // EOI

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

    private void processMarker(int marker) throws IOException {
        if(marker >= 0xE0 && (marker <= 0xEF || marker == 0xFE)) {
            int l = getU16() - 2;
            if(l < 0) {
                throw new IOException("bad length");
            }
            skip(l);
            return;
        }

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
                break;

            case 0xDB: {    // DQT - define dequant table
                int l = getU16() - 2;
                while(l >= 65) {
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
                if(l != 0) {
                    throw new IOException("bad DQT length");
                }
                break;
            }

            case 0xC4: {    // DHT - define huffman table
                int l = getU16() - 2;
                while(l > 17) {
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
                    int m = h.getNumSymbols();
                    l -= 17 + m;
                    if(l < 0) {
                        throw new IOException("bad DHT length");
                    }
                    read(h.values, 0, m);
                    huffmanTables[tc*4 + th] = h;
                }
                if(l != 0) {
                    throw new IOException("bad DHT length");
                }
                break;
            }
            
            default:
                throw new IOException("Unknown marker: " + Integer.toHexString(marker));
        }
    }

    private void skipPadding() throws IOException {
        int x;
        do {
            x = getU8();
        } while(x == 0);

        if(x == 0xFF) {
            marker = getU8();
        }
    }

    private void processScanHeader() throws IOException {
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
                    c.huffDC = huffmanTables[hd];
                    c.huffAC = huffmanTables[ha + 4];
                    if(c.huffDC == null || c.huffAC == null) {
                        throw new IOException("bad huffman table index");
                    }
                    order[i] = c;
                    break;
                }
            }
            if(order[i] == null) {
                throw new IOException("unknown color component");
            }
        }
        
        if(getU8() != 0) {
            throw new IOException("bad SOS");
        }
        getU8();
        if(getU8() != 0) {
            throw new IOException("bad SOS");
        }
    }

    private void imageWidth() throws IOException {
        int lf = getU16();
        if(lf < 11) {
            throw new IOException("bad SOF length");
        }

        if(getU8() != 8) {
            throw new IOException("only 8 bit JPEG supported");
        }

        imageHeight = getU16();
        imageWidth  = getU16();

        if(imageWidth <= 0 || imageHeight <= 0) {
            throw new IOException("Invalid image size");
        }

        int numComps = getU8();
        if(numComps != 3 && numComps != 1) {
            throw new IOException("bad component count");
        }

        if(lf != 8+3*numComps) {
            throw new IOException("bad SOF length");
        }

        int hMax = 1;
        int vMax = 1;

        components = new Component[numComps];
        for(int i=0 ; i<numComps ; i++) {
            Component c = new Component(getU8());
            int q = getU8();
            int tq = getU8();

            c.blocksPerMCUHorz = q >> 4;
            c.blocksPerMCUVert = q & 15;

            if(c.blocksPerMCUHorz == 0 || c.blocksPerMCUHorz > 4) {
                throw new IOException("bad H");
            }
            if(c.blocksPerMCUVert == 0 || c.blocksPerMCUVert > 4) {
                throw new IOException("bad V");
            }
            if(tq > 3) {
                throw new IOException("bad TQ");
            }
            c.dequant = dequant[tq];

            hMax = Math.max(hMax, c.blocksPerMCUHorz);
            vMax = Math.max(vMax, c.blocksPerMCUVert);

            components[i] = c;
        }

        int mcuW = hMax * 8;
        int mcuH = vMax * 8;

        imgHMax = hMax;
        imgVMax = vMax;
        mcuCountX = (imageWidth + mcuW - 1) / mcuW;
        mcuCountY = (imageHeight + mcuH - 1) / mcuH;

        for(int i=0 ; i<numComps ; i++) {
            Component c = components[i];
            c.width = (imageWidth * c.blocksPerMCUHorz + hMax - 1) / hMax;
            c.height = (imageHeight * c.blocksPerMCUVert + vMax - 1) / vMax;
            c.minReqWidth = mcuCountX * c.blocksPerMCUHorz * 8;
            c.minReqHeight = mcuCountY * c.blocksPerMCUVert * 8;
        }
    }

    private void ensureHeaderDecoded() throws IllegalStateException {
        if(!headerDecoded) {
            throw new IllegalStateException("need to decode header first");
        }
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
