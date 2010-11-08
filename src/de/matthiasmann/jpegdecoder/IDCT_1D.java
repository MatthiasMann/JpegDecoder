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

/**
 *
 * @author Matthias Mann
 */
public class IDCT_1D {

    int x0;
    int x1;
    int x2;
    int x3;
    int t0;
    int t1;
    int t2;
    int t3;

    private static final int C0  = f2f( 0.541196100);
    private static final int C1  = f2f(-1.847759065);
    private static final int C2  = f2f( 0.765366865);
    private static final int C3  = f2f( 1.175875602);
    private static final int C4  = f2f( 0.298631336);
    private static final int C5  = f2f( 2.053119869);
    private static final int C6  = f2f( 3.072711026);
    private static final int C7  = f2f( 1.501321110);
    private static final int C8  = f2f(-0.899976223);
    private static final int C9  = f2f(-2.562915447);
    private static final int C10 = f2f(-1.961570560);
    private static final int C11 = f2f(-0.390180644);

    void compute(int s0, int s1, int s2, int s3, int s4, int s5, int s6, int s7) {
        int p1, p2, p3, p4, p5;

        p1 = (s2+s6) * C0;
        p2 = (s0+s4) << 12;
        p3 = (s0-s4) << 12;
        p4 = p1 + s6*C1;
        p5 = p1 + s2*C2;
        
        x0 = p2+p5;
        x3 = p2-p5;
        x1 = p3+p4;
        x2 = p3-p4;

        p1 = s7+s1;
        p2 = s5+s3;
        p3 = s7+s3;
        p4 = s5+s1;
        p5 = (p3+p4)*C3;
        
        p1 = p5 + p1*C8;
        p2 = p5 + p2*C9;
        p3 = p3*C10;
        p4 = p4*C11;

        t0 = s7*C4 + p1 + p3;
        t1 = s5*C5 + p2 + p4;
        t2 = s3*C6 + p2 + p3;
        t3 = s1*C7 + p1 + p4;
    }

    private static strictfp int f2f(double x) {
        return (int)Math.round(Math.scalb(x, 12));
    }

}
