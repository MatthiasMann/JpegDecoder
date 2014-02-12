/*
 * Copyright (c) 2008-2014, Matthias Mann
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

import java.nio.ByteBuffer;

/**
 * Decode YUV data to RGBA data
 * 
 * @author Matthias Mann
 */
public class YUVtoRGBA implements YUVDecoder {
    
    public static final YUVtoRGBA instance = new YUVtoRGBA();

    public void decode(ByteBuffer out, int outPos, byte[] inY, byte[] inU, byte[] inV, int inPos, int count) {
        do {
            int y = (inY[inPos] & 255);
            int u = (inU[inPos] & 255) - 128;
            int v = (inV[inPos] & 255) - 128;
            int r = y + ((32768 + v*91881           ) >> 16);
            int g = y + ((32768 - v*46802 - u* 22554) >> 16);
            int b = y + ((32768           + u*116130) >> 16);
            if(r > 255) r = 255; else if(r < 0) r = 0;
            if(g > 255) g = 255; else if(g < 0) g = 0;
            if(b > 255) b = 255; else if(b < 0) b = 0;
            out.put(outPos+0, (byte)r);
            out.put(outPos+1, (byte)g);
            out.put(outPos+2, (byte)b);
            out.put(outPos+3, (byte)255);
            outPos += 4;
            inPos++;
        } while(--count > 0);
    }
    
}
