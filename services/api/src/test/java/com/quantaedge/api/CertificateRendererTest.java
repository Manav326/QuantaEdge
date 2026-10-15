package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
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
import org.springframework.beans.factory.annotation.Value;

class CertificateRendererTest {
  private final CertificateRenderer renderer = new CertificateRenderer();
  private final OffsetDateTime issuedAt = OffsetDateTime.parse("2026-10-12T18:00:00+05:30");

  @Test
  void publicVerificationOriginReadsTheConfiguredAppPublicBaseUrlEnvironmentProperty() throws Exception {
    Value value = CertificateRenderer.class.getDeclaredField("publicBaseUrl").getAnnotation(Value.class);
    assertEquals("${app.public.base.url:http://localhost:3000}", value.value());
  }

  @Test
  void allTemplatesRenderPdfAndPngFromTheSameA4LandscapeCanvas() throws Exception {
    for (String template : CertificateRenderer.TEMPLATE_IDS) {
      CertificateRenderer.RenderedCertificate rendered = renderer.render(
          template, "hi", "परीक्षण विद्यार्थी", "कक्षा 6 · गणित",
          "इस विषय में प्रकाशित सभी 10 पाठ सफलतापूर्वक पूरे किए।",
          "QE-2026-ABCDEF1234567890", issuedAt, false);

      assertTrue(rendered.pdf().length > 1_000, template + " PDF should contain the rendered page");
      assertTrue(rendered.png().length > 1_000, template + " PNG should contain the rendered page");
      assertArrayEquals(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47},
          java.util.Arrays.copyOf(rendered.png(), 4), "PNG signature: " + template);
      var image = ImageIO.read(new ByteArrayInputStream(rendered.png()));
      assertEquals(2480, image.getWidth());
      assertEquals(1754, image.getHeight());
      var qrCrop = image.getSubimage(1550, 1270, 160, 160);
      String decodedUrl = new MultiFormatReader().decode(
          new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(qrCrop)))).getText();
      assertEquals("http://localhost:3000/certificate/verify?code=QE-2026-ABCDEF1234567890", decodedUrl,
          "QR must open the official verification page for the exact certificate");

      var barcodeCrop = image.getSubimage(1740, 1324, 480, 54);
      String decodedBarcode = new MultiFormatReader().decode(
          new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(barcodeCrop)))).getText();
      assertEquals("QE-2026-ABCDEF1234567890", decodedBarcode,
          "The linear barcode must encode the same certificate number.");

      try (PDDocument document = Loader.loadPDF(rendered.pdf())) {
        assertEquals(1, document.getNumberOfPages());
        assertTrue(document.getPage(0).getMediaBox().getWidth()
            > document.getPage(0).getMediaBox().getHeight(), "PDF page should be landscape");
        assertEquals(841.89f, document.getPage(0).getMediaBox().getWidth(), 0.1f);
      }
    }
  }

  @Test
  void allTemplateIdsResolveToTheSameDesignAndCanonicalBrandIsPackaged() throws Exception {
    var first = renderer.render("CLASSIC", "en", "Real Student", "Real Assessment",
        "Source data summary", "QE-2026-ABCDEF1234567890", issuedAt, false).png();
    var second = renderer.render("MODERN", "en", "Real Student", "Real Assessment",
        "Source data summary", "QE-2026-ABCDEF1234567890", issuedAt, false).png();
    var third = renderer.render("ACADEMIC", "en", "Real Student", "Real Assessment",
        "Source data summary", "QE-2026-ABCDEF1234567890", issuedAt, false).png();
    assertArrayEquals(first, second);
    assertArrayEquals(first, third);
    assertTrue(getClass().getResource("/branding/quantaedge-icon.png") != null,
        "The canonical QuantaEdge Q mark must be included in the API artifact.");
    var wordmark = renderer.brandWordmarkArtwork();
    assertEquals(java.awt.image.BufferedImage.TYPE_INT_ARGB, wordmark.getType());
    assertTrue(wordmark.getWidth() > 100 && wordmark.getHeight() > 40,
        "The navy/orange QuantaEdge wordmark must render as a single transparent branding image.");
  }

  @Test
  void footerWordingLocalizesButTheQuantaEdgeBrandUsesTheSameImage() throws Exception {
    assertEquals("OFFICIALLY ISSUED BY", CertificateRenderer.lockedFooterWording("en"));
    assertEquals("आधिकारिक रूप से जारी", CertificateRenderer.lockedFooterWording("hi"));
    var wordmark = renderer.brandWordmarkArtwork();
    assertTrue(wordmark.getWidth() > 100 && wordmark.getHeight() > 40);
  }

  @Test
  void mixedScriptDynamicFieldsRenderInEitherCertificateLanguageWithoutTranslatingTheirValues() throws Exception {
    for (String language : List.of("en", "hi")) {
      String recipient = "Sujata शर्मा";
      String title = "Science · विज्ञान";
      String summary = "Assessment / मूल्यांकन";
      var details = new CertificateRenderer.CertificateDetails(
          "Science", "6", "90 / 100", "Passed", "2026-10-12",
          "Dr. सीमा", "TEACHER", "Final Exam / अंतिम परीक्षा", "EXAM-42");
      var rendered = renderer.render("CLASSIC", language, recipient, title, summary,
          "QE-2026-ABCDEF1234567890", issuedAt, details, false);
      var image = ImageIO.read(new ByteArrayInputStream(rendered.png()));
      assertEquals(2480, image.getWidth());
      assertEquals(1754, image.getHeight());
      assertEquals(-1, renderer.fontForText(new java.awt.Font("Noto Sans", java.awt.Font.PLAIN, 24), recipient)
          .canDisplayUpTo(recipient), "Mixed Hindi/English recipient must be displayable as stored.");
      assertEquals(-1, renderer.fontForText(new java.awt.Font("Noto Sans", java.awt.Font.PLAIN, 24), title)
          .canDisplayUpTo(title), "Subject names must retain their original language.");
    }
  }

  @Test
  void examIdIsAddedBelowTheAssessmentTitleWithoutChangingHeaderOrFooter() throws Exception {
    CertificateRenderer.CertificateDetails withoutId = new CertificateRenderer.CertificateDetails(
        "Science", "6", "90 / 100", "Passed", "2026-10-12", null, null, "Final Exam", null);
    CertificateRenderer.CertificateDetails withId = new CertificateRenderer.CertificateDetails(
        "Science", "6", "90 / 100", "Passed", "2026-10-12", null, null, "Final Exam", "12345");
    byte[] withoutPng = renderer.render("CLASSIC", "en", "Real Student", "Class 6 · Science",
        "Summary", "QE-2026-ABCDEF1234567890", issuedAt, withoutId, false).png();
    byte[] withPng = renderer.render("CLASSIC", "en", "Real Student", "Class 6 · Science",
        "Summary", "QE-2026-ABCDEF1234567890", issuedAt, withId, false).png();
    var withoutImage = ImageIO.read(new ByteArrayInputStream(withoutPng));
    var withImage = ImageIO.read(new ByteArrayInputStream(withPng));

    assertTrue(regionsEqual(withoutImage, withImage, 0, 300),
        "The certificate header must remain pixel-identical.");
    assertTrue(regionsEqual(withoutImage, withImage, 1500, 1754),
        "The certificate footer must remain pixel-identical.");
    assertTrue(!regionsEqual(withoutImage, withImage, 300, 1500),
        "The exam ID must create a change in the certificate body.");
  }

  private boolean regionsEqual(java.awt.image.BufferedImage left,
      java.awt.image.BufferedImage right, int startY, int endY) {
    for (int y = startY; y < endY; y++) {
      for (int x = 0; x < left.getWidth(); x++) {
        if (left.getRGB(x, y) != right.getRGB(x, y)) return false;
      }
    }
    return true;
  }

  @Test
  void certificateFrameDoesNotRenderTheOldOrangeCornerDots() throws Exception {
    var rendered = renderer.render("CLASSIC", "en", "Test Learner", "Class 6 · Mathematics",
        "Source data summary", "QE-2026-ABCDEF1234567890", issuedAt, false);
    var image = ImageIO.read(new ByteArrayInputStream(rendered.png()));
    int formerBottomLeftDot = image.getRGB(155, 1588);
    int formerBottomRightDot = image.getRGB(2325, 1588);
    int orange = new Color(0xF97316).getRGB();
    org.junit.jupiter.api.Assertions.assertNotEquals(orange, formerBottomLeftDot,
        "The old bottom-left decorative dot must not return.");
    org.junit.jupiter.api.Assertions.assertNotEquals(orange, formerBottomRightDot,
        "The old bottom-right decorative dot must not return.");
  }

  @Test
  void publicSamplesAreExplicitlyWatermarked() throws Exception {
    CertificateRenderer.RenderedCertificate sample = renderer.render(
        "MODERN", "en", "Sample Student", "Class 6 · Mathematics",
        "Design preview only. No achievement has been verified.",
        "QE-2026-000000000000", issuedAt, true);
    assertTrue(sample.pdf().length > 1_000);
    assertTrue(sample.png().length > 1_000);
    byte[] previewPng = renderer.renderSamplePng(
        "MODERN", "en", "Sample Student", "Class 6 · Mathematics",
        "Design preview only. No achievement has been verified.",
        "QE-2026-000000000000", issuedAt);
    var sampleImage = ImageIO.read(new ByteArrayInputStream(previewPng));
    assertEquals(1240, sampleImage.getWidth());
    assertEquals(877, sampleImage.getHeight());
  }

  @Test
  void sampleCertificatesShowAnExamIdPlaceholderInBothLanguages() {
    for (String language : List.of("en", "hi")) {
      String title = "hi".equals(language) ? "कक्षा 6 · गणित" : "Class 6 · Mathematics";
      String recipient = "hi".equals(language) ? "नमूना विद्यार्थी" : "Sample Student";
      String summary = "hi".equals(language)
          ? "केवल डिज़ाइन पूर्वावलोकन।" : "Design preview only.";
      var defaultSample = renderer.render("CLASSIC", language, recipient, title, summary,
          "QE-2026-000000000000", issuedAt, true);
      var explicitPlaceholder = renderer.render("CLASSIC", language, recipient, title, summary,
          "QE-2026-000000000000", issuedAt,
          new CertificateRenderer.CertificateDetails(null, null, null, null, null, null, null, title, "[ID]"),
          true);
      assertArrayEquals(explicitPlaceholder.png(), defaultSample.png(),
          "Sample certificates should display the same [ID] placeholder in " + language + ".");
    }
  }

  @Test
  void lockedEnglishAndHindiWordingIsExactAndCompanyNameNeverLocalizes() {
    var english = CertificateRenderer.lockedWording("en");
    assertEquals("CERTIFICATE OF ACHIEVEMENT", english.title());
    assertEquals("This certificate is awarded to", english.introduction());
    assertEquals("for", english.connector());
    assertEquals("successfully completing the assessment.", english.completion());

    var hindi = CertificateRenderer.lockedWording("hi");
    assertEquals("उपलब्धि प्रमाणपत्र", hindi.title());
    assertEquals("यह प्रमाणपत्र", hindi.introduction());
    assertEquals("को", hindi.connector());
    assertEquals("सफलतापूर्वक पूर्ण करने के लिए प्रदान किया जाता है।", hindi.completion());
    assertEquals("QuantaEdge", CertificateRenderer.BRAND_NAME, "The brand name must remain unchanged across languages.");
  }

  @Test
  void templateSelectionIsAllowListed() {
    assertEquals("CLASSIC", CertificateRenderer.normalizeTemplate(null));
    assertEquals("MODERN", CertificateRenderer.normalizeTemplate(" modern "));
    assertEquals(List.of("CLASSIC", "MODERN", "ACADEMIC"), CertificateRenderer.TEMPLATE_IDS);
    assertThrows(IllegalArgumentException.class, () -> CertificateRenderer.normalizeTemplate("../../file"));
  }
}
