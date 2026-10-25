package VulcanIDE;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.Icon;

/** Image icon surrounded by a one-pixel outline. */
final class ImageFrameIcon implements Icon {
    private final BufferedImage image;private final Color frame;
    ImageFrameIcon(BufferedImage image,Color frame){this.image=image;this.frame=frame;}
    public int getIconWidth(){return image.getWidth()+2;}
    public int getIconHeight(){return image.getHeight()+2;}
    /** Paints the outline and the image inside it. */
    public void paintIcon(Component c,Graphics g,int x,int y){
        Graphics copy=g.create();
        try {
            copy.setClip(x+1,y+1,image.getWidth(),image.getHeight());
            for(int row=0;row<image.getHeight();row+=12)for(int col=0;col<image.getWidth();col+=12){
                copy.setColor(((row/12+col/12)&1)==0?new Color(220,220,220):new Color(180,180,180));copy.fillRect(x+1+col,y+1+row,12,12);
            }
            copy.drawImage(image,x+1,y+1,null);
        }finally{copy.dispose();}
        g.setColor(frame);g.drawRect(x,y,getIconWidth()-1,getIconHeight()-1);
    }
}
