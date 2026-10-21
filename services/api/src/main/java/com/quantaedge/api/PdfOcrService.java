package com.quantaedge.api;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

/**
 * Optional OCR adapter for scanned textbooks.
 * It only extracts candidate text; chapter assignment and publication remain manual decisions.
 */
@Component
class PdfOcrService {
  static final int MAX_PAGES_PER_ANALYSIS = 250;
  private static final float RENDER_DPI = 130f;
  private static final long PAGE_TIMEOUT_SECONDS = 25;
  private static final long PROBE_TIMEOUT_SECONDS = 8;
  private static final long MAX_OUTPUT_BYTES = 2L * 1024 * 1024;

  boolean supportsLanguage(String bookLanguage) {
    Path output = null;
    try {
      output = Files.createTempFile("quantaedge-tesseract-langs-", ".txt");
      Process process = new ProcessBuilder("tesseract", "--list-langs")
          .redirectOutput(output.toFile())
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start();
      if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return false;
      }
      if (process.exitValue() != 0) return false;
      List<String> languages = Files.readAllLines(output, StandardCharsets.UTF_8).stream()
          .map(String::trim).filter(line -> !line.isEmpty()).toList();
      if (!languages.contains("eng")) return false;
      return !"hi".equalsIgnoreCase(bookLanguage) || languages.contains("hin");
    } catch (IOException ex) {
      return false;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return false;
    } finally {
      if (output != null) {
        try { Files.deleteIfExists(output); } catch (IOException ignored) { }
      }
    }
  }

  String extractPageText(PDDocument document, int zeroBasedPage, String bookLanguage) throws IOException {
    Path imagePath = null;
    Path outputPath = null;
    Path errorPath = null;
    try {
      BufferedImage image = new PDFRenderer(document).renderImageWithDPI(zeroBasedPage, RENDER_DPI, ImageType.GRAY);
      imagePath = Files.createTempFile("quantaedge-ocr-page-", ".png");
      outputPath = Files.createTempFile("quantaedge-ocr-output-", ".txt");
      errorPath = Files.createTempFile("quantaedge-ocr-error-", ".txt");
      if (!ImageIO.write(image, "png", imagePath.toFile())) {
        throw new IOException("The PDF page could not be rendered for OCR.");
      }
      String languages = "hi".equalsIgnoreCase(bookLanguage) ? "hin+eng" : "eng";
      Process process = new ProcessBuilder(
          "tesseract", imagePath.toString(), "stdout", "-l", languages, "--psm", "3")
          .redirectOutput(outputPath.toFile())
          .redirectError(errorPath.toFile())
          .start();
      if (!process.waitFor(PAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IOException("OCR timed out for PDF page " + (zeroBasedPage + 1) + ".");
      }
      if (process.exitValue() != 0) {
        String detail = Files.exists(errorPath) ? Files.readString(errorPath, StandardCharsets.UTF_8) : "";
        if (detail.length() > 300) detail = detail.substring(0, 300);
        throw new IOException("OCR failed for PDF page " + (zeroBasedPage + 1)
            + (detail.isBlank() ? "." : ": " + detail.trim()));
      }
      long outputBytes = Files.size(outputPath);
      if (outputBytes > MAX_OUTPUT_BYTES) {
        throw new IOException("OCR output was unexpectedly large for PDF page " + (zeroBasedPage + 1) + ".");
      }
      return Files.readString(outputPath, StandardCharsets.UTF_8);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IOException("OCR was interrupted.", ex);
    } finally {
      if (imagePath != null) try { Files.deleteIfExists(imagePath); } catch (IOException ignored) { }
      if (outputPath != null) try { Files.deleteIfExists(outputPath); } catch (IOException ignored) { }
      if (errorPath != null) try { Files.deleteIfExists(errorPath); } catch (IOException ignored) { }
    }
  }
}
