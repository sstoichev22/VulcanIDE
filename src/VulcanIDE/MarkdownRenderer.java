package VulcanIDE;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Renders Markdown to safe Swing HTML, blocking remote and escaping local images. */
final class MarkdownRenderer {
    private MarkdownRenderer() {}
    static String render(String source, Path file, int size, String family) {
        StringBuilder html=new StringBuilder("<html><head><style>body,pre,code{font-family:"+escape(family)+";font-size:"+size+"pt;}body{color:#dddfe5;margin:20px;}h1,h2,h3{color:#f1f3f8;}pre{background:#20242b;padding:12px;}code{color:#edc381;}blockquote{color:#a9bbce;}table{border-collapse:collapse;}td,th{border:1px solid #606874;padding:6px;}a{color:#77b8f8;}</style></head><body>");
        String[] lines=(source==null?"":source).replace("\r", "").split("\n",-1);
        boolean fenced=false;String fence="";String list=null;boolean quote=false;java.util.Set<String> headingIds=new java.util.HashSet<>();
        for(int i=0;i<lines.length;i++) {
            String line=lines[i], trimmed=line.trim();
            if(trimmed.startsWith("```")||trimmed.startsWith("~~~")) {
                if(!fenced){if(list!=null){html.append("</").append(list).append('>');list=null;}fence=trimmed.substring(0,3);html.append("<pre><code>");fenced=true;}
                else if(trimmed.startsWith(fence)){html.append("</code></pre>");fenced=false;}
                else html.append(escape(line)).append('\n');
                continue;
            }
            if(fenced){html.append(escape(line)).append('\n');continue;}
            boolean unordered=trimmed.matches("[-*+]\\s+.*"), ordered=trimmed.matches("\\d+\\.\\s+.*");
            String nextList=unordered?"ul":ordered?"ol":null;
            if(list!=null&&!list.equals(nextList)){html.append("</").append(list).append('>');list=null;}
            if(nextList!=null){if(list==null){list=nextList;html.append('<').append(list).append('>');}html.append("<li>").append(inline(trimmed.replaceFirst("^(?:[-*+]|\\d+\\.)\\s+", ""),file)).append("</li>");continue;}
            if(trimmed.isEmpty()){if(quote){html.append("</blockquote>");quote=false;}continue;}
            if(i+1<lines.length&&line.contains("|")&&lines[i+1].trim().matches("\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?")) {
                html.append("<table>");tableRow(html,line,"th",file);i++;
                while(i+1<lines.length&&!lines[i+1].isBlank()&&lines[i+1].contains("|"))tableRow(html,lines[++i],"td",file);
                html.append("</table>");continue;
            }
            Matcher heading=Pattern.compile("^(#{1,6})\\s+(.+?)(?:\\s+#+)?$").matcher(trimmed);
            if(heading.matches()){int level=heading.group(1).length();String base=slug(heading.group(2)),anchor=base;for(int duplicate=1;!headingIds.add(anchor);duplicate++)anchor=base+"-"+duplicate;html.append("<h").append(level).append(" id='").append(escape(anchor)).append("'>").append(inline(heading.group(2),file)).append("</h").append(level).append('>');}
            else if(trimmed.matches("(?:-{3,}|\\*{3,}|_{3,})"))html.append("<hr>");
            else if(trimmed.startsWith(">")){if(!quote){html.append("<blockquote>");quote=true;}html.append(inline(trimmed.substring(1).stripLeading(),file)).append("<br>");}
            else {if(quote){html.append("</blockquote>");quote=false;}html.append("<p>").append(inline(line,file)).append("</p>");}
        }
        if(fenced)html.append("</code></pre>");if(list!=null)html.append("</").append(list).append('>');if(quote)html.append("</blockquote>");
        return html.append("</body></html>").toString();
    }
    private static void tableRow(StringBuilder html,String line,String cell,Path file){html.append("<tr>");for(String column:line.trim().replaceAll("^\\||\\|$", "").split("\\|",-1))html.append('<').append(cell).append('>').append(inline(column.trim(),file)).append("</").append(cell).append('>');html.append("</tr>");}
    private static String inline(String line,Path file) {
        StringBuilder result=new StringBuilder();
        Matcher tokens=Pattern.compile("`([^`]+)`|(!?)\\[([^]\\n]*)]\\(([^)\\n]+)\\)").matcher(line);int last=0;
        while(tokens.find()) {
            result.append(emphasis(escape(line.substring(last,tokens.start()))));
            if(tokens.group(1)!=null)result.append("<code>").append(escape(tokens.group(1))).append("</code>");
            else {
                String destination=tokens.group(4).trim(),label=escape(tokens.group(3));
                if(tokens.group(2).equals("!")) {
                    Path image=localPath(file,destination);
                    if(image!=null&&Files.isRegularFile(image)&&image.toString().toLowerCase().matches(".*\\.(png|gif|jpe?g)$"))result.append("<img src='").append(escape(image.toUri().toString())).append("' alt='").append(label).append("' width='320'>");
                    else result.append("<i>[image: ").append(label).append("]</i>");
                } else if(safeLink(destination)) result.append("<a href='").append(escape(destination)).append("'>").append(label).append("</a>");
                else result.append(label);
            }
            last=tokens.end();
        }
        return result.append(emphasis(escape(line.substring(last)))).toString();
    }
    static boolean safeLink(String destination){return destination!=null&&!destination.isBlank()&&!destination.contains("\\")&&(destination.startsWith("#")||destination.matches("(?i)https?://.*")||!destination.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*"));}
    static Path localPath(Path file,String destination){try{if(file==null||destination.contains(":")||destination.startsWith("//"))return null;Path parent=file.toAbsolutePath().normalize().getParent(),path=parent.resolve(destination).normalize();return path.startsWith(parent)?path:null;}catch(RuntimeException e){return null;}}
    private static String emphasis(String text){return text.replaceAll("\\*\\*(.+?)\\*\\*","<b>$1</b>").replaceAll("__(.+?)__","<b>$1</b>").replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)","<i>$1</i>").replaceAll("~~(.+?)~~","<strike>$1</strike>");}
    static String escape(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    private static String slug(String text){return text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N} _-]", "").trim().replace(' ','-');}
}
