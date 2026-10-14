package com.quantaedge.api;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Renders each certificate from one canonical raster layout, then places that exact layout
 * into an A4 landscape PDF. Java2D text layout plus container Noto fonts preserves Hindi shaping.
 */
@Service
public class CertificateRenderer {
  public static final String BRAND_NAME = "QuantaEdge";
  public static final List<String> TEMPLATE_IDS = List.of("CLASSIC", "MODERN", "ACADEMIC");
  private static final int WIDTH = 2480;
  private static final int HEIGHT = 1754;
  private static final Color NAVY = new Color(0x152038);
  private static final Color ORANGE = new Color(0xF97316);
  private static final Color DEEP_ORANGE = new Color(0xC2410C);
  private static final Color GOLD = new Color(0xC6A56B);
  private static final Color INK = new Color(0x263247);
  private static final Color MUTED = new Color(0x647084);
  private static final Color PAPER = new Color(0xF7F8FC);
  private static final Color PALE = new Color(0xF3F5F8);

  @Value("${app.public.base.url:http://localhost:3000}")
  private String publicBaseUrl = "http://localhost:3000";

  public record RenderedCertificate(byte[] pdf, byte[] png) {}
  public record CertificateDetails(String subjectName, String classCode, String score, String result,
      String assessmentDate, String signatoryName, String signatoryRole, String assessmentName) {}
  public record CertificateWording(String title, String introduction, String connector, String completion) {}

  public static CertificateWording lockedWording(String language) {
    if ("hi".equalsIgnoreCase(language)) {
      return new CertificateWording("उपलब्धि प्रमाणपत्र", "यह प्रमाणपत्र", "को",
          "सफलतापूर्वक पूर्ण करने के लिए प्रदान किया जाता है।");
    }
    return new CertificateWording("CERTIFICATE OF ACHIEVEMENT", "This certificate is awarded to", "for",
        "successfully completing the assessment.");
  }
  private volatile BufferedImage cachedBrandIcon;

  public RenderedCertificate render(String templateId, String language, String recipientName,
      String achievementTitle, String achievementSummary, String certificateNumber,
      OffsetDateTime issuedAt, boolean sample) {
    return render(templateId, language, recipientName, achievementTitle, achievementSummary,
        certificateNumber, issuedAt, new CertificateDetails(null,null,null,null,null,null,null,null), sample);
  }

  public RenderedCertificate render(String templateId, String language, String recipientName,
      String achievementTitle, String achievementSummary, String certificateNumber,
      OffsetDateTime issuedAt, CertificateDetails details, boolean sample) {
    normalizeTemplate(templateId);
    boolean hindi = "hi".equalsIgnoreCase(language);
    if (recipientName == null || recipientName.isBlank()) throw new IllegalArgumentException("A real recipient name is required.");
    if (certificateNumber == null || certificateNumber.isBlank()) throw new IllegalArgumentException("The existing certificate ID is required.");
    if (issuedAt == null) throw new IllegalArgumentException("The recorded issue date is required.");
    BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    try {
      configure(g);
      drawClassic(g);
      drawContent(g, "CLASSIC", hindi, recipientName, achievementTitle, achievementSummary,
          certificateNumber, issuedAt, details, sample);
      if (sample) drawSampleWatermark(g, hindi);
      else { drawVerificationQr(g, certificateNumber, hindi); drawVerificationBarcode(g, certificateNumber, hindi); }
    } finally { g.dispose(); }
    try {
      ByteArrayOutputStream png = new ByteArrayOutputStream();
      if (!ImageIO.write(image, "png", png)) throw new IllegalStateException("PNG renderer is not available.");
      return new RenderedCertificate(toPdf(image), png.toByteArray());
    } catch (IOException ex) { throw new IllegalStateException("Certificate image could not be rendered.", ex); }
  }

  public byte[] renderSamplePng(String templateId, String language, String recipientName,
      String achievementTitle, String achievementSummary, String certificateNumber, OffsetDateTime issuedAt) {
    normalizeTemplate(templateId);
    boolean hindi = "hi".equalsIgnoreCase(language);
    BufferedImage image = new BufferedImage(WIDTH / 2, HEIGHT / 2, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    try {
      configure(g); g.scale(0.5, 0.5); drawClassic(g);
      drawContent(g, "CLASSIC", hindi, recipientName, achievementTitle, achievementSummary,
          certificateNumber, issuedAt, new CertificateDetails(null,null,null,null,null,null,null,null), true);
      drawSampleWatermark(g, hindi);
    } finally { g.dispose(); }
    try (ByteArrayOutputStream png = new ByteArrayOutputStream()) {
      if (!ImageIO.write(image, "png", png)) throw new IllegalStateException("PNG renderer is not available.");
      return png.toByteArray();
    } catch (IOException ex) { throw new IllegalStateException("Certificate preview could not be rendered.", ex); }
  }

  private void drawVerificationQr(Graphics2D g, String certificateNumber, boolean hindi) {
    String baseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim().replaceAll("/+$", "");
    URI baseUri;
    try {
      baseUri = URI.create(baseUrl);
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException("APP_PUBLIC_BASE_URL must be a valid canonical HTTPS website URL.", ex);
    }
    boolean localDevelopment = "http".equalsIgnoreCase(baseUri.getScheme())
        && ("localhost".equalsIgnoreCase(baseUri.getHost()) || "127.0.0.1".equals(baseUri.getHost()));
    boolean securePublicOrigin = "https".equalsIgnoreCase(baseUri.getScheme()) && baseUri.getHost() != null;
    if ((!securePublicOrigin && !localDevelopment) || baseUri.getUserInfo() != null
        || baseUri.getQuery() != null || baseUri.getFragment() != null) {
      throw new IllegalStateException("APP_PUBLIC_BASE_URL must be a canonical HTTPS origin without credentials, query, or fragment (HTTP localhost is allowed only for development).");
    }
    String verificationUrl = baseUrl + "/certificate/verify?code="
        + URLEncoder.encode(certificateNumber, StandardCharsets.UTF_8);
    try {
      BitMatrix matrix = new MultiFormatWriter().encode(verificationUrl, BarcodeFormat.QR_CODE,
          250, 250, Map.of(EncodeHintType.MARGIN, 4, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
      BufferedImage qr = MatrixToImageWriter.toBufferedImage(matrix);
      int size = 160;
      int x = 1550;
      int y = 1270;
      g.setColor(Color.WHITE);
      g.fillRect(x - 10, y - 10, size + 20, size + 20);
      g.drawImage(qr, x, y, size, size, null);
    } catch (WriterException ex) {
      throw new IllegalStateException("Certificate verification QR code could not be generated.", ex);
    }
  }

  private void drawVerificationBarcode(Graphics2D g, String certificateNumber, boolean hindi) {
    try {
      int width = 480;
      int height = 54;
      int x = 1740;
      int y = 1324;
      BitMatrix matrix = new MultiFormatWriter().encode(certificateNumber, BarcodeFormat.CODE_128,
          width, height, Map.of(EncodeHintType.MARGIN, 2));
      BufferedImage barcode = MatrixToImageWriter.toBufferedImage(matrix);
      g.setColor(Color.WHITE);
      g.fillRoundRect(x - 10, y - 5, width + 20, height + 10, 10, 10);
      g.drawImage(barcode, x, y, width, height, null);
    } catch (WriterException ex) {
      throw new IllegalStateException("Certificate verification barcode could not be generated.", ex);
    }
  }

  public static String normalizeTemplate(String value) {
    String normalized = value == null ? "CLASSIC" : value.trim().toUpperCase(Locale.ROOT);
    if (!TEMPLATE_IDS.contains(normalized)) {
      throw new IllegalArgumentException("Choose CLASSIC, MODERN, or ACADEMIC certificate design.");
    }
    return normalized;
  }

  private void configure(Graphics2D g) {
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
  }

  private void drawClassic(Graphics2D g) {
    g.setColor(PAPER); g.fillRect(0, 0, WIDTH, HEIGHT);
    // Layered navy, gold, and fine inset rules create a restrained formal certificate frame.
    g.setColor(NAVY); g.setStroke(new BasicStroke(16)); g.drawRect(34, 34, WIDTH - 68, HEIGHT - 68);
    g.setColor(GOLD); g.setStroke(new BasicStroke(4)); g.drawRect(62, 62, WIDTH - 124, HEIGHT - 124);
    g.setColor(new Color(0xD9E0E8)); g.setStroke(new BasicStroke(1.5f));
    g.drawRect(82, 82, WIDTH - 164, HEIGHT - 164);
  }

  private void drawModern(Graphics2D g) {
    g.setColor(Color.WHITE); g.fillRect(0, 0, WIDTH, HEIGHT);
    g.setColor(NAVY); g.fillRect(0, 0, WIDTH, 260); g.fillRect(0, HEIGHT - 178, WIDTH, 178);
    g.setColor(ORANGE); g.fillRect(0, 260, WIDTH, 20); g.fillRect(WIDTH - 42, 280, 42, HEIGHT - 458);
    g.setColor(PALE); g.fillRoundRect(180, 360, WIDTH - 360, HEIGHT - 650, 38, 38);
    g.setColor(new Color(0xE3E8F0)); g.setStroke(new BasicStroke(3));
    g.drawRoundRect(180, 360, WIDTH - 360, HEIGHT - 650, 38, 38);
  }

  private void drawAcademic(Graphics2D g) {
    g.setColor(new Color(0xF6F8F6)); g.fillRect(0, 0, WIDTH, HEIGHT);
    g.setColor(new Color(0x1F5A59)); g.setStroke(new BasicStroke(14));
    g.drawRoundRect(52, 52, WIDTH - 104, HEIGHT - 104, 74, 74);
    g.setColor(GOLD); g.setStroke(new BasicStroke(5));
    g.drawRoundRect(82, 82, WIDTH - 164, HEIGHT - 164, 60, 60);
    g.setColor(new Color(0xE8EEE9)); g.fillRoundRect(220, 210, WIDTH - 440, HEIGHT - 420, 48, 48);
    g.setColor(new Color(0x1F5A59)); g.setStroke(new BasicStroke(3));
    g.drawLine(560, 360, WIDTH - 560, 360); g.drawLine(560, HEIGHT - 320, WIDTH - 560, HEIGHT - 320);
    g.setColor(GOLD); g.fillOval(WIDTH / 2 - 22, 172, 44, 44);
  }

  private void drawContent(Graphics2D g, String template, boolean hindi, String recipient, String title,
      String summary, String number, OffsetDateTime issuedAt, CertificateDetails details, boolean sample) {
    if (recipient == null || recipient.isBlank()) throw new IllegalArgumentException("A real recipient name is required.");
    CertificateDetails d = details == null ? new CertificateDetails(null,null,null,null,null,null,null,null) : details;
    BufferedImage brandIcon = brandIcon();
    g.drawImage(brandIcon, 132, 95, 88, 88, null);
    drawBrandWordmark(g, 2320, 150, 58);
    g.setColor(MUTED);
    drawCentered(g, font(false, Font.BOLD, 19), "Smarter Decisions. Greater Growth.",
        2070, 192, 500);
    g.setColor(GOLD); g.setStroke(new BasicStroke(3)); g.drawLine(125, 260, WIDTH - 125, 260);

    CertificateWording wording = lockedWording(hindi ? "hi" : "en");
    String assessmentName = d.assessmentName() == null || d.assessmentName().isBlank() ? title : d.assessmentName();
    if (assessmentName == null || assessmentName.isBlank()) throw new IllegalArgumentException("A real assessment title is required.");
    int titleSize = 54;
    List<String> lines;
    do {
      lines = wrap(g, font(hindi, Font.BOLD, titleSize), assessmentName, WIDTH - 560);
      if (lines.size() <= 3 || titleSize <= 34) break;
      titleSize -= 4;
    } while (true);

    // Vertically center the award copy in the open band between the header and the
    // certificate-details footer. Longer assessment titles move the block upward to
    // preserve balanced whitespace without colliding with the footer.
    int headingY = lines.size() <= 1 ? 528 : 518 - (Math.min(lines.size(), 3) - 2) * 31;
    g.setColor(NAVY);
    drawCentered(g, font(hindi, Font.BOLD, 69), wording.title(),
        WIDTH / 2, headingY, WIDTH - 420);
    g.setColor(MUTED);
    drawCentered(g, font(hindi, Font.PLAIN, 35), wording.introduction(),
        WIDTH / 2, headingY + 72, WIDTH - 520);
    int recipientY = headingY + 188;
    g.setColor(NAVY);
    drawCentered(g, font(hindi, Font.BOLD, 82), recipient.trim(), WIDTH / 2, recipientY, WIDTH - 480);
    g.setColor(ORANGE);
    g.setStroke(new BasicStroke(5));
    g.drawLine(WIDTH / 2 - 350, recipientY + 32, WIDTH / 2 + 350, recipientY + 32);
    g.setColor(MUTED);
    drawCentered(g, font(hindi, Font.PLAIN, 34), wording.connector(), WIDTH / 2, recipientY + 94, WIDTH - 520);

    g.setColor(NAVY);
    int titleY = headingY + 356;
    int titleStep = titleSize + 7;
    for (String line : lines) {
      drawCentered(g, font(hindi, Font.BOLD, titleSize), line, WIDTH / 2, titleY, WIDTH - 560);
      titleY += titleStep;
    }
    int completionY = Math.max(titleY + 1, headingY + 458);
    g.setColor(INK);
    drawCentered(g, font(hindi, Font.PLAIN, 30), wording.completion(),
        WIDTH / 2, completionY, WIDTH - 560);

    List<String> fields = new ArrayList<>();
    if (d.subjectName() != null && !d.subjectName().isBlank()) fields.add((hindi ? "विषय: " : "Subject: ") + d.subjectName());
    if (d.classCode() != null && !d.classCode().isBlank()) fields.add((hindi ? "कक्षा: " : "Class: ") + d.classCode());
    if (d.score() != null && !d.score().isBlank()) fields.add((hindi ? "अंक: " : "Score: ") + d.score());
    if (d.result() != null && !d.result().isBlank()) fields.add((hindi ? "परिणाम: " : "Result: ") + d.result());
    int detailY = Math.min(1090, Math.max(980, completionY + 72));
    int detailRows = 0;
    if (!fields.isEmpty()) {
      if (fields.size() >= 3) {
        int columnWidth = (WIDTH - 600) / 2;
        for (int i = 0; i < fields.size(); i++) {
          int row = i / 2;
          int center = fields.size() % 2 == 1 && i == fields.size() - 1
              ? WIDTH / 2 : 300 + (i % 2) * columnWidth + columnWidth / 2;
          g.setColor(i % 2 == 0 ? NAVY : INK);
          drawCentered(g, font(hindi, Font.BOLD, 23), fields.get(i), center,
              detailY + row * 42, fields.size() % 2 == 1 && i == fields.size() - 1 ? WIDTH - 600 : columnWidth - 30);
          detailRows = Math.max(detailRows, row + 1);
        }
      } else {
        int columnWidth = (WIDTH - 600) / Math.max(fields.size(), 1);
        for (int i = 0; i < fields.size(); i++) {
          g.setColor(i % 2 == 0 ? NAVY : INK);
          drawCentered(g, font(hindi, Font.BOLD, 23), fields.get(i),
              300 + i * columnWidth + columnWidth / 2, detailY, columnWidth - 30);
        }
        detailRows = 1;
      }
    }
    if (d.assessmentDate() != null && !d.assessmentDate().isBlank()) {
      g.setColor(MUTED);
      int dateY = Math.min(1160, detailY + Math.max(detailRows, 1) * 42 + 28);
      drawCentered(g, font(hindi, Font.PLAIN, 22),
          (hindi ? "मूल्यांकन दिनांक: " : "Assessment Date: ") + formatDate(d.assessmentDate(), hindi),
          WIDTH / 2, dateY, WIDTH - 600);
    }

    g.setColor(new Color(0xD9D0BC)); g.setStroke(new BasicStroke(2)); g.drawLine(125, 1210, WIDTH - 125, 1210);
    g.setColor(DEEP_ORANGE); drawCentered(g, font(false, Font.BOLD, 19), hindi ? "अधिकृत हस्ताक्षर" : "AUTHORIZED BY", 420, 1257, 540);
    if (d.signatoryName() != null && !d.signatoryName().isBlank()) {
      g.setColor(NAVY); drawCentered(g, font(hindi, Font.BOLD, 25), d.signatoryName(), 420, 1308, 540);
      if (d.signatoryRole() != null && !d.signatoryRole().isBlank()) {
        g.setColor(MUTED); drawCentered(g, font(hindi, Font.PLAIN, 18),
            (hindi ? "परीक्षा निर्माता · " : "Test creator · ") + d.signatoryRole().replace('_', ' '), 420, 1345, 540);
      }
    } else {
      g.setColor(NAVY); drawCentered(g, font(hindi, Font.BOLD, 24), hindi ? "डिजिटल रूप से जारी" : "DIGITALLY ISSUED", 420, 1310, 540);
    }
    g.setColor(DEEP_ORANGE); drawCentered(g, font(hindi, Font.BOLD, 19), hindi ? "प्रमाणपत्र विवरण" : "CERTIFICATE DETAILS", 1080, 1257, 560);
    g.setColor(NAVY);
    drawCentered(g, font(hindi, Font.BOLD, 22),
        (hindi ? "प्रमाणपत्र संख्या: " : "Certificate No.: ") + (sample ? (hindi ? "नमूना — कोई ID नहीं" : "SAMPLE — NO CERTIFICATE ID") : number),
        1080, 1308, 650);
    g.setColor(MUTED);
    drawCentered(g, font(hindi, Font.PLAIN, 21),
        (hindi ? "जारी दिनांक: " : "Issue Date: ") + (sample ? (hindi ? "नमूना पूर्वावलोकन" : "Preview only") : formatDate(issuedAt.toString(), hindi)),
        1080, 1350, 650);
    g.setColor(DEEP_ORANGE); drawCentered(g, font(hindi, Font.BOLD, 19), hindi ? "सत्यापन" : "VERIFICATION", 1930, 1257, 800);
    if (!sample) {
      drawCentered(g, font(hindi, Font.BOLD, 15), hindi ? "प्रमाणपत्र बारकोड" : "CERTIFICATE BARCODE", 1980, 1311, 500);
      g.setColor(MUTED); drawCentered(g, font(false, Font.BOLD, 14), number, 1980, 1400, 500);
      drawCentered(g, font(hindi, Font.PLAIN, 14), hindi ? "QR से सत्यापित करें" : "SCAN QR TO VERIFY", 1630, 1460, 230);
    } else {
      g.setColor(MUTED); drawCentered(g, font(false, Font.BOLD, 17), "SAMPLE • NOT VALID", 1930, 1320, 800);
    }
    g.setColor(MUTED);
    drawCentered(g, font(false, Font.BOLD, 18), "OFFICIALLY ISSUED BY QUANTAEDGE", WIDTH / 2, 1545, 720);
  }

  private BufferedImage brandIcon() {
    BufferedImage image = cachedBrandIcon;
    if (image != null) return image;
    synchronized (this) {
      image = cachedBrandIcon;
      if (image != null) return image;
      try (InputStream input = getClass().getResourceAsStream("/branding/quantaedge-icon.png")) {
        if (input == null) throw new IllegalStateException("Canonical QuantaEdge icon resource is missing.");
        image = ImageIO.read(input);
        if (image == null) throw new IllegalStateException("Canonical QuantaEdge icon is not a readable PNG.");
        cachedBrandIcon = image;
        return image;
      } catch (IOException ex) {
        throw new IllegalStateException("Could not load canonical QuantaEdge icon.", ex);
      }
    }
  }

  private void drawBrandWordmark(Graphics2D g, int rightX, int baseline, int size) {
    Font font = font(false, Font.BOLD, size);
    TextLayout quanta = new TextLayout(BRAND_NAME.substring(0, 6), font, g.getFontRenderContext());
    TextLayout edge = new TextLayout(BRAND_NAME.substring(6), font, g.getFontRenderContext());
    float startX = (float) (rightX - quanta.getAdvance() - edge.getAdvance());
    g.setColor(NAVY);
    quanta.draw(g, startX, baseline);
    g.setColor(ORANGE);
    edge.draw(g, startX + quanta.getAdvance(), baseline);
  }

  private String formatDate(String raw, boolean hindi) {
    if (raw == null || raw.isBlank()) return "";
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(hindi ? "dd MMMM yyyy" : "MMMM d, yyyy",
        hindi ? Locale.forLanguageTag("hi-IN") : Locale.US);
    try {
      String value = raw.trim();
      return LocalDate.parse(value.length() >= 10 ? value.substring(0, 10) : value).format(formatter);
    } catch (RuntimeException ignored) {
      try { return OffsetDateTime.parse(raw.trim()).atZoneSameInstant(ZoneId.of("Asia/Kolkata")).format(formatter); }
      catch (RuntimeException ex) { return raw; }
    }
  }

  private void drawSampleWatermark(Graphics2D g, boolean hindi) {
    Graphics2D w = (Graphics2D) g.create();
    try {
      w.rotate(-Math.PI / 7, WIDTH / 2.0, HEIGHT / 2.0);
      w.setColor(new Color(249, 115, 22, 52));
      drawCentered(w, font(hindi, Font.BOLD, 148),
          hindi ? "नमूना • मान्य नहीं" : "SAMPLE • NOT VALID", WIDTH / 2, HEIGHT / 2, WIDTH + 150);
    } finally { w.dispose(); }
  }

  private int drawWrappedCentered(Graphics2D g, Font font, String value, int centerX,
      int baseline, int maxWidth, double lineSpacing) {
    if (value == null || value.isBlank()) return baseline;
    List<String> lines = wrap(g, font, value, maxWidth);
    int y = baseline;
    int step = (int) Math.round(font.getSize2D() * lineSpacing);
    for (String line : lines) { drawCentered(g, font, line, centerX, y, maxWidth); y += step; }
    return y - step;
  }

  private List<String> wrap(Graphics2D g, Font font, String value, int maxWidth) {
    List<String> result = new ArrayList<>();
    StringBuilder line = new StringBuilder();
    for (String word : value.trim().split("\\s+")) {
      String candidate = line.isEmpty() ? word : line + " " + word;
      if (!line.isEmpty() && measuredWidth(g, font, candidate) > maxWidth) {
        result.add(line.toString()); line = new StringBuilder(word);
      } else { line.setLength(0); line.append(candidate); }
    }
    if (!line.isEmpty()) result.add(line.toString());
    return result;
  }

  private int measuredWidth(Graphics2D g, Font font, String value) {
    if (value == null || value.isEmpty()) return 0;
    return (int) Math.ceil(new TextLayout(value, font, g.getFontRenderContext()).getAdvance());
  }

  private void drawCentered(Graphics2D g, Font font, String value, int centerX, int baseline, int maxWidth) {
    if (value == null || value.isBlank()) return;
    Font effective = font;
    while (effective.getSize2D() > 15 && measuredWidth(g, effective, value) > maxWidth) {
      effective = effective.deriveFont(effective.getSize2D() - 2f);
    }
    TextLayout layout = new TextLayout(value, effective, g.getFontRenderContext());
    layout.draw(g, centerX - layout.getAdvance() / 2.0f, baseline);
  }

  private Font font(boolean hindi, int style, int size) {
    return new Font(fontFamily(hindi), style, size);
  }

  private String fontFamily(boolean hindi) {
    String[] candidates = hindi
        ? new String[]{"Noto Sans Devanagari", "Mangal", "Nirmala UI", "Lohit Devanagari", "Dialog"}
        : new String[]{"Noto Sans", "DejaVu Sans", "Liberation Sans", "Arial", "Dialog"};
    String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
    for (String candidate : candidates) {
      for (String actual : available) {
        if (candidate.equalsIgnoreCase(actual)) return candidate;
      }
    }
    return "Dialog";
  }

  private byte[] toPdf(BufferedImage image) throws IOException {
    try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      PDPage page = new PDPage(new PDRectangle(841.89f, 595.28f));
      document.addPage(page);
      PDImageXObject embedded = LosslessFactory.createFromImage(document, image);
      try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
        stream.drawImage(embedded, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
      }
      document.save(output);
      return output.toByteArray();
    }
  }

  private String nonblank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
