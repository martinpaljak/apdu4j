/*
 * Copyright (c) 2019 Martin Paljak
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package apdu4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class CommandAPDU2 {
    private  int nc;
    private  int ne;
    private final byte[] data;
    private byte[] apdu;


    public int getCLA() {
        return apdu[0] & 0xff;
    }
    public int getP1() {
        return apdu[2] & 0xff;
    }
    public int getP2() {
        return apdu[3] & 0xff;
    }
    public int getINS() {
        return apdu[1] & 0xff;
    }
    public int getNe() { // FIXME
        return ne;
    }
    public int getNc() { // FIXME
        return nc;
    }
    public byte[] getData() { // FIXME
        if (data == null)
            return new byte[0];
        return data.clone();
    }

    public byte[] getBytes() {
        return apdu.clone();
    }


    public CommandAPDU2(int cla, int ins, int p1, int p2) {
        this.data = null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(cla);
        bos.write(ins);
        bos.write(p1);
        bos.write(p2);
        apdu = bos.toByteArray();
    }

    public CommandAPDU2(int cla, int ins, int p1, int p2, byte[] data, int le) {
        this.data = data.clone();
        ne = le;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bos.write(cla);
            bos.write(ins);
            bos.write(p1);
            bos.write(p2);
            bos.write(this.data.length);
            bos.write(this.data);
            bos.write(le);
            apdu = bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public CommandAPDU2(byte[] bytes) {
        this.data = null;
        this.apdu = bytes.clone();
    }

    public CommandAPDU2(int cla, int ins, int p1, int p2, byte[] data) {
        this.data = data.clone();
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bos.write(cla);
            bos.write(ins);
            bos.write(p1);
            bos.write(p2);
            bos.write(data.length);
            bos.write(data);
            apdu = bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public CommandAPDU2(int cla, int ins, int p1, int p2, int le) {
        data = null;
        ne = le;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(cla);
        bos.write(ins);
        bos.write(p1);
        bos.write(p2);
        bos.write(le);
        apdu = bos.toByteArray();
    }
}
