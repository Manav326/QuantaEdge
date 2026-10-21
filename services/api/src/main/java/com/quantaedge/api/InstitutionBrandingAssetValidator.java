package com.quantaedge.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.web.multipart.MultipartFile;

/**
 * Single validation boundary for all tenant-owned image assets.
 *
 * SVG, GIF, WebP and other active/animated formats are intentionally excluded. Validation uses
 * both byte signatures and a real decoder; the browser's file name and Content-Type are untrusted.
 */
public final class InstitutionBrandingAssetValidator {
  public static final int MAX_BYTES = 2 * 1024 * 1024;
  public static final int MAX_DIMENSION = 4096;
  public static final long MAX_PIXELS = 16_000_000L;

  public record ValidatedAsset(
      byte[] bytes, String contentType, String sha256, String fileName, int width, int height) {}

  private InstitutionBrandingAssetValidator() {}

  public static ValidatedAsset validate(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("Choose a school logo or app icon to upload.");
    }
    if (file.getSize() > MAX_BYTES) {
      throw new IllegalArgumentException("Branding images must be 2 MiB or smaller.");
    }

    final byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException ex) {
      throw new IllegalArgumentException("The branding image could not be read.");
    }
    if (bytes.length == 0 || bytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("Branding images must contain 1 byte to 2 MiB.");
    }

    String contentType = detectRasterType(bytes);
    if ("image/png".equals(contentType) && containsAnimatedPngControlChunk(bytes)) {
      throw new IllegalArgumentException("Animated PNG images are not accepted. Upload a static PNG or JPEG.");
    }
    int width;
    int height;
    try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      if (stream == null) throw new IllegalArgumentException("The branding image is invalid.");
      Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) throw new IllegalArgumentException("The branding image is invalid.");
      ImageReader reader = readers.next();
      try {
        reader.setInput(stream, true, true);
        width = reader.getWidth(0);
        height = reader.getHeight(0);
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
            || (long) width * height > MAX_PIXELS) {
          throw new IllegalArgumentException(
              "Branding image dimensions must be at most 4096 by 4096 pixels and 16 million pixels total.");
        }
        if (reader.read(0) == null) throw new IllegalArgumentException("The branding image could not be decoded.");
      } finally {
        reader.dispose();
      }
    } catch (IOException ex) {
      throw new IllegalArgumentException("The branding image is invalid or unreadable.");
    }

    final String digest;
    try {
      digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable.", ex);
    }

    String original = file.getOriginalFilename() == null ? "branding-image" : file.getOriginalFilename();
    String fileName = original.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
    while (fileName.startsWith(".")) fileName = fileName.substring(1);
    if (fileName.length() > 180) fileName = fileName.substring(fileName.length() - 180);
    if (fileName.isBlank()) fileName = "branding-image." + (contentType.equals("image/png") ? "png" : "jpg");

    return new ValidatedAsset(bytes, contentType, digest, fileName, width, height);
  }

  private static boolean containsAnimatedPngControlChunk(byte[] bytes) {
    int offset = 8; // Skip the PNG signature.
    while (offset <= bytes.length - 12) {
      long chunkLength = ((bytes[offset] & 0xffL) << 24)
          | ((bytes[offset + 1] & 0xffL) << 16)
          | ((bytes[offset + 2] & 0xffL) << 8)
          | (bytes[offset + 3] & 0xffL);
      if (chunkLength > bytes.length - offset - 12L) return false; // Decoder reports malformed framing.
      boolean animationControl = bytes[offset + 4] == 'a' && bytes[offset + 5] == 'c'
          && bytes[offset + 6] == 'T' && bytes[offset + 7] == 'L';
      if (animationControl) return true;
      boolean imageEnd = bytes[offset + 4] == 'I' && bytes[offset + 5] == 'E'
          && bytes[offset + 6] == 'N' && bytes[offset + 7] == 'D';
      if (imageEnd) return false;
      offset += (int) chunkLength + 12;
    }
    return false;
  }

  private static String detectRasterType(byte[] bytes) {
    if (bytes.length >= 8
        && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
        && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a) {
      return "image/png";
    }
    if (bytes.length >= 3
        && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) {
      return "image/jpeg";
    }
    throw new IllegalArgumentException(
        "Branding images must be PNG or JPEG. SVG, animated and active image formats are not accepted.");
  }
}
