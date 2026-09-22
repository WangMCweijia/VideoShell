import com.videoshell.data.model.*;
import com.videoshell.data.site.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import java.io.*;
import java.nio.file.*;

public class DumpGroups {
  public static void main(String[] a) throws Exception {
    String html = new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8");
    Document doc = Jsoup.parse(html, a[1]);
    for (PlayGroup g : HtmlExtractor.INSTANCE.parseGroups(doc, a[1])) {
      System.out.println("group=[" + g.getName() + "]  eps=" + g.getEpisodes().size()
        + "  first=" + (g.getEpisodes().isEmpty()?"-":g.getEpisodes().get(0).getName()+" "+g.getEpisodes().get(0).getUrl()));
    }
  }
}
