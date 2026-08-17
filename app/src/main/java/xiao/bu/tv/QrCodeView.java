package xiao.bu.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.io.UnsupportedEncodingException;

public final class QrCodeView extends View {
    private final Paint paint = new Paint();
    private boolean[][] modules;

    public QrCodeView(Context context, AttributeSet attributes) {
        super(context, attributes);
        paint.setAntiAlias(false);
        setBackgroundColor(Color.WHITE);
    }

    void setText(String text) {
        modules = text == null ? null : QrV3.encode(text);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (modules == null) {
            return;
        }
        int quiet = 4;
        int count = modules.length + quiet * 2;
        float scale = Math.min(getWidth(), getHeight()) / (float) count;
        float left = (getWidth() - count * scale) / 2f;
        float top = (getHeight() - count * scale) / 2f;
        paint.setColor(Color.BLACK);
        for (int y = 0; y < modules.length; y++) {
            for (int x = 0; x < modules.length; x++) {
                if (modules[y][x]) {
                    canvas.drawRect(left + (x + quiet) * scale,
                            top + (y + quiet) * scale,
                            left + (x + quiet + 1) * scale,
                            top + (y + quiet + 1) * scale, paint);
                }
            }
        }
    }

    private static final class QrV3 {
        private static final int SIZE = 29;
        private static final int DATA_CODEWORDS = 55;
        private static final int ECC_CODEWORDS = 15;

        static boolean[][] encode(String text) {
            byte[] input;
            try {
                input = text.getBytes("UTF-8");
            } catch (UnsupportedEncodingException impossible) {
                input = text.getBytes();
            }
            if (input.length > 53) {
                throw new IllegalArgumentException("QR text is too long");
            }
            byte[] data = makeData(input);
            byte[] ecc = remainder(data, divisor(ECC_CODEWORDS));
            byte[] codewords = new byte[data.length + ecc.length];
            System.arraycopy(data, 0, codewords, 0, data.length);
            System.arraycopy(ecc, 0, codewords, data.length, ecc.length);
            boolean[][] result = new boolean[SIZE][SIZE];
            boolean[][] function = new boolean[SIZE][SIZE];
            drawFunctions(result, function);
            drawCodewords(result, function, codewords);
            drawFormat(result, function);
            return result;
        }

        private static byte[] makeData(byte[] input) {
            byte[] result = new byte[DATA_CODEWORDS];
            int bit = 0;
            bit = append(result, bit, 0x4, 4);
            bit = append(result, bit, input.length, 8);
            for (byte value : input) {
                bit = append(result, bit, value & 0xff, 8);
            }
            bit = Math.min(bit + 4, DATA_CODEWORDS * 8);
            bit = (bit + 7) / 8 * 8;
            int pad = 0;
            while (bit < DATA_CODEWORDS * 8) {
                bit = append(result, bit, pad++ % 2 == 0 ? 0xec : 0x11, 8);
            }
            return result;
        }

        private static int append(byte[] target, int bit, int value, int count) {
            for (int i = count - 1; i >= 0 && bit < target.length * 8; i--, bit++) {
                target[bit >>> 3] |= ((value >>> i) & 1) << (7 - (bit & 7));
            }
            return bit;
        }

        private static void drawFunctions(boolean[][] qr, boolean[][] function) {
            for (int i = 0; i < SIZE; i++) {
                set(qr, function, 6, i, i % 2 == 0);
                set(qr, function, i, 6, i % 2 == 0);
            }
            finder(qr, function, 3, 3);
            finder(qr, function, SIZE - 4, 3);
            finder(qr, function, 3, SIZE - 4);
            alignment(qr, function, 22, 22);
            for (int i = 0; i < 8; i++) {
                set(qr, function, 8, i == 6 ? 7 : i, false);
                set(qr, function, SIZE - 1 - i, 8, false);
            }
            for (int i = 8; i < 15; i++) {
                set(qr, function, 14 - i, 8, false);
                set(qr, function, 8, SIZE - 15 + i, false);
            }
            set(qr, function, 7, 8, false);
            set(qr, function, 8, 8, false);
            set(qr, function, 8, SIZE - 8, true);
        }

        private static void finder(boolean[][] qr, boolean[][] function, int cx, int cy) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dx = -4; dx <= 4; dx++) {
                    int x = cx + dx;
                    int y = cy + dy;
                    if (x >= 0 && y >= 0 && x < SIZE && y < SIZE) {
                        int distance = Math.max(Math.abs(dx), Math.abs(dy));
                        set(qr, function, x, y, distance != 2 && distance != 4);
                    }
                }
            }
        }

        private static void alignment(boolean[][] qr, boolean[][] function, int cx, int cy) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    set(qr, function, cx + dx, cy + dy,
                            Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                }
            }
        }

        private static void drawCodewords(boolean[][] qr, boolean[][] function, byte[] data) {
            int bit = 0;
            for (int right = SIZE - 1; right >= 1; right -= 2) {
                if (right == 6) {
                    right--;
                }
                for (int vertical = 0; vertical < SIZE; vertical++) {
                    int y = ((right + 1) & 2) == 0 ? SIZE - 1 - vertical : vertical;
                    for (int offset = 0; offset < 2; offset++) {
                        int x = right - offset;
                        if (function[y][x]) {
                            continue;
                        }
                        boolean value = bit < data.length * 8
                                && ((data[bit >>> 3] >>> (7 - (bit & 7))) & 1) != 0;
                        value ^= (x + y) % 2 == 0;
                        qr[y][x] = value;
                        bit++;
                    }
                }
            }
        }

        private static void drawFormat(boolean[][] qr, boolean[][] function) {
            int data = 1 << 3;
            int remainder = data;
            for (int i = 0; i < 10; i++) {
                remainder = (remainder << 1) ^ ((remainder >>> 9) * 0x537);
            }
            int bits = ((data << 10) | remainder) ^ 0x5412;
            for (int i = 0; i <= 5; i++) qr[i][8] = bit(bits, i);
            qr[7][8] = bit(bits, 6);
            qr[8][8] = bit(bits, 7);
            qr[8][7] = bit(bits, 8);
            for (int i = 9; i < 15; i++) qr[8][14 - i] = bit(bits, i);
            for (int i = 0; i < 8; i++) qr[8][SIZE - 1 - i] = bit(bits, i);
            for (int i = 8; i < 15; i++) qr[SIZE - 15 + i][8] = bit(bits, i);
            qr[SIZE - 8][8] = true;
        }

        private static boolean bit(int value, int index) {
            return ((value >>> index) & 1) != 0;
        }

        private static void set(boolean[][] qr, boolean[][] function,
                int x, int y, boolean value) {
            qr[y][x] = value;
            function[y][x] = true;
        }

        private static byte[] divisor(int degree) {
            byte[] result = new byte[degree];
            result[degree - 1] = 1;
            int root = 1;
            for (int i = 0; i < degree; i++) {
                for (int j = 0; j < degree; j++) {
                    result[j] = (byte) multiply(result[j] & 0xff, root);
                    if (j + 1 < degree) result[j] ^= result[j + 1];
                }
                root = multiply(root, 2);
            }
            return result;
        }

        private static byte[] remainder(byte[] data, byte[] divisor) {
            byte[] result = new byte[divisor.length];
            for (byte value : data) {
                int factor = (value ^ result[0]) & 0xff;
                System.arraycopy(result, 1, result, 0, result.length - 1);
                result[result.length - 1] = 0;
                for (int i = 0; i < result.length; i++) {
                    result[i] ^= (byte) multiply(divisor[i] & 0xff, factor);
                }
            }
            return result;
        }

        private static int multiply(int x, int y) {
            int result = 0;
            for (int i = 7; i >= 0; i--) {
                result = (result << 1) ^ ((result >>> 7) * 0x11d);
                result ^= ((y >>> i) & 1) * x;
            }
            return result;
        }
    }
}
