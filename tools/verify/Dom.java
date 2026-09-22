import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** 打印某个链接在 DOM 里的真实祖先链，用于确定导航容器选择器。 */
public class Dom {
    public static void main(String[] args) throws Exception {
        String html = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        Document doc = Jsoup.parse(html, "https://czzy.app");
        System.out.println("文件: " + args[0]);
        for (int i = 1; i < args.length; i++) {
            String href = args[i];
            System.out.println("\n=== href = " + href + " ===");
            int n = 0;
            for (Element a : doc.select("a[href=" + href + "]")) {
                n++;
                System.out.println("  命中 #" + n + "  <a>" + a.text().trim() + "</a>");
                StringBuilder sb = new StringBuilder();
                Element p = a.parent();
                int d = 0;
                while (p != null && d < 7) {
                    sb.append(p.tagName());
                    if (!p.className().isEmpty()) sb.append('.').append(p.className().trim().replace(' ', '.'));
                    if (!p.id().isEmpty()) sb.append('#').append(p.id());
                    sb.append("  <  ");
                    p = p.parent();
                    d++;
                }
                System.out.println("    祖先链: " + sb);
            }
            if (n == 0) System.out.println("  （无匹配）");
        }
        System.out.println("\n=== 各个疑似导航容器的命中数 ===");
        for (String sel : new String[]{".navlist", ".navtop", ".nav", "nav", "header", ".menu",
                ".submenu_mi", ".navlist hidden-md-and-down", "ul.navlist", ".navlist ul",
                ".navtop ul", "[class*=navlist]", "[class*=navtop]", ".footnav", ".footer_nav"}) {
            System.out.printf("  %-30s %d 个元素%n", sel, doc.select(sel).size());
        }
        System.out.println("\n=== .navlist / .navtop 内的链接 ===");
        for (String sel : new String[]{".navlist", ".navtop", "[class*=navlist]", "[class*=navtop]"}) {
            for (Element box : doc.select(sel)) {
                System.out.println("  容器 " + sel + " class=" + box.className());
                for (Element a : box.select("a[href]")) {
                    System.out.println("     " + a.attr("href") + "  |  " + a.text().trim());
                }
            }
        }
    }
}
