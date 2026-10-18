package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class InstitutionBrandingAssetValidatorTest {
  @Test
  void acceptsDecodedPngAndComputesStableDigestAndDimensions() throws Exception {
    byte[] png = imageBytes("png", 64, 40);
    MultipartFile file = new MockMultipartFile(
        "file", "../../school-logo.svg", "image/svg+xml", png);

    InstitutionBrandingAssetValidator.ValidatedAsset asset =
        InstitutionBrandingAssetValidator.validate(file);

    assertEquals("image/png", asset.contentType());
    assertEquals(64, asset.width());
    assertEquals(40, asset.height());
    assertEquals(png.length, asset.bytes().length);
    assertEquals(64, asset.sha256().length());
    assertTrue(asset.sha256().matches("[0-9a-f]{64}"));
    assertFalse(asset.fileName().contains("/"));
    assertFalse(asset.fileName().contains("\\"));
  }

  @Test
  void acceptsRealJpegEvenWhenTheBrowserProvidedTypeIsWrong() throws Exception {
    byte[] jpeg = imageBytes("jpg", 30, 20);
    MultipartFile file = new MockMultipartFile("file", "icon.png", "image/png", jpeg);

    InstitutionBrandingAssetValidator.ValidatedAsset asset =
        InstitutionBrandingAssetValidator.validate(file);

    assertEquals("image/jpeg", asset.contentType());
    assertEquals(30, asset.width());
    assertEquals(20, asset.height());
    assertNotNull(asset.sha256());
  }

  @Test
  void rejectsMissingSvgAndMalformedRasterPayloads() {
    assertThrows(IllegalArgumentException.class,
        () -> InstitutionBrandingAssetValidator.validate(null));
    assertThrows(IllegalArgumentException.class,
        () -> InstitutionBrandingAssetValidator.validate(
            new MockMultipartFile("file", "logo.svg", "image/svg+xml", "<svg/>".getBytes())));
    assertThrows(IllegalArgumentException.class,
        () -> InstitutionBrandingAssetValidator.validate(
            new MockMultipartFile("file", "fake.png", "image/png",
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0, 1, 2, 3})));
  }

  @Test
  void rejectsOversizedUploadsBeforeImageDecoding() {
    byte[] oversize = new byte[InstitutionBrandingAssetValidator.MAX_BYTES + 1];
    MultipartFile file = new MockMultipartFile("file", "too-large.png", "image/png", oversize);

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> InstitutionBrandingAssetValidator.validate(file));
    assertTrue(error.getMessage().contains("2 MiB"));
  }

  @Test
  void rejectsImagePixelDimensionsAboveSafetyLimit() throws Exception {
    byte[] wide = imageBytes("png", InstitutionBrandingAssetValidator.MAX_DIMENSION + 1, 1);
    MultipartFile file = new MockMultipartFile("file", "wide.png", "image/png", wide);

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> InstitutionBrandingAssetValidator.validate(file));
    assertTrue(error.getMessage().contains("dimensions"));
  }

  private static byte[] imageBytes(String format, int width, int height) throws IOException {
    BufferedImage image = new BufferedImage(width, height,
        (format.equalsIgnoreCase("jpg") || format.equalsIgnoreCase("jpeg"))
            ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
    try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      if (!ImageIO.write(image, format, output)) throw new IllegalStateException("Image writer unavailable: " + format);
      return output.toByteArray();
    } finally {
      image.flush();
    }
  }
}
