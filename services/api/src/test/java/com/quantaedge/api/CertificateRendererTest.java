package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.List;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

class CertificateRendererTest {
  private final CertificateRenderer renderer = new CertificateRenderer();
  private final OffsetDateTime issuedAt = OffsetDateTime.parse("2026-10-12T18:00:00+05:30");

  @Test
  void allTemplatesRenderPdfAndPngFromTheSameA4LandscapeCanvas() throws Exception {
    for (String template : CertificateRenderer.TEMPLATE_IDS) {
      CertificateRenderer.RenderedCertificate rendered = renderer.render(
          template, "hi", "परीक्षण विद्यार्थी", "कक्षा 6 · गणित",
          "इस विषय में प्रकाशित सभी 10 पाठ सफलतापूर्वक पूरे किए।",
          "QE-2026-ABCDEF123456", issuedAt, false);

      assertTrue(rendered.pdf().length > 1_000, template + " PDF should contain the rendered page");
      assertTrue(rendered.png().length > 1_000, template + " PNG should contain the rendered page");
      assertArrayEquals(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47},
          java.util.Arrays.copyOf(rendered.png(), 4), "PNG signature: " + template);
      var image = ImageIO.read(new ByteArrayInputStream(rendered.png()));
      assertEquals(2480, image.getWidth());
      assertEquals(1754, image.getHeight());
      var qrCrop = image.getSubimage(2480 - 390, 1754 - 455, 250, 250);
      String decodedUrl = new MultiFormatReader().decode(
          new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(qrCrop)))).getText();
      assertEquals("http://localhost:3000/certificate/verify?code=QE-2026-ABCDEF123456", decodedUrl,
          "QR must open the official verification page for the exact certificate");

      try (PDDocument document = Loader.loadPDF(rendered.pdf())) {
        assertEquals(1, document.getNumberOfPages());
        assertTrue(document.getPage(0).getMediaBox().getWidth()
            > document.getPage(0).getMediaBox().getHeight(), "PDF page should be landscape");
        assertEquals(841.89f, document.getPage(0).getMediaBox().getWidth(), 0.1f);
      }
    }
  }

  @Test
  void publicSamplesAreExplicitlyWatermarked() throws Exception {
    CertificateRenderer.RenderedCertificate sample = renderer.render(
        "MODERN", "en", "Sample Student", "Class 6 · Mathematics",
        "Design preview only. No achievement has been verified.",
        "QE-2026-000000000000", issuedAt, true);
    assertTrue(sample.pdf().length > 1_000);
    assertTrue(sample.png().length > 1_000);
    var sampleImage = ImageIO.read(new ByteArrayInputStream(sample.png()));
    assertEquals(1240, sampleImage.getWidth());
    assertEquals(877, sampleImage.getHeight());
  }

  @Test
  void templateSelectionIsAllowListed() {
    assertEquals("CLASSIC", CertificateRenderer.normalizeTemplate(null));
    assertEquals("MODERN", CertificateRenderer.normalizeTemplate(" modern "));
    assertEquals(List.of("CLASSIC", "MODERN", "ACADEMIC"), CertificateRenderer.TEMPLATE_IDS);
    assertThrows(IllegalArgumentException.class, () -> CertificateRenderer.normalizeTemplate("../../file"));
  }
}
