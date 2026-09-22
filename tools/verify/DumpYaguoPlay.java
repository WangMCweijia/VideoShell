
import com.videoshell.data.model.*;
import com.videoshell.data.site.*;
import kotlin.coroutines.Continuation; import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2; import kotlinx.coroutines.BuildersKt;
import java.io.*;
public class DumpYaguoPlay {
  static final String SITE="https://capable.fzchosdi.cc";
  @SuppressWarnings({"unchecked","rawtypes"})
  static <T> T block(Function2 fn) throws Exception { return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, fn); }
  public static void main(String[] a) throws Exception {
    // 列表卡片 id=3379 的播放页：从 detail 的 domPlayUrls 跳的地址，先从 detail 拿
    SiteConfig site = new SiteConfig("yg","yg",SITE,"",SiteConfig.MODE_HTML,"","",0L);
    SiteAdapter ad = AdapterFactory.INSTANCE.create(site);
    String html = block((scope,cont)->com.videoshell.data.net.Http.INSTANCE.getOrNull(
        SITE+"/drama/video/3379/", SITE, com.videoshell.data.net.Http.UA, false,
        (Continuation<? super String>) cont));
    System.out.println("play page " + (html==null?"FAIL":html.length()+" bytes"));
    if (html!=null) try (Writer w=new OutputStreamWriter(new FileOutputStream("_yg_play_3379.html"),"UTF-8")){w.write(html);}
  }
}
