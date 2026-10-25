package VulcanIDE;

/** Vector icon for Java files. */
final class IntellijJavaIcon implements javax.swing.Icon {
    private final int width, height;
    IntellijJavaIcon(int width, int height) { this.width=width; this.height=height; }
    public int getIconWidth() { return width; }
    public int getIconHeight() { return height; }
    /** Paints the Java file icon. */
    public void paintIcon(java.awt.Component c, java.awt.Graphics graphics, int x, int y) {
        java.awt.Graphics2D g=(java.awt.Graphics2D)graphics.create();
        try {
            g.translate(x,y); g.scale(width/16.0,height/16.0);
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new java.awt.Color(0xE66D17));
            g.fill(new java.awt.geom.RoundRectangle2D.Double(2,13,12,1,1,1));
            java.awt.geom.Path2D.Double p0=new java.awt.geom.Path2D.Double();
            p0.moveTo(2.5,3.6);
            p0.curveTo(2.5,3.31172,2.50039,3.12559,2.51196,2.98392);
            p0.curveTo(2.52306,2.84809,2.5419,2.79773,2.5545,2.773);
            p0.curveTo(2.60243,2.67892,2.67892,2.60243,2.773,2.5545);
            p0.curveTo(2.79773,2.5419,2.84809,2.52306,2.98392,2.51196);
            p0.curveTo(3.12559,2.50039,3.31172,2.5,3.6,2.5);
            p0.lineTo(11.4,2.5);
            p0.curveTo(11.6883,2.5,11.8744,2.50039,12.0161,2.51196);
            p0.curveTo(12.1519,2.52306,12.2023,2.5419,12.227,2.5545);
            p0.curveTo(12.3211,2.60243,12.3976,2.67892,12.4455,2.773);
            p0.lineTo(12.8864,2.54834);
            p0.lineTo(12.4455,2.773);
            p0.curveTo(12.4581,2.79773,12.4769,2.84809,12.488,2.98392);
            p0.curveTo(12.4996,3.12559,12.5,3.31172,12.5,3.6);
            p0.lineTo(12.5,6.5);
            p0.curveTo(12.5,7.93194,12.4946,8.53707,12.3493,9.016);
            p0.curveTo(12.01,10.1346,11.1346,11.01,10.016,11.3493);
            p0.curveTo(9.53707,11.4946,8.93194,11.5,7.5,11.5);
            p0.curveTo(6.06806,11.5,5.46293,11.4946,4.984,11.3493);
            p0.curveTo(3.8654,11.01,2.99003,10.1346,2.65071,9.016);
            p0.curveTo(2.50543,8.53707,2.5,7.93194,2.5,6.5);
            p0.lineTo(2.5,3.6);
            p0.closePath();
            g.setColor(new java.awt.Color(0xFFF4EB));
            g.fill(p0);
            g.setColor(new java.awt.Color(0xE66D17));
            g.draw(p0);
            java.awt.geom.Path2D.Double p1=new java.awt.geom.Path2D.Double();
            p1.moveTo(12.5,2.5);
            p1.lineTo(14.5,2.5);
            p1.curveTo(14.7761,2.5,15,2.72386,15,3);
            p1.lineTo(15,4.18121);
            p1.curveTo(15,4.78125,14.6424,5.32356,14.0909,5.55993);
            p1.lineTo(12.5,6.24173);
            p1.lineTo(12.5,2.5);
            p1.closePath();
            g.setColor(new java.awt.Color(0xE66D17));
            g.draw(p1);
        } finally { g.dispose(); }
    }
}
