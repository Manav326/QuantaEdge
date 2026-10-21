package com.quantaedge.api;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;

/** Watermarked PDF rendering for the HRMS design preview only, never for fiscal issuance. */
@Service
public class InvoicePreviewPdfRenderer {
  private static final int WIDTH=1240;
  private static final int HEIGHT=1754;
  private static final Color INK=new Color(0x152038);
  private static final Color MUTED=new Color(0x697589);
  private static final Color LINE=new Color(0xE3E6EB);

  public record RenderedPreview(byte[] pdf,byte[] png) {}

  public RenderedPreview renderPreview(Map<String,Object> design) {
    Map<String,Object> d=design==null?Map.of():design;
    Color primary=color(d.get("primaryColor"),new Color(0xF97316));
    Color accent=color(d.get("accentColor"),new Color(0x0F766E));
    String surface=String.valueOf(d.getOrDefault("surfaceStyle","WARM_PAPER"));
    Color paper=switch(surface) {
      case "CLEAN_WHITE" -> Color.WHITE;
      case "SOFT_SLATE" -> new Color(0xF1F5F9);
      default -> new Color(0xFCF9F2);
    };
    boolean showLogo=Boolean.TRUE.equals(d.get("showLogo"));
    String layout=String.valueOf(d.getOrDefault("layout","EDITORIAL"));
    String footer=safe(String.valueOf(d.getOrDefault("footerText","Thank you for learning with us.")),160);
    BufferedImage image=new BufferedImage(WIDTH,HEIGHT,BufferedImage.TYPE_INT_RGB);
    Graphics2D g=image.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
      g.setColor(paper);g.fillRect(0,0,WIDTH,HEIGHT);
      g.setPaint(new java.awt.GradientPaint(0,0,primary,WIDTH,0,accent));g.fillRect(0,0,WIDTH,13);
      g.setColor(Color.WHITE);g.fillRoundRect(66,62,1108,164,28,28);
      if(showLogo) {
        g.setColor(primary);g.fillRoundRect(94,91,76,76,22,22);
        g.setColor(Color.WHITE);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,24));g.drawString("QE",112,138);
      }
      int supplierX=showLogo?194:94;
      g.setColor(INK);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,25));g.drawString("LEGAL SUPPLIER NAME",supplierX,112);
      g.setColor(MUTED);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,16));
      g.drawString("Registered business address - State and PIN",supplierX,145);
      g.drawString("GSTIN: configured approved registration",supplierX,174);
      g.setColor(primary);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,24));g.drawString("TAX INVOICE",824,103);
      g.setColor(INK);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,16));g.drawString("PREVIEW - NOT ISSUED",824,137);
      g.setColor(MUTED);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,14));g.drawString("Illustrative layout only",824,165);

      g.setColor(primary);g.setStroke(new BasicStroke(3));g.drawLine(66,256,1174,256);
      g.setColor(accent);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,16));g.drawString("BILL TO",94,305);
      g.setColor(INK);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,20));g.drawString("Customer billing name",94,340);
      g.setColor(MUTED);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,15));
      g.drawString("Billing address and state",94,371);g.drawString("GSTIN, where applicable",94,398);
      g.setColor(accent);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,16));g.drawString("COURSE / ORDER",650,305);
      g.setColor(INK);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,20));g.drawString("Class 6 Mathematics",650,340);
      g.setColor(MUTED);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,15));
      g.drawString("Course order reference - preview",650,371);g.drawString("Beneficiary: learner example",650,398);

      int tableTop=456;
      g.setColor(INK);g.fillRoundRect(66,tableTop,1108,58,12,12);
      g.setColor(Color.WHITE);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,14));
      g.drawString("DESCRIPTION",92,tableTop+35);g.drawString("QTY",810,tableTop+35);g.drawString("VALUE",1005,tableTop+35);
      g.setColor(Color.WHITE);g.fillRect(66,tableTop+58,1108,168);
      g.setColor(LINE);g.setStroke(new BasicStroke(1));g.drawLine(66,tableTop+58,1174,tableTop+58);g.drawLine(66,tableTop+133,1174,tableTop+133);g.drawLine(66,tableTop+226,1174,tableTop+226);
      g.setColor(INK);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,17));g.drawString("Course access",92,tableTop+97);
      g.setColor(MUTED);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,13));g.drawString("Illustrative line item - classification supplied by finance",92,tableTop+119);
      g.setColor(INK);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,16));g.drawString("1",817,tableTop+99);g.drawString("INR 1,000.00",1000,tableTop+99);
      g.setColor(INK);g.drawString("Offer discount",92,tableTop+173);g.drawString("-",817,tableTop+173);g.drawString("- INR 100.00",1000,tableTop+173);

      g.setColor(accent);g.fillRoundRect(66,740,530,210,18,18);
      g.setColor(Color.WHITE);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,18));g.drawString("TAX BREAKDOWN",92,783);
      g.setColor(new Color(0xF3F7F7));g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,15));
      g.drawString("Tax components and rate come from the",92,820);g.drawString("approved transaction snapshot, not this sample.",92,848);
      g.drawString("Order / payment reference: preview only",92,905);

      g.setColor(INK);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,15));g.drawString("Discounted value",650,785);
      g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,18));g.drawString("INR 900.00 - sample only",930,785);
      g.setColor(MUTED);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,14));g.drawString("Applicable tax",650,833);
      g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,14));g.drawString("Calculated per approved profile",875,833);
      g.setColor(primary);g.fillRoundRect(632,864,542,86,16,16);
      g.setColor(Color.WHITE);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,18));g.drawString("TOTAL",660,914);
      g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,17));g.drawString("Final amount determined per sale",820,914);

      g.setColor(LINE);g.drawLine(66,1050,1174,1050);
      g.setColor(INK);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,18));
      String[] footerLines=wrap(g,footer,760);
      int fy=1090;for(String line:footerLines){g.drawString(line,66,fy);fy+=26;}
      g.setColor(MUTED);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,13));
      g.drawString("Supplier particulars, statutory text and authentication come from the approved invoice snapshot.",66,fy+12);
      g.setColor(new Color(primary.getRed(),primary.getGreen(),primary.getBlue(),30));
      AffineTransform old=g.getTransform();g.rotate(-Math.PI/9,WIDTH/2.0,HEIGHT/2.0);
      g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,54));g.drawString("PREVIEW - NOT A TAX INVOICE",185,890);g.setTransform(old);
      g.setColor(accent);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,12));g.drawString(layout.equals("CLASSIC")?"CLASSIC LAYOUT PREVIEW":"EDITORIAL LAYOUT PREVIEW",66,HEIGHT-68);
      g.setColor(MUTED);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,12));g.drawString("QuantaEdge Finance Studio - sample only",66,HEIGHT-43);
    } finally {g.dispose();}

    try {
      byte[] pngBytes;
      try(ByteArrayOutputStream pngOut=new ByteArrayOutputStream()) {
        if(!ImageIO.write(image,"png",pngOut))throw new IOException("PNG encoder is unavailable.");
        pngBytes=pngOut.toByteArray();
      }
      byte[] pdfBytes;
      try(PDDocument document=new PDDocument();ByteArrayOutputStream pdfOut=new ByteArrayOutputStream()) {
        PDPage pdfPage=new PDPage(PDRectangle.A4);document.addPage(pdfPage);
        PDImageXObject embedded=LosslessFactory.createFromImage(document,image);
        try(PDPageContentStream stream=new PDPageContentStream(document,pdfPage)) {
          stream.drawImage(embedded,0,0,pdfPage.getMediaBox().getWidth(),pdfPage.getMediaBox().getHeight());
        }
        document.save(pdfOut);pdfBytes=pdfOut.toByteArray();
      }
      return new RenderedPreview(pdfBytes,pngBytes);
    } catch(IOException ex) {
      throw new IllegalStateException("Invoice design preview could not be rendered.",ex);
    }
  }

  private static Color color(Object raw,Color fallback) {
    if(raw==null)return fallback;
    String value=String.valueOf(raw);
    if(!value.matches("#[0-9A-Fa-f]{6}"))return fallback;
    return new Color(Integer.parseInt(value.substring(1),16));
  }

  private static String safe(String raw,int max) {
    String clean=raw==null?"":raw.replaceAll("[\\p{Cntrl}]"," ").trim();
    return clean.length()>max?clean.substring(0,max):clean;
  }

  private static String[] wrap(Graphics2D g,String text,int width) {
    String clean=safe(text,500);
    java.util.List<String> lines=new java.util.ArrayList<>();
    StringBuilder line=new StringBuilder();
    for(String word:clean.split("\\s+")) {
      String candidate=line.isEmpty()?word:line+" "+word;
      if(g.getFontMetrics().stringWidth(candidate)>width&&!line.isEmpty()) {
        lines.add(line.toString());line.setLength(0);line.append(word);
      } else {if(!line.isEmpty())line.append(' ');line.append(word);}
      if(lines.size()>=3)break;
    }
    if(!line.isEmpty()&&lines.size()<3)lines.add(line.toString());
    return lines.toArray(String[]::new);
  }
}
