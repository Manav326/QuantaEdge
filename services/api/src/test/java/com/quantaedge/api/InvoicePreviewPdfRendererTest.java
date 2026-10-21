package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;

class InvoicePreviewPdfRendererTest {
  private final InvoicePreviewPdfRenderer renderer=new InvoicePreviewPdfRenderer();

  @Test
  void rendersValidSinglePageA4PdfAndPngPreview() throws Exception {
    InvoicePreviewPdfRenderer.RenderedPreview rendered=renderer.renderPreview(Map.of(
        "primaryColor","#F97316","accentColor","#0F766E",
        "surfaceStyle","WARM_PAPER","layout","EDITORIAL","showLogo",true,
        "footerText","Thank you for learning with us."));
    assertTrue(rendered.pdf().length>1_000);
    assertTrue(rendered.png().length>1_000);
    try(var document=Loader.loadPDF(rendered.pdf())) {
      assertEquals(1,document.getNumberOfPages());
      assertTrue(document.getPage(0).getMediaBox().getHeight()>document.getPage(0).getMediaBox().getWidth());
    }
    assertTrue(javax.imageio.ImageIO.read(new ByteArrayInputStream(rendered.png())).getWidth()>1000);
  }

  @Test
  void unknownDesignOptionsFallBackToSafePreviewDefaults() throws Exception {
    var rendered=renderer.renderPreview(Map.of("primaryColor","not-a-colour","surfaceStyle","INJECTED"));
    try(var document=Loader.loadPDF(rendered.pdf())) {
      assertEquals(1,document.getNumberOfPages());
    }
  }
}
