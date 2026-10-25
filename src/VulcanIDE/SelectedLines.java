package VulcanIDE;
import javax.swing.*;
import javax.swing.text.*;

/** Indents, unindents, and toggles comments on the selected lines. */
final class SelectedLines {
    /** Line operation to apply. */
    enum Operation { INDENT, UNINDENT, COMMENT }
    static void apply(JTextPane editor,Operation operation,String marker){
        if(!editor.isEditable())return;
        try {
            Document document=editor.getDocument();String text=document.getText(0,document.getLength());
            int a=editor.getSelectionStart(),b=editor.getSelectionEnd();
            int start=text.lastIndexOf('\n',Math.max(-1,a-1))+1;
            int last=b>a?b-1:b;int end=text.indexOf('\n',last);if(end<0)end=text.length();
            String[] lines=text.substring(start,end).split("\n",-1);
            boolean uncomment=operation==Operation.COMMENT;
            for(String line:lines)if(!line.isBlank()&&!line.stripLeading().startsWith(marker))uncomment=false;
            for(int i=0;i<lines.length;i++) {
                String line=lines[i];int indent=0;while(indent<line.length()&&(line.charAt(indent)==' '||line.charAt(indent)=='\t'))indent++;
                if(operation==Operation.INDENT)lines[i]="\t"+line;
                else if(operation==Operation.UNINDENT){int n=line.startsWith("\t")?1:Math.min(4,indent);lines[i]=line.substring(n);}
                else if(!line.isBlank()) {
                    if(uncomment){int n=indent+marker.length();if(n<line.length()&&line.charAt(n)==' ')n++;lines[i]=line.substring(0,indent)+line.substring(n);}
                    else lines[i]=line.substring(0,indent)+marker+" "+line.substring(indent);
                }
            }
            String replacement=String.join("\n",lines);
            ((AbstractDocument)document).replace(start,end-start,replacement,null);
            if(a!=b)editor.select(start,start+replacement.length());else editor.setCaretPosition(Math.min(start+replacement.length(),start+Math.max(0,a-start+(replacement.length()-(end-start)))));
        }catch(BadLocationException error){throw new IllegalStateException(error);}
    }
}
