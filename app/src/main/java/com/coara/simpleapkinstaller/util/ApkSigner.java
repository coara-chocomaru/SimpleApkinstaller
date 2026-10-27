package com.coara.simpleapkinstaller.util;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ApkSigner {
    private static final int ALGORITHM_RSA_PKCS1_SHA256 = 0x0103;
    private static final int ID_V2 = 0x7109871a;
    private static final int ID_V3 = 0xf05368c0;
    private static final int ID_V31 = 0x1b93ad61;
    private static final int CHUNK_SIZE = 1024 * 1024;
    private static final int EOCD_MIN_SIZE = 22;
    private static final int EOCD_SIGNATURE = 0x06054b50;
    private static final byte[] BLOCK_MAGIC = "APK Sig Block 42".getBytes(StandardCharsets.US_ASCII);

    private static final String KEY_ASSET = "key.pk8";
    private static final String CERT_ASSET = "cert.pem";

    private static volatile PrivateKey cachedKey;
    private static volatile X509Certificate cachedCertificate;

    private ApkSigner() {}

    private static final class Layout {
        long eocdOffset;
        long cdOffset;
        long cdSize;
        long entriesEnd;
        byte[] eocd;
    }

    public static boolean isSigned(File apk) {
        try (RandomAccessFile raf = new RandomAccessFile(apk, "r")) {
            Layout layout = readLayout(raf);
            if (hasSignatureBlock(raf, layout.cdOffset)) return true;
        } catch (IOException ignored) {
        }
        return hasJarSignature(apk);
    }

    private static boolean hasJarSignature(File apk) {
        boolean manifest = false;
        boolean signature = false;
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName().toUpperCase(Locale.ROOT);
                if (!name.startsWith("META-INF/")) continue;
                if (name.equals("META-INF/MANIFEST.MF")) manifest = true;
                if (name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC")) signature = true;
            }
        } catch (IOException e) {
            return false;
        }
        return manifest && signature;
    }

    private static boolean hasSignatureBlock(RandomAccessFile raf, long cdOffset) throws IOException {
        if (cdOffset < 32) return false;
        byte[] footer = new byte[24];
        raf.seek(cdOffset - 24);
        raf.readFully(footer);
        for (int i = 0; i < BLOCK_MAGIC.length; i++) {
            if (footer[8 + i] != BLOCK_MAGIC[i]) return false;
        }
        long blockSize = ByteBuffer.wrap(footer, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        if (blockSize < 24 || blockSize > cdOffset - 8) return false;
        long pairsStart = cdOffset - blockSize - 8 + 8;
        long pairsEnd = cdOffset - 24;
        long position = pairsStart;
        byte[] header = new byte[12];
        while (position + 12 <= pairsEnd) {
            raf.seek(position);
            raf.readFully(header);
            ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            long length = buffer.getLong();
            int id = buffer.getInt();
            if (id == ID_V2 || id == ID_V3 || id == ID_V31) return true;
            if (length < 4 || position + 8 + length > pairsEnd) return false;
            position += 8 + length;
        }
        return false;
    }

    private static Layout readLayout(RandomAccessFile raf) throws IOException {
        long length = raf.length();
        if (length < EOCD_MIN_SIZE) throw new IOException("file too small");
        int tailSize = (int) Math.min(length, EOCD_MIN_SIZE + 65535);
        byte[] tail = new byte[tailSize];
        raf.seek(length - tailSize);
        raf.readFully(tail);

        ByteBuffer buffer = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = tailSize - EOCD_MIN_SIZE; i >= 0; i--) {
            if (buffer.getInt(i) != EOCD_SIGNATURE) continue;
            int commentLength = buffer.getShort(i + 20) & 0xFFFF;
            if (i + EOCD_MIN_SIZE + commentLength != tailSize) continue;

            Layout layout = new Layout();
            layout.eocdOffset = length - tailSize + i;
            layout.cdSize = buffer.getInt(i + 12) & 0xFFFFFFFFL;
            layout.cdOffset = buffer.getInt(i + 16) & 0xFFFFFFFFL;
            int entryCount = buffer.getShort(i + 10) & 0xFFFF;
            if (layout.cdOffset == 0xFFFFFFFFL || layout.cdSize == 0xFFFFFFFFL || entryCount == 0xFFFF) {
                throw new IOException("zip64 is not supported");
            }
            if (layout.cdOffset + layout.cdSize != layout.eocdOffset) throw new IOException("central directory is not adjacent to EOCD");
            layout.eocd = new byte[EOCD_MIN_SIZE + commentLength];
            System.arraycopy(tail, i, layout.eocd, 0, layout.eocd.length);
            return layout;
        }
        throw new IOException("EOCD not found");
    }

    private static long findEntriesEnd(RandomAccessFile raf, long cdOffset) throws IOException {
        if (cdOffset < 32) return cdOffset;
        byte[] footer = new byte[24];
        raf.seek(cdOffset - 24);
        raf.readFully(footer);
        for (int i = 0; i < BLOCK_MAGIC.length; i++) {
            if (footer[8 + i] != BLOCK_MAGIC[i]) return cdOffset;
        }
        long blockSize = ByteBuffer.wrap(footer, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        if (blockSize < 24 || blockSize + 8 > cdOffset) return cdOffset;
        return cdOffset - blockSize - 8;
    }

    private static synchronized void loadKey(Context context) throws IOException, GeneralSecurityException {
        if (cachedKey != null && cachedCertificate != null) return;
        byte[] keyBytes;
        try (InputStream in = context.getAssets().open(KEY_ASSET);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            FileUtils.copy(in, out, null);
            keyBytes = out.toByteArray();
        }
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        X509Certificate certificate;
        try (InputStream in = context.getAssets().open(CERT_ASSET)) {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        cachedKey = key;
        cachedCertificate = certificate;
    }

    public static void sign(Context context, File input, File output) throws IOException, GeneralSecurityException {
        loadKey(context);
        sign(input, output, cachedKey, cachedCertificate);
    }

    public static void sign(File input, File output, PrivateKey key, X509Certificate certificate) throws IOException, GeneralSecurityException {
        try (RandomAccessFile raf = new RandomAccessFile(input, "r")) {
            Layout layout = readLayout(raf);
            layout.entriesEnd = findEntriesEnd(raf, layout.cdOffset);

            byte[] eocdForDigest = layout.eocd.clone();
            ByteBuffer.wrap(eocdForDigest).order(ByteOrder.LITTLE_ENDIAN).putInt(16, (int) layout.entriesEnd);

            byte[] digest = computeDigest(raf, layout, eocdForDigest);
            byte[] block = buildSigningBlock(digest, key, certificate);

            byte[] eocdOut = layout.eocd.clone();
            long newCdOffset = layout.entriesEnd + block.length;
            if (newCdOffset > 0xFFFFFFFEL) throw new IOException("output too large");
            ByteBuffer.wrap(eocdOut).order(ByteOrder.LITTLE_ENDIAN).putInt(16, (int) newCdOffset);

            try (OutputStream out = new FileOutputStream(output)) {
                copyRange(raf, 0, layout.entriesEnd, out);
                out.write(block);
                copyRange(raf, layout.cdOffset, layout.cdSize, out);
                out.write(eocdOut);
            } catch (IOException | RuntimeException e) {
                FileUtils.deleteRecursive(output);
                throw e;
            }
        }
    }

    private static void copyRange(RandomAccessFile raf, long start, long length, OutputStream out) throws IOException {
        byte[] buffer = new byte[FileUtils.BUFFER_SIZE];
        raf.seek(start);
        long remaining = length;
        while (remaining > 0) {
            int read = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("unexpected end of file");
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static byte[] computeDigest(RandomAccessFile raf, Layout layout, byte[] eocd) throws IOException, GeneralSecurityException {
        MessageDigest chunkDigest = MessageDigest.getInstance("SHA-256");
        ByteArrayOutputStream digests = new ByteArrayOutputStream();
        byte[] buffer = new byte[CHUNK_SIZE];
        int chunkCount = 0;

        chunkCount += digestRange(raf, 0, layout.entriesEnd, buffer, chunkDigest, digests);
        chunkCount += digestRange(raf, layout.cdOffset, layout.cdSize, buffer, chunkDigest, digests);

        int offset = 0;
        while (offset < eocd.length) {
            int length = Math.min(CHUNK_SIZE, eocd.length - offset);
            System.arraycopy(eocd, offset, buffer, 0, length);
            digests.write(chunkDigestOf(chunkDigest, buffer, length));
            chunkCount++;
            offset += length;
        }

        MessageDigest top = MessageDigest.getInstance("SHA-256");
        top.update((byte) 0x5a);
        top.update(le32(chunkCount));
        top.update(digests.toByteArray());
        return top.digest();
    }

    private static int digestRange(RandomAccessFile raf, long start, long length, byte[] buffer, MessageDigest md, ByteArrayOutputStream digests) throws IOException {
        int count = 0;
        long remaining = length;
        raf.seek(start);
        while (remaining > 0) {
            int size = (int) Math.min(CHUNK_SIZE, remaining);
            raf.readFully(buffer, 0, size);
            digests.write(chunkDigestOf(md, buffer, size));
            remaining -= size;
            count++;
        }
        return count;
    }

    private static byte[] chunkDigestOf(MessageDigest md, byte[] buffer, int length) {
        md.reset();
        md.update((byte) 0xa5);
        md.update(le32(length));
        md.update(buffer, 0, length);
        return md.digest();
    }

    private static byte[] buildSigningBlock(byte[] contentDigest, PrivateKey key, X509Certificate certificate) throws IOException, GeneralSecurityException {
        byte[] digestRecord = concat(le32(ALGORITHM_RSA_PKCS1_SHA256), prefixed(contentDigest));
        byte[] digests = prefixed(prefixed(digestRecord));
        byte[] certificates = prefixed(prefixed(certificate.getEncoded()));
        byte[] attributes = le32(0);
        byte[] signedData = concat(digests, certificates, attributes);

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(signedData);
        byte[] signatureBytes = signature.sign();

        byte[] signatureRecord = concat(le32(ALGORITHM_RSA_PKCS1_SHA256), prefixed(signatureBytes));
        byte[] signatures = prefixed(prefixed(signatureRecord));
        byte[] publicKey = prefixed(certificate.getPublicKey().getEncoded());
        byte[] signer = concat(prefixed(signedData), signatures, publicKey);
        byte[] signers = prefixed(prefixed(signer));

        byte[] pair = concat(le64(4L + signers.length), le32(ID_V2), signers);
        long size = pair.length + 24L;
        return concat(le64(size), pair, le64(size), BLOCK_MAGIC);
    }

    private static byte[] prefixed(byte[] data) {
        return concat(le32(data.length), data);
    }

    private static byte[] le32(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static byte[] le64(long value) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) total += part.length;
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }
}
