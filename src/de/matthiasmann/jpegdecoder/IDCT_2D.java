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

import java.nio.ByteBuffer;

/**
 *
 * @author Matthias Mann
 */
public class IDCT_2D extends IDCT_1D {

    final int[] tmp2D = new int[64];

    public void compute(ByteBuffer out, int outPos, int outStride, short[] data) {
        final int[] tmp = tmp2D;

        for(int i=0 ; i<8 ; i++) {
            compute(
                    data[i   ],
                    data[i+ 8],
                    data[i+16],
                    data[i+24],
                    data[i+32],
                    data[i+40],
                    data[i+48],
                    data[i+56]);
            int x0o = x0 + 512;
            tmp[i   ] = (x0o+t3) >> 10;
            tmp[i+56] = (x0o-t3) >> 10;
            int x1o = x1 + 512;
            tmp[i+ 8] = (x1o+t2) >> 10;
            tmp[i+48] = (x1o-t2) >> 10;
            int x2o = x2 + 512;
            tmp[i+16] = (x2o+t1) >> 10;
            tmp[i+40] = (x2o-t1) >> 10;
            int x3o = x3 + 512;
            tmp[i+24] = (x3o+t0) >> 10;
            tmp[i+32] = (x3o-t0) >> 10;
        }

        for(int i=0 ; i<64 ; i+=8) {
            compute(
                    tmp[i  ],
                    tmp[i+1],
                    tmp[i+2],
                    tmp[i+3],
                    tmp[i+4],
                    tmp[i+5],
                    tmp[i+6],
                    tmp[i+7]);
            int x0o = x0 + (257 << 16);
            out.put(outPos  , clampShift17(x0o+t3));
            out.put(outPos+7, clampShift17(x0o-t3));
            int x1o = x1 + (257 << 16);
            out.put(outPos+1, clampShift17(x1o+t2));
            out.put(outPos+6, clampShift17(x1o-t2));
            int x2o = x2 + (257 << 16);
            out.put(outPos+2, clampShift17(x2o+t1));
            out.put(outPos+5, clampShift17(x2o-t1));
            int x3o = x3 + (257 << 16);
            out.put(outPos+3, clampShift17(x3o+t0));
            out.put(outPos+4, clampShift17(x3o-t0));
            outPos += outStride;
        }
    }

    private static byte clampShift17(int x) {
        x >>= 17;
        if(x < 0) {
            return 0;
        }
        if(x > 255) {
            return (byte)255;
        }
        return (byte)x;
    }
}
