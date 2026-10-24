package ps.reso.instaeclipse.utils.text;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HtmlExport {

    private static final int[] AVATAR_COLORS = {
            0xFFE1306C, 0xFF833AB4, 0xFFF77737, 0xFF0095F6, 0xFF00A86B, 0xFFC13584, 0xFFFD1D1D, 0xFF5851DB};
    private static final Pattern SRC = Pattern.compile("src=\"([^\"]*)\"");

    public static final String SEARCH_SCRIPT = "const q=document.getElementById('q');"
            + "const th=[...document.querySelectorAll('.thread')];"
            + "function mk(e,s){e.querySelectorAll('mark').forEach(m=>m.replaceWith(...m.childNodes));e.normalize();"
            + "if(!s)return false;const w=document.createTreeWalker(e,NodeFilter.SHOW_TEXT),ns=[];"
            + "while(w.nextNode())ns.push(w.currentNode);let hit=false;"
            + "for(let n of ns){let l=n.data.toLowerCase();"
            + "if(l.length!==n.data.length){if(l.includes(s))hit=true;continue;}"
            + "let i=l.indexOf(s);while(i>=0){const m=n.splitText(i);n=m.splitText(s.length);"
            + "const k=document.createElement('mark');m.replaceWith(k);k.appendChild(m);hit=true;"
            + "l=n.data.toLowerCase();i=l.indexOf(s);}}return hit;}"
            + "q.addEventListener('input',()=>{const s=q.value.trim().toLowerCase();th.forEach(t=>{"
            + "const cs=[...t.querySelectorAll('.c')];let any=false;cs.forEach((c,i)=>{let h=false;"
            + "c.querySelectorAll('.u,.t').forEach(e=>{if(mk(e,s))h=true;});c.dataset.h=h?1:0;if(h)any=true;"
            + "if(i>0)c.classList.toggle('hide',!!s&&!h);});t.classList.toggle('hide',!!s&&!any);"
            + "const d=t.querySelector('details');if(d)d.open=!!s&&cs.slice(1).some(c=>c.dataset.h==1);});});";

    private HtmlExport() {}

    public static String esc(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&#39;"); break;
                case '\n': b.append("<br>"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    public static int avatarColor(String user) {
        int hash = user == null ? 0 : user.hashCode();
        return AVATAR_COLORS[(hash & 0x7fffffff) % AVATAR_COLORS.length];
    }

    public static String avatarHex(String user) {
        return String.format(Locale.US, "%06X", avatarColor(user) & 0xFFFFFF);
    }

    public static String initial(String user) {
        return user == null || user.isEmpty() ? "?" : user.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    public static String comment(String user, String metaHtml, String text, String extraHtml) {
        return "<div class=c><div class=av style=\"background:#" + avatarHex(user) + "\">" + esc(initial(user))
                + "</div><div class=body><span class=u>" + esc(user) + "</span><span class=m>" + metaHtml
                + "</span><div class=t>" + esc(text) + "</div>" + extraHtml + "</div></div>";
    }

    public static String thread(String comment, String summary, List<String> replies) {
        StringBuilder b = new StringBuilder("<div class=thread>").append(comment);
        if (!replies.isEmpty()) {
            b.append("<details><summary>").append(esc(summary)).append("</summary><div class=replies>");
            for (String r : replies) b.append(r);
            b.append("</div></details>");
        }
        return b.append("</div>").toString();
    }

    public static String searchBox(String hint) {
        return "<div class=search><input id=q placeholder=\"" + esc(hint) + "\" autocomplete=off></div>";
    }

    public static String embed(String html, Map<String, String> urls) {
        if (urls.isEmpty()) return html;
        Matcher m = SRC.matcher(html);
        StringBuilder out = new StringBuilder(html.length() + urls.size() * 160);
        int at = 0;
        while (m.find()) {
            String url = urls.get(m.group(1));
            if (url == null) continue;
            out.append(html, at, m.start()).append("src=\"").append(esc(url)).append('"');
            at = m.end();
        }
        return out.append(html, at, html.length()).toString();
    }
}
